package com.github.reygnn.guru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportCodecTest {

    private fun encoded(r: GuruReport = report()) = ReportCodec.encode(r)

    @Test
    fun `a report survives the round trip`() {
        val r = report()
        assertEquals(r, ReportCodec.decode(encoded(r)))
    }

    /** Backslashes, a literal "\n", CR, '=', a key-looking line and non-BMP text must all come back exact. */
    @Test
    fun `a hostile trace survives the round trip`() {
        val trace = "a\\b\\n not a newline\r\nline=2\nid=evil\n😀 \\"
        val r = report(trace = trace, thread = "t=1\\")
        assertEquals(r, ReportCodec.decode(encoded(r)))
    }

    @Test
    fun `every report field is written, one per line`() {
        assertEquals(15, encoded().trimEnd('\n').lines().size)
    }

    @Test
    fun `an unknown key from a newer Guru is skipped`() {
        assertEquals(report(), ReportCodec.decode(encoded() + "newField=x\n"))
    }

    @Test
    fun `no magic, no report`() {
        assertNull(ReportCodec.decode(""))
        assertNull(ReportCodec.decode(encoded().replaceFirst("guru 1", "guru 2")))
    }

    @Test
    fun `a missing field is no report`() {
        assertNull(ReportCodec.decode(encoded().lines().filterNot { it.startsWith("sdk=") }.joinToString("\n")))
    }

    @Test
    fun `a bad number, kind or id is no report`() {
        assertNull(ReportCodec.decode(encoded().replace("versionCode=42", "versionCode=4x2")))
        assertNull(ReportCodec.decode(encoded().replace("kind=CRASH", "kind=REBOOT")))
        assertNull(ReportCodec.decode(encoded(report(id = "a/b"))))
    }

    @Test
    fun `a line without a key is no report`() {
        assertNull(ReportCodec.decode(encoded() + "=value\n"))
        assertNull(ReportCodec.decode(encoded() + "junk\n"))
    }

    @Test
    fun `an unknown or dangling escape is no report`() {
        assertNull(ReportCodec.decode(encoded().replace("thread=main", "thread=ma\\tin")))
        assertNull(ReportCodec.decode(encoded().replace("thread=main", "thread=main\\")))
    }

    /** A crash during the write leaves a .new, never a torn report — but a torn one must still fail. */
    @Test
    fun `a report cut off before its trace is no report`() {
        val text = encoded()
        assertNull(ReportCodec.decode(text.substring(0, text.indexOf("trace="))))
    }
}
