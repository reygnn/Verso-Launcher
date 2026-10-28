package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comments that name code which is no longer there. The next session trusts a comment, so a name
 * that outlived its declaration misleads it (CLAUDE.md, "Comments and KDoc").
 *
 * Checked: every KDoc link in a comment of Verso's Kotlin sources, and in the Markdown files every
 * name in backticks that has the form `Owner.member` (Owner one of Verso's or Guru's types) or
 * `Name.kt`. A link resolves when each of its segments is declared somewhere in Verso's or Guru's
 * code, or imported there; a link in code that ships resolves only against code that ships.
 *
 * Why: the IDE does not resolve a test from main code, so such a link would be dead there.
 * Why: a resolver this loose catches what drifts in practice, a name that is gone everywhere. It
 *   does not see a link to the wrong member of the right name. Names written as plain prose are
 *   not seen at all; the convention says to write them as links.
 * Why: Guru's own comments are left out. Guru is a subtree, fixed upstream (CLAUDE.md, "Guru").
 */
class CommentLinkTest {

    /** The repo root: unit tests run with the module directory as the working directory. */
    private val root: File = if (File("src/main").isDirectory) File("..") else File(".")

    private fun kotlinFiles(vararg dirs: String): List<File> =
        dirs.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Verso's sources, whose comments are checked. */
    private val verso = kotlinFiles("app/src")

    /** Guru's sources: a name may be declared there, its comments are not checked. */
    private val guru = kotlinFiles("guru/library/src", "guru/sample/src")

    /** Every name declared in Verso and Guru, tests included: for a test's comments and the Markdown files. */
    private val everywhere by lazy { Names(verso + guru) }

    /** The names of the code that ships: Verso's without its tests, and Guru's library. */
    private val shipped by lazy { Names(verso.filterNot(::isTest) + kotlinFiles("guru/library/src/main")) }

    private val kotlinFileNames: Set<String> by lazy { (verso + guru).mapTo(HashSet()) { it.name } }

    @Test fun `every link in a comment names code that exists`() {
        val broken = verso.flatMap { file ->
            val names = if (isTest(file)) everywhere else shipped
            commentsOf(file.readText()).flatMap { (line, comment) ->
                links(comment).filterNot(names::resolves).map { "${file.name}:$line: [$it]" }
            }
        }
        assertEquals(emptyList<String>(), broken)
    }

    @Test fun `every code name in the Markdown files exists`() {
        val docs = listOf("CLAUDE.md", "CAVEATS.md", "README.md", "app/src/test/CLAUDE.md")
        val broken = docs.filter { File(root, it).isFile }.flatMap { doc ->
            File(root, doc).readLines().flatMapIndexed { i, text ->
                codeNames(text).filterNot(::exists).map { name -> "$doc:${i + 1}: $name" }
            }
        }
        assertEquals(emptyList<String>(), broken)
    }

    // ---- the scanner, checked on its own -------------------------------------------------------

    @Test fun `only comments are scanned, not code or strings`() {
        val source = """
            val a = names[key] // the [current] read
            val s = "a [link] in a string" /* and [Block.member] here */
            /** [kdoc] */
        """.trimIndent()
        val found = commentsOf(source).flatMap { (line, text) -> links(text).map { line to it } }
        assertEquals(listOf(1 to "current", 2 to "Block.member", 3 to "kdoc"), found)
    }

    @Test fun `a Markdown link or a link's label is no code name`() {
        assertEquals(listOf("Target"), links("see [the docs](https://x.y) and [label][Target]"))
    }

    @Test fun `declarations are found in their usual forms`() {
        val source = """
            import android.net.Uri
            import java.util.Locale as L
            internal class Commands(private val app: VersoApp, then: (Int) -> Unit) {
                private var current: Read? = null
                fun <T : Ordered> rowsFor(mode: Mode): List<T> = emptyList()
                fun Context.dp(value: Int) = value
                enum class Edge { ACT, PASS }
            }
        """.trimIndent()
        val found = declarations(source)
        for (name in listOf("Uri", "L", "Commands", "app", "then", "current", "rowsFor", "T", "mode", "dp", "value", "ACT", "PASS")) {
            assertTrue(name, name in found)
        }
    }

