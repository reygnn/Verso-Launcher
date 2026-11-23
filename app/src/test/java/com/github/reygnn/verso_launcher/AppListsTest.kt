package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

/** The order and the choice of the lists. The apps are [testApp]s, so no test ever builds a ComponentName. */
class AppListsTest {

    /** Apps named by their (already ROOT-folded) label, for ordering tests: "a" has the key "p/a". */
    private fun rows(vararg names: String): List<AppEntry> = testApps(*names)

    private fun List<AppEntry>.keys(): List<String> = map { it.key }

    // ---- tag order ----------------------------------------------------------

    @Test fun `tags sort in the device language's alphabet, umlauts with their base letter`() {
        assertEquals(
            listOf("arbeit", "ärzte", "spiele", "zuhause"),
            AppLists.sortedTags(listOf("zuhause", "ärzte", "spiele", "arbeit"), Locale.GERMANY),
        )
        assertEquals(
            listOf("arbeit", "zuhause", "ärzte"),
            AppLists.sortedTags(listOf("ärzte", "zuhause", "arbeit"), Locale.of("sv", "SE")),
        )
    }

    @Test fun `the tag overview uses that order`() {
        val tags = mapOf("p/a" to listOf("zuhause", "ärzte"), "p/b" to listOf("arbeit"))
        assertEquals(
            listOf("arbeit", "ärzte", "zuhause"),
            AppLists.tagsInUse(listOf(testApp("p/a", "a"), testApp("p/b", "b")), tags, Locale.GERMANY),
        )
    }

    // ---- named: custom names applied to an enumeration ------------------------------

    /** An enumerated app: label = its own system label, as an enumeration delivers it. */
    private fun sys(label: String) = AppEntry(label, "p.${label.lowercase()}", "C", label)

    @Test fun `named shows a custom name and keeps the system label beside it`() {
        val shown = AppLists.named(listOf(sys("Alpha")), mapOf(sys("Alpha").key to "Zulu"), Locale.GERMANY)
        assertEquals("Zulu", shown.single().label)
        assertEquals("zulu", shown.single().labelLower)  // search sees what is on screen
        assertEquals("Alpha", shown.single().systemLabel)
    }

    @Test fun `named falls back to the system label for an entry that carries a stale name`() {
        val stale = sys("Alpha").withLabel("Old")
        val shown = AppLists.named(listOf(stale), emptyMap(), Locale.GERMANY)
        assertEquals("Alpha", shown.single().label)
        assertEquals("alpha", shown.single().labelLower)
    }

    @Test fun `named keeps an entry whose label already fits as it is`() {
        val alpha = sys("Alpha")
        assertSame(alpha, AppLists.named(listOf(alpha), emptyMap(), Locale.GERMANY).single())
    }

    /** The enumeration sorts already: with no label changed, named must not sort again. */
    @Test fun `named keeps the order it gets when no label changes`() {
        val apps = listOf(sys("Bravo"), sys("Alpha"))
        assertEquals(listOf("Bravo", "Alpha"), AppLists.named(apps, emptyMap(), Locale.GERMANY).map { it.label })
    }

    @Test fun `named sorts by the shown label in the device language`() {
        val apps = listOf(sys("Zoom"), sys("Bravo"), sys("Alpha"))
        val names = mapOf(sys("Alpha").key to "Ärzte")
        // German files Ä under A …
        assertEquals(listOf("Ärzte", "Bravo", "Zoom"), AppLists.named(apps, names, Locale.GERMANY).map { it.label })
        // … Swedish after Z: the order follows the name, in the user's alphabet.
        assertEquals(listOf("Bravo", "Zoom", "Ärzte"), AppLists.named(apps, names, Locale.of("sv", "SE")).map { it.label })
    }

    @Test fun `named applies names to a work copy by its own key only`() {
        val personal = AppEntry("Gmail", "p.gmail", "C", "Gmail")
        val work = AppEntry("Gmail (Work)", "p.gmail", "C", "Gmail (Work)", profile = 10)
        val shown = AppLists.named(listOf(personal, work), mapOf(work.key to "Job mail"), Locale.GERMANY)
        assertEquals(listOf("Gmail", "Job mail"), shown.map { it.label })
    }

    // ---- sortedForDrawer ----------------------------------------------------

    private fun folded(vararg labels: String) = labels.map { testApp("p/$it", it) }

