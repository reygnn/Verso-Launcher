package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source scans for what neither a type nor a test reaches: a call that must not appear, a template trap, the manifest. */
class SourceGuardTest {

    // Unit tests run with the module directory as the working directory.
    private fun source(path: String): String {
        val file = File(path).takeIf { it.isFile } ?: File("app", path)
        assertTrue("source not found: $path", file.isFile)
        return file.readText()
    }

    // Rule 7.
    @Test fun `MainActivity reads outside files with parseForeign, never the lenient parse`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        assertFalse(Regex("""ConfigJson\s*\.\s*parse\s*\(""").containsMatchIn(activity))
        assertFalse(Regex("""ConfigJson\s*::\s*parse\b(?!Foreign)""").containsMatchIn(activity))
        assertTrue(activity.contains("ConfigJson.parseForeign"))
    }

    @Test fun `no string template reaches through an object without braces`() {
        // "$BackupFiles.DIR" compiles to "BackupFiles@1a2b.DIR"; "$FILE.tmp" (a value, then a suffix) is fine.
        val dir = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val offenders = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                "${f.name}:${i + 1}: $line".takeIf { Regex("""\$[A-Z][a-z][A-Za-z0-9_]*\.[A-Za-z_]""").containsMatchIn(line) }
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /** A default would turn a wire MainActivity forgot into a silent no-op; tests use testLauncherState. */
    @Test fun `LauncherState's constructor defaults nothing`() {
        val state = source("src/main/kotlin/com/github/reygnn/verso_launcher/LauncherState.kt")
        val params = state.substringAfter("internal class LauncherState(").substringBefore("\n) {")
        assertTrue("constructor not found", params.contains("private val io:"))
        assertEquals(emptyList<String>(), params.lines().filter { " = " in it.substringBefore("//") })
    }

    /**
     * Rules 5 and 15: the permit's constructor is module-internal, so the type alone would let any
     * class build one past the load check. Only LauncherState.editPermit may.
     */
    @Test fun `an EditPermit is built only by LauncherState's editPermit`() {
        val dir = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        // A call (also split over lines) or a constructor reference (LauncherState::EditPermit).
        val built = Regex("""\bEditPermit\s*\(|::\s*EditPermit\b""")
        val sites = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            built.findAll(f.readText()).map { f.name }
        }.toList()
        assertEquals(listOf("LauncherState.kt"), sites)
    }

    /** What no JVM test reaches: the Activity's wiring of two rules into LauncherState. */
    @Test fun `MainActivity wires the restore write and the command gate into LauncherState`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        // Hard rule 10: a restore's write ends the wait to seed, even with its hand-back lost.
        assertTrue(activity.contains("writeConfig = state::writeRestored"))
        // Hard rules 10 and 15: the seeding takes a permit from the real gate.
        assertTrue(activity.contains("idle = { gate.idle() }"))
    }

    // Rule 13.
    @Test fun `density stays out of configChanges`() {

        val manifest = source("src/main/AndroidManifest.xml")
        val changes = Regex("""android:configChanges="([^"]*)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no configChanges found", changes.isNotEmpty())
        for (c in changes) assertFalse("density in configChanges: $c", "density" in c.split('|'))
    }

    /**
     * Guru (guru/): installed once, from the Application, with Verso's words; its disk work
     * (its store and the MediaStore save) on the one IO thread (hard rule 3), behind Guard (hard
     * rule 2). A second install elsewhere would replace this setup.
     */
    @Test fun `Guru is installed once, from VersoApp, on the IO thread behind Guard`() {
        val app = source("src/main/kotlin/com/github/reygnn/verso_launcher/VersoApp.kt")
        assertTrue(Regex("""\bGuru\.install\(\s*this,\s*io = """).containsMatchIn(app))
        assertTrue(app.contains("io.execute(Guard.wrap("))
        assertTrue(app.contains("texts = VersoGuruTexts(this)"))
        val dir = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val sites = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""\bGuru\s*\.\s*install\s*\(""").findAll(f.readText()).map { f.name }
        }.toList()
        assertEquals(listOf("VersoApp.kt"), sites)
    }

    /**
     * The alert on the next start: asked for in every onCreate, unconditionally — an alert a
     * recreation took away comes back only with the next call. Handed back with a plain
     * runOnUiThread, never onUi: Guru puts its list back only when its own check sees the
     * destroyed activity, and onUi would drop the hand-back first (GuruWiringTest).
     */
    @Test fun `MainActivity asks for pending Guru reports in onCreate, not through onUi`() {
        val activity = source("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        val onCreate = activity.substringAfter("override fun onCreate(").substringBefore("\n    override fun ")
        assertTrue(onCreate.contains("\n        Guru.showPending(this) { show -> runOnUiThread(show) }\n"))
        assertEquals(1, Regex("""\bGuru\s*\.\s*showPending\s*\(""").findAll(activity).count())
    }

    /** Guru's store (crash reports, the installation id) never goes to the cloud nor to a new device. */
    @Test fun `Guru's files stay out of cloud backup and device transfer`() {
        val rules = source("src/main/res/xml/data_extraction_rules.xml")
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(cloud.contains("""<exclude domain="file" path="." />"""))
        assertTrue(transfer.contains("""<exclude domain="file" path="guru/" />"""))
    }

    /** Renamed classes or lost line numbers would change a crash's Guru number with every release. */
    @Test fun `R8 keeps names and line numbers for Guru's reports`() {
        val rules = source("proguard-rules.pro").lines().map { it.trim() }
        assertTrue(rules.contains("-dontobfuscate"))
        assertTrue(rules.contains("-keepattributes SourceFile,LineNumberTable"))
    }
}