    @Test fun `a source under a test directory is a test`() {
        assertTrue(isTest(File("app/src/test/java/A.kt")))
        assertTrue(isTest(File("guru/library/src/androidTest/kotlin/A.kt")))
        assertFalse(isTest(File("app/src/main/kotlin/A.kt")))
        assertFalse(isTest(File("app/src/dailyDriver/kotlin/test/A.kt")))
    }

    @Test fun `a Markdown name is checked only in a code form`() {
        val line = "`Commands.adopt`, `HomeStyle.kt`, `~open`, `R.string`, `config-link.txt`, `WallpaperColors`"
        assertEquals(listOf("Commands.adopt", "HomeStyle.kt", "R.string"), codeNames(line))
    }

    // ---- what is checked -----------------------------------------------------------------------

    /** `Name.kt` is a Kotlin file; `Owner.member` has Verso's or Guru's type as owner, and its members declared. */
    private fun exists(name: String): Boolean = when {
        name.endsWith(".kt") -> name in kotlinFileNames
        name.substringBefore('.') !in everywhere.types -> true // a platform type ("Intent.ACTION_…") or R
        else -> name.split('.').drop(1).all { it in everywhere.declared }
    }

    /** The names a set of sources declares or imports, and which of them are types. */
    private class Names(sources: List<File>) {
        val declared: Set<String> = sources.flatMapTo(HashSet()) { declarations(it.readText()) }
        val types: Set<String> = sources.flatMapTo(HashSet()) { typeDeclarations(it.readText()) }

        /**
         * The first segment of [link] is declared or imported here, and so is every later one when
         * the first is one of these types.
         *
         * Why: a platform type's member, such as Color.TRANSPARENT, cannot be checked from the
         *   sources.
         */
        fun resolves(link: String): Boolean {
            val segments = link.split('.')
            if (segments.first() !in declared) return false
            return segments.first() !in types || segments.drop(1).all { it in declared }
        }
    }

