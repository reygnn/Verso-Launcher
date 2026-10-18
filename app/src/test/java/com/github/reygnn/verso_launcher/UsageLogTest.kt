package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.exp

class UsageLogTest {

    @get:Rule val folder = TemporaryFolder()

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
            "g\t1.0\tnow\n"
        assertEquals(mapOf("a" to Usage(1.0, 5L)), UsageLog.parse(text))
    }

    @Test fun `no file is an empty log, and a write lands whole`() {
        val dir = folder.newFolder()
        assertTrue(UsageLog.read(dir).isEmpty())
        val log = mapOf("a" to Usage(2.0, t0))
        UsageLog.write(dir, log, t0)
        assertEquals(log, UsageLog.read(dir))
        assertTrue(!File(dir, "${UsageLog.FILE}.tmp").exists())
    }

    @Test fun `an unreadable file is an empty log`() {
        val dir = folder.newFolder()
        File(dir, UsageLog.FILE).mkdir()  // a directory where the file should be
        assertTrue(UsageLog.read(dir).isEmpty())
    }
}
