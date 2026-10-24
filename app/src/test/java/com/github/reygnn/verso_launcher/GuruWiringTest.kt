package com.github.reygnn.verso_launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.github.reygnn.guru.GuruEnv
import com.github.reygnn.guru.GuruHandler
import com.github.reygnn.guru.GuruProcess
import com.github.reygnn.guru.GuruStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Guru as Verso wires it: VersoApp's install on the real IO thread, MainActivity's showPending.
 * Guru's own behaviour is tested in its repo; this only shows that it runs here at all and that
 * Verso's hand-back loses no alert. A start is VersoApp.onCreate run again, as a process start
 * would run it, with a report the handler left behind.
 */
@RunWith(RobolectricTestRunner::class)
class GuruWiringTest {

    private val app = RuntimeEnvironment.getApplication() as VersoApp
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val hold = CountDownLatch(1)
    private var handler: Thread.UncaughtExceptionHandler? = null
    private lateinit var media: Downloads

    @Before fun start() {
        // Guru.install sets the handler for the whole JVM; a later test starts from a clean one.
        handler = Thread.getDefaultUncaughtExceptionHandler()
        media = Robolectric.setupContentProvider(Downloads::class.java, MediaStore.AUTHORITY)
        // Robolectric's own start of VersoApp has queued Guru's steps already; done before the report.
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        GuruStore(File(app.filesDir, "guru"), log = { _, _ -> }, syncDir = {}).write(
            GuruProcess(GuruEnv(app.packageName, "0", 0, "16", 36, "brand", "model", "device"), 0)
                .crash(UUID.randomUUID().toString(), "worker", IllegalStateException("boom"), System.currentTimeMillis() - 60_000),
        )
    }

    @After fun release() {
        hold.countDown()
        settle()
        if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun settle() = repeat(4) {
        app.io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun reportsLeft() = File(app.filesDir, "guru").list().orEmpty().count { it.startsWith("guru-") }

    private fun alertShowing() = ShadowDialog.getLatestDialog()?.isShowing == true

    @Test fun `a report left by the last run is saved on Verso's IO thread and shown`() {
        app.onCreate()
        controller.setup()
        settle()

        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is GuruHandler)
        assertEquals(1, media.published())
        assertEquals(0, reportsLeft())
        assertTrue(alertShowing())
    }

    @Test fun `an alert whose activity was recreated before it came shows on the new one`() {
        // The save is slow (MediaStore); a recreation lands before Guru hands the alert back.
        app.io.execute { hold.await(5, TimeUnit.SECONDS) }
        app.onCreate()
        controller.setup()
        controller.recreate()
        hold.countDown()
        settle()

        assertEquals(1, media.published())
        assertTrue(alertShowing())
        assertEquals(1, ShadowDialog.getShownDialogs().size)
    }

    /** MediaStore for Robolectric, which has none: what Guru's save needs, its bytes in real files. */
    class Downloads : ContentProvider() {
        private val pending = mutableMapOf<Uri, Int>()

        fun published() = pending.values.count { it == 0 }

        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri =
            uri.buildUpon().appendPath((pending.size + 1).toString()).build().also { pending[it] = 1 }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            File(context!!.cacheDir, "download-${uri.lastPathSegment}"),
            ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE,
        )

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            pending[uri] = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) ?: return 0
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) =
            if (pending.remove(uri) != null) 1 else 0

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))

        override fun getType(uri: Uri): String? = null
    }
}
