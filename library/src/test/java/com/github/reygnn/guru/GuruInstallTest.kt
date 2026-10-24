package com.github.reygnn.guru

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class GuruInstallTest {

    @get:Rule val host = GuruHostRule()

    // Every save refused: the reports the exit look writes stay in the store, where these tests
    // read them. Saving is GuruSaveTest's.
    @Before
    fun setUp() {
        FakeMediaStore.install().refuse = (1..100).toSet()
    }

    private val now get() = System.currentTimeMillis()

    private fun exit(reason: Int, at: Long, description: String = "", dump: String? = null) =
        exit(reason, at, description, dump?.byteInputStream())

    private fun exit(reason: Int, at: Long, description: String, dump: InputStream?) {
        val am = host.app.getSystemService(ActivityManager::class.java)
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setTimestamp(at)
            .setDescription(description)
            .setProcessName(host.app.packageName)
        if (dump != null) builder.setTraceInputStream(dump)
        shadowOf(am).addApplicationExitInfo(builder.build())
    }

    // ---- the handler ------------------------------------------------------------

    @Test
    fun `a second install keeps one handler - one report, the previous handler reached once`() {
        val reached = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> reached += e }
        Guru.install(host.app, host.io)
        Guru.install(host.app, host.io)

        val boom = IllegalStateException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(listOf<Throwable>(boom), reached)
        val pending = host.store.pending()
        assertEquals(1, pending.size)
        assertEquals(GuruKind.CRASH, pending.single().kind)
        assertTrue(pending.single().trace.startsWith("java.lang.IllegalStateException: boom"))
    }

    // ---- no receiver ------------------------------------------------------------

    @Test
    fun `install registers no receiver`() {
        val before = shadowOf(host.app).registeredReceivers.size

        Guru.install(host.app, host.io)

        assertEquals(before, shadowOf(host.app).registeredReceivers.size)
    }

    // ---- the exit records -------------------------------------------------------

    @Test
    fun `the exit look waits for the host's io`() {
        exit(ApplicationExitInfo.REASON_ANR, now - 1_000, "Input dispatching timed out")
        Guru.install(host.app, host.io)

        assertEquals(2, host.queuedIo())
        assertEquals(null, host.store.exitsSeen())
    }

    @Test
    fun `the first start reports nothing and sets the mark`() {
        exit(ApplicationExitInfo.REASON_ANR, now - 1_000, "Input dispatching timed out")
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, now - 2_000, "SIGSEGV")
        val before = now

        Guru.install(host.app, host.io)
        host.runIo()

        assertTrue(host.store.pending().isEmpty())
        val mark = host.store.exitsSeen()
        assertNotNull(mark)
        assertTrue(mark!! >= before)
    }

    @Test
    fun `an ANR after the mark becomes a report with its dump`() {
        host.store.markExitsSeen(now - 10 * GuruHostRule.MINUTE_MS)
        val at = now - GuruHostRule.MINUTE_MS
        exit(ApplicationExitInfo.REASON_ANR, at, "Input dispatching timed out", dump = "\"main\" prio=5 tid=1 Sleeping\n  at java.lang.Thread.sleep")

        Guru.install(host.app, host.io)
        host.runIo()

        val anr = host.store.pending().single()
        assertEquals(GuruKind.ANR, anr.kind)
        assertEquals(at, anr.crashedAt)
        assertTrue(anr.trace.startsWith("ApplicationNotResponding: Input dispatching timed out"))
        assertTrue(anr.trace.contains("\"main\" prio=5 tid=1 Sleeping"))
        assertEquals(at, host.store.exitsSeen())
    }

    @Test
    fun `a Java crash the handler caught is not reported twice`() {
        host.store.markExitsSeen(now - 10 * GuruHostRule.MINUTE_MS)
        val caught = host.crashed("0b6c2f8e-1d2a-4c3b-9e8f-000000000001", at = now - 2 * GuruHostRule.MINUTE_MS)
        exit(ApplicationExitInfo.REASON_CRASH, caught.crashedAt + 500, "crash")

        Guru.install(host.app, host.io)
        host.runIo()

        assertEquals(listOf(caught.id), host.store.pending().map { it.id })
    }

    @Test
    fun `exits before the mark and other reasons stay out`() {
        val mark = now - GuruHostRule.MINUTE_MS
        host.store.markExitsSeen(mark)
        exit(ApplicationExitInfo.REASON_ANR, mark - 1, "old")
        exit(ApplicationExitInfo.REASON_USER_REQUESTED, mark + 1_000, "force stop")

        Guru.install(host.app, host.io)
        host.runIo()

        assertTrue(host.store.pending().isEmpty())
    }

    @Test
    fun `an unreadable ANR dump still makes a report, and the mark moves`() {
        host.store.markExitsSeen(now - 10 * GuruHostRule.MINUTE_MS)
        val crashAt = now - 2 * GuruHostRule.MINUTE_MS
        val anrAt = now - GuruHostRule.MINUTE_MS
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, crashAt, "SIGSEGV")
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("dump gone")
        }
        exit(ApplicationExitInfo.REASON_ANR, anrAt, "Input dispatching timed out", unreadable)

        Guru.install(host.app, host.io)
        host.runIo()

        val pending = host.store.pending()
        assertEquals(setOf(GuruKind.ANR, GuruKind.NATIVE), pending.map { it.kind }.toSet())
        assertEquals("ApplicationNotResponding: Input dispatching timed out", pending.single { it.kind == GuruKind.ANR }.trace)
        assertEquals(anrAt, host.store.exitsSeen())
    }

    @Test
    fun `a dump that throws unchecked still makes a report, and the mark moves`() {
        host.store.markExitsSeen(now - 10 * GuruHostRule.MINUTE_MS)
        val at = now - GuruHostRule.MINUTE_MS
        val broken = object : InputStream() {
            override fun read(): Int = throw IllegalStateException("dump gone")
        }
        exit(ApplicationExitInfo.REASON_ANR, at, "Input dispatching timed out", broken)

        Guru.install(host.app, host.io)
        host.runIo()

        assertEquals("ApplicationNotResponding: Input dispatching timed out", host.store.pending().single().trace)
        assertEquals(at, host.store.exitsSeen())
    }

    @Test
    fun `a native crash's tombstone stays out`() {
        host.store.markExitsSeen(now - 10 * GuruHostRule.MINUTE_MS)
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, now - GuruHostRule.MINUTE_MS, "SIGSEGV", dump = "TOMBSTONE BYTES")

        Guru.install(host.app, host.io)
        host.runIo()

        assertEquals("NativeCrash: SIGSEGV", host.store.pending().single().trace)
    }

    @Test
    fun `an ANR report beside a crash exit does not hide it`() {
        host.store.markExitsSeen(now - 10 * GuruHostRule.MINUTE_MS)
        val anr = report(id = "0b6c2f8e-1d2a-4c3b-9e8f-000000000002", kind = GuruKind.ANR, crashedAt = now - 2 * GuruHostRule.MINUTE_MS)
        host.store.write(anr)
        exit(ApplicationExitInfo.REASON_CRASH, anr.crashedAt + 500, "crash")

        Guru.install(host.app, host.io)
        host.runIo()

        assertEquals(setOf(GuruKind.ANR, GuruKind.CRASH), host.store.pending().map { it.kind }.toSet())
    }
}
