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

    @Test fun `density stays out of configChanges`() {
        val manifest = source("src/main/AndroidManifest.xml")
        val changes = Regex("""android:configChanges="([^"]*)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no configChanges found", changes.isNotEmpty())
        for (c in changes) assertFalse("density in configChanges: $c", "density" in c.split('|'))
    }
}
