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

    @Test fun `backup and a restore's undo point take the config only once it is loaded`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        // Before the load (or while unreadable) memory holds the empty default: exporting it
        // would replace the real backup or undo point with nothing (hard rules 5, 7).
        for (name in listOf("backup", "adoptRestored")) {
            val body = activity.substringAfter("private fun $name(", missingDelimiterValue = "")
                .substringBefore("\n    }\n")
            assertTrue("$name not found", body.isNotEmpty())
            assertTrue("$name must use loadedSnapshot", body.contains("state.loadedSnapshot()"))
            assertFalse("$name reads state.config directly", Regex("""state\.config\b(?!Loaded|Unreadable)""").containsMatchIn(body))
        }
    }

    @Test fun `every command runs inside the gate`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        // Three ways in (hard rule 15): the typed commands, the ~restore-saf answer, a confirmed
        // ~cleanup. The command bodies take the run's ticket, so none can be called outside one.
        assertEquals(3, Regex("""gate\.start\s*\{""").findAll(activity).count())
        for (body in listOf("backup", "restoreConfig", "cleanup", "adoptRestored")) {
            val inRun = Regex("""(?<!fun |\.)\b$body\(t\b""").findAll(activity).count()
            val all = Regex("""(?<!fun |\.)\b$body\(""").findAll(activity).count()
            assertEquals("$body( called outside a run", all, inRun)
        }
        assertTrue(Regex("""fun runCleanup\([^)]*\)\s*\{\s*val accepted = gate\.start""").containsMatchIn(activity))
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
