package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Locale

class GuruNumberTest {

    @get:Rule val locale = GuruLocaleRule()

    private fun crash(type: String, vararg frames: String) =
        (listOf(type) + frames.map { "\tat $it" }).joinToString("\n")

    // ---- hash ----------------------------------------------------------------

    /** Published FNV-1a 32 test vectors: the number must not depend on a JVM's hashCode. */
    @Test
    fun `fnv1a matches the published vectors`() {
        assertEquals(0x811C9DC5L, GuruNumber.fnv1a(""))
        assertEquals(0xE40C292CL, GuruNumber.fnv1a("a"))
        assertEquals(0xBF9CF968L, GuruNumber.fnv1a("foobar"))
    }

    /** Over UTF-8 bytes, not chars or Latin-1: an exception type or frame may carry any letter. */
    @Test
    fun `fnv1a hashes the UTF-8 bytes`() {
        assertEquals(0x119DD44AL, GuruNumber.fnv1a("ü"))
        assertEquals(0x298F832BL, GuruNumber.fnv1a("€"))
        assertEquals(0xB8226E38L, GuruNumber.fnv1a("Grüße"))
    }

    @Test
    fun `the number is the kind, a dot, and eight hex digits`() {
        assertTrue(Regex("""#80000001\.[0-9A-F]{8}""").matches(GuruNumber.of(GuruKind.CRASH, "x")))
        assertTrue(GuruNumber.of(GuruKind.ANR, "x").startsWith("#80000002."))
        assertTrue(GuruNumber.of(GuruKind.NATIVE, "x").startsWith("#80000003."))
    }

    /** Arabic would format %X with Arabic-Indic digits under a default-locale format. */
    @Test
    fun `the number is ASCII in any default locale`() {
        val trace = crash("java.lang.Error", "a.B.c(B.kt:1)")
        val ascii = GuruNumber.of(GuruKind.CRASH, trace)
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals(ascii, GuruNumber.of(GuruKind.CRASH, trace))
    }

    // ---- Java crashes ----------------------------------------------------------

    @Test
    fun `messages and line numbers do not change the number`() {
        val a = crash("java.lang.IllegalStateException: user 17", "a.B.c(B.kt:10)", "a.B.d(B.kt:20)")
        val b = crash("java.lang.IllegalStateException: user 4711", "a.B.c(B.kt:11)", "a.B.d(B.kt:99)")
        assertEquals(GuruNumber.of(GuruKind.CRASH, a), GuruNumber.of(GuruKind.CRASH, b))
    }

    @Test
    fun `another type or method is another number`() {
        fun number(trace: String) = GuruNumber.of(GuruKind.CRASH, trace)
        val base = number(crash("java.lang.IllegalStateException", "a.B.c(B.kt:10)"))
        assertNotEquals(base, number(crash("java.lang.Error", "a.B.c(B.kt:10)")))
        assertNotEquals(base, number(crash("java.lang.IllegalStateException", "a.B.e(B.kt:10)")))
    }

    /** Both sides of FRAMES: the third frame counts, the fourth does not. */
    @Test
    fun `only the first three frames count`() {
        fun with(third: String, fourth: String) =
            GuruNumber.of(GuruKind.CRASH, crash("java.lang.Error", "a.A.one(A.kt:1)", "a.A.two(A.kt:2)", third, fourth))
        assertEquals(with("a.A.three(A.kt:3)", "a.A.four(A.kt:4)"), with("a.A.three(A.kt:3)", "z.Z.other(Z.kt:4)"))
        assertNotEquals(with("a.A.three(A.kt:3)", "a.A.four(A.kt:4)"), with("z.Z.other(Z.kt:3)", "a.A.four(A.kt:4)"))
    }

    @Test
    fun `a real trace is signed by its root cause`() {
        val trace = IllegalStateException("outer", IOException("disk full")).stackTraceToString()
        val signature = GuruNumber.signature(GuruKind.CRASH, trace)
        assertEquals("java.io.IOException", signature.lines().first())
        assertTrue(signature.lines().drop(1).all { '(' !in it })
    }

