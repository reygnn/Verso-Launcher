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
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    private val refreshed = mutableListOf<Boolean>()
    /** An edit permit, as MainActivity takes one from its gate before every edit. */
    private val gate = CommandGate()
    private val permit get() = state.editPermit(gate.idle()!!)!!
    private var saveFailures = 0
    /** Process-wide like VersoApp's: it outlives a LauncherState. */
    private val process = ProcessState()
    private val listChanges = mutableListOf<Boolean>()
    /** Runs in onRefreshed: what the Activity sees when it re-renders. */
    private var onReport: () -> Unit = {}

    private val state = testLauncherState(
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
        onRefreshed = { replaced, changed -> refreshed += replaced; listChanges += changed; onReport() },
        isFreshInstall = { fresh },
        defaultFavoriteCandidates = { candidatesCalls++; candidates },
        process = process,
        idle = { gate.idle() },
    )

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

    @Test fun `cold start reads the config from disk and commits the apps`() {
        disk = VersoConfig(favorites = listOf(key("Charlie")))
        assertFalse(state.appsLoaded)

        state.refresh()
        runAll()

        assertTrue(state.appsLoaded)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
        assertEquals(listOf(key("Charlie")), state.config.favorites)
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

    @Test fun `loadedSnapshot is a copy of the loaded config`() {
        disk = VersoConfig(favorites = listOf(key("Alpha")))
        assertNull("not before the first load", state.loadedSnapshot())
        started()
        val snap = state.loadedSnapshot()!!
        state.toggleFavorite(permit, key("Bravo"))
        assertEquals(listOf(key("Alpha")), snap.favorites)
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
        disk = VersoConfig(favorites = listOf(key("Alpha")))
        state.refresh()
        runIo()
        assertFalse(state.adopt(VersoConfig(favorites = listOf(key("Bravo")))))
        assertFalse(state.configLoaded)
        assertTrue("nothing adopted", state.config.favorites.isEmpty())
        runAll()
        assertEquals(listOf(key("Alpha")), state.config.favorites)
        assertEquals(listOf(true), refreshed)
    }

    @Test fun `adopt before a fresh first load is refused, and the defaults are still seeded`() {
        fresh = true
        state.refresh()
        runIo()
        assertFalse(state.adopt(VersoConfig(favorites = listOf(key("Bravo")))))
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
    }

    @Test fun `the first load of the newest generation adopts the file unchanged`() {
        disk = VersoConfig(
            favorites = listOf(key("Charlie"), key("Alpha")),
            hidden = linkedSetOf(key("Bravo")),
            names = linkedMapOf(key("Alpha") to "Mine"),
        )
        state.refresh()
        state.refresh()
        runAll()
        assertEquals(disk, state.loadedSnapshot())
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

    @Test fun `a cleanup removes every entry of the keys in one save`() {
        disk = VersoConfig(
            hidden = linkedSetOf("gone/A"),
            favorites = listOf(key("Alpha"), "gone/A"),
            names = linkedMapOf("gone/A" to "Old"),
            tags = linkedMapOf("gone/A" to mutableListOf("x"), key("Alpha") to mutableListOf("y")),
        )
        started()
        cleanup()
        runAll()
        assertEquals(listOf(key("Alpha")), state.config.favorites)
        assertTrue(state.config.hidden.isEmpty())
        assertTrue(state.config.names.isEmpty())
        assertEquals(setOf(key("Alpha")), state.config.tags.keys)
        assertEquals(1, written.size)
    }

    @Test fun `a cleanup asked before the first load is refused and the load still lands`() {
        disk = VersoConfig(favorites = listOf(key("Alpha"), "gone/A"))
        state.refresh()
        cleanup()
        runAll()
        assertEquals(listOf("done:${CleanupOutcome.Refused}"), cleanupLog)
        assertTrue(state.configLoaded)
        assertEquals(listOf(key("Alpha"), "gone/A"), state.config.favorites)
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
        disk = VersoConfig(favorites = listOf(key("Alpha"), "gone/A"))
        started()
    }

    @Test fun `cleanup writes the untouched config as undo point, then forgets in one save`() {
        startedWithOrphan()
        cleanup()
        runIo()
        assertEquals(listOf(key("Alpha"), "gone/A"), state.config.favorites)
        runAll()
        assertTrue(cleanupLog[1].startsWith("undo:fav=${key("Alpha")},gone/A;"))
        assertTrue(cleanupLog[1].endsWith("|written=0"))
        assertEquals(listOf(key("Alpha")), state.config.favorites)
        assertEquals(1, written.size)
        assertTrue(cleanupLog.last().startsWith("done:Done"))
    }

    @Test fun `no undo point, no cleanup`() {
        startedWithOrphan()
        undoName = null
        cleanup()
        runAll()
        assertEquals(listOf(key("Alpha"), "gone/A"), state.config.favorites)
        assertTrue(written.isEmpty())
        assertEquals("done:${CleanupOutcome.UndoFailed}", cleanupLog.last())
    }

    @Test fun `an incomplete re-check removes nothing and writes no undo point`() {
        startedWithOrphan()
        recheckPlan = CleanupPlan.Incomplete
        cleanup()
        runAll()
        assertEquals(listOf(key("Alpha"), "gone/A"), state.config.favorites)
        assertTrue(cleanupLog.none { it.startsWith("undo:") })
        assertEquals("done:${CleanupOutcome.Incomplete}", cleanupLog.last())
    }

    @Test fun `only what was confirmed AND is still an orphan is removed`() {
        disk = VersoConfig(favorites = listOf(key("Alpha"), "gone/A", "gone/B"))
        started()
        // gone/B was not in the dialog; gone/A came back since (no longer an orphan).
        recheckPlan = CleanupPlan.Offer(Orphans(setOf("gone/B"), 1, 0, 0, 0))
        cleanup(confirmed = setOf("gone/A"))
        runAll()
        assertEquals(listOf(key("Alpha"), "gone/A", "gone/B"), state.config.favorites)
        assertEquals("done:${CleanupOutcome.NothingToDo}", cleanupLog.last())
    }

    @Test fun `a config replaced between the check and the removal is left alone`() {
        startedWithOrphan()
        cleanup()
        runIo()                                            // check + undo point done
        val restored = VersoConfig(favorites = listOf(key("Bravo"), "gone/A"), names = linkedMapOf("gone/A" to "Old"))
        mainQueue.addFirst(Runnable { assertTrue(state.adopt(restored)) })  // a restore lands first
        runAll()
        assertEquals(listOf(key("Bravo"), "gone/A"), state.config.favorites)
        assertEquals("Old", state.config.names["gone/A"])
        assertEquals("done:${CleanupOutcome.Superseded}", cleanupLog.last())
    }

    @Test fun `an edit between the check and the removal is kept, and the cleanup still runs`() {
        startedWithOrphan()
        cleanup()
        runIo()
        mainQueue.addFirst(Runnable { state.toggleFavorite(permit, key("Bravo")) })
        runAll()
        assertEquals(listOf(key("Alpha"), key("Bravo")), state.config.favorites)
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
        assertEquals(listOf(key("Alpha"), "gone/A"), state.config.favorites)
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

    /** Home can show cached favorites before the load lands; a long-press there gets no permit. */
    @Test fun `no edit permit until the first load has landed, and it lands as read`() {
        disk = VersoConfig(names = mapOf(key("Bravo") to "Mine"))
        state.refresh()
        runIo()
        assertNull(state.editPermit(gate.idle()!!))
        runAll()
        assertTrue(state.configLoaded)
        assertTrue(state.appsLoaded)
        assertEquals("Mine", state.config.names[key("Bravo")])
        assertNull(state.config.names[key("Alpha")])
        assertEquals(emptyList<String>(), written)
    }

    @Test fun `a permit from before a command is void even once it ended, a fresh one edits`() {
        started()
        val before = permit
        gate.start { }
        state.toggleFavorite(before, key("Alpha"))
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

    @Test fun `save before the first load writes nothing`() {
        // The empty default in memory must never be written over the real file.
        disk = VersoConfig(favorites = listOf(key("Alpha")))
        state.refresh()  // the load is queued, not yet run
        state.save()
        runAll()
        assertEquals(emptyList<String>(), written)
        assertEquals(listOf(key("Alpha")), state.config.favorites)
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

    // ---- toggles ----------------------------------------------------------------

    /** Every edit, not only some: each one checks the permit itself (audit 2026-10-22, #10). */
    @Test fun `an edit with a permit from before a command started is ignored`() {
        // Two favorites, so a move would succeed if it ignored the permit.
        disk = VersoConfig(favorites = listOf(key("Alpha"), key("Bravo")))
        started()
        val stale = permit
        gate.start {
            state.toggleFavorite(stale, key("Charlie"))
            state.toggleHidden(stale, key("Alpha"))
            state.toggleTag(stale, key("Alpha"), "work")
            state.rename(stale, key("Alpha"), "Alpha", "Mine", emptyList())
            assertFalse(state.moveFavorite(stale, key("Bravo"), key("Alpha")))
            assertNull(state.toggleOrder(stale))
        }
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

    /** The restore wrote verso.json itself (RestoreSteps); adopt must not write it again. */
    @Test fun `adopt replaces the config and re-renders at once, without writing, reading or enumerating`() {
        started()
        listChanges.clear()
        val before = enumerations
        val restored = VersoConfig(favorites = listOf(key("Bravo")))
        assertTrue(state.adopt(restored))
        assertEquals(listOf(key("Bravo")), state.config.favorites)
        assertEquals("config replaced", listOf(true), refreshed)
        assertEquals("re-render", listOf(true), listChanges)
        runAll()
        assertEquals(emptyList<String>(), written)
        assertEquals(1, loadConfigCalls)
        assertEquals(before, enumerations)
    }

    @Test fun `adopt reports only once the restored config and its names are in place`() {
        started()
        val seen = mutableListOf<Pair<Set<String>, List<String>>>()
        onReport = { seen += state.config.favorites.toSet() to labels() }
        val restored = VersoConfig(favorites = listOf(key("Bravo")), names = linkedMapOf(key("Alpha") to "Zulu"))
        assertTrue(state.adopt(restored))
        assertEquals(listOf(setOf(key("Bravo")) to listOf("Bravo", "Charlie", "Zulu")), seen)
    }

    @Test fun `a refresh hand-off landing after adopt keeps the restored config and the new apps`() {
        started()
        installed = installed + app("Delta")  // installed while the restore runs
        state.refresh()
        runIo()                               // its hand-off is queued
        assertTrue(state.adopt(VersoConfig(favorites = listOf(key("Bravo")))))
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
        // Ended by the seeding: a "~cleanup" that later removes every default must not bring them back.
        assertFalse(process.seedPending.get())
        assertFalse(state.needsRefresh)
    }

    /** A restore's write could be overwritten by the seeding's save (audit 2026-10-22, #1). */
    @Test fun `while a command runs nothing is seeded, and a later refresh seeds`() {
        freshStartWithoutAList()
        var running: CommandGate.Ticket? = null
        gate.start { t -> gate.hold(t); running = t }
        state.refresh()
        runAll()
        assertTrue(state.config.favorites.isEmpty())
        assertEquals(emptyList<String>(), written)
        assertTrue("the wait stays", process.seedPending.get())
        assertTrue("a resume tries again", state.needsRefresh)
        gate.finish(checkNotNull(running)) {}
        state.refresh()
        runAll()
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites)
        // The same list as last time, but home must show the seeds (audit 2026-10-22 (2), #1).
        assertEquals("re-rendered", true, listChanges.last())
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

    /** Audit 2026-10-22, #2: a recreation drops the hand-back, so adopt never ends the wait. */
    @Test fun `a restore written ends the wait, even when its hand-back is lost`() {
        freshStartWithoutAList()
        assertTrue(state.writeRestored("restored"))
        assertFalse(process.seedPending.get())
        disk = VersoConfig(hidden = linkedSetOf(key("Bravo")))  // the restored file: no favorites
        val next = testLauncherState(
            io = { task -> ioQueue.addLast(task); true },
            main = { task -> mainQueue.addLast(task) },
            loadConfig = { disk },
            enumerateApps = { Enumeration(installed, true) },
            serialize = { "" },
            writeConfig = { true },
            onRefreshed = { _, _ -> },
            defaultFavoriteCandidates = { candidates },
            process = process,
        )
        next.refresh()
        runAll()
        assertTrue(next.config.favorites.isEmpty())
    }

    @Test fun `a restore that failed to write keeps the wait`() {
        freshStartWithoutAList()
        writeSucceeds = false
        assertFalse(state.writeRestored("restored"))
        assertTrue(process.seedPending.get())
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
        assertTrue(state.adopt(VersoConfig(hidden = linkedSetOf(key("Bravo")))))
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
