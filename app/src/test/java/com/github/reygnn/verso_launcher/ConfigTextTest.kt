package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The config file the user writes: its lines, the apps they name, and a new file written from a config. */
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
        val tags = (1..ConfigJson.MAX_TAGS_PER_APP + 5).joinToString(", ") { "t$it" }
        val config = resolved("name Maps = \u202EKarte\u202C\ntag Maps = #Reise, Reise, $tags")
        assertEquals("Karte", config.names[maps.key])
        assertEquals("reise", config.tags[maps.key]!!.first())
        assertEquals(ConfigJson.MAX_TAGS_PER_APP, config.tags[maps.key]!!.size)
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
        assertFalse(text.isNoConfig)
    }

    @Test fun `a file of lines none of which is an entry is no config, an empty or comment-only one is`() {
        assertTrue(ConfigText.parse("{\n  \"favorites\": []\n}").isNoConfig)
        assertFalse(ConfigText.parse("").isNoConfig)
        assertFalse(ConfigText.parse("# nothing yet\n\n").isNoConfig)
    }

    /** Hard rule 7: an old backup is read with parseForeign; a file that only starts like JSON is lines. */
    @Test fun `a JSON config of an earlier version reads as its keys`() {
        val config = VersoConfig(favorites = listOf(maps.key), hidden = setOf(firefox.key), names = mapOf(gmail.key to "Mail"))
        assertEquals(config, ConfigText.resolve(ConfigText.read("\uFEFF  " + legacyJson(config)), apps))
        assertEquals(ConfigText.of(config), ConfigText.read(legacyJson(config)))
        assertTrue("JSON, but no config", ConfigText.read("{\"name\": \"some package.json\"}").isNoConfig)
        assertTrue("not even JSON", ConfigText.read("{ fav Maps").isNoConfig)
        assertEquals(listOf("Maps"), ConfigText.read("fav Maps").favorites)
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
        assertEquals(config, ConfigText.resolve(ConfigText.of(config), apps))
    }

    /** A label changes with the device language, a package does not: the label is a comment. */
    @Test fun `a new file names apps by package or key, labels above them, and reads back as it was`() {
        val config = VersoConfig(
            hidden = setOf(maps.key, workGmail.key),
            favorites = listOf(dialer.key, gmail.key, "com.gone/com.gone.Main", whatsApp.key),
            names = mapOf(firefox.key to "Web = Browser"),
            tags = mapOf(maps.key to listOf("travel", "map")),
        )
        val text = ConfigText.format(config, apps, header = listOf("Verso", "", "one line each"))
        assertEquals(
            """
            # Verso
            #
            # one line each

            # Phone
            fav ${dialer.key}
            # Gmail
            fav com.google.gm
            fav com.gone/com.gone.Main
            # WhatsApp
            fav com.whatsapp

            # Maps
            hide com.google.maps
            # Gmail (Work)
            hide ${workGmail.key}

            # Firefox
            name org.mozilla.firefox = Web = Browser

            # Maps
            tag com.google.maps = travel, map

            """.trimIndent(),
            text,
        )
        assertEquals(config, ConfigText.resolve(ConfigText.parse(text), apps))
    }

    /** One package, two launcher activities: the package would name both. */
    @Test fun `a package with two launcher activities is never used for one of them`() {
        val config = VersoConfig(favorites = listOf(contacts.key))
        val text = ConfigText.format(config, listOf(dialer, contacts), header = emptyList())
        assertEquals("\n# Contacts\nfav ${contacts.key}\n", text)
        assertEquals(config, ConfigText.resolve(ConfigText.parse(text), listOf(dialer, contacts)))
    }

    @Test fun `a label no comment line could hold is cleaned there, and the entry stays`() {
        val odd = AppEntry(" No\ntes\u202E ", "org.notes", "org.notes.Main", " No\ntes\u202E ")
        val config = VersoConfig(favorites = listOf(odd.key))
        val text = ConfigText.format(config, listOf(odd), header = emptyList())
        assertEquals("\n# No tes\nfav org.notes\n", text)
        assertEquals(config, ConfigText.resolve(ConfigText.parse(text), listOf(odd)))
    }
}