    @Test fun `German drawer files umlauts under their base letter, not after Z`() {
        val sorted = AppLists.sortedForDrawer(folded("Zoom", "Übersetzer", "Bravo", "Ärzte", "Öffi", "Apotheke"), Locale.GERMANY)
        assertEquals(listOf("p/Apotheke", "p/Ärzte", "p/Bravo", "p/Öffi", "p/Übersetzer", "p/Zoom"), sorted.keys())
    }

    @Test fun `accented letters sort with their base letter`() {
        val sorted = AppLists.sortedForDrawer(folded("Facebook", "Éclair", "Drive"), Locale.FRANCE)
        assertEquals(listOf("p/Drive", "p/Éclair", "p/Facebook"), sorted.keys())
    }

    @Test fun `the drawer follows the device language's alphabet`() {
        // Swedish files Ä after Z — "alphabetical" is the user's alphabet, not a fixed one.
        val sorted = AppLists.sortedForDrawer(folded("Zoom", "Ärzte", "Apotheke"), Locale.of("sv", "SE"))
        assertEquals(listOf("p/Apotheke", "p/Zoom", "p/Ärzte"), sorted.keys())
    }

    @Test fun `labels the collator calls equal but that differ are ordered by the label itself`() {
        // "ma\u0001il" collates equal to "mail" but is the smaller string; key order alone would put p/a first.
        val withControl = testApp("p/b", "ma\u0001il")
        val plain = testApp("p/a", "mail")
        assertEquals(listOf("p/b", "p/a"), AppLists.sortedForDrawer(listOf(withControl, plain), Locale.GERMANY).keys())
        assertEquals(listOf("p/b", "p/a"), AppLists.sortedForDrawer(listOf(plain, withControl), Locale.GERMANY).keys())
    }

    @Test fun `tags the collator calls equal still get a stable order`() {
        assertEquals(listOf("wor\u0001k", "work"), AppLists.sortedTags(listOf("wor\u0001k", "work"), Locale.GERMANY))
        assertEquals(listOf("wor\u0001k", "work"), AppLists.sortedTags(listOf("work", "wor\u0001k"), Locale.GERMANY))
    }

    @Test fun `labels the collator calls equal still get a stable total order`() {
        val a = testApp("p/b", "mail")
        val b = testApp("p/a", "mail")
        assertEquals(listOf("p/a", "p/b"), AppLists.sortedForDrawer(listOf(a, b), Locale.GERMANY).keys())
        assertEquals(listOf("p/a", "p/b"), AppLists.sortedForDrawer(listOf(b, a), Locale.GERMANY).keys())
    }

    // ---- favoritesInDisplayOrder --------------------------------------------

