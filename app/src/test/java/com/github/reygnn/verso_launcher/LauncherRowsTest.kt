package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherRowsTest {


    private val apps = listOf(
        orderedFake("com.a/A", "Alpha"),
        orderedFake("com.b/B", "Bravo"),
        orderedFake("com.c/C", "Charlie"),
    )
    private val favorites = linkedSetOf("com.c/C", "com.a/A") // rank: C=0, A=1
    private val hidden = setOf("com.b/B")
    private val tags = mapOf("com.a/A" to listOf("games"), "com.c/C" to listOf("work"))
    private val recents = listOf("com.b/B", "com.a/A") // most-recent-first

    private fun rowsFor(mode: Mode, trimmed: String, tagEditTag: String? = null) =
        LauncherLogic.rowsFor(mode, trimmed, apps, hidden, favorites, tags, recents, tagEditTag)

    private fun appKeys(rows: List<Row<OrderedFake>>) =
        rows.filterIsInstance<AppRow<OrderedFake>>().map { it.entry.key }

    private fun tagNames(rows: List<Row<OrderedFake>>) =
        rows.filterIsInstance<TagRow>().map { it.name }

    // ---- ORDER ("%") -------------------------------------------------------------

    @Test fun `percent shows the one row that turns the order the other way`() {
        val toUsage = LauncherLogic.rowsFor(Mode.ORDER, "%", apps, hidden, favorites, tags, recents, null)
        assertEquals(listOf<Row<OrderedFake>>(OrderRow(toUsage = true)), toUsage)
        val back = LauncherLogic.rowsFor(Mode.ORDER, "%", apps, hidden, favorites, tags, recents, null, byUsage = true)
        assertEquals(listOf<Row<OrderedFake>>(OrderRow(toUsage = false)), back)
    }

    @Test fun `text after the percent is a typo, not a switch`() {
        assertTrue(LauncherLogic.rowsFor(Mode.ORDER, "%abc", apps, hidden, favorites, tags, recents, null).isEmpty())
    }

    @Test fun `by usage, the plain drawer is most used first and a search is not reordered`() {
        val usage = mapOf("com.c/C" to Usage(2.0, 0L))
        val plain = LauncherLogic.rowsFor(Mode.NORMAL, "", apps, hidden, favorites, tags, recents, null, byUsage = true, usage = usage)
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(plain))
        val search = LauncherLogic.rowsFor(Mode.NORMAL, "a", apps, hidden, favorites, tags, recents, null, byUsage = true, usage = usage)
        assertEquals(appKeys(rowsFor(Mode.NORMAL, "a")), appKeys(search))
    }

    // ---- NORMAL --------------------------------------------------------------

    // The empty prompt is the plain alphabetical drawer — no favorites regrouping (that is "*").

    @Test fun `empty prompt shows the drawer in its incoming order, not favorites-first`() {
        // C outranks A as a favorite, yet the incoming (already sorted) order stays.
        assertEquals(listOf("com.a/A", "com.c/C"), appKeys(rowsFor(Mode.NORMAL, "")))
    }

    @Test fun `empty prompt is the same drawer whether or not favorites are set`() {
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "", apps, hidden, favorites = emptySet(), tags, recents, null,
        )
        assertEquals(listOf("com.a/A", "com.c/C"), appKeys(rows))
    }

    @Test fun `empty prompt keeps a hidden favorite - favoriting overrides hiding`() {
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "", apps, hidden, favorites = linkedSetOf("com.b/B"), tags, recents, null,
        )
        assertEquals(listOf("com.a/A", "com.b/B", "com.c/C"), appKeys(rows))
    }

    @Test fun `star still groups the favorites nearest the prompt, unlike the empty drawer`() {
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.NORMAL, "*")))
        assertEquals(listOf("com.a/A", "com.c/C"), appKeys(rowsFor(Mode.NORMAL, "")))
    }

    @Test fun `star shows the drawer keeping favorites and dropping hidden non-favorites`() {
        // B is hidden and not a favorite; favorites sink to the bottom in rank order.
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.NORMAL, "*")))
    }

    @Test fun `a prefix before the star narrows the drawer to labels starting with it`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.NORMAL, "a*")))
        assertEquals(listOf("com.c/C"), appKeys(rowsFor(Mode.NORMAL, "c*")))
    }

    /** Once the star is typed the caret sits after it, so the prompt grows "*a", not "a*". */
    @Test fun `a prefix AFTER the star narrows the drawer the same way`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.NORMAL, "*a")))
        assertEquals(listOf("com.c/C"), appKeys(rowsFor(Mode.NORMAL, "*c")))
    }

    @Test fun `the star prefix is case-insensitive`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.NORMAL, "A*")))
    }

    @Test fun `the star prefix matches the START only, never a substring`() {
        // "lph" is inside "Alpha" but not its start.
        assertTrue(appKeys(rowsFor(Mode.NORMAL, "lph*")).isEmpty())
    }

    @Test fun `the star prefix excludes a hidden non-favorite even when its name matches`() {
        // In contrast to the plain "brav" search, which reaches hidden apps.
        assertTrue(appKeys(rowsFor(Mode.NORMAL, "b*")).isEmpty())
    }

    @Test fun `the star prefix keeps a hidden FAVORITE, mirroring the bare drawer`() {
        val favsWithHiddenB = linkedSetOf("com.b/B")
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "b*", apps, hidden, favsWithHiddenB, tags, recents, null,
        )
        assertEquals(listOf("com.b/B"), appKeys(rows))
    }

    @Test fun `a star prefix matching several apps orders favorites last`() {
        // The shared fixture never matches >1 app for a prefix; the banana must drop out.
        val local = listOf(
            orderedFake("com.apple/A", "Apple"),
            orderedFake("com.applet/A", "Applet"),
            orderedFake("com.apricot/A", "Apricot"),
            orderedFake("com.banana/A", "Banana"),
        )
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "ap*", local, emptySet(), linkedSetOf("com.applet/A"),
            emptyMap(), emptyList(), null,
        )
        assertEquals(listOf("com.apple/A", "com.apricot/A", "com.applet/A"), appKeys(rows))
    }

    /** Both stars are stripped, so "**" is the empty prefix. */
    @Test fun `a double star is the whole drawer, like a bare star`() {
        assertEquals(appKeys(rowsFor(Mode.NORMAL, "*")), appKeys(rowsFor(Mode.NORMAL, "**")))
    }

    @Test fun `a star prefix over an empty app list is empty`() {
        val rows = LauncherLogic.rowsFor(
            Mode.NORMAL, "a*", emptyList<OrderedFake>(), emptySet(), emptySet(), emptyMap(), emptyList(), null,
        )
        assertTrue(appKeys(rows).isEmpty())
    }

    @Test fun `plain search spans all apps including hidden ones`() {
        assertEquals(listOf("com.b/B"), appKeys(rowsFor(Mode.NORMAL, "brav")))
    }

    // ---- COMMAND -------------------------------------------------------------

    @Test fun `bare tilde lists every command harmless-first as CommandRows`() {
        val rows = rowsFor(Mode.COMMAND, "~")
        assertTrue(rows.all { it is CommandRow })
        assertEquals(LauncherLogic.COMMANDS.map { it.first }, rows.filterIsInstance<CommandRow>().map { it.name })
        // "~crash", where it exists, farthest from the bottom-anchored prompt; then the harmless one.
        val names = rows.filterIsInstance<CommandRow>().map { it.name }
        assertEquals(if (DailyDriver.ENABLED) listOf("~crash", "~restart") else listOf("~restart"), names.take(names.indexOf("~restart") + 1))
    }

    @Test fun `tilde prefix narrows the command overview`() {
        val rows = rowsFor(Mode.COMMAND, "~b")
        assertEquals(1, rows.size)
        assertEquals(Command.BACKUP, (rows.single() as CommandRow).command)
    }

    @Test fun `a non-matching tilde prefix yields an empty command overview`() {
        assertTrue(rowsFor(Mode.COMMAND, "~zzz").isEmpty())
    }

    // ---- TAG_FILTER ("#") ----------------------------------------------------

    @Test fun `bare hash lists the in-use tags sorted, as TagRows`() {
        assertEquals(listOf("games", "work"), tagNames(rowsFor(Mode.TAG_FILTER, "#")))
    }

    @Test fun `bare hash drops ghost tags whose apps are all uninstalled`() {
        val ghost = tags + ("com.z/Z" to listOf("archived"))
        val rows = LauncherLogic.rowsFor(Mode.TAG_FILTER, "#", apps, hidden, favorites, ghost, recents, null)
        assertEquals(listOf("games", "work"), tagNames(rows)) // "archived" has no live app
    }

    @Test fun `hash with text prefix-matches tags and returns their apps`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.TAG_FILTER, "#gam")))
    }

    @Test fun `tag filter surfaces a hidden app - tagging overrides hiding`() {
        // The shared fixture's hidden app carries no tag, so tag it here.
        val tagsWithHiddenApp = mapOf("com.b/B" to listOf("work"))
        val rows = LauncherLogic.rowsFor(
            Mode.TAG_FILTER, "#work", apps, hidden, favorites, tagsWithHiddenApp, recents, null,
        )
        assertEquals(listOf("com.b/B"), appKeys(rows))
    }

    /** canonicalTag keeps a leading sigil only because the drill's "#" puts it at position 1, where parseMode ignores it. */
    @Test fun `a sigil-named tag is listed, stays a tag filter when drilled, and finds its app`() {
        for (name in listOf("!work", "-work", "~work", "?work", "!!work")) {
            val sigilTags = mapOf("com.a/A" to listOf(name))
            val overview = LauncherLogic.rowsFor(
                Mode.TAG_FILTER, "#", apps, hidden, favorites, sigilTags, recents, null,
            )
            assertEquals("\"$name\" missing from the # overview", listOf(name), tagNames(overview))
            val drilled = "#$name"
            assertEquals("\"$drilled\" is no longer a tag filter", Mode.TAG_FILTER, LauncherLogic.parseMode(drilled))
            val filtered = LauncherLogic.rowsFor(
                Mode.TAG_FILTER, drilled, apps, hidden, favorites, sigilTags, recents, null,
            )
            assertEquals("\"$drilled\" did not find its app", listOf("com.a/A"), appKeys(filtered))
        }
    }

    // ---- TAG_EDIT ("##") -----------------------------------------------------

    @Test fun `bare double-hash lists ALL tags including ghosts, as TagRows`() {
        val ghost = tags + ("com.z/Z" to listOf("archived"))
        val rows = LauncherLogic.rowsFor(Mode.TAG_EDIT, "##", apps, hidden, favorites, ghost, recents, null)
        assertEquals(listOf("archived", "games", "work"), tagNames(rows))
    }

    @Test fun `double-hash with a chosen tag lists every app unreordered`() {
        val rows = rowsFor(Mode.TAG_EDIT, "##work", tagEditTag = "work")
        assertEquals(apps.map { it.key }, appKeys(rows))
    }

    // ---- tagEditTagFor -------------------------------------------------------

    @Test fun `tagEditTagFor is null outside TAG_EDIT`() {
        for (m in Mode.entries.filter { it != Mode.TAG_EDIT }) {
            assertNull("expected null for $m", LauncherLogic.tagEditTagFor(m, "##work"))
        }
    }

    @Test fun `tagEditTagFor returns the canonical tail of a chosen tag`() {
        assertEquals("work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##work"))
        assertEquals("work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##Work"))    // folded
        assertEquals("work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##  work"))  // trimmed
    }

    @Test fun `tagEditTagFor keeps the overview up for a tail that canonicalises to nothing`() {
        // A phantom "" tag would turn the overview into an all-apps edit list for tag "".
        assertNull(LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##"))
        assertNull(LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "###"))
        assertNull(LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##,"))
    }

    @Test fun `tagEditTagFor keeps a sigil-named tag (the bulk-edit mirror of drilling it)`() {
        assertEquals("!work", LauncherLogic.tagEditTagFor(Mode.TAG_EDIT, "##!work"))
    }

    // ---- RECENTS / FAV_REORDER / edit modes ----------------------------------

    @Test fun `recents are reversed so the most recent sits nearest the prompt`() {
        // recents = [B, A] most-recent-first; reversed for isStackFromBottom -> [A, B].
        assertEquals(listOf("com.a/A", "com.b/B"), appKeys(rowsFor(Mode.RECENTS, "?")))
    }

    @Test fun `a hidden app stays in the recents`() {
        // Kolibri left them out; a hidden app is launched only by searching for it on purpose.
        assertTrue("com.b/B" in hidden)
        assertTrue("com.b/B" in appKeys(rowsFor(Mode.RECENTS, "?")))
    }

    @Test fun `text after the question mark narrows the recents, keeping their order`() {
        assertEquals(listOf("com.b/B"), appKeys(rowsFor(Mode.RECENTS, "?bra")))
        assertEquals(listOf("com.a/A", "com.b/B"), appKeys(rowsFor(Mode.RECENTS, "?a")))
        assertEquals(EmptyHint.NO_MATCH, LauncherLogic.emptyHint(Mode.RECENTS, "?zzz", null))
    }

    @Test fun `reorder lists exactly the favorites in stored order, ignoring trailing text`() {
        assertEquals(listOf("com.c/C", "com.a/A"), appKeys(rowsFor(Mode.FAV_REORDER, "!!ignored")))
    }

    @Test fun `edit modes list every app narrowed by the text after the sigil`() {
        assertEquals(listOf("com.a/A"), appKeys(rowsFor(Mode.FAV_EDIT, "!al")))
        assertEquals(listOf("com.b/B"), appKeys(rowsFor(Mode.HIDDEN_EDIT, "-brav")))
    }

    @Test fun `a bare edit-mode sigil lists every app (empty needle)`() {
        assertEquals(apps.map { it.key }, appKeys(rowsFor(Mode.FAV_EDIT, "!")))
        assertEquals(apps.map { it.key }, appKeys(rowsFor(Mode.HIDDEN_EDIT, "-")))
    }

    // ---- rowPrefix -----------------------------------------------------------

    @Test fun `rowPrefix picks the right marker per edit mode`() {
        assertEquals("[x] ", LauncherLogic.rowPrefix(Mode.HIDDEN_EDIT, isHidden = true, false, false, false))
        assertEquals("[ ] ", LauncherLogic.rowPrefix(Mode.HIDDEN_EDIT, isHidden = false, false, false, false))
        assertEquals("[x] ", LauncherLogic.rowPrefix(Mode.FAV_EDIT, false, isFavorite = true, false, false))
        assertEquals("\u00BB ", LauncherLogic.rowPrefix(Mode.FAV_REORDER, false, false, isPicked = true, false))
        assertEquals("  ", LauncherLogic.rowPrefix(Mode.FAV_REORDER, false, false, isPicked = false, false))
        assertEquals("[x] ", LauncherLogic.rowPrefix(Mode.TAG_EDIT, false, false, false, isTagged = true))
    }

    @Test fun `rowPrefix is empty in the read modes that carry no marker`() {
        for (m in listOf(Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND)) {
            assertEquals("", LauncherLogic.rowPrefix(m, true, true, true, true))
        }
    }

    @Test fun `edit-mode markers are column-aligned (equal width)`() {
        assertEquals("[ ] ".length, "[x] ".length)
        assertEquals("  ".length, "\u00BB ".length)
    }
}
