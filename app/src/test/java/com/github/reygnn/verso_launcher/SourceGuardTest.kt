package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Crude source scans for two hard rules that live in Activity glue and the manifest, where no
 * JVM test reaches. Each guards an edit that would be easy to make and silently dangerous:
 *  - a restore through the lenient [ConfigJson.parse] accepts "{}" and wipes the config
 *    (hard rule 7: outside files go through parseForeign only);
 *  - `density` in configChanges stops a display-size change from recreating the activity, and
 *    every dp-derived pixel value goes stale (hard rule 13).
 */
class SourceGuardTest {

    // Unit tests run with the module directory as the working directory.
    private fun source(path: String): String {
        val file = File(path).takeIf { it.isFile } ?: File("app", path)
        assertTrue("source not found: $path", file.isFile)
        return file.readText()
    }

    @Test fun `MainActivity reads outside files with parseForeign, never the lenient parse`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        assertFalse(Regex("""ConfigJson\s*\.\s*parse\s*\(""").containsMatchIn(activity))
        assertFalse(Regex("""ConfigJson\s*::\s*parse\b(?!Foreign)""").containsMatchIn(activity))
        assertTrue(activity.contains("ConfigJson.parseForeign"))
    }

    @Test fun `cleanup's IO steps keep their guards`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        // Every way enumerate() can miss an app marks it incomplete: the serial lookup, a
        // failing profile, a failing app, a silently empty profile (hard rule 14).
        assertTrue(Regex("""complete = false""").findAll(activity).count() >= 4)
        // The undo point goes to its own base, never over the backup or the restore undo point.
        assertTrue(Regex("""backupStore\.write\(\s*BackupFiles\.PRE_CLEANUP_BASE""").containsMatchIn(activity))
        assertTrue(activity.contains("Command.CLEANUP -> cleanup(t)"))
    }

    @Test fun `the enumeration carries no custom names`() {
        // Names are applied on the main thread from the live config (LauncherLogic.named). An
        // enumeration that baked them in again would go stale with every rename in flight — the
        // race a whole counter (labelGeneration) once existed to catch.
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        assertTrue(activity.contains("private fun enumerate(): Enumeration?"))
        assertFalse(Regex("""\.names\s*\[""").containsMatchIn(
            activity.substringAfter("private fun enumerate(").substringBefore("\n    }\n"),
        ))
    }

    @Test fun `the copy of an unreadable config is published before its files are renamed`() {
        // Hard rule 6: once the files are renamed, the next start is a fresh install's. A process
        // killed between the renames and the copy would leave the config only in the private
        // files — so the copy is read and published first, while the files still lie in place.
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        val body = activity.substringAfter("private fun setAsideUnreadableConfig()").substringBefore("\n    }\n")
        val copy = body.indexOf("backupStore.write(BackupFiles.UNREADABLE_BASE")
        val rename = body.indexOf("store.setAsideUnreadable()")
        assertTrue("copy written: $copy, set aside: $rename", copy in 0 until rename)
        assertTrue(body.contains("store.readForCopy()"))
    }

    @Test fun `the prompt is written through setPrompt only`() {
        // The prompt's busy-layer InputFilter lets the activity's own writes through only via
        // setPrompt (promptByCode). A plain setText under the layer runs the filter over an empty
        // dest and so EMPTIES the prompt instead of writing it — silently out of step with the mode.
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        val outside = activity.replace(
            activity.substringAfter("private fun setPrompt(").substringBefore("\n    }\n"), "",
        )
        assertFalse(Regex("""prompt\.setText\(""").containsMatchIn(outside))
        assertTrue(activity.contains("private fun setPrompt("))
    }

    @Test fun `the restore-saf read is the one step Back may abandon`() {
        // Hard rule 15: a content provider can hold that read up for good; Back must be able to
        // abandon it. Only CommandGate.cancel reads the flag, so nothing else would notice.
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        val body = activity.substringAfter("private fun readPicked(").substringBefore("\n    }\n")
        assertTrue(body.contains("t.cancelable = true"))
    }

    @Test fun `every command run starts through the gate`() {
        // Hard rule 15: a command that bypassed CommandGate would run with no busy layer up, and
        // the user's taps could interleave with it again. The entry points: a typed
        // command, the "~restore-saf" pick's answer, a confirmed "~cleanup" or "~reset".
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        assertTrue(activity.contains("gate.start(command)"))
        assertTrue(activity.contains("gate.start(Command.RESTORE_SAF)"))
        assertTrue(activity.contains("gate.start(Command.CLEANUP)"))
        assertTrue(activity.contains("gate.start(Command.RESET)"))
        assertTrue(activity.contains("Command.RESET -> reset(t)"))
        // The ways a command used to guard itself piecemeal stay gone.
        for (gone in listOf("restorePick", "checkingTicket", "reloadToastPending", "reloadConfig")) {
            assertFalse("$gone is back", activity.contains(gone))
        }
    }

    @Test fun `no string template reaches through an object without braces`() {
        // "$BackupFiles.DIR" is "BackupFiles@1a2b.DIR" — it compiles and silently breaks
        // (it once sent ~restore-saf's picker to a folder that does not exist). A CamelCase
        // name followed by a dot inside a template is that mistake; "$FILE.tmp" or "$base.tmp"
        // (a value, then a literal suffix) is not.
        val dir = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val offenders = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                "${f.name}:${i + 1}: $line".takeIf { Regex("""\$[A-Z][a-z][A-Za-z0-9_]*\.[A-Za-z_]""").containsMatchIn(line) }
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun `density stays out of configChanges`() {
        val manifest = source("src/main/AndroidManifest.xml")
        val changes = Regex("""android:configChanges="([^"]*)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no configChanges found", changes.isNotEmpty())
        for (c in changes) assertFalse("density in configChanges: $c", "density" in c.split('|'))
    }
}