    /** A(B(C)): the last "Caused by:" is the root cause, not the first. */
    @Test
    fun `a chain of causes is signed by its last`() {
        val trace = listOf(
            "java.lang.IllegalStateException: a",
            "\tat a.A.run(A.kt:1)",
            "Caused by: java.lang.IllegalArgumentException: b",
            "\tat b.B.run(B.kt:2)",
            "Caused by: java.io.IOException: c",
            "\tat c.C.run(C.kt:3)",
        ).joinToString("\n")
        assertEquals("java.io.IOException\nc.C.run", GuruNumber.signature(GuruKind.CRASH, trace))
    }

    /** "Type : message" (a toString with a space) signs like "Type: message". */
    @Test
    fun `a space before the colon does not change the number`() {
        assertEquals(
            GuruNumber.of(GuruKind.CRASH, crash("com.example.Odd: boom", "a.B.c(B.kt:1)")),
            GuruNumber.of(GuruKind.CRASH, crash("com.example.Odd : boom", "a.B.c(B.kt:1)")),
        )
        assertEquals("com.example.Odd\na.B.c", GuruNumber.signature(GuruKind.CRASH, crash("com.example.Odd : boom", "a.B.c(B.kt:1)")))
    }

    /** An indented "Caused by:" belongs to a suppressed exception, not to the crash. */
    @Test
    fun `a suppressed exception's cause is not the root cause`() {
        val trace = listOf(
            "java.lang.IllegalStateException: top",
            "\tat a.B.c(B.kt:1)",
            "\tSuppressed: java.lang.RuntimeException: closing",
            "\t\tat a.B.close(B.kt:9)",
            "\tCaused by: java.io.IOException: inner",
        ).joinToString("\n")
        assertEquals("java.lang.IllegalStateException\na.B.c", GuruNumber.signature(GuruKind.CRASH, trace))
    }

    @Test
    fun `a trace that is no exception falls back to its first line`() {
        assertEquals("CRASH\nnot an exception at all", GuruNumber.signature(GuruKind.CRASH, "\n  not an exception at all  \n"))
    }

    // ---- ANR and native ------------------------------------------------------------

    @Test
    fun `an ANR is signed by the main thread's frames, not another thread's`() {
        val dump = listOf(
            "ApplicationNotResponding: Input dispatching timed out",
            "",
            "\"Signal Catcher\" daemon prio=10 tid=7 Runnable",
            "  at other.Thread.ignored(Native method)",
            "",
            "\"main\" prio=5 tid=1 Blocked",
            "  | group=\"main\" sCount=1",
            "  at com.example.Db.query(Db.kt:40)",
            "  - waiting to lock <0x0a1b> held by thread 12",
            "  at com.example.Main.onResume(Main.kt:12)",
            "  at android.app.Activity.performResume(Activity.java:9000)",
            "  at android.app.Instrumentation.callActivityOnResume(Instrumentation.java:1)",
            "",
        ).joinToString("\n")
        assertEquals(
            "ANR\ncom.example.Db.query\ncom.example.Main.onResume\nandroid.app.Activity.performResume",
            GuruNumber.signature(GuruKind.ANR, dump),
        )
    }

    /** The main block ends at its blank line: a short main stack takes no frames of the next thread. */
    @Test
    fun `an ANR's main thread with one frame takes none of the next thread's`() {
        val dump = listOf(
            "ApplicationNotResponding: Input dispatching timed out",
            "",
            "\"main\" prio=5 tid=1 Native",
            "  at android.os.MessageQueue.nativePollOnce(Native method)",
            "",
            "\"Binder:1_1\" prio=5 tid=9 Native",
            "  at android.os.BinderProxy.transactNative(Native method)",
            "  at android.os.BinderProxy.transact(BinderProxy.java:1)",
            "",
        ).joinToString("\n")
        assertEquals("ANR\nandroid.os.MessageQueue.nativePollOnce", GuruNumber.signature(GuruKind.ANR, dump))
    }

    @Test
    fun `an ANR without a main thread falls back to its first line`() {
        assertEquals("ANR\nApplicationNotResponding: x", GuruNumber.signature(GuruKind.ANR, "ApplicationNotResponding: x"))
    }

    @Test
    fun `a native crash is signed by its first line`() {
        assertEquals("NATIVE\nNativeCrash: signal 11", GuruNumber.signature(GuruKind.NATIVE, "NativeCrash: signal 11\n\nmore"))
    }
}