    @Test fun `favoritesInDisplayOrder keeps favorites in config order and drops the rest`() {
        val all = rows("a", "b", "c")
        // Favorites config order is c-then-a, independent of the all-list order.
        val result = AppLists.favoritesInDisplayOrder(all, linkedSetOf("p/c", "p/a"))
        assertEquals(listOf("p/c", "p/a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder drops a favorite whose app is not present`() {
        val all = rows("a", "b")
        val result = AppLists.favoritesInDisplayOrder(all, linkedSetOf("p/a", "p/gone"))
        assertEquals(listOf("p/a"), result.keys())
    }

    @Test fun `favoritesInDisplayOrder with no favorites is empty`() {
        assertEquals(emptyList<String>(), AppLists.favoritesInDisplayOrder(rows("a", "b"), emptySet()).keys())
    }

    // ---- pickDefaultFavorites -----------------------------------------------

    @Test fun `pickDefaultFavorites takes the first launchable candidate per slot, in slot order`() {
        val all = rows("dialer", "sms", "mail", "chrome", "firefox", "cam")
        val slots = listOf(
            listOf("p/dialer"),
            listOf("p/sms"),
            listOf("p/mail"),
            listOf("p/firefox", "p/chrome"),  // the user's default browser comes first
            listOf("p/cam"),
        )
        assertEquals(
            listOf("p/dialer", "p/sms", "p/mail", "p/firefox", "p/cam"),
            AppLists.pickDefaultFavorites(all, slots),
        )
    }

    @Test fun `pickDefaultFavorites falls back past a candidate that is not launchable`() {
        val all = rows("chrome")
        assertEquals(listOf("p/chrome"), AppLists.pickDefaultFavorites(all, listOf(listOf("p/gone", "p/chrome"))))
    }

    @Test fun `pickDefaultFavorites skips an empty slot and never adds an app twice`() {
        val all = rows("google", "cam")
        // One app covering phone AND messenger, a slot with no handler at all.
        val slots = listOf(listOf("p/google"), listOf("p/google"), emptyList(), listOf("p/cam"))
        assertEquals(listOf("p/google", "p/cam"), AppLists.pickDefaultFavorites(all, slots))
    }

    @Test fun `a slot whose first choice is taken stays empty rather than falling through`() {
        // Not swapped for a second candidate the user never chose: one app covering two jobs shows once.
        val all = rows("google", "othersms")
        assertEquals(listOf("p/google"), AppLists.pickDefaultFavorites(all, listOf(listOf("p/google"), listOf("p/google", "p/othersms"))))
    }

    @Test fun `pickDefaultFavorites with no launchable candidate is empty`() {
        assertEquals(emptyList<String>(), AppLists.pickDefaultFavorites(rows("a"), listOf(listOf("p/x"))))
    }

    // ---- drawer -------------------------------------------------------------

    @Test fun `drawer excludes hidden apps`() {
        val result = AppLists.drawer(rows("a", "b", "c"), hidden = setOf("p/b"), favorites = emptySet())
        assertEquals(listOf("p/a", "p/c"), result.keys())
    }

    @Test fun `drawer keeps a favorite even when it is also hidden`() {
        val result = AppLists.drawer(rows("a", "b", "c"), hidden = setOf("p/b"), favorites = setOf("p/b"))
        assertEquals(listOf("p/a", "p/b", "p/c"), result.keys())
    }

    // ---- orderWithFavorites -------------------------------------------------

    @Test fun `orderWithFavorites returns input unchanged when there are no favorites`() {
        val apps = rows("a", "b", "c")
        assertSame(apps, AppLists.orderWithFavorites(apps, emptySet()))
    }

    @Test fun `orderWithFavorites puts non-favorites first then favorites in rank order`() {
        val apps = rows("a", "b", "c", "d")
        // Favorites c (rank 0) and a (rank 1); non-favorites b, d keep their order.
        val result = AppLists.orderWithFavorites(apps, linkedSetOf("p/c", "p/a"))
        assertEquals(listOf("p/b", "p/d", "p/c", "p/a"), result.keys())
    }

    // ---- recentsInDisplayOrder ----------------------------------------------

    @Test fun `recentsInDisplayOrder reverses so the newest sits last`() {
        val all = rows("a", "b", "c")
        // c was launched most recently; the newest lands nearest the prompt.
        val result = AppLists.recentsInDisplayOrder(all, listOf("p/c", "p/b", "p/a"))
        assertEquals(listOf("p/a", "p/b", "p/c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder drops a recent whose app is gone`() {
        val all = rows("a", "b")
        val result = AppLists.recentsInDisplayOrder(all, listOf("p/gone", "p/b", "p/a"))
        assertEquals(listOf("p/a", "p/b"), result.keys())
    }

    @Test fun `recentsInDisplayOrder - a gone app takes no place among the shown`() {
        val all = rows("a", "b", "c")
        val result = AppLists.recentsInDisplayOrder(all, listOf("p/c", "p/gone", "p/b", "p/a"), limit = 2)
        assertEquals(listOf("p/b", "p/c"), result.keys())
    }

    @Test fun `recentsInDisplayOrder shows the newest eight`() {
        val names = (1..10).map { "k$it" }
        val result = AppLists.recentsInDisplayOrder(rows(*names.toTypedArray()), names.reversed().map { "p/$it" })
        assertEquals((3..10).map { "p/k$it" }, result.keys())
    }

    @Test fun `recentsInDisplayOrder with no recents is empty`() {
        assertEquals(emptyList<String>(), AppLists.recentsInDisplayOrder(rows("a", "b"), emptyList()).keys())
    }

    // ---- tagsInUse ----------------------------------------------------------

    @Test fun `tagsInUse lists only tags borne by an installed app, sorted`() {
        val tags = mapOf(
            "p/a" to listOf("work", "fun"),
            "p/b" to listOf("games"),
            "p/gone" to listOf("ghost"),   // app not in the installed list
        )
        assertEquals(listOf("fun", "games", "work"), AppLists.tagsInUse(rows("a", "b"), tags))
    }

    @Test fun `tagsInUse is empty when no installed app is tagged`() {
        val tags = mapOf("p/gone" to listOf("ghost"))
        assertEquals(emptyList<String>(), AppLists.tagsInUse(rows("a", "b"), tags))
    }

    @Test fun `tagsInUse lists a tag two apps share once`() {
        val tags = mapOf("p/a" to listOf("work"), "p/b" to listOf("work", "fun"))
        assertEquals(listOf("fun", "work"), AppLists.tagsInUse(rows("a", "b"), tags))
    }
}
