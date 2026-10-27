package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The IO thread and the main thread are two manual FIFO queues, so a test decides exactly when
 * the background part of a load runs and when its result is handed back, and can put a tap in between.
 */
class LauncherStateTest {

    @get:Rule val locale = DefaultLocaleRule()

    // ---- harness -------------------------------------------------------------

    private val ioQueue = ArrayDeque<Runnable>()
    private val mainQueue = ArrayDeque<Runnable>()
    private var ioOpen = true

    /** "The file on disk", null when it is there but cannot be read or parsed. NOT updated by writeConfig: a test
     *  that saves and then reloads must set it itself. */
    private var disk: VersoConfig? = VersoConfig()
    private var loadConfigCalls = 0

    /** Reported without custom names and NOT sorted (reversed below), so the tests see LauncherState apply both. */
    private var installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))
    private var enumerationFails = false
    private var enumerationComplete = true
    private var enumerations = 0

    private val written = mutableListOf<String>()
    private var writeSucceeds = true

    private val refreshed = mutableListOf<Boolean>()
    /** An edit permit, as MainActivity takes one before every edit. */
    private val permit get() = state.editPermit()
    private var saveFailures = 0
    private var unusableReports = 0
    /** Runs in onConfigUnusable: what the window sees when it says so. */
    private var onUnusable: () -> Unit = {}
    /** Process-wide like VersoApp's: it outlives a LauncherState. */
    private val process = ProcessState()
    private val listChanges = mutableListOf<Boolean>()
    /** Runs in onRefreshed: what the Activity sees when it re-renders. */
    private var onReport: () -> Unit = {}

    /** Built on first use, so a test sets [disk] and [fresh] first: the file is read before the state exists. */
    private val state by lazy { testLauncherState(
        io = { task -> if (ioOpen) { ioQueue.addLast(task); true } else false },
        main = { task -> mainQueue.addLast(task) },
        loadConfig = { loadConfigCalls++; disk },
        enumerateApps = {
            enumerations++
            // Sorted, as Apps.enumerate hands it over.
            if (enumerationFails) null
            else Enumeration(LauncherLogic.sortedForDrawer(installed.reversed()), enumerationComplete)
        },
        serialize = { c ->
            "fav=${c.favorites.joinToString(",")};hid=${c.hidden.joinToString(",")};" +
                "names=${c.names.entries.joinToString(",") { "${it.key}=${it.value}" }};" +
                "tags=${c.tags.entries.joinToString(",") { "${it.key}=${it.value.joinToString("+")}" }}"
        },
        writeConfig = { payload -> written += payload; writeSucceeds },
        onSaveFailed = { saveFailures++ },
        onConfigUnusable = { unusableReports++; onUnusable() },
        onRefreshed = { replaced, changed -> refreshed += replaced; listChanges += changed; onReport() },
        isFreshInstall = { fresh },
        defaultFavoriteCandidates = { candidatesCalls++; candidates },
        process = process,
    ) }

    private var fresh = false
    private var candidates = listOf(listOf(key("Charlie")), listOf("gone/Gone", key("Alpha")))
    private var candidatesCalls = 0

    private fun app(label: String) =
        AppEntry(label, "com.$label".lowercase(), "com.${label.lowercase()}.Main", label)

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
        loadConfig = { VersoConfig() }, enumerateApps = { Enumeration(emptyList(), true) },
        serialize = { "" }, writeConfig = { true }, onRefreshed = { _, changed -> listChanged += changed },
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
        written.clear()
        refreshed.clear()
    }

    // ---- cold start / warm refresh -----------------------------------------------

    /** Hard rule 5: the file is read before the state exists — there is no "not loaded yet". */
    @Test fun `the config is in place from the start, the first refresh commits the apps`() {
        disk = VersoConfig(favorites = listOf(key("Charlie")))
        assertEquals(listOf(key("Charlie")), state.config.favorites)
        assertFalse(state.appsLoaded)

        state.refresh()
        runAll()

        assertTrue(state.appsLoaded)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertEquals(listOf(false), refreshed)  // the config was not replaced: it was there
        assertEquals(1, loadConfigCalls)
    }

    @Test fun `a warm refresh does not re-read the file`() {
        started()
        state.refresh()
        runAll()
        assertEquals(1, loadConfigCalls)
        assertEquals(listOf(false), refreshed)
    }

    /** ConfigStore set the unreadable file aside (hard rule 4): the next change writes a new one. */
    @Test fun `a config that cannot be read loads as empty, said once, and the next change is saved`() {
        disk = null
        state.refresh()
        runAll()
        state.refresh()
        runAll()

        assertEquals(VersoConfig(), state.config)
        assertEquals(1, unusableReports)
        state.toggleOrder(permit)
        runAll()
        assertEquals(1, written.size)
    }

    @Test fun `a readable config and a fresh install say nothing`() {
        disk = VersoConfig(favorites = listOf(key("Charlie")))
        started()
        assertEquals(0, unusableReports)

        fresh = true
        val next = testLauncherState(
            io = { task -> ioQueue.addLast(task); true },
            main = { task -> mainQueue.addLast(task) },
            loadConfig = { VersoConfig() },
            enumerateApps = { Enumeration(installed, true) },
            serialize = { "" },
            writeConfig = { true },
            onConfigUnusable = { unusableReports++ },
            onRefreshed = { _, _ -> },
            isFreshInstall = { fresh },
        )
        next.refresh()
        runAll()
        assertEquals(0, unusableReports)
    }

    /** A needless re-render would void a tap in flight. */
    @Test fun `a hand-off says whether the list changed`() {
        started()
        listChanges.clear()
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

    @Test fun `the first list shows the custom names read at the start`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        state.refresh()
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("the system label stays on the entry", "Alpha", state.apps.last().systemLabel)
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
        assertEquals(listOf(false), refreshed)
    }

    @Test fun `a refresh superseded after its work is dropped at the hand-off`() {
        started()
        state.refresh()
        runIo()                       // first load done, result queued for main
        installed = installed + app("Delta")
        state.refresh()               // newer request before the first hand-off ran
        runMain()                     // first hand-off: superseded, ignored
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertTrue(refreshed.isEmpty())
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), labels())
    }

    // ---- toggles vs. an in-flight load -----------------------------------------------

    @Test fun `a toggle during a load does not throw the new app list away`() {
        started()
        installed = installed + app("Delta")
        state.refresh()
        runIo()
        state.toggleHidden(permit, key("Alpha"))
        runAll()
        assertTrue("Delta" in labels())
    }

    // ---- rename vs. an in-flight load -------------------------------------------------

    @Test fun `a rename during a load keeps its name, and the load's new apps still land`() {
        started()
        installed = installed + app("Delta")
        state.refresh()
        runIo()                                            // enumerated before the rename
        state.rename(permit, key("Alpha"), "Alpha", "Zulu", emptyList())
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Delta", "Zulu"), labels())
        assertFalse(state.needsRefresh)
    }

    @Test fun `rename relabels and re-sorts in memory without re-enumerating`() {
        started()
        val before = enumerations
        state.rename(permit, key("Alpha"), "Alpha", "Zulu", listOf("work"))
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("Zulu", state.config.names[key("Alpha")])
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        assertEquals(before, enumerations)
    }

    @Test fun `a rename re-sorts in the device language's alphabet`() {
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        started()
        state.rename(permit, key("Charlie"), "Charlie", "Ärzte", emptyList())
        assertEquals(listOf("Alpha", "Ärzte", "Bravo"), labels())
    }

    @Test fun `a rename re-sorts by the device language, not a fixed alphabet`() {
        // Swedish files Ä after Z; German alone could not tell a locale-blind sort apart.
        java.util.Locale.setDefault(java.util.Locale.of("sv", "SE"))
        started()
        state.rename(permit, key("Alpha"), "Alpha", "Ärzte", emptyList())
        assertEquals(listOf("Bravo", "Charlie", "Ärzte"), labels())
    }

    @Test fun `a rename stores the name cleaned, whoever commits it`() {
        started()
        state.rename(permit, key("Alpha"), "Alpha", " Zu\u202Elu\u200B ", emptyList())
        assertEquals("Zulu", state.config.names[key("Alpha")])
    }

    @Test fun `renaming back to the system label clears the override`() {
        started()
        state.rename(permit, key("Alpha"), "Alpha", "Zulu", emptyList())
        state.rename(permit, key("Alpha"), "Alpha", "Alpha", emptyList())
        assertNull(state.config.names[key("Alpha")])
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    /**
     * The dialog starts from the label cut to 100 chars (its LengthFilter), and a work app's label
     * is the badged one, which can be longer. An OK without a change is no custom name.
     */
    @Test fun `an untouched OK on a label too long for a name keeps no custom name`() {
        started()
        val label = "Work " + "x".repeat(LauncherLogic.MAX_NAME_LENGTH)
        state.rename(permit, key("Alpha"), label, label.take(LauncherLogic.MAX_NAME_LENGTH), listOf("work"))
        assertNull(state.config.names[key("Alpha")])
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
    }

    @Test fun `an untouched OK on a label with bidi isolates keeps no custom name`() {
        started()
        val label = "\u2068Alpha\u2069"
        state.rename(permit, key("Alpha"), label, label, emptyList())
        assertNull(state.config.names[key("Alpha")])
    }

    @Test fun `a rename with no tags removes the tag key`() {
        started()
        state.rename(permit, key("Alpha"), "Alpha", "", listOf("work"))
        state.rename(permit, key("Alpha"), "Alpha", "", emptyList())
        assertFalse(key("Alpha") in state.config.tags)
    }

    // ---- enumeration failure ------------------------------------------------------

    @Test fun `a failed enumeration keeps the last known good list`() {
        started()
        enumerationFails = true
        state.refresh()
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertEquals(listOf(false), refreshed)  // the hand-off still ran
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

    @Test fun `clearing a name during a load shows the system label once the load lands`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        started()
        state.refresh()
        runIo()
        state.rename(permit, key("Alpha"), "Alpha", "", emptyList())   // blank: back to the app's own
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `a restore shows its names at once, and drops the names it does not have`() {
        disk = VersoConfig(names = linkedMapOf(key("Bravo") to "Mine"))
        started()
        state.adopt(VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu")))
        assertEquals("before any refresh lands", listOf("Bravo", "Charlie", "Zulu"), labels())
    }

    /** A window "~restart" left behind holds the old state: its permit must not edit the new one. */
    @Test fun `a permit of another state edits nothing here, its own does`() {
        started()
        state.toggleFavorite(otherPermit(), key("Alpha"))
        assertTrue(state.config.favorites.isEmpty())
        state.toggleFavorite(permit, key("Alpha"))
        assertEquals(listOf(key("Alpha")), state.config.favorites)
    }

    // ---- saving ---------------------------------------------------------------

    @Test fun `every mutation saves a snapshot taken at the moment of the change`() {
        started()
        state.toggleFavorite(permit, key("Alpha"))
        runAll()
        state.toggleFavorite(permit, key("Bravo"))
        runAll()
        assertEquals(
            listOf(
                "fav=${key("Alpha")};hid=;names=;tags=",
                "fav=${key("Alpha")},${key("Bravo")};hid=;names=;tags=",
            ),
            written,
        )
    }

    @Test fun `saves queued faster than they are written collapse to the newest`() {
        started()
        state.toggleFavorite(permit, key("Alpha"))
        state.toggleFavorite(permit, key("Bravo"))
        state.toggleFavorite(permit, key("Charlie"))
        runAll()
        // Only the last payload hits the disk — and it carries all three changes.
        assertEquals(listOf("fav=${key("Alpha")},${key("Bravo")},${key("Charlie")};hid=;names=;tags="), written)
    }

    @Test fun `every failed save is reported, a working one is not`() {
        started()
        state.toggleFavorite(permit, key("Alpha"))
        runAll()
        assertEquals(0, saveFailures)
        writeSucceeds = false
        state.toggleFavorite(permit, key("Bravo"))
        runAll()
        state.toggleFavorite(permit, key("Charlie"))
        runAll()
        assertEquals("each one", 2, saveFailures)
    }

    @Test fun `a save skipped for a newer one is no failure`() {
        started()
        val before = written.size
        state.toggleFavorite(permit, key("Alpha"))
        state.toggleFavorite(permit, key("Bravo"))   // the first write is skipped: latest wins
        runAll()
        assertEquals(before + 1, written.size)
        assertEquals(0, saveFailures)
    }

    @Test fun `a refused save does not make the save queued ahead of it skip its write`() {
        started()
        state.toggleFavorite(permit, key("Alpha"))   // queued
        ioOpen = false
        state.toggleFavorite(permit, key("Bravo"))   // refused: it will never run
        runAll()
        assertEquals(listOf("fav=${key("Alpha")};hid=;names=;tags="), written)
    }

    // ---- flushSave: QueuedWork.waitToFinish's part (hard rule 4) ---------------------------------

    @Test fun `a stop writes a save the IO thread has not run, and its task then writes nothing`() {
        started()
        val before = written.size
        state.toggleFavorite(permit, key("Alpha"))
        state.toggleFavorite(permit, key("Bravo"))
        state.flushSave()
        assertEquals("only the newest state", listOf("fav=${key("Alpha")},${key("Bravo")};hid=;names=;tags="), written.drop(before))
        runAll()
        assertEquals(before + 1, written.size)
        assertEquals(0, saveFailures)
    }

    @Test fun `a stop with nothing unsaved writes nothing and says nothing`() {
        started()
        state.toggleFavorite(permit, key("Alpha"))
        runAll()
        val before = written.size
        state.flushSave()
        assertEquals(before, written.size)
        assertEquals(0, saveFailures)
    }

    @Test fun `a write failed at the stop is reported at once`() {
        started()
        writeSucceeds = false
        state.toggleFavorite(permit, key("Alpha"))
        state.flushSave()
        assertEquals(1, saveFailures)
        runAll()
        assertEquals("the IO task found nothing left to write", 1, saveFailures)
    }

    /** A real IO thread in the middle of a write: the stop must not return before the file is on disk. */
    @Test fun `a stop waits for a write the IO thread has begun`() {
        val inWrite = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val ioThreadQueue = LinkedBlockingQueue<Runnable>()
        val blocking = testLauncherState(
            io = { task -> ioThreadQueue.add(task) },
            main = { task -> task.run() },
            loadConfig = { VersoConfig() },
            enumerateApps = { Enumeration(installed, true) },
            serialize = { "s" },
            writeConfig = { inWrite.countDown(); finish.await(5, TimeUnit.SECONDS); done.set(true); true },
            onRefreshed = { _, _ -> },
        )
        blocking.refresh()
        ioThreadQueue.take().run()
        blocking.toggleFavorite(blocking.editPermit(), key("Alpha"))
        val ioThread = Thread { ioThreadQueue.take().run() }.apply { start() }
        assertTrue(inWrite.await(5, TimeUnit.SECONDS))
        val stop = Thread { blocking.flushSave() }.apply { start() }
        stop.join(200)
        assertTrue("the stop waits while the write runs", stop.isAlive)
        finish.countDown()
        stop.join(5000)
        ioThread.join(5000)
        assertFalse(stop.isAlive)
        assertTrue(done.get())
    }

    // ---- toggles ----------------------------------------------------------------

    /** A loaded state of its own, so its permit is a real one — for another state. */
    private fun otherPermit(): LauncherState.EditPermit {
        val q = Queues()
        val other = standalone(q).apply { refresh() }
        q.drain()
        return other.editPermit()
    }

    /** Every edit, not only some: each one checks the permit itself (audit 2026-10-22, #10). */
    @Test fun `an edit with another state's permit is ignored`() {
        // Two favorites, so a move would succeed if it ignored the permit.
        disk = VersoConfig(favorites = listOf(key("Alpha"), key("Bravo")))
        started()
        val stale = otherPermit()
        state.toggleFavorite(stale, key("Charlie"))
        state.toggleHidden(stale, key("Alpha"))
        state.toggleTag(stale, key("Alpha"), "work")
        state.rename(stale, key("Alpha"), "Alpha", "Mine", emptyList())
        assertFalse(state.moveFavorite(stale, key("Bravo"), key("Alpha")))
        assertNull(state.toggleOrder(stale))
        runAll()
        assertEquals(disk, state.config)
        assertTrue(written.isEmpty())
    }

    @Test fun `toggling twice restores the set, and a new favorite goes last`() {
        started()
        state.toggleFavorite(permit, key("Charlie"))
        state.toggleFavorite(permit, key("Alpha"))
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        state.toggleFavorite(permit, key("Charlie"))
        assertEquals(listOf(key("Alpha")), state.config.favorites)
    }

    @Test fun `favorites() follows the stored order and skips uninstalled ones`() {
        disk = VersoConfig(favorites = listOf(key("Charlie"), "gone/Gone", key("Alpha")))
        started()
        assertEquals(listOf("Charlie", "Alpha"), state.favorites().map { it.label })
    }

    @Test fun `toggleTag adds, removes, and drops an emptied key`() {
        started()
        state.toggleTag(permit, key("Alpha"), "Work")
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        state.toggleTag(permit, key("Alpha"), "work")
        assertFalse(key("Alpha") in state.config.tags)
    }

    @Test fun `moveFavorite reorders and saves, an impossible move does neither`() {
        disk = VersoConfig(favorites = listOf(key("Alpha"), key("Bravo"), key("Charlie")))
        started()
        assertTrue(state.moveFavorite(permit, key("Alpha"), key("Charlie")))
        assertEquals(listOf(key("Bravo"), key("Charlie"), key("Alpha")), state.config.favorites)
        runAll()
        assertEquals(1, written.size)

        assertFalse(state.moveFavorite(permit, key("Alpha"), "gone/Gone"))
        assertFalse(state.moveFavorite(permit, key("Alpha"), key("Alpha")))
        runAll()
        assertEquals(1, written.size)
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

    @Test fun `the order toggle is saved once the load has landed`() {
        started()
        assertEquals(true, state.toggleOrder(permit))
        assertTrue(state.config.byUsage)
        runAll()
        assertEquals(1, written.size)
        assertEquals(false, state.toggleOrder(permit))
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

    // ---- restore ------------------------------------------------------------------

    /** Saved like an edit: memory is the truth, the IO thread follows (hard rule 3). */
    @Test fun `adopt replaces the config, re-renders at once and saves it, without reading or enumerating`() {
        started()
        listChanges.clear()
        val before = enumerations
        val restored = VersoConfig(favorites = listOf(key("Bravo")))
        state.adopt(restored)
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        assertEquals("config replaced", listOf(true), refreshed)
        assertEquals("re-render", listOf(true), listChanges)
        runAll()
        assertEquals(listOf("fav=${key("Bravo")};hid=;names=;tags="), written)
        assertEquals(1, loadConfigCalls)
        assertEquals(before, enumerations)
    }

    // ---- replace: "~restore" and "~undo" (hard rule 7) --------------------------

    private val kept = mutableListOf<String>()
    private var keepSucceeds = true
    private fun replace(next: VersoConfig) = state.replace(next) { payload -> kept += payload; keepSucceeds }

    @Test fun `replace keeps the replaced config first, then adopts and saves the new one`() {
        disk = VersoConfig(favorites = listOf(key("Alpha")))
        started()
        assertEquals(Replaced.DONE, replace(VersoConfig(favorites = listOf(key("Bravo")))))
        assertEquals("the undo point holds the old config", listOf("fav=${key("Alpha")};hid=;names=;tags="), kept)
        assertEquals("written before anything was saved", emptyList<String>(), written)
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        runAll()
        assertEquals(listOf("fav=${key("Bravo")};hid=;names=;tags="), written)
    }

    @Test fun `no undo point, no replace`() {
        disk = VersoConfig(favorites = listOf(key("Alpha")))
        started()
        keepSucceeds = false
        assertEquals(Replaced.UNDO_FAILED, replace(VersoConfig(favorites = listOf(key("Bravo")))))
        assertEquals(listOf(key("Alpha")), state.config.favorites)
        runAll()
        assertEquals(emptyList<String>(), written)
    }

    /** "~undo ~undo" ends where it began, and an equal file leaves the undo point as it was. */
    @Test fun `replacing with the config in use keeps nothing and changes nothing`() {
        disk = VersoConfig(favorites = listOf(key("Alpha")))
        started()
        assertEquals(Replaced.SAME, replace(VersoConfig(favorites = listOf(key("Alpha")))))
        assertEquals(emptyList<String>(), kept)
        runAll()
        assertEquals(emptyList<String>(), written)
    }

    @Test fun `a replace that cannot be saved is said, and memory keeps it`() {
        started()
        writeSucceeds = false
        assertEquals(Replaced.DONE, replace(VersoConfig(favorites = listOf(key("Bravo")))))
        runAll()
        assertEquals(1, saveFailures)
        assertEquals(listOf(key("Bravo")), state.config.favorites)
    }

    @Test fun `adopt reports only once the restored config and its names are in place`() {
        started()
        val seen = mutableListOf<Pair<Set<String>, List<String>>>()
        onReport = { seen += state.config.favorites.toSet() to labels() }
        val restored = VersoConfig(favorites = listOf(key("Bravo")), names = linkedMapOf(key("Alpha") to "Zulu"))
        state.adopt(restored)
        assertEquals(listOf(setOf(key("Bravo")) to listOf("Bravo", "Charlie", "Zulu")), seen)
    }

    @Test fun `a refresh hand-off landing after adopt keeps the restored config and the new apps`() {
        started()
        installed = installed + app("Delta")  // installed while the restore runs
        state.refresh()
        runIo()                               // its hand-off is queued
        state.adopt(VersoConfig(favorites = listOf(key("Bravo"))))
        runAll()
        assertTrue("Delta" in labels())
        assertEquals(listOf(key("Bravo")), state.config.favorites)
    }

    // ---- default favorites -------------------------------------------------------

    @Test fun `a fresh install seeds the default favorites in slot order and saves them`() {
        fresh = true
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertEquals(listOf("Charlie", "Alpha"), state.favorites().map { it.label })
        assertEquals(1, written.size)
        assertTrue(written.single().startsWith("fav=${key("Charlie")},${key("Alpha")};"))
    }

    @Test fun `a fresh install whose recovered config has favorites is not seeded`() {
        // Hard rule 10: only into a config with no favorites, even when the install counts as fresh.
        fresh = true
        disk = VersoConfig(favorites = listOf(key("Bravo")))
        state.refresh()
        runAll()
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        assertEquals(emptyList<String>(), written)
    }

    @Test fun `an existing config is never seeded, even with no favorites`() {
        fresh = false
        started()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(0, candidatesCalls)
        assertEquals(emptyList<String>(), written)
    }

    @Test fun `a warm refresh does not seed`() {
        started()
        fresh = true  // the file vanished behind our back: memory is the truth now
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(0, candidatesCalls)
    }

    @Test fun `a failed first enumeration seeds nothing and saves nothing`() {
        fresh = true
        enumerationFails = true
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(emptyList<String>(), written)
    }

    /** The first load counts as fresh only once; the wait carries the seeding to a later refresh. */
    private fun freshStartWithoutAList() {
        fresh = true
        enumerationFails = true
        state.refresh()
        runAll()
        fresh = false
        enumerationFails = false
    }

    @Test fun `a first enumeration without a list leaves the seeding to the next one that has one`() {
        freshStartWithoutAList()
        assertTrue("a resume tries again", state.needsRefresh)
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertEquals(1, written.size)
        // Ended by the seeding: removing every default later must not bring them back.
        assertFalse(process.seedPending.get())
        assertFalse(state.needsRefresh)
    }

    /** No candidate app installed: nothing to seed, and the wait ends (audit 2026-10-22 (2), #9). */
    @Test fun `a fresh install with no candidate app ends the wait without seeding`() {
        fresh = true
        candidates = emptyList()
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(emptyList<String>(), written)
        assertFalse(process.seedPending.get())
        assertFalse("no recount on every resume", state.needsRefresh)
    }

    @Test fun `an edit meanwhile does not end the wait`() {
        freshStartWithoutAList()
        state.toggleOrder(permit)
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
    }

    @Test fun `a favorite chosen meanwhile ends the wait, even one toggled off again`() {
        freshStartWithoutAList()
        state.toggleFavorite(permit, key("Bravo"))
        state.toggleFavorite(permit, key("Bravo"))
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(0, candidatesCalls)
    }

    @Test fun `a restore meanwhile ends the wait`() {
        freshStartWithoutAList()
        state.adopt(VersoConfig(hidden = linkedSetOf(key("Bravo"))))
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
    }

    /** ~restart or a language change recreates the activity after an edit wrote verso.json. */
    @Test fun `the wait outlives a recreated instance that finds verso json already there`() {
        freshStartWithoutAList()
        state.toggleOrder(permit)
        disk = VersoConfig(byUsage = true)
        val next = testLauncherState(
            io = { task -> ioQueue.addLast(task); true },
            main = { task -> mainQueue.addLast(task) },
            loadConfig = { disk },
            enumerateApps = { Enumeration(installed, true) },
            serialize = { "" },
            writeConfig = { true },
            onRefreshed = { _, _ -> },
            isFreshInstall = { fresh },
            defaultFavoriteCandidates = { candidates },
            process = process,
        )
        next.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), next.config.favorites)
        assertTrue("the file's own settings stay", next.config.byUsage)
    }

    /** An enumeration that never completes (a profile that stays unreadable) must not starve the defaults. */
    @Test fun `an incomplete first enumeration still seeds`() {
        fresh = true
        enumerationComplete = false
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
    }

    @Test fun `an existing config whose first enumeration failed is not seeded later either`() {
        fresh = false
        enumerationFails = true
        state.refresh()
        runAll()
        enumerationFails = false
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(0, candidatesCalls)
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

    @Test fun `the status is no part of the key, and a rename keeps it`() {
        val ready = AppEntry("Gmail", "com.g", "com.g.Main", "Gmail", profile = 10)
        val paused = AppEntry("Gmail", "com.g", "com.g.Main", "Gmail", profile = 10, status = AppStatus.PAUSED)
        assertEquals(ready.key, paused.key)  // favorites and names survive a pause
        assertEquals(AppStatus.PAUSED, paused.withLabel("Mail").status)
    }

    @Test fun `a work copy is its own row with its own favorite and name state`() {
        val work = AppEntry("Charlie (Work)", "com.charlie", "com.charlie.Main", "Charlie (Work)", profile = 10)
        installed = installed + work
        disk = VersoConfig(favorites = listOf(key("Charlie")))
        started()
        assertEquals(listOf("Charlie"), state.favorites().map { it.label })
        state.toggleFavorite(permit, work.key)
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
