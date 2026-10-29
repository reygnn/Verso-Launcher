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

    // Rule 7: the config file is read through readCapped, so one too large is refused whole.
    @Test fun `the config file is read with readCapped`() {
        assertTrue(has(code("src/main/kotlin/com/github/reygnn/verso_launcher/ConfigFile.kt"), "ConfigText.readCapped(it)"))
    }

    @Test fun `no string template reaches through an object without braces`() {
        // "$LauncherLogic.MAX_TAG_LENGTH" compiles to "LauncherLogic@1a2b.MAX_TAG_LENGTH"; "$FILE.tmp" (a value, then a suffix) is fine.
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
     * Hard rule 4, what no JVM test reaches: the config file is read and never written. Nothing
     * names a writer next to it, and no code opens a document for writing.
     */
    @Test fun `nothing writes the config file or a document`() {
        val file = code("src/main/kotlin/com/github/reygnn/verso_launcher/ConfigFile.kt")
        for (writer in listOf("writeText", "writeBytes", "outputStream", "writeDisposable", "delete", "renameTo")) {
            assertFalse("ConfigFile.kt names $writer", Regex("""\b$writer\b""").containsMatchIn(file))
        }
        val dir = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val writers = dir.walk().filter { it.extension == "kt" }
            .filter { Regex("""\bopenOutputStream\s*\(""").containsMatchIn(withoutComments(it.readText(), "kt")) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), writers)
    }

    // Rule 13.
    @Test fun `density stays out of configChanges`() {

        val manifest = source("src/main/AndroidManifest.xml")
        val changes = Regex("""android:configChanges="([^"]*)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no configChanges found", changes.isNotEmpty())
        for (c in changes) assertFalse("density in configChanges: $c", "density" in c.split('|'))
    }

    /**
     * Hard rule 16: the process's code reaches the window only as a LauncherWindow — no Activity,
     * no MainActivity, so nothing of it can hold an instance or call one that is gone. A type keeps
     * Core on the Application (its constructor takes VersoApp); this keeps an Activity from coming
     * in sideways (an import, a cast, a field).
     */
    @Test fun `the process's code knows no Activity`() {
        val process = listOf(
            "Core.kt", "ConfigFile.kt", "LauncherWindow.kt",
            "LauncherState.kt", "LauncherStateParts.kt", "DrawerOrder.kt",
        )
        for (file in process) {
            val text = code("src/main/kotlin/com/github/reygnn/verso_launcher/$file")
            assertFalse("$file names MainActivity", Regex("""\bMainActivity\b""").containsMatchIn(text))
            assertFalse("$file names an Activity", Regex("""\bandroid\s*\.\s*app\s*\.\s*Activity\b""").containsMatchIn(text))
        }
        val app = code("src/main/kotlin/com/github/reygnn/verso_launcher/VersoApp.kt")
        assertTrue("the live window is a LauncherWindow", has(app, "internal var instance: LauncherWindow? = null"))
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

    /** Without the source file and line numbers a logcat trace names no mapping and no line. */
    @Test fun `R8 keeps source files and line numbers for crash traces`() {
        val rules = code("proguard-rules.pro")
        assertTrue(has(rules, "-keepattributes SourceFile,LineNumberTable"))
    }
}
