package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The config file the user writes: its lines, the apps they name, and its capped read ([ConfigText.readCapped]). */
class ConfigTextTest {

    private val whatsApp = AppEntry("WhatsApp", "com.whatsapp", "com.whatsapp.Main", "WhatsApp")
    private val firefox = AppEntry("Firefox", "org.mozilla.firefox", "org.mozilla.Launch", "Firefox")
    private val maps = AppEntry("Maps", "com.google.maps", "com.google.maps.Main", "Maps")
    private val gmail = AppEntry("Gmail", "com.google.gm", "com.google.gm.Main", "Gmail")
    private val workGmail = AppEntry("Gmail (Work)", "com.google.gm", "com.google.gm.Main", "Gmail (Work)", profile = 10)
    // One package, two launcher activities: its package names both.
    private val dialer = AppEntry("Phone", "com.android.dialer", "com.android.dialer.Dial", "Phone")
    private val contacts = AppEntry("Contacts", "com.android.dialer", "com.android.dialer.People", "Contacts")
    private val apps = listOf(whatsApp, firefox, maps, gmail, workGmail, dialer, contacts)

    private fun resolved(text: String) = ConfigText.resolve(ConfigText.parse(text), apps)

    @Test fun `each kind of line sets its part, the favorites in file order`() {
        val config = resolved(
            """
            fav Firefox
            fav WhatsApp
            hide Maps
            name Firefox = Web
            tag Maps = travel, map
            """.trimIndent(),
        )
        assertEquals(listOf(firefox.key, whatsApp.key), config.favorites)
        assertEquals(setOf(maps.key), config.hidden)
        assertEquals(mapOf(firefox.key to "Web"), config.names)
        assertEquals(mapOf(maps.key to listOf("travel", "map")), config.tags)
    }

    @Test fun `an app is named by its key, its package or its system label`() {
        val config = resolved("fav ${maps.key}\nfav org.mozilla.firefox\nfav whatsapp")
        assertEquals(listOf(maps.key, firefox.key, whatsApp.key), config.favorites)
    }

    /** Hard rule 9: the label is matched folded, so a Turkish İ in the file finds the app's i. */
    @Test fun `a label is matched folded`() {
        val iban = AppEntry("iBAN", "com.bank", "com.bank.Main", "iBAN")
        val config = ConfigText.resolve(ConfigText.parse("fav İBAN"), listOf(iban))
        assertEquals(listOf(iban.key), config.favorites)
    }

    @Test fun `a package names every launcher activity of it in the personal profile only`() {
        assertEquals(listOf(dialer.key, contacts.key), resolved("fav com.android.dialer").favorites)
        assertEquals(listOf(gmail.key), resolved("fav com.google.gm").favorites)
    }

    @Test fun `another profile's copy is named by its badged label or its key`() {
        assertEquals(listOf(workGmail.key), resolved("fav Gmail (Work)").favorites)
        assertEquals(listOf(workGmail.key), resolved("fav ${workGmail.key}").favorites)
    }

    /** Hard rule 14: what the file says about an app that is gone stays, by key; by name it waits. */
    @Test fun `a key of an app that is gone stands, a package or label of one names nothing`() {
        val config = resolved("fav com.gone/com.gone.Main\nfav com.gone\nfav Gone App")
        assertEquals(listOf("com.gone/com.gone.Main"), config.favorites)
    }

    @Test fun `a label with a slash is a label, not a key`() {
        val band = AppEntry("AC/DC", "com.band", "com.band.Main", "AC/DC")
        assertEquals(listOf(band.key), ConfigText.resolve(ConfigText.parse("fav AC/DC"), listOf(band)).favorites)
    }

    @Test fun `the last name wins, tags add up without duplicates, an app named twice is one favorite`() {
        val config = resolved(
            """
            name Maps = One
            name com.google.maps = Two
            tag Maps = a, b
            tag Maps = b, c
            fav Maps
            fav com.google.maps
            """.trimIndent(),
        )
        assertEquals("Two", config.names[maps.key])
        assertEquals(listOf("a", "b", "c"), config.tags[maps.key])
        assertEquals(listOf(maps.key), config.favorites)
    }

