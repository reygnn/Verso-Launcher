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

    /** A block comment leaves its line breaks, so a line in the result is the same line of [text]. */
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
                text.startsWith("/*", i) -> {
                    val end = after(text, "*/", i + 2)
                    out.append(' ').append("\n".repeat(text.substring(i, end).count { it == '\n' }))
                    i = end
                }
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

    // ---- The conventions: a detector on text, its own test, and its scan of the sources ----
    // Each detector answers the 1-based lines it flags; findings names them as "File.kt:line: text".

    private fun dir(path: String): File = listOf(File(path), File("app", path)).first { it.isDirectory }

    private fun kotlinFiles(vararg roots: String): List<File> =
        roots.flatMap { root -> dir(root).walk().filter { it.extension == "kt" }.toList() }.sortedBy { it.path }

    /** The sources of every build: main and both twins of DailyDriver. */
    private fun shipped(): List<File> = kotlinFiles("src/main/kotlin", "src/dailyDriver/kotlin", "src/public/kotlin")

    /** [shipped] and the tests, but this file: its fixtures are the findings its detectors must make. */
    private fun everySource(): List<File> = shipped() + kotlinFiles("src/test/java").filter { it.name != "SourceGuardTest.kt" }

    private fun findings(files: List<File>, detect: (text: String) -> List<Int>): List<String> = files.flatMap { file ->
        val lines = file.readLines()
        detect(file.readText()).map { n -> "${file.name}:$n: ${lines[n - 1].trim()}" }
    }

    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

    /**
     * An annotation whose line above is no line comment, or with a comment behind it (CLAUDE.md,
     * "Warnings are errors": the reason goes above, where it stays out of detekt's baseline IDs).
     */
    private fun suppressWithoutReason(text: String): List<Int> {
        val lines = text.lines()
        return lines.indices.filter { i ->
            SUPPRESS.containsMatchIn(lines[i]) &&
                (!lines.getOrElse(i - 1) { "" }.trim().startsWith("//") || "//" in lines[i].substringAfter("@Suppress("))
        }.map { it + 1 }
    }

    @Test fun `the reason detector wants a line comment above every Suppress`() {
        val text = """
            // a reason
            @Suppress("A")
            fun a() = Unit
            /** KDoc is no reason. */
            @Suppress("B")
            fun b() = Unit
            // a reason
            val x = @Suppress("C") try { 1 } catch (e: Exception) { 2 }
            val y = @Suppress("D") try { 1 } catch (e: Exception) { 2 }
            // a reason
            @Suppress("E") // and one behind it
            /* @Suppress("F") in a comment */ val s = "@Suppress(\"G\")"
        """.trimIndent()
        assertEquals(listOf(5, 9, 11), suppressWithoutReason(text))
    }

    @Test fun `every Suppress has its reason on the line above`() {
        assertEquals(emptyList<String>(), findings(everySource(), ::suppressWithoutReason))
    }

    /** A catch whose block names no log call. [code] is without comments, so a commented call counts for nothing. */
    private fun catchWithoutLog(code: String): List<Int> = CATCH.findAll(code)
        .filterNot { LOG.containsMatchIn(blockAt(code, it.range.last)) }
        .map { lineOf(code, it.range.first) }
        .toList()

    /** The braces opened at [open] and everything up to the one that closes them. */
    private fun blockAt(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}' && --depth == 0) return code.substring(open, i + 1)
        }
        return code.substring(open)
    }

    @Test fun `the log detector flags a catch that says nothing`() {
        val text = withoutComments(
            """
            try { a() } catch (e: Exception) { log("a failed", e) }
            try { b() } catch (e: SecurityException) {
                if (loud) { Log.w("Verso", "b denied", e) }
            }
            try { c() } catch (e: RuntimeException) { null }
            try { d() } catch (e: Exception) {
                // Log.w("Verso", "d", e)
                fallback()
            }
            """.trimIndent(),
            "kt",
        )
        assertEquals(listOf(5, 6), catchWithoutLog(text))
    }

    /** Hard rule 2: a catch on main always logs, and the IO thread's degrade too. */
    @Test fun `every catch logs`() {
        assertEquals(emptyList<String>(), findings(shipped()) { catchWithoutLog(withoutComments(it, "kt")) })
    }

    private fun threadsMade(code: String): List<Int> = THREADS.findAll(code).map { lineOf(code, it.range.first) }.distinct().toList()

    @Test fun `the thread detector flags every way to another thread`() {
        val text = """
            val io = Executors.newSingleThreadExecutor()
            Thread { work() }.start()
            val t = HandlerThread("x")
            kotlin.concurrent.thread { work() }
            Timer().schedule(task, 1)
            runOnUiThread { draw() }
            mainHandler.post { draw() }
        """.trimIndent()
        assertEquals(listOf(1, 2, 3, 4, 5), threadsMade(text))
    }

    /** Hard rule 3: one IO thread, made in VersoApp, and no other executor anywhere. */
    @Test fun `no thread is made outside VersoApp`() {
        val files = shipped().filter { it.name != "VersoApp.kt" }
        assertEquals(emptyList<String>(), findings(files) { threadsMade(withoutComments(it, "kt")) })
        assertEquals("the IO thread itself", 1, threadsMade(code("src/main/kotlin/com/github/reygnn/verso_launcher/VersoApp.kt")).size)
    }

    private fun errorsCaught(code: String): List<Int> = ERROR_CAUGHT.findAll(code).map { lineOf(code, it.range.first) }.toList()

    @Test fun `the Error detector flags Throwable, any Error and runCatching`() {
        val text = """
            try { a() } catch (e: Throwable) { log(e) }
            try { a() } catch (e: OutOfMemoryError) { log(e) }
            try { a() } catch (t: kotlin.Throwable) { log(t) }
            val r = runCatching { a() }
            try { a() } catch (e: Exception) { log(e) }
            try { a() } catch (e: IllegalStateException) { log(e) }
        """.trimIndent()
        assertEquals(listOf(1, 2, 3, 4), errorsCaught(text))
    }

    /** Hard rule 2: Guard catches every Exception and lets an Error crash; nothing else catches one. */
    @Test fun `no Error is caught`() {
        assertEquals(emptyList<String>(), findings(shipped()) { errorsCaught(withoutComments(it, "kt")) })
    }

    /**
     * lint-baseline.xml and detekt-baseline.xml only ever shrink (CLAUDE.md, "Warnings are errors").
     * The count is pinned both ways: a finding fixed lowers [DETEKT_BASELINE] in the same commit.
     *
     * Why: the count alone misses one entry fixed and another baselined at once. That takes a new
     *   detektBaseline run, which this rule rules out on its own.
     */
    @Test fun `the baselines only shrink`() {
        val lintIssues = Regex("""<issue\b""").findAll(source("lint-baseline.xml")).count()
        assertEquals("lint-baseline.xml: fix a finding, do not baseline it", 0, lintIssues)
        val ids = Regex("""<ID>""").findAll(source("detekt-baseline.xml")).count()
        assertTrue("detekt-baseline.xml grew to $ids: fix the finding, do not baseline it", ids <= DETEKT_BASELINE)
        assertEquals("detekt-baseline.xml shrank: lower DETEKT_BASELINE to $ids", DETEKT_BASELINE, ids)
    }

    /**
     * Lines whose comment holds two different German function words. A word alone or an umlaut is
     * no finding: comments name "Über", "Ä under A" and "ß" as examples of what Verso handles.
     */
    private fun germanComments(text: String): List<Int> = text.lines().mapIndexedNotNull { i, line ->
        val words = GERMAN.findAll(commentOf(line)).map { it.value.lowercase() }.toSet()
        (i + 1).takeIf { words.size >= 2 }
    }

    /** The comment in [line]: a KDoc or block line whole, else what follows a slash pair outside a string. */
    private fun commentOf(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return trimmed
        var quotes = 0
        for (i in 0 until line.length - 1) {
            if (line[i] == '"') quotes++
            if (line[i] == '/' && line[i + 1] == '/' && quotes % 2 == 0) return line.substring(i + 2)
        }
        return ""
    }

    @Test fun `the German detector wants two function words in a comment`() {
        val text = """
            // Das ist nicht nötig, weil die Liste leer ist
            /** Wird nur einmal gelesen. */
            // "uber" finds "Über"; German files Ä under A
            // Über allein
            val s = "und oder nicht" // English here
            val u = "content://x" // und dann nichts
        """.trimIndent()
        assertEquals(listOf(1, 2, 6), germanComments(text))
    }

    /** Comments are English, like the code (CLAUDE.md, "Comments and KDoc"); commit messages may be German. */
    @Test fun `comments are English`() {
        assertEquals(emptyList<String>(), findings(everySource(), ::germanComments))
    }

    private companion object {
        /** detekt-baseline.xml's entries; lowered with every one fixed, never raised. */
        const val DETEKT_BASELINE = 28

        // At the line's start, or after "=" as in "val x = @Suppress(…) try"; a quote, slash or star
        // before it is a string or a comment.
        val SUPPRESS = Regex("""^\s*(?:[^"/*]*=\s*)?@Suppress\(""")
        val CATCH = Regex("""\bcatch\s*\([^)]*\)\s*\{""")
        val LOG = Regex("""\b(?:log|logWarning)\s*\(|\bLog\s*\.\s*[ewi]\s*\(""")

        // "Thread {" too: a trailing lambda needs no parentheses.
        val THREADS = Regex(
            """\bExecutors\s*\.|\bThread\s*[({]|\bHandlerThread\b|\bkotlin\s*\.\s*concurrent\b|""" +
                """\bTimer\s*\(|\bAsyncTask\b|\bForkJoinPool\b""",
        )
        val ERROR_CAUGHT = Regex("""\bcatch\s*\(\s*\w+\s*:\s*(?:[\w.]+\.)?(?:Throwable|\w*Error)\s*\)|\brunCatching\b""")

        // Words with no English twin; "die", "das", "mit" and "will" are left out for that reason.
        val GERMAN = Regex(
            """(?i)(?<!\p{L})(?:und|oder|nicht|ist|sind|wird|werden|wenn|weil|dass|eine|einen|einem|einer|""" +
                """auch|noch|nur|kein|keine|aber|für|schon|dann|hier|nichts|einmal)(?!\p{L})""",
        )
    }
}
