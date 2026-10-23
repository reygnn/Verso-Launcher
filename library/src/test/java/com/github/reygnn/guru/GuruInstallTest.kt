package com.github.reygnn.guru

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

@RunWith(RobolectricTestRunner::class)
class GuruInstallTest {

    @get:Rule val host = GuruHostRule()

    private val now get() = System.currentTimeMillis()

    private fun exit(reason: Int, at: Long, description: String = "", dump: String? = null) {
        val am = host.app.getSystemService(ActivityManager::class.java)
        val builder = ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setTimestamp(at)
            .setDescription(description)
            .setProcessName(host.app.packageName)
        if (dump != null) builder.setTraceInputStream(dump.byteInputStream())
        shadowOf(am).addApplicationExitInfo(builder.build())
    }

    private fun receiversFor(action: String) =
        shadowOf(host.app).registeredReceivers.count { it.intentFilter.hasAction(action) }

    // ---- the handler ------------------------------------------------------------

    @Test
    fun `a second install keeps one handler - one report, the previous handler reached once`() {
        val reached = mutableListOf<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> reached += e }
        Guru.install(host.app, GuruSink.Share(), host.io)
        Guru.install(host.app, GuruSink.Share(), host.io)

        val boom = IllegalStateException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(listOf<Throwable>(boom), reached)
        val pending = host.store.pending()
        assertEquals(1, pending.size)
        assertEquals(GuruKind.CRASH, pending.single().kind)
        assertTrue(pending.single().trace.startsWith("java.lang.IllegalStateException: boom"))
    }

    // ---- the receiver -----------------------------------------------------------

    @Test
    fun `Share registers the chooser's receiver, not exported`() {
        Guru.install(host.app, GuruSink.Share(), host.io)

        val action = host.app.packageName + ".guru.SHARED"
        val registered = shadowOf(host.app).registeredReceivers.filter { it.intentFilter.hasAction(action) }
        assertTrue(registered.isNotEmpty())
        assertTrue(registered.all { it.flags and Context.RECEIVER_NOT_EXPORTED != 0 })
    }

    @Test
    fun `Acra registers no receiver`() {
        Guru.install(host.app, GuruSink.Acra("https://acra.example.org/report", "u", "p"), host.io)

        assertEquals(0, receiversFor(host.app.packageName + ".guru.SHARED"))
    }

    // ---- the exit records -------------------------------------------------------

    @Test
    fun `the exit look waits for the host's io`() {
        exit(ApplicationExitInfo.REASON_ANR, now - 1_000, "Input dispatching timed out")
        Guru.install(host.app, GuruSink.Share(), host.io)

        assertEquals(1, host.queuedIo())
        assertEquals(null, host.store.exitsSeen())
    }

    @Test
    fun `the first start reports nothing and sets the mark`() {
        exit(ApplicationExitInfo.REASON_ANR, now - 1_000, "Input dispatching timed out")
        exit(ApplicationExitInfo.REASON_CRASH_NATIVE, now - 2_000, "SIGSEGV")
        val before = now

        Guru.install(host.app, GuruSink.Share(), host.io)
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

        Guru.install(host.app, GuruSink.Share(), host.io)
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

        Guru.install(host.app, GuruSink.Share(), host.io)
        host.runIo()

        assertEquals(listOf(caught.id), host.store.pending().map { it.id })
    }

    @Test
    fun `exits before the mark and other reasons stay out`() {
        val mark = now - GuruHostRule.MINUTE_MS
        host.store.markExitsSeen(mark)
        exit(ApplicationExitInfo.REASON_ANR, mark - 1, "old")
        exit(ApplicationExitInfo.REASON_USER_REQUESTED, mark + 1_000, "force stop")

        Guru.install(host.app, GuruSink.Share(), host.io)
        host.runIo()

        assertTrue(host.store.pending().isEmpty())
    }
}
