package com.github.reygnn.guru

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.provider.MediaStore
import android.view.ViewGroup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.time.ZoneId

/** Saving at the start, before anything is shown: install's second io step. */
@RunWith(RobolectricTestRunner::class)
class GuruSaveTest {

    @get:Rule val host = GuruHostRule()

    private val a = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000a"
    private val b = "0b6c2f8e-1d2a-4c3b-9e8f-00000000000b"

    private lateinit var media: FakeMediaStore

    @Before
    fun setUp() {
        media = FakeMediaStore.install()
    }

    /** A start: install, the exit history already marked, both io steps run. */
    private fun start() {
        Guru.install(host.app, host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)
        host.runIo()
    }

    private val label get() = host.app.applicationInfo.loadLabel(host.app.packageManager).toString()

    private val name get() = MediaStore.MediaColumns.DISPLAY_NAME

    /** The alert for what this start saved: how many it names, or null when none comes. */
    private fun alertCount(): Int? {
        Guru.showPending(host.activity().get()) { it() }
        host.runIo()
        val dialog = ShadowDialog.getLatestDialog() ?: return null
        val said = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).contentDescription.toString()
        return (GuruStore.KEEP downTo 1).firstOrNull { said.contains(GuruTexts.English.saved(it)) }
    }

    private fun FakeMediaStore.Row.json() = JSONObject(file.readText(Charsets.UTF_8))

    private fun ours() = media.rows.filter { it.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).trimEnd('/') == "Download/Guru" }

    /** Two reports, a older than b: pending (and saved) b first. */
    private fun twoReports(): Pair<GuruReport, GuruReport> {
        val older = host.crashed(a, at = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS)
        val newer = host.crashed(b)
        return older to newer
    }

    @Test
    fun `the save waits for the host's io, behind the exit look`() {
        host.crashed(a)
        Guru.install(host.app, host.io)

        assertEquals(2, host.queuedIo())
        assertTrue(media.rows.isEmpty())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `a start saves each report as a json file in Download-Guru and removes it`() {
        val (older, newer) = twoReports()

        start()

        val zone = ZoneId.systemDefault()
        assertEquals(
            listOf(newer, older).map { GuruDownloads.baseName(label, it, zone) + ".json" },
            media.rows.map { it.values.getAsString(name) },
        )
        media.rows.forEach { row ->
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.collection)
            assertEquals("Download/Guru", row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
            assertEquals("application/json", row.values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
            assertEquals(0, row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        }
        assertTrue(host.store.pending().isEmpty())
    }

    @Test
    fun `each file holds its own report's ACRA JSON`() {
        val (older, newer) = twoReports()

        start()

        mapOf(newer to media.rows[0], older to media.rows[1]).forEach { (report, row) ->
            assertEquals(report.id, row.json().getString("REPORT_ID"))
            assertEquals(report.number, row.json().getJSONObject("CUSTOM_DATA").getString("GURU_MEDITATION"))
        }
    }

    @Test
    fun `a file is hidden until all its bytes are in`() {
        host.crashed(a)

        start()

        val row = media.rows.single()
        assertEquals(1, row.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(row.file.length(), row.sizeAtPublish)
        assertTrue(row.file.length() > 0)
    }

    @Test
    fun `nothing pending saves nothing`() {
        start()

        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `files are named after the app's label`() {
        host.crashed(a)
        host.app.applicationInfo.nonLocalizedLabel = "Guru Sample"

        start()

        assertTrue(media.rows.single().values.getAsString(name).startsWith("Guru_Sample-"))
    }

    @Test
    fun `an app without a label names its files by package`() {
        val report = host.crashed(a)
        host.app.applicationInfo.nonLocalizedLabel = " "

        start()

        assertEquals(
            GuruDownloads.baseName(host.app.packageName, report, ZoneId.systemDefault()) + ".json",
            media.rows.single().values.getAsString(name),
        )
    }

    @Test
    fun `a refused save keeps the report for the next start`() {
        host.crashed(a)
        media.refuse = setOf(1)

        start()

        assertTrue(media.rows.isEmpty())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `a failed write leaves no file and keeps the report`() {
        host.crashed(a)
        media.failWrite = true

        start()

        assertTrue(media.rows.isEmpty())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `a file the provider will not publish is not saved`() {
        host.crashed(a)
        media.refuseUpdate = true

        start()

        assertTrue(media.rows.isEmpty())
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `a provider that throws keeps that report and saves the rest`() {
        val (older, newer) = twoReports()
        media.throwOn = setOf(1)

        start()

        assertEquals(listOf(older.id), media.rows.map { it.json().getString("REPORT_ID") })
        assertEquals(listOf(newer.id), host.store.pending().map { it.id })
    }

    @Test
    fun `an unfinished file that cannot be removed keeps that report and stops no other`() {
        val (older, newer) = twoReports()
        media.failWriteOn = setOf(1L)
        media.throwOnDelete = true

        start()

        assertEquals(listOf(older.id), media.rows.filter { it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 }
            .map { it.json().getString("REPORT_ID") })
        assertEquals(listOf(newer.id), host.store.pending().map { it.id })
        assertEquals(1, alertCount())
    }

    @Test
    fun `a refused save comes again on the next start`() {
        host.crashed(a)
        media.refuse = setOf(1)
        start()

        start()

        assertEquals(listOf(a), media.rows.map { it.json().getString("REPORT_ID") })
        assertTrue(host.store.pending().isEmpty())
    }

    @Test
    fun `a crash the handler caught is saved once, though its exit record comes too`() {
        val caught = host.crashed(a, at = System.currentTimeMillis() - 2 * GuruHostRule.MINUTE_MS)
        val am = host.app.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_CRASH)
                .setTimestamp(caught.crashedAt + 500)
                .setProcessName(host.app.packageName)
                .build(),
        )
        Guru.install(host.app, host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - 10 * GuruHostRule.MINUTE_MS)

        host.runIo()

        assertEquals(listOf(a), media.rows.map { it.json().getString("REPORT_ID") })
        assertTrue(host.store.pending().isEmpty())
    }

    // ---- pruning ------------------------------------------------------------------

    @Test
    fun `only the newest 32 of Guru's own files stay`() {
        assertEquals(32, GuruDownloads.KEEP)
        val old = (1..GuruDownloads.KEEP).map { media.published("Download/Guru/") }
        host.crashed(a)

        start()

        assertEquals(GuruDownloads.KEEP, ours().size)
        assertEquals(old.drop(1).map { it.id } + media.rows.last().id, ours().map { it.id })
    }

    @Test
    fun `files not ours, or elsewhere, are never pruned`() {
        val foreign = (1..GuruDownloads.KEEP + 2).map { media.published("Download/Guru/", owner = "com.example.other") }
        val elsewhere = (1..GuruDownloads.KEEP + 2).map { media.published("Download/Other/") }
        host.crashed(a)

        start()

        assertTrue(media.rows.containsAll(foreign + elsewhere))
    }

    @Test
    fun `nothing pending prunes nothing`() {
        repeat(GuruDownloads.KEEP + 1) { media.published("Download/Guru/") }

        start()

        assertEquals(GuruDownloads.KEEP + 1, ours().size)
    }

    @Test
    fun `a listing that fails prunes nothing and keeps the save`() {
        repeat(GuruDownloads.KEEP) { media.published("Download/Guru/") }
        host.crashed(a)
        media.throwOnQuery = true

        start()

        assertEquals(GuruDownloads.KEEP + 1, ours().size)
        assertTrue(host.store.pending().isEmpty())
        assertEquals(1, alertCount())
    }

    @Test
    fun `an old file that cannot be pruned stops nothing`() {
        repeat(GuruDownloads.KEEP) { media.published("Download/Guru/") }
        val (older, newer) = twoReports()
        media.throwOnDelete = true

        start()

        assertEquals(listOf(newer.id, older.id), ours().takeLast(2).map { it.json().getString("REPORT_ID") })
        assertTrue(host.store.pending().isEmpty())
        assertEquals(2, alertCount())
    }

    @Test
    fun `pending reports prune even when none could be saved`() {
        repeat(GuruDownloads.KEEP + 1) { media.published("Download/Guru/") }
        host.crashed(a)
        media.refuse = setOf(GuruDownloads.KEEP + 2)

        start()

        assertEquals(GuruDownloads.KEEP, ours().size)
        assertEquals(listOf(a), host.store.pending().map { it.id })
    }

    @Test
    fun `every file carries the installation's one id`() {
        twoReports()

        start()

        val id = host.store.installationId { error("made already") }
        assertEquals(listOf(id, id), media.rows.map { it.json().getString("INSTALLATION_ID") })
    }

    @Test
    fun `a trace beyond ASCII comes back exact`() {
        val text = "java.lang.IllegalStateException: Grüße ✓ 版 😀"
        host.store.write(report(id = a, trace = "$text\n\tat com.example.app.Main.run(Main.kt:12)"))

        start()

        assertTrue(media.rows.single().json().getString("STACK_TRACE").startsWith(text))
    }

    @Test
    fun `the crashed app's version, as install read it, is in the file's name`() {
        shadowOf(host.app.packageManager).getInternalMutablePackageInfo(host.app.packageName).versionName = "2.5.0"
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        Guru.install(host.app, host.io)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        start()

        val got = media.rows.single().values.getAsString(name)
        assertTrue(got, got.contains("-2.5.0-"))
    }

    @Test
    fun `a saved crash still meets its exit record at a later start`() {
        val caught = host.crashed(a, at = System.currentTimeMillis() - 30_000)
        start()
        val am = host.app.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_CRASH)
                .setTimestamp(caught.crashedAt + 500)
                .setProcessName(host.app.packageName)
                .build(),
        )

        start()

        assertEquals(listOf(a), media.rows.map { it.json().getString("REPORT_ID") })
        assertTrue(host.store.pending().isEmpty())
    }

    @Test
    fun `a saved ANR's time does not hide a crash exit beside it`() {
        val anr = report(id = a, kind = GuruKind.ANR, crashedAt = System.currentTimeMillis() - 30_000).also { host.store.write(it) }
        start()
        val am = host.app.getSystemService(ActivityManager::class.java)
        shadowOf(am).addApplicationExitInfo(
            ApplicationExitInfoBuilder.newBuilder()
                .setReason(ApplicationExitInfo.REASON_CRASH)
                .setTimestamp(anr.crashedAt + 500)
                .setProcessName(host.app.packageName)
                .build(),
        )

        start()

        assertEquals(listOf("ANR", "CRASH"), media.rows.map { it.json().getJSONObject("CUSTOM_DATA").getString("GURU_KIND") })
    }

    /**
     * Each io step is guarded on its own: an exit look that throws (here the system's
     * ActivityManager) reaches neither the host's io thread nor the save after it.
     */
    @Test
    fun `an exit look that throws stops neither io nor the save`() {
        host.crashed(a)
        val app = object : Application() {
            init {
                attachBaseContext(host.app)
            }

            override fun getSystemService(name: String): Any? =
                if (name == Context.ACTIVITY_SERVICE) throw SecurityException("no exit records") else super.getSystemService(name)
        }
        Guru.install(app, host.io)
        host.store.markExitsSeen(System.currentTimeMillis() - GuruHostRule.MINUTE_MS)

        host.runIo()

        assertEquals(listOf(a), media.rows.map { it.json().getString("REPORT_ID") })
    }

    // ---- saves that died halfway --------------------------------------------------

    @Test
    fun `a save that died halfway is cleared, its report saved anew`() {
        val halfway = media.pending("Download/Guru/")
        host.crashed(a)

        start()

        assertTrue(halfway !in media.rows)
        assertEquals(listOf(a), media.rows.map { it.json().getString("REPORT_ID") })
    }

    @Test
    fun `hidden rows not ours, or elsewhere, stay`() {
        val foreign = media.pending("Download/Guru/", owner = "com.example.other")
        val elsewhere = media.pending("Download/Other/")
        host.crashed(a)

        start()

        assertTrue(media.rows.containsAll(listOf(foreign, elsewhere)))
    }

    @Test
    fun `hidden rows do not count among the 32`() {
        repeat(GuruDownloads.KEEP - 1) { media.published("Download/Guru/") }
        repeat(3) { media.pending("Download/Guru/") }
        host.crashed(a)

        start()

        assertEquals(GuruDownloads.KEEP, ours().size)
        assertTrue(ours().all { it.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 })
    }
}
