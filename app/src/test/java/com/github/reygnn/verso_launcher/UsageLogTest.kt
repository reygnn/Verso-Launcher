package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp

class UsageLogTest {

    @get:Rule val folder = TemporaryFolder()

    private val noLog: (String, Exception?) -> Unit = { _, _ -> }

    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    @Test fun `a launch keeps Kolibri's decay - a day, a week, a month`() {
        val one = Usage(1.0, t0)
        assertEquals(exp(-0.0864), UsageLog.decayed(one, t0 + day), 1e-9)
        assertEquals(0.917, UsageLog.decayed(one, t0 + day), 0.001)
        assertEquals(0.546, UsageLog.decayed(one, t0 + 7 * day), 0.001)
        assertEquals(0.075, UsageLog.decayed(one, t0 + 30 * day), 0.001)
    }

    @Test fun `a clock set back counts as no time, never as a gain`() {
        assertEquals(2.0, UsageLog.decayed(Usage(2.0, t0), t0 - day), 0.0)
    }

    @Test fun `a launch adds one to what is left of the earlier ones`() {
        var log = UsageLog.recorded(emptyMap(), "a", t0)
        log = UsageLog.recorded(log, "a", t0)
        assertEquals(Usage(2.0, t0), log["a"])
        log = UsageLog.recorded(log, "a", t0 + 7 * day)
        assertEquals(2.0 * exp(-0.6048) + 1.0, log.getValue("a").score, 1e-9)
        assertEquals(t0 + 7 * day, log.getValue("a").at)
    }

    @Test fun `most used first, ties and unused apps keep the incoming order`() {
        val apps = listOf("a", "b", "c", "d", "e").map { OrderedFake(it) }
        val log = mapOf(
            "b" to Usage(1.0, t0),
            "d" to Usage(3.0, t0),
            "e" to Usage(1.0, t0),
        )
        assertEquals(listOf("d", "b", "e", "a", "c"), UsageLog.byUsage(apps, log, t0).map { it.key })
    }

    @Test fun `recent beats frequent once enough time has passed`() {
        val apps = listOf(OrderedFake("old"), OrderedFake("new"))
        val log = mapOf("old" to Usage(5.0, t0), "new" to Usage(1.0, t0 + 30 * day))
        assertEquals(listOf("new", "old"), UsageLog.byUsage(apps, log, t0 + 30 * day).map { it.key })
    }

    @Test fun `launches counted before the file was read add to what it held`() {
        val disk = mapOf("a" to Usage(2.0, t0), "b" to Usage(1.0, t0))
        val memory = mapOf("a" to Usage(1.0, t0 + day), "c" to Usage(1.0, t0 + day))
        val merged = UsageLog.merged(disk, memory, t0 + day)
        assertEquals(2.0 * exp(-0.0864) + 1.0, merged.getValue("a").score, 1e-9)
        assertEquals(Usage(1.0, t0), merged["b"])
        assertEquals(1.0, merged.getValue("c").score, 1e-9)
    }

    @Test fun `the file round-trips and forgets what has decayed away`() {
        val log = mapOf(
            "com.a/com.a.Main" to Usage(3.5, t0),
            "com.b/com.b.Main#10" to Usage(1.0, t0),
            "com.gone/com.gone.Main" to Usage(1.0, t0 - 60 * day),
        )
        val text = UsageLog.format(log, t0)
        assertEquals(log - "com.gone/com.gone.Main", UsageLog.parse(text))
    }

    @Test fun `junk lines are skipped, not trusted`() {
        val text = "a\t1.0\t5\n" +
            "\t1.0\t5\n" +          // no key
            "b\tNaN\t5\n" +
            "c\t-1\t5\n" +
            "d\tInfinity\t5\n" +
            "e\t1.0\n" +            // a torn line
            "f\t1.0\t5\textra\n" +
            "g\t1.0\tnow\n" +
            "h\t1.0\t-9223372036854775808\n"  // would never decay: now - at overflows
        assertEquals(mapOf("a" to Usage(1.0, 5L)), UsageLog.parse(text))
    }

    /** The boundaries of the junk above: a score must be above 0, a time may be 0. */
    @Test fun `a score of 0 is junk, a time of 0 is not`() {
        assertEquals(mapOf("b" to Usage(1.0, 0L)), UsageLog.parse("a\t0.0\t5\nb\t1.0\t0\n"))
    }

    @Test fun `an entry right at the forget line is kept, one below it is dropped`() {
        val log = mapOf("kept" to Usage(UsageLog.FORGET_BELOW, t0), "gone" to Usage(UsageLog.FORGET_BELOW * 0.99, t0))
        assertEquals(setOf("kept"), UsageLog.parse(UsageLog.format(log, t0)).keys)
    }

    @Test fun `no file is an empty log, and a write lands whole`() {
        val dir = folder.newFolder()
        assertEquals(emptyMap<String, Usage>(), UsageLog.read(dir, noLog))
        val log = mapOf("a" to Usage(2.0, t0))
        UsageLog.write(dir, log, t0)
        assertEquals(log, UsageLog.read(dir, noLog))
        assertTrue(!File(dir, "${UsageLog.FILE}.tmp").exists())
    }

    @Test fun `an unreadable file is not an empty log - it must not be written over`() {
        val dir = folder.newFolder()
        val file = File(dir, UsageLog.FILE).apply { writeText("a\t1.0\t5\n") }
        file.setReadable(false)
        // Root reads it all the same: then there is no unreadable file to test, a skip, not a failure.
        assumeFalse("running as root", file.canRead())
        assertNull(UsageLog.read(dir, noLog))
    }

    @Test fun `a file of exactly the cap is read`() {
        val dir = folder.newFolder()
        val entry = "a\t1.0\t5\n"
        File(dir, UsageLog.FILE).writeText(entry + "\n".repeat(UsageLog.MAX_BYTES.toInt() - entry.length))
        assertEquals(mapOf("a" to Usage(1.0, 5L)), UsageLog.read(dir, noLog))
    }

    @Test fun `a file too large is not read, only replaced`() {
        val dir = folder.newFolder()
        File(dir, UsageLog.FILE).writeText("a\t1.0\t5\n" + "x".repeat(UsageLog.MAX_BYTES.toInt()))
        assertEquals(emptyMap<String, Usage>(), UsageLog.read(dir, noLog))
    }

    @Test fun `a launch stamped in the future starts to decay once the clock is right`() {
        val ahead = mapOf("a" to Usage(3.0, t0 + 365 * day), "b" to Usage(1.0, t0))
        // A later launch of another app settles it.
        val log = UsageLog.recorded(ahead, "b", t0)
        assertEquals(Usage(3.0, t0), log["a"])
        assertEquals(3.0 * exp(-0.6048), UsageLog.decayed(log.getValue("a"), t0 + 7 * day), 1e-9)
        // So does the load, and the file never keeps it.
        assertEquals(Usage(3.0, t0), UsageLog.merged(ahead, emptyMap(), t0)["a"])
        assertEquals("a\t3.0\t$t0\n", UsageLog.format(mapOf("a" to Usage(3.0, t0 + day)), t0))
    }
}
