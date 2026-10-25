package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source scans for what neither a type nor a test reaches: a call that must not appear, a template
 * trap, the manifest. A snippet is matched token by token ([has]), so a reformat is no finding, and
 * comments are left out ([code]), so a commented-out line passes nothing.
 */
class SourceGuardTest {

    // Unit tests run with the module directory as the working directory.
    private fun source(path: String): String {
        val file = File(path).takeIf { it.isFile } ?: File("app", path)
        assertTrue("source not found: $path", file.isFile)
        return file.readText()
    }

    /** [source] without its comments. */
    private fun code(path: String): String = withoutComments(source(path), path.substringAfterLast('.'))

    /**
     * Comments left out: Kotlin's line and block comments (never a slash pair or star inside a
     * string, as in a MIME type or "content://"), ProGuard's #, XML's <!-- -->.
     */
    private fun withoutComments(text: String, extension: String): String =
        when (extension) {
            "kt" -> withoutKotlinComments(text)
            "pro" -> text.replace(Regex("""#[^\n]*"""), "")
            "xml" -> text.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
            else -> text
        }

    private fun withoutKotlinComments(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            // A string is copied whole: a slash pair or a star inside it opens no comment.
            val stringEnd = when {
                text.startsWith("\"\"\"", i) -> after(text, "\"\"\"", i + 3)
                text[i] == '"' || text[i] == '\'' -> quoteEnd(text, i)
                else -> -1
            }
            when {
                stringEnd >= 0 -> out.append(text, i, stringEnd).also { i = stringEnd }
                text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                text.startsWith("/*", i) -> out.append(' ').also { i = after(text, "*/", i + 2) }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    /** Just past the next [marker] from [from], or the end of [text]. */
    private fun after(text: String, marker: String, from: Int): Int =
        text.indexOf(marker, from).let { if (it < 0) text.length else it + marker.length }

    /** Just past the quote that closes the one at [start] on its line; a backslash escapes. */
    private fun quoteEnd(text: String, start: Int): Int {
        var j = start + 1
        while (j < text.length && text[j] != text[start] && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
        return minOf(j + 1, text.length)
    }

    /** [snippet] in [text], whatever whitespace stands between its tokens (a word, or one sign). */
    private fun pattern(snippet: String): Regex {
        val tokens = Regex("""\w+|[^\w\s]""").findAll(snippet).map { it.value }.toList()
        val joined = tokens.zipWithNext { a, b ->
            // Two words need some space between them; anything else may touch or not.
            if (a.last().isLetterOrDigit() && b.first().isLetterOrDigit()) """\s+""" else """\s*"""
        }
        return Regex(tokens.indices.joinToString("") { i -> Regex.escape(tokens[i]) + joined.getOrElse(i) { "" } })
    }

    private fun has(text: String, snippet: String) = pattern(snippet).containsMatchIn(text)

    @Test fun `a snippet survives a reformat, not a change or a comment`() {
        assertTrue(has("idle = {\n    gate.idle()\n}", "idle = { gate.idle() }"))
        assertTrue(has("idle={gate.idle()}", "idle = { gate.idle() }"))
        assertFalse(has("idle = { gate.busy() }", "idle = { gate.idle() }"))
        assertFalse(has("privateval io:", "private val io:"))
        val stripped = withoutComments(
            "// idle = { gate.idle() }\n/* idle = { gate.idle() } */\nval x = \"content://a\" // note\n" +
                "setType(\"*/*\")\nwriteConfig = w\n",
            "kt",
        )
        assertFalse(has(stripped, "idle = { gate.idle() }"))
        assertTrue(has(stripped, "val x = \"content://a\""))
        assertTrue("a \"/*\" in a string opens no comment", has(stripped, "writeConfig = w"))
    }

    // Rule 7: the commands read outside files (Commands); the window must not read one either.
    @Test fun `outside files are read with parseForeign, never the lenient parse`() {
        val commands = code("src/main/kotlin/com/github/reygnn/verso_launcher/Commands.kt")
        for (path in listOf("MainActivity.kt", "Commands.kt")) {
            val text = code("src/main/kotlin/com/github/reygnn/verso_launcher/$path")
            assertFalse(path, Regex("""ConfigJson\s*\.\s*parse\s*\(""").containsMatchIn(text))
            assertFalse(path, Regex("""ConfigJson\s*::\s*parse\b(?!Foreign)""").containsMatchIn(text))
        }
        assertTrue(has(commands, "ConfigJson.parseForeign"))
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
        val state = code("src/main/kotlin/com/github/reygnn/verso_launcher/LauncherState.kt")
        val params = state.substringAfter("internal class LauncherState(").substringBefore("\n) {")
        assertTrue("constructor not found", has(params, "private val io:"))
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

    /** What no JVM test reaches: the wiring of two rules into LauncherState (Commands' and Core's). */
    @Test fun `the restore write and the command gate are wired into LauncherState`() {
        val commands = code("src/main/kotlin/com/github/reygnn/verso_launcher/Commands.kt")
        val core = code("src/main/kotlin/com/github/reygnn/verso_launcher/Core.kt")
        // Hard rule 10: a restore's write ends the wait to seed, even with its hand-back lost.
        assertTrue(has(commands, "writeConfig = state::writeRestored"))
        // Hard rules 10 and 15: the seeding takes a permit from the real, process-wide gate.
        assertTrue(has(core, "idle = { app.gate.idle() }"))
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
        val app = code("src/main/kotlin/com/github/reygnn/verso_launcher/VersoApp.kt")
        assertTrue(has(app, "Guru.install(this, io ="))
        assertTrue(has(app, "io.execute(Guard.wrap("))
        assertTrue(has(app, "texts = VersoGuruTexts(this)"))
        val dir = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val sites = dir.walk().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""\bGuru\s*\.\s*install\s*\(""").findAll(f.readText()).map { f.name }
        }.toList()
        assertEquals(listOf("VersoApp.kt"), sites)
    }

    /**
     * A recreation finds the process's state loaded, and its own first refresh may bring the same
     * list and re-render nothing: onCreate must draw the loaded state itself, unconditionally on
     * anything but that. Robolectric lists no apps (no LauncherApps activities), so appsLoaded never
     * turns true there and no flow test can see a blank drawer; this pin is the guard.
     */
    @Test fun `MainActivity draws a loaded state in onCreate`() {
        val activity = code("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        val onCreate = activity.substringAfter("override fun onCreate(").substringBefore("\n    override fun ")
        assertTrue(has(onCreate, "if (state.appsLoaded) appDrawer.refilter()"))
    }

    /**
     * The alert on the next start: asked for in every onCreate, unconditionally — an alert a
     * recreation took away comes back only with the next call. Handed back with a plain
     * runOnUiThread, never onUi: Guru puts its list back only when its own check sees the
     * destroyed activity, and onUi would drop the hand-back first. Robolectric cannot open that
     * window (a recreate runs the queued hand-back first), so this pin is the guard.
     */
    @Test fun `MainActivity asks for pending Guru reports in onCreate, not through onUi`() {
        val activity = code("src/main/kotlin/com/github/reygnn/verso_launcher/MainActivity.kt")
        val onCreate = activity.substringAfter("override fun onCreate(").substringBefore("\n    override fun ")
        val call = pattern("Guru.showPending(this) { show -> runOnUiThread(show) }").find(onCreate)
        assertTrue("not in onCreate", call != null)
        // Unconditional: at the body's own level, inside no block (an if, a lambda) of onCreate.
        val before = onCreate.substring(onCreate.indexOf('{') + 1, call!!.range.first)
        assertEquals(0, before.count { it == '{' } - before.count { it == '}' })
        assertEquals(1, Regex("""\bGuru\s*\.\s*showPending\s*\(""").findAll(activity).count())
    }

    /** Guru's store (crash reports, the installation id) never goes to the cloud nor to a new device. */
    @Test fun `Guru's files stay out of cloud backup and device transfer`() {
        val rules = code("src/main/res/xml/data_extraction_rules.xml")
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(has(cloud, """<exclude domain="file" path="." />"""))
        assertTrue(has(transfer, """<exclude domain="file" path="guru/" />"""))
    }

    /** Renamed classes or lost line numbers would change a crash's Guru number with every release. */
    @Test fun `R8 keeps names and line numbers for Guru's reports`() {
        val rules = code("proguard-rules.pro")
        assertTrue(has(rules, "-dontobfuscate"))
        assertTrue(has(rules, "-keepattributes SourceFile,LineNumberTable"))
    }
}