    private companion object {

        /** A source in a test source set of Verso or Guru: its directory right under src is test or androidTest. */
        fun isTest(file: File): Boolean =
            Regex("""(^|/)src/(test|androidTest)/""").containsMatchIn(file.invariantSeparatorsPath)

        /** One comment of a source: the line it starts on, and where it is in the text. */
        class Comment(val line: Int, val start: Int, val end: Int)

        /** The comments of a Kotlin source, each with the line it starts on. */
        fun commentsOf(text: String): List<Pair<Int, String>> =
            commentRanges(text).map { it.line to text.substring(it.start, it.end) }

        /**
         * Where the comments of a Kotlin source are.
         *
         * Why: a string is skipped whole, so a slash pair inside it opens no comment and its
         *   brackets are no link. A string template with quotes inside its braces is not modelled,
         *   as in SourceGuardTest.
         */
        fun commentRanges(text: String): List<Comment> {
            val out = mutableListOf<Comment>()
            var i = 0
            var line = 1
            fun advanceTo(end: Int) {
                line += (i until end).count { text[it] == '\n' }
                i = end
            }
            while (i < text.length) {
                val stringEnd = stringEndAt(text, i)
                val commentEnd = if (stringEnd >= 0) -1 else commentEndAt(text, i)
                when {
                    stringEnd >= 0 -> advanceTo(stringEnd)
                    commentEnd >= 0 -> {
                        out += Comment(line, i, commentEnd)
                        advanceTo(commentEnd)
                    }
                    else -> advanceTo(i + 1)
                }
            }
            return out
        }

        /** Just past the string that opens at [i], or -1 if none does. */
        fun stringEndAt(text: String, i: Int): Int = when {
            text.startsWith("\"\"\"", i) -> after(text, "\"\"\"", i + 3)
            text[i] == '"' || text[i] == '\'' -> quoteEnd(text, i)
            else -> -1
        }

        /** The end of the comment that opens at [i], or -1 if none does. */
        fun commentEndAt(text: String, i: Int): Int = when {
            text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0) text.length else it }
            text.startsWith("/*", i) -> after(text, "*/", i + 2)
            else -> -1
        }

        /** Just past the next [marker] from [from], or the end of [text]. */
        fun after(text: String, marker: String, from: Int): Int =
            text.indexOf(marker, from).let { if (it < 0) text.length else it + marker.length }

        /** Just past the quote that closes the one at [start] on its line; a backslash escapes. */
        fun quoteEnd(text: String, start: Int): Int {
            var j = start + 1
            while (j < text.length && text[j] != text[start] && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
            return minOf(j + 1, text.length)
        }

        /**
         * The KDoc links in [comment]. A Markdown link (brackets, then parentheses) is none, and of a
         * link with a label (brackets twice) only the second pair counts.
         */
        fun links(comment: String): List<String> =
            LINK.findAll(comment).map { it.groupValues[1] }.toList()

        private val LINK = Regex("""\[([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)](?![(\[])""")

        /** Names in backticks in a Markdown line that have a code form: `Owner.member` or `Name.kt`. */
        fun codeNames(line: String): List<String> =
            Regex("`([^`\n]+)`").findAll(line).map { it.groupValues[1].removeSuffix("()") }
                .filter { CODE_NAME.matches(it) }.toList()

        private val CODE_NAME = Regex("""[A-Z]\w*(\.[A-Za-z_]\w*)+""")

        /** The names [source] declares or imports, comments left out (a commented-out line declares nothing). */
        fun declarations(source: String): Set<String> {
            val code = withoutComments(source)
            val names = HashSet<String>()
            for (pattern in DECLARATIONS) pattern.findAll(code).forEach { names += it.groupValues[1] }
            IMPORT.findAll(code).forEach { names += it.groups[2]?.value ?: it.groupValues[1].substringAfterLast('.') }
            ENUM.findAll(code).forEach { m ->
                m.groupValues[1].split(',').mapNotNull { Regex("""^\s*([A-Z_][A-Z0-9_]*)""").find(it)?.groupValues?.get(1) }
                    .forEach { names += it }
            }
            return names
        }

        /** The classes, interfaces and objects [source] declares. */
        fun typeDeclarations(source: String): Set<String> =
            TYPE.findAll(withoutComments(source)).mapTo(HashSet()) { it.groupValues[1] }

        fun withoutComments(source: String): String = buildString {
            var at = 0
            for (comment in commentRanges(source)) {
                append(source, at, comment.start).append(' ')
                at = comment.end
            }
            append(source, at, source.length)
        }

        private val TYPE = Regex("""\b(?:class|interface|object|typealias)\s+([A-Za-z_]\w*)""")

        private val DECLARATIONS = listOf(
            TYPE,
            // fun name(, fun <T> name(, fun Receiver.name(
            Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?([A-Za-z_]\w*)\s*\("""),
            // val name, var Receiver.name
            Regex("""\b(?:val|var)\s+(?:[\w.<>?, ]+\.)?([A-Za-z_]\w*)"""),
            // a parameter, or a property of a constructor: "(name:" or ", name:"
            Regex("""[(,]\s*(?:@\w+\s+)*(?:(?:private|internal|public|protected|override|open|vararg)\s+)*(?:(?:val|var)\s+)?([A-Za-z_]\w*)\s*:"""),
            // a type parameter: "<T>", "<T : Ordered>", "<out T>"
            Regex("""[<,]\s*(?:(?:in|out|reified)\s+)?([A-Z]\w*)\s*[:,>]"""),
        )

        private val IMPORT = Regex("""(?m)^import\s+([\w.]+?)(?:\.\*)?(?:\s+as\s+(\w+))?\s*$""")

        private val ENUM = Regex("""\benum\s+class\s+\w+[^{]*\{([^;}]*)""")
    }
}
