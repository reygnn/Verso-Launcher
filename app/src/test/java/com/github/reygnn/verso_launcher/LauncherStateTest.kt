package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The IO thread and the main thread are two manual FIFO queues, so a test decides exactly when
 * the background part of a load runs and when its result is handed back, and can put a new text in
 * between.
 */
class LauncherStateTest {

    @get:Rule val locale = DefaultLocaleRule()

    // ---- harness -------------------------------------------------------------

    private val ioQueue = ArrayDeque<Runnable>()
    private val mainQueue = ArrayDeque<Runnable>()

    /** The user's file as Core read it before the state exists; set before the first use of [state]. */
    private var file = ""
    /** No config at all (no file linked): the defaults. */
    private var fresh = false

    /** Reported without custom names and NOT sorted (reversed below), so the tests see LauncherState apply both. */
    private var installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))
    private var enumerationFails = false
    private var enumerationComplete = true
    private var enumerations = 0

    private val listChanges = mutableListOf<Boolean>()
    /** Runs in onRefreshed: what the Activity sees when it re-renders. */
    private var onReport: () -> Unit = {}
    private val orderWrites = mutableListOf<Boolean>()
    /** Process-wide like VersoApp's: it outlives a LauncherState. */
    private val process = ProcessState()

    private var candidates = listOf(listOf(key("Charlie")), listOf("gone/Gone", key("Alpha")))
    private var candidatesCalls = 0

    /** Built on first use, so a test sets [file] and [fresh] first. */
    private val state by lazy {
        testLauncherState(
            io = { task -> ioQueue.addLast(task); true },
            main = { task -> mainQueue.addLast(task) },
            initial = ConfigText.read(file),
            useDefaults = fresh,
            enumerateApps = {
                enumerations++
                // Sorted, as Apps.enumerate hands it over.
                if (enumerationFails) null
                else Enumeration(LauncherLogic.sortedForDrawer(installed.reversed()), enumerationComplete)
            },
            onRefreshed = { changed -> listChanges += changed; onReport() },
            defaultFavoriteCandidates = { candidatesCalls++; candidates },
            process = process,
            writeOrder = { orderWrites += it },
        )
    }

    private fun app(label: String) =
        AppEntry(label, "com.${label.lowercase()}", "com.${label.lowercase()}.Main", label)

    private fun key(label: String) = app(label).key

    private fun runIo() { while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run() }
    private fun runMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run() }
    private fun runAll() { while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) { runIo(); runMain() } }

    /** IO and main queues of their own, for a second LauncherState built by [standalone]. */
    private class Queues {
        val io = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        fun runIo() { while (io.isNotEmpty()) io.removeFirst().run() }
        fun drain() {
            while (io.isNotEmpty() || main.isNotEmpty()) {
                runIo()
                while (main.isNotEmpty()) main.removeFirst().run()
            }
        }
    }

    /** A LauncherState with no config or apps to speak of, for the recents and usage files. */
    private fun standalone(
        q: Queues = Queues(),
        listChanged: MutableList<Boolean> = mutableListOf(),
        process: ProcessState = ProcessState(),
        loadRecents: () -> List<String>? = { emptyList() },
        writeRecents: (List<String>) -> Unit = {},
        loadUsage: () -> Map<String, Usage>? = { emptyMap() },
        writeUsage: (Map<String, Usage>) -> Unit = {},
    ) = testLauncherState(
        io = { q.io.addLast(it); true }, main = { q.main.addLast(it) },
        enumerateApps = { Enumeration(emptyList(), true) },
        onRefreshed = { changed -> listChanged += changed },
        process = process,
        loadRecents = loadRecents,
        writeRecents = writeRecents,
        loadUsage = loadUsage,
        writeUsage = { log, _ -> writeUsage(log) },
        now = { 1000L },
    )

    private fun labels() = state.apps.map { it.label }

    private fun started() {
        state.refresh()
        runAll()
        listChanges.clear()
    }

    // ---- cold start / warm refresh -----------------------------------------------

    /** Hard rule 5: the config is there before the state exists; a key needs no list to name its app. */
    @Test fun `a key in the file is in place from the start, the first refresh commits the apps`() {
        file = "fav ${key("Charlie")}"
        assertEquals(listOf(key("Charlie")), state.config.favorites)
        assertFalse(state.appsLoaded)

        state.refresh()
        runAll()

        assertTrue(state.appsLoaded)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertEquals(listOf(true), listChanges)
        assertEquals(listOf("Charlie"), state.favorites().map { it.label })
    }

    /** Home shows the cached favorites meanwhile (MainActivity): nothing is lost waiting for the list. */
    @Test fun `a label or a package names its app once the first list is in`() {
        file = "fav Charlie\nfav com.alpha\nhide Bravo"
        assertEquals(emptyList<String>(), state.config.favorites)
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertEquals(setOf(key("Bravo")), state.config.hidden)
    }

    @Test fun `an app the file names by label becomes a favorite once it is installed`() {
        file = "fav Delta"
        started()
        assertEquals(emptyList<String>(), state.config.favorites)
        installed = installed + app("Delta")
        state.refresh()
        runAll()
        assertEquals(listOf(key("Delta")), state.config.favorites)
        assertEquals(listOf(true), listChanges)
    }

    /** A needless re-render would void a tap in flight. */
    @Test fun `a hand-off says whether the list changed`() {
        started()
        state.refresh()
        runAll()
        assertEquals(listOf(false), listChanges)
        installed = installed + app("Delta")
        state.refresh()
        runAll()
        assertEquals(listOf(false, true), listChanges)
        assertTrue("Delta" in labels())
    }

    @Test fun `the list is shown in drawer order whatever order the enumeration reports`() {
        started()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `the first list shows the custom names of the file`() {
        file = "name Alpha = Zulu"
        state.refresh()
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("the system label stays on the entry", "Alpha", state.apps.last().systemLabel)
    }

    @Test fun `a custom name re-sorts the drawer in the device language's alphabet`() {
        // Swedish files Ä after Z; German alone could not tell a locale-blind sort apart.
        java.util.Locale.setDefault(java.util.Locale.of("sv", "SE"))
        file = "name Alpha = Ärzte"
        started()
        assertEquals(listOf("Bravo", "Charlie", "Ärzte"), labels())
    }

    @Test fun `apps stay unloaded until the first enumeration commits`() {
        state.refresh()
        runIo()
        assertFalse(state.appsLoaded)  // result still waiting on the main queue
        runMain()
        assertTrue(state.appsLoaded)
    }

    // ---- newest refresh wins ---------------------------------------------------

    @Test fun `a refresh superseded while queued skips its work entirely`() {
        started()
        val before = enumerations
        state.refresh()
        state.refresh()
        runAll()
        assertEquals("only the newest load enumerates", before + 1, enumerations)
        assertEquals(listOf(false), listChanges)
    }

    @Test fun `a refresh superseded after its work is dropped at the hand-off`() {
        started()
        state.refresh()
        runIo()                       // first load done, result queued for main
        installed = installed + app("Delta")
        state.refresh()               // newer request before the first hand-off ran
        runMain()                     // first hand-off: superseded, ignored
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertTrue(listChanges.isEmpty())
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), labels())
    }

    // ---- enumeration failure ------------------------------------------------------

    @Test fun `a failed enumeration keeps the last known good list`() {
        started()
        enumerationFails = true
        state.refresh()
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertEquals(listOf(false), listChanges)  // the hand-off still ran
    }

    // ---- needsRefresh: what a resume must retry --------------------------------------

    @Test fun `a cold start needs a refresh until its load has landed`() {
        assertTrue(state.needsRefresh)
        state.refresh()
        runIo()
        assertTrue("requested is not landed", state.needsRefresh)
        runMain()
        assertFalse(state.needsRefresh)
    }

    @Test fun `a failed enumeration leaves the launcher needing a refresh, a good one clears it`() {
        started()
        enumerationFails = true
        state.refresh()
        runAll()
        assertTrue(state.needsRefresh)
        enumerationFails = false
        state.refresh()
        runAll()
        assertFalse(state.needsRefresh)
    }

    @Test fun `an incomplete enumeration is shown but leaves the launcher needing a refresh`() {
        started()
        installed = installed + app("Delta")
        enumerationComplete = false
        state.refresh()
        runAll()
        assertTrue("shown", "Delta" in labels())
        assertTrue(state.needsRefresh)
        listChanges.clear()
        state.refresh()
        runAll()
        assertEquals("the same incomplete list again: no re-render", listOf(false), listChanges)
        enumerationComplete = true
        state.refresh()
        runAll()
        assertFalse(state.needsRefresh)
    }

    @Test fun `a complete enumeration after an incomplete one shows the apps it adds`() {
        enumerationComplete = false
        started()
        installed = installed + app("Delta")
        enumerationComplete = true
        state.refresh()
        runAll()
        assertTrue("Delta" in labels())
        assertFalse(state.needsRefresh)
    }

    // ---- a new text: the file read anew ------------------------------------------------

    @Test fun `a new text sets its config at once and re-renders, without enumerating`() {
        file = "fav Alpha\nname Bravo = Mine"
        started()
        val before = enumerations
        state.adopt(ConfigText.parse("fav Bravo\nname Alpha = Zulu"))
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        assertEquals("the names it does not have are gone", listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals(listOf(true), listChanges)
        assertEquals(before, enumerations)
    }

    @Test fun `a new text reports only once its config and its names are in place`() {
        started()
        val seen = mutableListOf<Pair<List<String>, List<String>>>()
        onReport = { seen += state.config.favorites to labels() }
        state.adopt(ConfigText.parse("fav Bravo\nname Alpha = Zulu"))
        assertEquals(listOf(listOf(key("Bravo")) to listOf("Bravo", "Charlie", "Zulu")), seen)
    }

    @Test fun `a refresh hand-off landing after a new text keeps that text's config and the new apps`() {
        started()
        installed = installed + app("Delta")
        state.refresh()
        runIo()                               // its hand-off is queued
        state.adopt(ConfigText.parse("fav Bravo\nfav Delta"))
        runAll()
        assertTrue("Delta" in labels())
        assertEquals("Delta named by label once its list is in", listOf(key("Bravo"), key("Delta")), state.config.favorites)
    }

    @Test fun `the drawer order stays when a new text comes`() {
        started()
        state.toggleOrder()
        state.adopt(ConfigText.parse("fav Bravo"))
        assertTrue(state.config.byUsage)
    }

    // ---- recents ----------------------------------------------------------------

    @Test fun `recents start from the process-wide list and every launch goes back to it`() {
        val kept = ProcessState(recents = Kept(listOf("a", "b")))
        val s = standalone(process = kept)
        assertEquals(listOf("a", "b"), s.recentKeys)
        s.recordLaunch("c")
        assertEquals(listOf("c", "a", "b"), kept.recents.value)
    }

    @Test fun `recents are newest first, de-duplicated and capped`() {
        for (i in 1..10) state.recordLaunch("k$i")
        state.recordLaunch("k5")
        assertEquals(listOf("k5", "k10", "k9", "k8", "k7", "k6", "k4", "k3", "k2", "k1"), state.recentKeys)
        for (i in 11..40) state.recordLaunch("k$i")
        assertEquals(LauncherState.RECENTS_KEPT, state.recentKeys.size)
        assertEquals("k40", state.recentKeys.first())
    }

    @Test fun `recents_txt is read once, launches before it come first, and every later launch is written`() {
        val q = Queues()
        val writes = mutableListOf<List<String>>()
        var reads = 0
        val s = standalone(q, loadRecents = { reads++; listOf("a", "b") }, writeRecents = { writes += it })
        s.recordLaunch("b")
        q.drain()
        assertEquals("not before the file was read", emptyList<List<String>>(), writes)
        s.refresh()
        q.drain()
        assertEquals(listOf("b", "a"), s.recentKeys)
        assertEquals(listOf(listOf("b", "a")), writes)
        s.recordLaunch("c")
        s.refresh()
        q.drain()
        assertEquals(1, reads)
        assertEquals(listOf("c", "b", "a"), writes.last())
    }

    @Test fun `reading recents_txt with no launch before it writes nothing`() {
        val q = Queues()
        val writes = mutableListOf<List<String>>()
        val s = standalone(q, loadRecents = { listOf("a") }, writeRecents = { writes += it })
        s.refresh()
        q.drain()
        assertEquals(listOf("a"), s.recentKeys)
        assertEquals(emptyList<List<String>>(), writes)
    }

    @Test fun `a recreated instance takes over whether recents_txt was read`() {
        val q = Queues()
        val writes = mutableListOf<List<String>>()
        val kept = ProcessState()
        val first = standalone(q, process = kept, loadRecents = { listOf("a") }, writeRecents = { writes += it })
        first.recordLaunch("b")
        assertEquals(listOf("b"), kept.recents.value)
        assertFalse(kept.recents.loaded)
        first.refresh()
        q.drain()
        assertEquals(listOf("b", "a"), kept.recents.value)
        assertTrue(kept.recents.loaded)

        var reads = 0
        val second = standalone(q, process = kept, loadRecents = { reads++; null }, writeRecents = { writes += it })
        second.refresh()
        q.drain()
        assertEquals(0, reads)
        second.recordLaunch("c")
        q.drain()
        assertEquals(listOf("c", "b", "a"), writes.last())
    }

    @Test fun `an unreadable recents_txt is not written over, a later read still merges and re-renders`() {
        val q = Queues()
        val writes = mutableListOf<List<String>>()
        val listChanged = mutableListOf<Boolean>()
        var readable = false
        val s = standalone(
            q, listChanged, loadRecents = { if (readable) listOf("a") else null }, writeRecents = { writes += it },
        )
        s.refresh()
        q.drain()
        s.recordLaunch("b")
        q.drain()
        assertEquals(emptyList<List<String>>(), writes)
        readable = true
        s.refresh()
        q.drain()
        assertEquals(listOf("b", "a"), s.recentKeys)
        assertEquals(listOf(listOf("b", "a")), writes)
        assertEquals("same apps, but the ? list changed", true, listChanged.last())
    }

    /**
     * Two instances on one ProcessState whose hand-offs both land (in production onUi drops the
     * destroyed one's): the files are merged in once, not twice (audit 2026-10-22, #17).
     */
    @Test fun `two instances reading the same files merge them in once`() {
        val q = Queues()
        val kept = ProcessState()
        val recentWrites = mutableListOf<List<String>>()
        fun instance() = standalone(
            q, process = kept,
            loadRecents = { listOf("a") }, writeRecents = { recentWrites += it },
            loadUsage = { mapOf("a" to Usage(1.0, 1000L)) },
        )
        val first = instance()
        val second = instance()
        first.recordLaunch("a")
        first.refresh()
        second.refresh()  // asked before either read landed: both read the files
        q.drain()
        assertEquals("one launch plus the file's one", Usage(2.0, 1000L), kept.usage.value["a"])
        assertEquals(listOf("a"), kept.recents.value)
        assertEquals("written once", 1, recentWrites.size)
    }

    // ---- "%" order and usage -----------------------------------------------------

    /** The app's own preference (order.txt), never the user's file. */
    @Test fun `the order toggle changes the config and writes order_txt on the IO thread`() {
        started()
        assertEquals(true, state.toggleOrder())
        assertTrue(state.config.byUsage)
        assertEquals("not on main", emptyList<Boolean>(), orderWrites)
        runAll()
        assertEquals(listOf(true), orderWrites)
        assertEquals(false, state.toggleOrder())
        runAll()
        assertEquals(listOf(true, false), orderWrites)
    }

    @Test fun `the order comes in from order_txt and stays across a new list`() {
        val s = testLauncherState(
            io = { task -> task.run(); true }, main = { task -> task.run() },
            enumerateApps = { Enumeration(installed, true) }, onRefreshed = {},
            byUsage = true,
        )
        s.refresh()
        assertTrue(s.config.byUsage)
    }

    @Test fun `an unreadable usage_txt is not written over, a later read still merges`() {
        val q = Queues()
        val writes = mutableListOf<Map<String, Usage>>()
        var readable = false
        val s = standalone(
            q, loadUsage = { if (readable) mapOf("a" to Usage(5.0, 1000L)) else null }, writeUsage = { writes += it },
        )
        s.refresh()
        q.drain()
        s.recordLaunch("b")
        q.drain()
        assertTrue("a failed read must not lead to a write", writes.isEmpty())
        readable = true
        s.refresh()
        q.drain()
        assertEquals(mapOf("a" to Usage(5.0, 1000L), "b" to Usage(1.0, 1000L)), writes.single())
    }

    @Test fun `launches before usage_txt is read are added to it, then every launch is written`() {
        val q = Queues()
        val writes = mutableListOf<Map<String, Usage>>()
        val kept = ProcessState()
        val s = standalone(q, process = kept, loadUsage = { mapOf("a" to Usage(1.0, 1000L)) }, writeUsage = { writes += it })
        s.recordLaunch("a")
        assertTrue("not written over a file not read yet", q.io.isEmpty())
        assertFalse(kept.usage.loaded)
        s.refresh()
        q.drain()
        assertEquals(Usage(2.0, 1000L), s.usage["a"])
        assertEquals(listOf(mapOf("a" to Usage(2.0, 1000L))), writes)
        assertTrue(kept.usage.loaded)
        assertEquals(s.usage, kept.usage.value)
        s.recordLaunch("b")
        q.runIo()
        assertEquals(setOf("a", "b"), writes.last().keys)
    }

    // ---- default favorites (hard rule 10) -------------------------------------------

    @Test fun `with no config at all the default favorites come from the list, in slot order`() {
        fresh = true
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertEquals(listOf("Charlie", "Alpha"), state.favorites().map { it.label })
        assertEquals("the order is all Verso writes", emptyList<Boolean>(), orderWrites)
    }

    /** Never written, so never stale: a candidate installed later joins with the next list. */
    @Test fun `the defaults follow the list`() {
        fresh = true
        installed = listOf(app("Bravo"), app("Charlie"))
        started()
        assertEquals(listOf(key("Charlie")), state.config.favorites)
        installed = installed + app("Alpha")
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertEquals(listOf(true), listChanges)
    }

    /** An empty file is the user's: no favorites, not the defaults. */
    @Test fun `a file, even an empty one, is never given the defaults`() {
        file = ""
        started()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(0, candidatesCalls)
    }

    @Test fun `a failed first enumeration picks no defaults, the next list does`() {
        fresh = true
        enumerationFails = true
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertTrue("a resume tries again", state.needsRefresh)
        enumerationFails = false
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
    }

    /** An enumeration that never completes (a profile that stays unreadable) must not starve the defaults. */
    @Test fun `an incomplete enumeration still picks the defaults`() {
        fresh = true
        enumerationComplete = false
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
    }

    @Test fun `a file linked ends the defaults, also against a load picked for them before`() {
        fresh = true
        state.refresh()
        runIo()                                   // the defaults are picked, the hand-off waits
        state.adopt(ConfigText.parse("fav Bravo"))
        runAll()
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        state.refresh()
        runAll()
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        assertEquals("no more picks", 1, candidatesCalls)
    }

    // ---- AppEntry --------------------------------------------------------------

    @Test fun `a personal app keeps the plain key, another profile's copy gets the serial`() {
        val personal = AppEntry("Gmail", "com.google.android.gm", "com.google.android.gm.Main", "Gmail")
        val work = AppEntry("Gmail (Work)", "com.google.android.gm", "com.google.android.gm.Main", "Gmail (Work)", profile = 10)
        assertEquals("com.google.android.gm/com.google.android.gm.Main", personal.key)
        assertEquals("com.google.android.gm/com.google.android.gm.Main#10", work.key)
        assertEquals(work.key, work.withLabel("Mail").key)

        assertEquals(10L, work.withLabel("Mail").profile)
    }

    @Test fun `the status is no part of the key, and a custom name keeps it`() {
        val ready = AppEntry("Gmail", "com.g", "com.g.Main", "Gmail", profile = 10)
        val paused = AppEntry("Gmail", "com.g", "com.g.Main", "Gmail", profile = 10, status = AppStatus.PAUSED)
        assertEquals(ready.key, paused.key)  // favorites and names survive a pause
        assertEquals(AppStatus.PAUSED, paused.withLabel("Mail").status)
    }

    @Test fun `a work copy is its own row with its own favorite state`() {
        val work = AppEntry("Charlie (Work)", "com.charlie", "com.charlie.Main", "Charlie (Work)", profile = 10)
        installed = installed + work
        file = "fav Charlie"
        started()
        assertEquals(listOf("Charlie"), state.favorites().map { it.label })
        state.adopt(ConfigText.parse("fav Charlie\nfav Charlie (Work)"))
        assertEquals(listOf("Charlie", "Charlie (Work)"), state.favorites().map { it.label })
    }

    @Test fun `the personal key is package slash class, the format old configs were written in`() {
        val e = AppEntry("Mail", "com.example.mail", "com.example.mail.MainActivity", "Mail")
        assertEquals("com.example.mail/com.example.mail.MainActivity", e.key)
        assertEquals("mail", e.labelLower)
        assertEquals("inbox", e.withLabel("Inbox").labelLower)
        assertEquals("a plain copy cannot leave it stale", "inbox", e.copy(label = "Inbox").labelLower)
        assertEquals(e.key, e.withLabel("Inbox").key)
    }
}
