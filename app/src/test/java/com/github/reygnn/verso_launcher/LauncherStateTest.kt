package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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

    /** "The file on disk". NOT updated by writeConfig: a test that saves and then reloads must set it itself. */
    private var disk = VersoConfig()
    private var diskUnreadable = false
    private var setAsideWorks = true
    private var setAsideCalls = 0
    private var loadConfigCalls = 0

    /** Reported without custom names and NOT sorted (reversed below), so the tests see LauncherState apply both. */
    private var installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))
    private var enumerationFails = false
    private var enumerationComplete = true
    private var enumerations = 0

    private val written = mutableListOf<String>()
    private var writeSucceeds = true

    private fun runIoThrowing() {
        try {
            ioQueue.removeFirst().run()
            throw AssertionError("expected the IO task to throw")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    private val refreshed = mutableListOf<Boolean>()
    /** An edit permit, as MainActivity takes one from its gate before every edit. */
    private val gate = CommandGate()
    private val idle get() = gate.idle()!!
    private var saveFailures = 0
    /** Process-wide like VersoApp's: it outlives a LauncherState. */
    private val startedOverFlag = java.util.concurrent.atomic.AtomicBoolean()
    private val listChanges = mutableListOf<Boolean>()
    /** Runs in onRefreshed: what the Activity sees when it re-renders. */
    private var onReport: () -> Unit = {}

    private val state = LauncherState(
        io = { task -> if (ioOpen) { ioQueue.addLast(task); true } else false },
        main = { task -> mainQueue.addLast(task) },
        // A copy: otherwise a test could pass only because a mutation also "changed the disk".
        loadConfig = { loadConfigCalls++; if (diskUnreadable) null else disk.snapshot() },
        setAsideUnreadable = {
            setAsideCalls++
            if (setAsideWorks) { diskUnreadable = false; disk = VersoConfig(); fresh = true }
            setAsideWorks
        },
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
        onRefreshed = { replaced, changed -> refreshed += replaced; listChanges += changed; onReport() },
        isFreshInstall = { fresh },
        startedOverFlag = startedOverFlag,
        defaultFavoriteCandidates = { candidatesCalls++; candidates },
    )

    private var fresh = false
    private var candidates = listOf(listOf(key("Charlie")), listOf("gone/Gone", key("Alpha")))
    private var candidatesCalls = 0

    private fun app(label: String) =
        AppEntry.of(label, "com.$label".lowercase(), "com.${label.lowercase()}.Main", label)

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
        initialRecents: List<String> = emptyList(),
        initialRecentsLoaded: Boolean = false,
        onRecentsChanged: (List<String>, Boolean) -> Unit = { _, _ -> },
        loadRecents: () -> List<String>? = { emptyList() },
        writeRecents: (List<String>) -> Unit = {},
        loadUsage: () -> Map<String, Usage>? = { emptyMap() },
        writeUsage: (Map<String, Usage>) -> Unit = {},
        onUsageChanged: (Map<String, Usage>, Boolean) -> Unit = { _, _ -> },
    ) = LauncherState(
        io = { q.io.addLast(it); true }, main = { q.main.addLast(it) },
        loadConfig = { VersoConfig() }, enumerateApps = { Enumeration(emptyList(), true) },
        serialize = { "" }, writeConfig = { true }, onRefreshed = { _, changed -> listChanged += changed },
        initialRecents = initialRecents,
        initialRecentsLoaded = initialRecentsLoaded,
        onRecentsChanged = onRecentsChanged,
        loadRecents = loadRecents,
        writeRecents = writeRecents,
        loadUsage = loadUsage,
        writeUsage = { log, _ -> writeUsage(log) },
        now = { 1000L },
        onUsageChanged = onUsageChanged,
    )

    private fun labels() = state.apps.map { it.label }

    private fun started() {
        state.refresh()
        runAll()
        written.clear()
        refreshed.clear()
    }

    // ---- cold start / warm refresh -----------------------------------------------

    @Test fun `cold start reads the config from disk and commits the apps`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Charlie")))
        assertFalse(state.appsLoaded)

        state.refresh()
        runAll()

        assertTrue(state.appsLoaded)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertEquals(setOf(key("Charlie")), state.config.favorites)
        assertEquals(listOf(true), refreshed)  // config replaced by the disk copy
        assertEquals(1, loadConfigCalls)
    }

    @Test fun `a warm refresh does not re-read the file`() {
        started()
        state.refresh()
        runAll()
        assertEquals(1, loadConfigCalls)
        assertEquals(listOf(false), refreshed)
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

    @Test fun `the first load shows the custom names it read`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        state.refresh()
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("the system label stays on the entry", "Alpha", state.apps.last().systemLabel)
    }

    // ---- an unreadable config: set aside on the first load, then a fresh start -----------

    @Test fun `an unreadable config is set aside and the first load starts over with the defaults`() {
        diskUnreadable = true
        state.refresh()
        runAll()
        assertEquals(1, setAsideCalls)
        assertTrue(state.configLoaded)
        assertEquals(setOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertEquals(1, written.size)
        assertTrue("said once", state.takeStartedOver())
        assertFalse(state.takeStartedOver())
    }

    @Test fun `if it cannot be set aside it stays not loaded, no edit, no save, and is retried`() {
        diskUnreadable = true
        setAsideWorks = false
        state.refresh()
        runAll()
        assertFalse(state.configLoaded)
        assertTrue(state.appsLoaded)
        assertTrue(state.needsRefresh)
        assertNull(state.loadedSnapshot())
        state.toggleFavorite(idle, key("Alpha"))
        state.toggleHidden(idle, key("Bravo"))
        state.toggleTag(idle, key("Alpha"), "work")
        state.rename(idle, key("Alpha"), "Alpha", "Mine", emptyList())
        assertFalse(state.moveFavorite(idle, key("Alpha"), key("Bravo")))
        state.save()
        runAll()
        assertTrue(written.isEmpty())
        assertFalse(state.takeStartedOver())
        setAsideWorks = true
        state.refresh()  // a resume: needsRefresh
        runAll()
        assertTrue(state.configLoaded)
    }

    /** Superseded before its hand-off, the next load finds no verso.json: a plain fresh install. */
    @Test fun `a start-over whose hand-off was lost is still said`() {
        diskUnreadable = true
        state.refresh()
        runIo()                 // set aside; its hand-off is queued
        state.refresh()         // supersedes it
        runAll()
        assertEquals(1, setAsideCalls)
        assertTrue(state.configLoaded)
        assertEquals(setOf(key("Charlie"), key("Alpha")), state.config.favorites)
        assertTrue("said", state.takeStartedOver())
    }

    @Test fun `a fresh install with nothing set aside is no start-over`() {
        fresh = true
        started()
        assertFalse(state.takeStartedOver())
    }

    @Test fun `a start-over is said by the next instance when the first was recreated`() {
        diskUnreadable = true
        state.refresh()
        runIo()
        mainQueue.clear()       // onUi drops the hand-off for the destroyed instance
        assertFalse("not loaded: not yet", state.takeStartedOver())
        val next = LauncherState(
            io = { task -> ioQueue.addLast(task); true },
            main = { task -> mainQueue.addLast(task) },
            loadConfig = { disk.snapshot() },
            enumerateApps = { Enumeration(installed, true) },
            serialize = { "" },
            writeConfig = { true },
            onRefreshed = { _, _ -> },
            startedOverFlag = startedOverFlag,
        )
        next.refresh()
        runAll()
        assertTrue(next.takeStartedOver())
        assertFalse("said once", next.takeStartedOver())
    }

    @Test fun `a readable config is never set aside`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Bravo")))
        started()
        assertEquals(0, setAsideCalls)
        assertFalse(state.takeStartedOver())
    }

    @Test fun `loadedSnapshot is a copy of the loaded config`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        assertNull("not before the first load", state.loadedSnapshot())
        started()
        val snap = state.loadedSnapshot()!!
        state.toggleFavorite(idle, key("Bravo"))
        assertEquals(setOf(key("Alpha")), snap.favorites)
    }

    @Test fun `configLoaded is false until the disk copy is loaded`() {
        assertFalse(state.configLoaded)
        state.refresh()
        runIo()
        assertFalse(state.configLoaded)
        runMain()
        assertTrue(state.configLoaded)
    }

    @Test fun `adopt before the first load is refused, and the first load still lands`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        state.refresh()
        runIo()
        assertFalse(state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo")))))
        assertFalse(state.configLoaded)
        assertTrue("nothing adopted", state.config.favorites.isEmpty())
        runAll()
        assertEquals(setOf(key("Alpha")), state.config.favorites)
        assertEquals(listOf(true), refreshed)
    }

    @Test fun `adopt before a fresh first load is refused, and the defaults are still seeded`() {
        fresh = true
        state.refresh()
        runIo()
        assertFalse(state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo")))))
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites.toList())
    }

    @Test fun `the first load of the newest generation adopts the file unchanged`() {
        disk = VersoConfig(
            favorites = linkedSetOf(key("Charlie"), key("Alpha")),
            hidden = linkedSetOf(key("Bravo")),
            names = linkedMapOf(key("Alpha") to "Mine"),
        )
        state.refresh()
        state.refresh()
        runAll()
        assertTrue(disk.sameContent(state.loadedSnapshot()!!))
        assertEquals(listOf(true), refreshed)
        assertEquals(emptyList<String>(), written)
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
        state.toggleHidden(idle, key("Alpha"))
        runAll()
        assertTrue("Delta" in labels())
    }

    // ---- rename vs. an in-flight load -------------------------------------------------

    @Test fun `a rename during a load keeps its name, and the load's new apps still land`() {
        started()
        installed = installed + app("Delta")
        state.refresh()
        runIo()                                            // enumerated before the rename
        state.rename(idle, key("Alpha"), "Alpha", "Zulu", emptyList())
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Delta", "Zulu"), labels())
        assertFalse(state.needsRefresh)
    }

    @Test fun `rename relabels and re-sorts in memory without re-enumerating`() {
        started()
        val before = enumerations
        state.rename(idle, key("Alpha"), "Alpha", "Zulu", listOf("work"))
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("Zulu", state.config.names[key("Alpha")])
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        assertEquals(before, enumerations)
    }

    @Test fun `a rename re-sorts in the device language's alphabet`() {
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        started()
        state.rename(idle, key("Charlie"), "Charlie", "Ärzte", emptyList())
        assertEquals(listOf("Alpha", "Ärzte", "Bravo"), labels())
    }

    @Test fun `a rename re-sorts by the device language, not a fixed alphabet`() {
        // Swedish files Ä after Z; German alone could not tell a locale-blind sort apart.
        java.util.Locale.setDefault(java.util.Locale.of("sv", "SE"))
        started()
        state.rename(idle, key("Alpha"), "Alpha", "Ärzte", emptyList())
        assertEquals(listOf("Bravo", "Charlie", "Ärzte"), labels())
    }

    @Test fun `a cleanup removes every entry of the keys in one save`() {
        disk = VersoConfig(
            hidden = linkedSetOf("gone/A"),
            favorites = linkedSetOf(key("Alpha"), "gone/A"),
            names = linkedMapOf("gone/A" to "Old"),
            tags = linkedMapOf("gone/A" to mutableListOf("x"), key("Alpha") to mutableListOf("y")),
        )
        started()
        cleanup()
        runAll()
        assertEquals(setOf(key("Alpha")), state.config.favorites)
        assertTrue(state.config.hidden.isEmpty())
        assertTrue(state.config.names.isEmpty())
        assertEquals(setOf(key("Alpha")), state.config.tags.keys)
        assertEquals(1, written.size)
    }

    @Test fun `a cleanup asked before the first load is refused and the load still lands`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha"), "gone/A"))
        state.refresh()
        cleanup()
        runAll()
        assertEquals(listOf("done:${CleanupOutcome.Refused}"), cleanupLog)
        assertTrue(state.configLoaded)
        assertEquals(setOf(key("Alpha"), "gone/A"), state.config.favorites)
        assertTrue(written.isEmpty())
    }

    // ---- cleanup: re-check, undo point first, then forget (hard rule 14) --------------

    private val cleanupLog = mutableListOf<String>()
    private var undoName: String? = "verso-pre-cleanup-2026-10-13_120000.json"
    private var recheckPlan: CleanupPlan = CleanupPlan.Offer(Orphans(setOf("gone/A"), 1, 0, 0, 0))
    private var recheckThrows = false

    private fun cleanup(confirmed: Set<String> = setOf("gone/A")) = state.cleanup(
        confirmed = confirmed,
        recheck = { cfg ->
            cleanupLog += "recheck:${cfg.favorites}"
            if (recheckThrows) throw IllegalStateException("recheck bug")
            recheckPlan
        },
        writeUndo = { payload -> cleanupLog += "undo:$payload|written=${written.size}"; undoName },
    ) { cleanupLog += "done:$it" }

    private fun startedWithOrphan() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha"), "gone/A"))
        started()
    }

    @Test fun `cleanup writes the untouched config as undo point, then forgets in one save`() {
        startedWithOrphan()
        cleanup()
        runIo()
        assertEquals(setOf(key("Alpha"), "gone/A"), state.config.favorites)
        runAll()
        assertTrue(cleanupLog[1].startsWith("undo:fav=${key("Alpha")},gone/A;"))
        assertTrue(cleanupLog[1].endsWith("|written=0"))
        assertEquals(setOf(key("Alpha")), state.config.favorites)
        assertEquals(1, written.size)
        assertTrue(cleanupLog.last().startsWith("done:Done"))
    }

    @Test fun `no undo point, no cleanup`() {
        startedWithOrphan()
        undoName = null
        cleanup()
        runAll()
        assertEquals(setOf(key("Alpha"), "gone/A"), state.config.favorites)
        assertTrue(written.isEmpty())
        assertEquals("done:${CleanupOutcome.UndoFailed}", cleanupLog.last())
    }

    @Test fun `an incomplete re-check removes nothing and writes no undo point`() {
        startedWithOrphan()
        recheckPlan = CleanupPlan.Incomplete
        cleanup()
        runAll()
        assertEquals(setOf(key("Alpha"), "gone/A"), state.config.favorites)
        assertTrue(cleanupLog.none { it.startsWith("undo:") })
        assertEquals("done:${CleanupOutcome.Incomplete}", cleanupLog.last())
    }

    @Test fun `only what was confirmed AND is still an orphan is removed`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha"), "gone/A", "gone/B"))
        started()
        // gone/B was not in the dialog; gone/A came back since (no longer an orphan).
        recheckPlan = CleanupPlan.Offer(Orphans(setOf("gone/B"), 1, 0, 0, 0))
        cleanup(confirmed = setOf("gone/A"))
        runAll()
        assertEquals(setOf(key("Alpha"), "gone/A", "gone/B"), state.config.favorites)
        assertEquals("done:${CleanupOutcome.NothingToDo}", cleanupLog.last())
    }

    @Test fun `a config replaced between the check and the removal is left alone`() {
        startedWithOrphan()
        cleanup()
        runIo()                                            // check + undo point done
        val restored = VersoConfig(favorites = linkedSetOf(key("Bravo"), "gone/A"), names = linkedMapOf("gone/A" to "Old"))
        mainQueue.addFirst(Runnable { assertTrue(state.adopt(restored)) })  // a restore lands first
        runAll()
        assertEquals(setOf(key("Bravo"), "gone/A"), state.config.favorites)
        assertEquals("Old", state.config.names["gone/A"])
        assertEquals("done:${CleanupOutcome.Superseded}", cleanupLog.last())
    }

    @Test fun `an edit between the check and the removal is kept, and the cleanup still runs`() {
        startedWithOrphan()
        cleanup()
        runIo()
        mainQueue.addFirst(Runnable { state.toggleFavorite(idle, key("Bravo")) })
        runAll()
        assertEquals(setOf(key("Alpha"), key("Bravo")), state.config.favorites)
        assertTrue(cleanupLog.last().startsWith("done:Done"))
    }

    @Test fun `a refused IO task cleans nothing and says so`() {
        startedWithOrphan()
        ioOpen = false
        cleanup()
        assertEquals(listOf("done:${CleanupOutcome.Refused}"), cleanupLog)
    }

    @Test fun `a cleanup whose re-check throws still reports, and removes nothing`() {
        startedWithOrphan()
        recheckThrows = true
        cleanup()
        runIoThrowing()
        runMain()
        assertEquals("done:${CleanupOutcome.Refused}", cleanupLog.last())
        assertEquals(setOf(key("Alpha"), "gone/A"), state.config.favorites)
    }

    @Test fun `a rename stores the name cleaned, whoever commits it`() {
        started()
        state.rename(idle, key("Alpha"), "Alpha", " Zu\u202Elu\u200B ", emptyList())
        assertEquals("Zulu", state.config.names[key("Alpha")])
    }

    @Test fun `renaming back to the system label clears the override`() {
        started()
        state.rename(idle, key("Alpha"), "Alpha", "Zulu", emptyList())
        state.rename(idle, key("Alpha"), "Alpha", "Alpha", emptyList())
        assertNull(state.config.names[key("Alpha")])
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `a rename with no tags removes the tag key`() {
        started()
        state.rename(idle, key("Alpha"), "Alpha", "", listOf("work"))
        state.rename(idle, key("Alpha"), "Alpha", "", emptyList())
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
        enumerationComplete = true
        state.refresh()
        runAll()
        assertFalse(state.needsRefresh)
    }

    @Test fun `clearing a name during a load shows the system label once the load lands`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        started()
        state.refresh()
        runIo()
        state.rename(idle, key("Alpha"), "Alpha", "", emptyList())   // blank: back to the app's own
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `a cleanup of a listed app's name shows its own label again`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        recheckPlan = CleanupPlan.Offer(Orphans(setOf(key("Alpha")), 0, 0, 1, 0))
        started()
        cleanup(setOf(key("Alpha")))
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `a restore shows its names at once, and drops the names it does not have`() {
        disk = VersoConfig(names = linkedMapOf(key("Bravo") to "Mine"))
        started()
        assertTrue(state.adopt(VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))))
        assertEquals("before any refresh lands", listOf("Bravo", "Charlie", "Zulu"), labels())
    }

    @Test fun `a rename before the config is loaded is ignored and the first load still lands`() {
        disk = VersoConfig().apply { names[key("Bravo")] = "Mine" }
        state.refresh()
        runIo()
        state.rename(idle, key("Alpha"), "Alpha", "Zulu", listOf("work"))  // a cached favorite
        runAll()
        assertTrue(state.configLoaded)
        assertTrue(state.appsLoaded)
        assertEquals("Mine", state.config.names[key("Bravo")])
        assertNull(state.config.names[key("Alpha")])
        assertEquals(emptyList<String>(), written)
    }

    // ---- saving ---------------------------------------------------------------

    @Test fun `every mutation saves a snapshot taken at the moment of the change`() {
        started()
        state.toggleFavorite(idle, key("Alpha"))
        runAll()
        state.toggleFavorite(idle, key("Bravo"))
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
        state.toggleFavorite(idle, key("Alpha"))
        state.toggleFavorite(idle, key("Bravo"))
        state.toggleFavorite(idle, key("Charlie"))
        runAll()
        // Only the last payload hits the disk — and it carries all three changes.
        assertEquals(listOf("fav=${key("Alpha")},${key("Bravo")},${key("Charlie")};hid=;names=;tags="), written)
    }

    @Test fun `save before the first load writes nothing`() {
        // The empty default in memory must never be written over the real file.
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        state.refresh()  // the load is queued, not yet run
        state.save()
        runAll()
        assertEquals(emptyList<String>(), written)
        assertEquals(setOf(key("Alpha")), state.config.favorites)
    }

    @Test fun `every failed save is reported, a working one is not`() {
        started()
        state.toggleFavorite(idle, key("Alpha"))
        runAll()
        assertEquals(0, saveFailures)
        writeSucceeds = false
        state.toggleFavorite(idle, key("Bravo"))
        runAll()
        state.toggleFavorite(idle, key("Charlie"))
        runAll()
        assertEquals("each one", 2, saveFailures)
    }

    @Test fun `a save skipped for a newer one is no failure`() {
        started()
        val before = written.size
        state.toggleFavorite(idle, key("Alpha"))
        state.toggleFavorite(idle, key("Bravo"))   // the first write is skipped: latest wins
        runAll()
        assertEquals(before + 1, written.size)
        assertEquals(0, saveFailures)
    }

    @Test fun `a refused save does not make the save queued ahead of it skip its write`() {
        started()
        state.toggleFavorite(idle, key("Alpha"))   // queued
        ioOpen = false
        state.toggleFavorite(idle, key("Bravo"))   // refused: it will never run
        runAll()
        assertEquals(listOf("fav=${key("Alpha")};hid=;names=;tags="), written)
    }

    // ---- toggles ----------------------------------------------------------------

    @Test fun `an edit with a permit from before a command started is ignored`() {
        started()
        val stale = idle
        gate.start {
            state.toggleFavorite(stale, key("Alpha"))
            state.rename(stale, key("Alpha"), "Alpha", "Mine", emptyList())
            assertFalse(state.moveFavorite(stale, key("Alpha"), key("Bravo")))
        }
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertTrue(state.config.names.isEmpty())
        assertTrue(written.isEmpty())
    }

    @Test fun `toggling twice restores the set, and a new favorite goes last`() {
        started()
        state.toggleFavorite(idle, key("Charlie"))
        state.toggleFavorite(idle, key("Alpha"))
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites.toList())
        state.toggleFavorite(idle, key("Charlie"))
        assertEquals(listOf(key("Alpha")), state.config.favorites.toList())
    }

    @Test fun `favorites() follows the stored order and skips uninstalled ones`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Charlie"), "gone/Gone", key("Alpha")))
        started()
        assertEquals(listOf("Charlie", "Alpha"), state.favorites().map { it.label })
    }

    @Test fun `toggleTag adds, removes, and drops an emptied key`() {
        started()
        state.toggleTag(idle, key("Alpha"), "Work")
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        state.toggleTag(idle, key("Alpha"), "work")
        assertFalse(key("Alpha") in state.config.tags)
    }

    @Test fun `moveFavorite reorders and saves, an impossible move does neither`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha"), key("Bravo"), key("Charlie")))
        started()
        assertTrue(state.moveFavorite(idle, key("Alpha"), key("Charlie")))
        assertEquals(listOf(key("Bravo"), key("Charlie"), key("Alpha")), state.config.favorites.toList())
        runAll()
        assertEquals(1, written.size)

        assertFalse(state.moveFavorite(idle, key("Alpha"), "gone/Gone"))
        assertFalse(state.moveFavorite(idle, key("Alpha"), key("Alpha")))
        runAll()
        assertEquals(1, written.size)
    }

    // ---- recents ----------------------------------------------------------------

    @Test fun `recents start from the process-wide list and report every change`() {
        val reported = mutableListOf<List<String>>()
        val s = standalone(initialRecents = listOf("a", "b"), onRecentsChanged = { keys, _ -> reported += keys })
        assertEquals(listOf("a", "b"), s.recentKeys)
        s.recordLaunch("c")
        assertEquals(listOf(listOf("c", "a", "b")), reported)
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
        val reported = mutableListOf<Pair<List<String>, Boolean>>()
        val first = standalone(
            q, loadRecents = { listOf("a") }, writeRecents = { writes += it },
            onRecentsChanged = { keys, loaded -> reported += keys to loaded },
        )
        first.recordLaunch("b")
        assertEquals(listOf("b") to false, reported.last())
        first.refresh()
        q.drain()
        assertEquals(listOf("b", "a") to true, reported.last())

        var reads = 0
        val (keys, loaded) = reported.last()
        val second = standalone(
            q, initialRecents = keys, initialRecentsLoaded = loaded,
            loadRecents = { reads++; null }, writeRecents = { writes += it },
        )
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

    // ---- "%" order and usage -----------------------------------------------------

    @Test fun `the order toggle is refused before the load and saved after it`() {
        assertNull(state.toggleOrder(idle))
        assertTrue(written.isEmpty())
        started()
        assertEquals(true, state.toggleOrder(idle))
        assertTrue(state.config.byUsage)
        runAll()
        assertEquals(1, written.size)
        assertEquals(false, state.toggleOrder(idle))
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
        val reported = mutableListOf<Pair<Map<String, Usage>, Boolean>>()
        val s = standalone(
            q, loadUsage = { mapOf("a" to Usage(1.0, 1000L)) }, writeUsage = { writes += it },
            onUsageChanged = { log, loaded -> reported += log to loaded },
        )
        s.recordLaunch("a")
        assertTrue("not written over a file not read yet", q.io.isEmpty())
        assertEquals(false, reported.last().second)
        s.refresh()
        q.drain()
        assertEquals(Usage(2.0, 1000L), s.usage["a"])
        assertEquals(listOf(mapOf("a" to Usage(2.0, 1000L))), writes)
        assertEquals(true, reported.last().second)
        s.recordLaunch("b")
        q.runIo()
        assertEquals(setOf("a", "b"), writes.last().keys)
    }

    // ---- restore ------------------------------------------------------------------

    /** The restore wrote verso.json itself (RestoreSteps); adopt must not write it again. */
    @Test fun `adopt replaces the config and re-renders at once, without writing, reading or enumerating`() {
        started()
        listChanges.clear()
        val before = enumerations
        val restored = VersoConfig(favorites = linkedSetOf(key("Bravo")))
        assertTrue(state.adopt(restored))
        assertEquals(setOf(key("Bravo")), state.config.favorites)
        assertEquals("config replaced", listOf(true), refreshed)
        assertEquals("re-render", listOf(true), listChanges)
        runAll()
        assertEquals(emptyList<String>(), written)
        assertEquals(1, loadConfigCalls)
        assertEquals(before, enumerations)
    }

    @Test fun `adopt reports only once the restored config is in place`() {
        started()
        val seen = mutableListOf<Set<String>>()
        onReport = { seen += state.config.favorites.toSet() }
        assertTrue(state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo")))))
        assertEquals(listOf(setOf(key("Bravo"))), seen)
    }

    @Test fun `a refresh requested before a restore still lands after it`() {
        started()
        installed = installed + app("Delta")  // installed while the restore runs
        state.refresh()
        runIo()                               // its hand-off is queued
        assertTrue(state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo")))))
        runAll()
        assertTrue("Delta" in labels())
        assertEquals(setOf(key("Bravo")), state.config.favorites)
    }

    // ---- default favorites -------------------------------------------------------

    @Test fun `a fresh install seeds the default favorites in slot order and saves them`() {
        fresh = true
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites.toList())
        assertEquals(listOf("Charlie", "Alpha"), state.favorites().map { it.label })
        assertEquals(1, written.size)
        assertTrue(written.single().startsWith("fav=${key("Charlie")},${key("Alpha")};"))
    }

    @Test fun `a fresh install whose recovered config has favorites is not seeded`() {
        // Hard rule 10: only into a config with no favorites, even when the install counts as fresh.
        fresh = true
        disk = VersoConfig(favorites = linkedSetOf(key("Bravo")))
        state.refresh()
        runAll()
        assertEquals(setOf(key("Bravo")), state.config.favorites)
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

    // ---- AppEntry --------------------------------------------------------------

    @Test fun `a personal app keeps the plain key, another profile's copy gets the serial`() {
        val personal = AppEntry.of("Gmail", "com.google.android.gm", "com.google.android.gm.Main", "Gmail")
        val work = AppEntry.of("Gmail (Work)", "com.google.android.gm", "com.google.android.gm.Main", "Gmail (Work)", profile = 10)
        assertEquals("com.google.android.gm/com.google.android.gm.Main", personal.key)
        assertEquals("com.google.android.gm/com.google.android.gm.Main#10", work.key)
        assertEquals(work.key, work.withLabel("Mail").key)

        assertEquals(10L, work.withLabel("Mail").profile)
    }

    @Test fun `the status is no part of the key, and a rename keeps it`() {
        val ready = AppEntry.of("Gmail", "com.g", "com.g.Main", "Gmail", profile = 10)
        val paused = AppEntry.of("Gmail", "com.g", "com.g.Main", "Gmail", profile = 10, status = AppStatus.PAUSED)
        assertEquals(ready.key, paused.key)  // favorites and names survive a pause
        assertEquals(AppStatus.PAUSED, paused.withLabel("Mail").status)
    }

    @Test fun `a work copy is its own row with its own favorite and name state`() {
        val work = AppEntry.of("Charlie (Work)", "com.charlie", "com.charlie.Main", "Charlie (Work)", profile = 10)
        installed = installed + work
        disk = VersoConfig(favorites = linkedSetOf(key("Charlie")))
        started()
        assertEquals(listOf("Charlie"), state.favorites().map { it.label })
        state.toggleFavorite(idle, work.key)
        assertEquals(listOf("Charlie", "Charlie (Work)"), state.favorites().map { it.label })
    }

    @Test fun `the personal key is package slash class, the format old configs were written in`() {
        val e = AppEntry.of("Mail", "com.example.mail", "com.example.mail.MainActivity", "Mail")
        assertEquals("com.example.mail/com.example.mail.MainActivity", e.key)
        assertEquals("mail", e.labelLower)
        assertEquals("inbox", e.withLabel("Inbox").labelLower)
        assertEquals("a plain copy cannot leave it stale", "inbox", e.copy(label = "Inbox").labelLower)
        assertEquals(e.key, e.withLabel("Inbox").key)
    }
}