    @Test fun `names and tags come in cleaned, a tag list capped`() {
        val tags = (1..LauncherLogic.MAX_TAGS_PER_APP + 5).joinToString(", ") { "t$it" }
        val config = resolved("name Maps = \u202EKarte\u202C\ntag Maps = #Reise, Reise, $tags")
        assertEquals("Karte", config.names[maps.key])
        assertEquals("reise", config.tags[maps.key]!!.first())
        assertEquals(LauncherLogic.MAX_TAGS_PER_APP, config.tags[maps.key]!!.size)
    }

    @Test fun `comments, blank lines, a BOM, CRLF and any keyword case are fine`() {
        val text = ConfigText.parse("\uFEFF# mine\r\n\r\n  FAV   Maps  \r\nHide\tFirefox\r\n")
        assertEquals(listOf("Maps"), text.favorites)
        assertEquals(listOf("Firefox"), text.hidden)
        assertEquals(emptyList<Int>(), text.badLines)
    }

    @Test fun `a line that is no entry is counted by number, and the rest still counts`() {
        val text = ConfigText.parse(
            """
            fav Maps
            favorite Firefox
            fav
            name Maps
            name Maps =
            name = Karte
            tag Maps = ,#,
            hide Firefox
            """.trimIndent(),
        )
        assertEquals(listOf(2, 3, 4, 5, 6, 7), text.badLines)
        assertEquals(listOf("Maps"), text.favorites)
        assertEquals(listOf("Firefox"), text.hidden)
    }

    /** An old JSON backup put where the config goes: every line skipped, nothing set, no throw. */
    @Test fun `a file of no entries sets nothing, an empty or comment-only one skips nothing`() {
        val json = ConfigText.parse("{\n  \"favorites\": []\n}")
        assertEquals(listOf(1, 2, 3), json.badLines)
        assertEquals(ConfigText(badLines = listOf(1, 2, 3)), json)
        assertEquals(ConfigText(), ConfigText.parse(""))
        assertEquals(ConfigText(), ConfigText.parse("# nothing yet\n\n"))
    }

    @Test fun `a name may hold an equals sign, only the first one splits`() {
        assertEquals("a = b", resolved("name Maps = a = b").names[maps.key])
    }

    @Test fun `a config of keys reads as it was`() {
        val config = VersoConfig(
            hidden = setOf(maps.key),
            favorites = listOf(whatsApp.key, "com.gone/com.gone.Main"),
            names = mapOf(firefox.key to "Web"),
            tags = mapOf(maps.key to listOf("travel")),
        )
        assertEquals(config, ConfigText.resolve(textOf(config), apps))
    }

    // ---- readCapped: the OOM guard of the file's read ------------------------------

    private fun capped(text: String, max: Int) = ConfigText.readCapped(ByteArrayInputStream(text.toByteArray()), maxBytes = max)

    @Test fun `reads content under the cap`() {
        assertEquals("hello", capped("hello", 16))
    }

    @Test fun `content exactly at the cap is accepted (inclusive)`() {
        assertEquals("x".repeat(8), capped("x".repeat(8), 8))
    }

    @Test fun `content one byte over the cap is refused, not truncated`() {
        assertNull(capped("x".repeat(9), 8))
    }

    @Test fun `an empty stream reads as empty, never null`() {
        assertEquals("", capped("", 8))
    }

    @Test fun `a chunked over-cap stream is still refused`() {
        // A real ContentProvider can hand out bytes a few at a time.
        val chunky = object : InputStream() {
            private var remaining = 9
            override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(3, len, remaining)
                for (i in 0 until n) b[off + i] = 'x'.code.toByte()
                remaining -= n
                return n
            }
        }
        assertNull(ConfigText.readCapped(chunky, maxBytes = 8))
    }
}
