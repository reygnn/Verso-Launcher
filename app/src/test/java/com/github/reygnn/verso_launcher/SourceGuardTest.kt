package com.github.reygnn.verso_launcher

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
        assertTrue(activity.contains("Command.CLEANUP -> cleanup()"))
    }

    @Test fun `no string template reaches through an object without braces`() {
        // "$BackupFiles.DIR" is "BackupFiles@1a2b.DIR" — it compiles and silently breaks
        // (it once sent ~restore-saf's picker to a folder that does not exist).
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        assertFalse(Regex("""\$[A-Z][A-Za-z]*\.[A-Z]""").containsMatchIn(activity))
    }

    @Test fun `density stays out of configChanges`() {
        val manifest = source("src/main/AndroidManifest.xml")
        val changes = Regex("""android:configChanges="([^"]*)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no configChanges found", changes.isNotEmpty())
        for (c in changes) assertFalse("density in configChanges: $c", "density" in c.split('|'))
    }
}
