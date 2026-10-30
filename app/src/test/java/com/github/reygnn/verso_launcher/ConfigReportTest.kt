package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** What "~verify" says about the user's file. */
class ConfigReportTest {

    private val gmail = AppEntry("Gmail", "com.google.gm", "com.google.gm.Main", "Gmail")
    private val maps = AppEntry("Maps", "com.google.maps", "com.google.maps.Main", "Maps")
    private val apps = listOf(gmail, maps)

    private fun report(text: String, apps: List<AppEntry>? = this.apps, complete: Boolean = true) =
        ConfigReport.findings(ConfigStatus.READ, ConfigText.parse(text), apps, complete)

    @Test fun `a file of entries that all name an app is all good`() {
        assertEquals(listOf(ConfigFinding.AllGood), report("# mine\nfav Gmail\nhide ${maps.key}\nname Maps = Karte"))
    }

    @Test fun `an empty file is all good, not missing`() {
        assertEquals(listOf(ConfigFinding.AllGood), report(""))
    }

    /** The two kinds interleave by line number: the order the user reads the file in. */
    @Test fun `bad lines and lines that name no app come in file order, with their text`() {
        val found = report("fav Gmial\nfavourite Maps\nfav Gmail\ntag Mpas = travel")
        assertEquals(
            listOf(
                ConfigFinding.NoApp(ConfigLine(1, "fav Gmial")),
                ConfigFinding.BadLine(ConfigLine(2, "favourite Maps")),
                ConfigFinding.NoApp(ConfigLine(4, "tag Mpas = travel")),
            ),
            found,
        )
    }

    /** Hard rule 14: the key of an app that is gone stays in effect, but it names nothing installed. */
    @Test fun `a key of an app that is gone names no app`() {
        assertEquals(
            listOf(ConfigFinding.NoApp(ConfigLine(1, "fav com.gone/com.gone.Main"))),
            report("fav com.gone/com.gone.Main"),
        )
    }

    @Test fun `a file that was not read is that one finding`() {
        for (status in listOf(ConfigStatus.MISSING, ConfigStatus.TOO_LARGE, ConfigStatus.UNREADABLE)) {
            assertEquals(listOf(ConfigFinding.NotRead(status)), ConfigReport.findings(status, ConfigText(), apps, complete = true))
        }
    }

    /** Before the first list no entry can be called unresolved: bad lines only, and why. */
    @Test fun `before the first list only bad lines show, and that the list is missing`() {
        assertEquals(
            listOf(ConfigFinding.BadLine(ConfigLine(2, "favourite Maps")), ConfigFinding.ListIncomplete),
            report("fav Gmial\nfavourite Maps", apps = null),
        )
    }

    /** A profile that was not read may hold the app: the report says the list is not whole. */
    @Test fun `an incomplete list is said`() {
        assertEquals(listOf(ConfigFinding.ListIncomplete), report("fav Gmail", complete = false))
        assertEquals(
            listOf(ConfigFinding.NoApp(ConfigLine(1, "fav Gmial")), ConfigFinding.ListIncomplete),
            report("fav Gmial", complete = false),
        )
    }
}
