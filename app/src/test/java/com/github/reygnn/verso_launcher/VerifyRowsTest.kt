package com.github.reygnn.verso_launcher

import android.app.Activity
import android.text.TextUtils
import android.view.View
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the rows of "~verify" say and how they wrap; which findings there are is ConfigReportTest's.
 * The words are spelled out, not read from the resources: two swapped messages would still match
 * their own resource.
 */
@RunWith(RobolectricTestRunner::class)
class VerifyRowsTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val gmail = AppEntry("Gmail", "com.google.gm", "com.google.gm.Main", "Gmail")

    private fun drawer(status: ConfigStatus = ConfigStatus.READ, file: String = "", complete: Boolean = true): AppDrawer {
        val state = testLauncherState(
            io = { it.run() },
            main = { it.run() },
            enumerateApps = { Enumeration(listOf(gmail), complete) },
            onRefreshed = {},
            text = ConfigText.parse(file),
            fileStatus = status,
        ).also { it.refresh() }
        return AppDrawer(
            activity = activity,
            state = state,
            palette = HomeStyle.palette(lightWallpaper = false),
            onAction = {},
            onLongPress = {},
            onFiltered = {},
            onModeChanged = {},
        )
    }

    private val AppDrawer.list: ListView get() = view.collect<ListView>().single()

    private fun AppDrawer.row(position: Int = 0, recycled: View? = null) =
        list.adapter.getView(position, recycled, list) as TextView

    private fun AppDrawer.rows(): List<String> =
        (0 until list.adapter.count).map { row(it).text.toString() }

    private fun said(status: ConfigStatus = ConfigStatus.READ, file: String = "", complete: Boolean = true): List<String> =
        drawer(status, file, complete).apply { setPrompt(LauncherLogic.VERIFY) }.rows()

    // ---- the words ------------------------------------------------------------------------

    @Test fun `no file names where it is looked for, in this build's own folder`() {
        assertEquals(
            listOf("No config.txt in Android/data/${activity.packageName}/files/: the default favorites show"),
            said(ConfigStatus.MISSING),
        )
    }

    @Test fun `a file too large and an unreadable one each say their own`() {
        assertEquals(
            listOf("config.txt is over 1 MB and was not read: the default favorites show"),
            said(ConfigStatus.TOO_LARGE),
        )
        assertEquals(
            listOf("config.txt could not be read: the default favorites show"),
            said(ConfigStatus.UNREADABLE),
        )
    }

    @Test fun `a bad line and a line without an app say their number and their text`() {
        assertEquals(
            listOf(
                "Line 2, no entry, skipped: favourite Gmail",
                "Line 4, no installed app by that name: fav Gmial",
            ),
            said(file = "# mine\nfavourite Gmail\nfav Gmail\nfav Gmial"),
        )
    }

    /** A percent sign in the user's line is an argument of the format, never a part of it. */
    @Test fun `a line with a percent sign is shown as it stands`() {
        assertEquals(listOf("Line 1, no entry, skipped: 100%d %s"), said(file = "100%d %s"))
    }

    @Test fun `a list that is not whole says so, a file without findings says all is good`() {
        assertEquals(
            listOf("Not every app could be listed: an app missing above may be one of them"),
            said(file = "fav Gmail", complete = false),
        )
        assertEquals(listOf("config.txt: every line is an entry, every app found"), said(file = "fav Gmail"))
    }

    /** The translation has to take the same arguments in the same order. */
    @Config(qualifiers = "de")
    @Test fun `the German rows take the number and the text too`() {
        assertEquals(
            listOf(
                "Zeile 1, kein Eintrag, übersprungen: favourite Gmail",
                "Zeile 2, keine installierte App dieses Namens: fav Gmial",
            ),
            said(file = "favourite Gmail\nfav Gmial"),
        )
        assertEquals(
            listOf("Keine config.txt in Android/data/${activity.packageName}/files/: die Standard-Favoriten werden gezeigt"),
            said(ConfigStatus.MISSING),
        )
    }

    // ---- the wrap -------------------------------------------------------------------------

    private fun assertOneLine(row: TextView) {
        assertTrue(row.isSingleLine)
        assertEquals(TextUtils.TruncateAt.END, row.ellipsize)
    }

    private fun assertWraps(row: TextView) {
        assertFalse(row.isSingleLine)
        assertNull(row.ellipsize)
    }

    @Test fun `a finding is read whole, its row wraps`() {
        val drawer = drawer(file = "favourite Gmail")
        drawer.setPrompt(LauncherLogic.VERIFY)
        assertWraps(drawer.row())
    }

    @Test fun `a finding's row recycled for an app is one line again`() {
        val drawer = drawer(file = "favourite Gmail")
        drawer.setPrompt(LauncherLogic.VERIFY)
        val finding = drawer.row()
        drawer.setPrompt("")
        val app = drawer.row(recycled = finding)
        assertEquals("Gmail", app.text.toString())
        assertOneLine(app)
    }

    @Test fun `a finding's row recycled for a command is one line again`() {
        val drawer = drawer(file = "favourite Gmail")
        drawer.setPrompt(LauncherLogic.VERIFY)
        val finding = drawer.row()
        drawer.setPrompt("~")
        assertTrue(drawer.list.adapter.getItem(0) is CommandRow)
        assertOneLine(drawer.row(recycled = finding))
    }

    @Test fun `an app's row recycled for a finding wraps`() {
        val drawer = drawer(file = "favourite Gmail")
        drawer.setPrompt("")
        val app = drawer.row()
        assertOneLine(app)
        drawer.setPrompt(LauncherLogic.VERIFY)
        assertWraps(drawer.row(recycled = app))
    }
}
