package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [LauncherState]: the data layer and its concurrency rules.
 *
 * The IO thread and the main thread are two manual FIFO queues, so a test decides exactly
 * when the background part of a load runs and when its result is handed back — and can put a
 * tap in between. Plain JUnit, no coroutines, no Android: LauncherState takes its threads as
 * functions precisely so these interleavings are deterministic.
 */
class LauncherStateTest {

    @get:Rule val locale = DefaultLocaleRule()

    // ---- harness -------------------------------------------------------------

    private val ioQueue = ArrayDeque<Runnable>()
    private val mainQueue = ArrayDeque<Runnable>()
    private var ioOpen = true

    /** What loadConfig() returns — "the file on disk". NOT updated by writeConfig (which only
     *  records payloads in [written]): a test that saves and then reloads must set it itself,
     *  or it reads a file a real device would no longer have. */
    private var disk = VersoConfig()
    /** The file is there but cannot be read (ConfigStore.load null). */
    private var diskUnreadable = false
    private var setAsideWorks = true
    private var setAsideCalls = 0
    private var loadConfigCalls = 0

    /** The installed apps under their system labels — what an enumeration reports, as is: no
     *  custom names, and NOT sorted (reversed below), so the tests see LauncherState apply both. */
    private var installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))
    private var enumerationFails = false
    private var enumerations = 0

    /** Every payload handed to writeConfig, in order. */
    private val written = mutableListOf<String>()
    private var writeSucceeds = true

    /** Runs the next IO task, expecting it to throw (the IO thread's guard would log it). */
    private fun runIoThrowing() {
        try {
            ioQueue.removeFirst().run()
            throw AssertionError("expected the IO task to throw")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    private val refreshed = mutableListOf<Boolean>()
    private val listChanges = mutableListOf<Boolean>()

    private val state = LauncherState(
        io = { task -> if (ioOpen) { ioQueue.addLast(task); true } else false },
        main = { task -> mainQueue.addLast(task) },
        // A copy, like a real read: the file and the live config must never be one object,
        // or a test could pass only because a mutation also "changed the disk".
        loadConfig = { loadConfigCalls++; if (diskUnreadable) null else disk.snapshot() },
        setAsideUnreadable = {
            setAsideCalls++
            if (setAsideWorks) { diskUnreadable = false; disk = VersoConfig() }
            setAsideWorks
        },
        enumerateApps = {
            enumerations++
            if (enumerationFails) null else installed.reversed()
        },
        // A readable stand-in for ConfigJson (org.json is not needed to test the rules).
        serialize = { c ->
            "fav=${c.favorites.joinToString(",")};hid=${c.hidden.joinToString(",")};" +
                "names=${c.names.entries.joinToString(",") { "${it.key}=${it.value}" }};" +
                "tags=${c.tags.entries.joinToString(",") { "${it.key}=${it.value.joinToString("+")}" }}"
        },
        writeConfig = { payload -> written += payload; writeSucceeds },
        onRefreshed = { replaced, changed -> refreshed += replaced; listChanges += changed },
        isFreshInstall = { fresh },
        defaultFavoriteCandidates = { candidatesCalls++; candidates },
    )

    /** What isFreshInstall() answers — "no verso.json on disk yet". */
    private var fresh = false
    /** The default-favorite slots the Activity would resolve. */
    private var candidates = listOf(listOf(key("Charlie")), listOf("gone/Gone", key("Alpha")))
    private var candidatesCalls = 0

    private fun app(label: String) =
        AppEntry.of(label, "com.$label".lowercase(), "com.${label.lowercase()}.Main", label)

    private fun key(label: String) = app(label).key

    private fun runIo() { while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run() }
    private fun runMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run() }
    private fun runAll() { while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) { runIo(); runMain() } }

    private fun labels() = state.apps.map { it.label }

    /** Cold start finished: config read from [disk], apps enumerated, queues drained. */
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

    /** A refresh that brings the same list asks for no re-render (it would void a tap in
     *  flight); a new app, a changed status or a new config do. */
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
        state.toggleFavorite(key("Alpha"))
        state.toggleHidden(key("Bravo"))
        state.toggleTag(key("Alpha"), "work")
        state.rename(key("Alpha"), "Alpha", "Mine", emptyList())
        assertFalse(state.moveFavorite(key("Alpha"), key("Bravo")))
        state.save()
        runAll()
        assertTrue(written.isEmpty())
        assertFalse(state.takeStartedOver())
        setAsideWorks = true
        state.refresh()  // a resume: needsRefresh
        runAll()
        assertTrue(state.configLoaded)
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
        state.toggleFavorite(key("Bravo"))
        assertEquals(setOf(key("Alpha")), snap.favorites)
    }

    @Test fun `configLoaded is false until the disk copy or a restore is adopted`() {
        assertFalse(state.configLoaded)
        state.refresh()
        runIo()
        assertFalse(state.configLoaded)
        runMain()
        assertTrue(state.configLoaded)
    }

    @Test fun `a restore marks the config loaded even before the first refresh`() {
        state.adopt(VersoConfig())
        assertTrue(state.configLoaded)
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
        state.toggleHidden(key("Alpha"))
        runAll()
        assertTrue("Delta" in labels())
    }

    // ---- rename vs. an in-flight load -------------------------------------------------

    @Test fun `a rename during a load keeps its name, and the load's new apps still land`() {
        // A list carries no names: it lands, and the names are applied at its hand-off.
        started()
        installed = installed + app("Delta")
        state.refresh()
        runIo()                                            // enumerated before the rename
        state.rename(key("Alpha"), "Alpha", "Zulu", emptyList())
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Delta", "Zulu"), labels())
        assertFalse(state.needsRefresh)
    }

    @Test fun `rename relabels and re-sorts in memory without re-enumerating`() {
        started()
        val before = enumerations
        state.rename(key("Alpha"), "Alpha", "Zulu", listOf("work"))
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("Zulu", state.config.names[key("Alpha")])
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        assertEquals(before, enumerations)
    }

    @Test fun `a rename re-sorts in the device language's alphabet`() {
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        started()
        state.rename(key("Charlie"), "Charlie", "Ärzte", emptyList())
        assertEquals(listOf("Alpha", "Ärzte", "Bravo"), labels())
    }

    @Test fun `a rename re-sorts by the device language, not a fixed alphabet`() {
        // Swedish files Ä after Z — a test with German alone could not tell that apart from a
        // locale-blind sort.
        java.util.Locale.setDefault(java.util.Locale.of("sv", "SE"))
        started()
        state.rename(key("Alpha"), "Alpha", "Ärzte", emptyList())
        assertEquals(listOf("Bravo", "Charlie", "Ärzte"), labels())
    }

    @Test fun `forget removes every entry of the keys in one save`() {
        disk = VersoConfig(
            hidden = linkedSetOf("gone/A"),
            favorites = linkedSetOf(key("Alpha"), "gone/A"),
            names = linkedMapOf("gone/A" to "Old"),
            tags = linkedMapOf("gone/A" to mutableListOf("x"), key("Alpha") to mutableListOf("y")),
        )
        started()
        state.forget(setOf("gone/A"))
        runAll()
        assertEquals(setOf(key("Alpha")), state.config.favorites)
        assertTrue(state.config.hidden.isEmpty())
        assertTrue(state.config.names.isEmpty())
        assertEquals(setOf(key("Alpha")), state.config.tags.keys)
        assertEquals(1, written.size)
    }

    @Test fun `forget while the first load is in flight is ignored and the load still lands`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha"), "gone/A"))
        state.refresh()
        state.forget(setOf("gone/A"))
        runAll()
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
        assertEquals(setOf(key("Alpha"), "gone/A"), state.config.favorites)  // nothing removed yet
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
        mainQueue.addFirst(Runnable { state.adopt(restored) })  // a restore lands first
        runAll()
        assertEquals(setOf(key("Bravo"), "gone/A"), state.config.favorites)
        assertEquals("Old", state.config.names["gone/A"])
        assertEquals("done:${CleanupOutcome.Superseded}", cleanupLog.last())
    }

    @Test fun `an edit between the check and the removal is kept, and the cleanup still runs`() {
        startedWithOrphan()
        cleanup()
        runIo()
        mainQueue.addFirst(Runnable { state.toggleFavorite(key("Bravo")) })
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

    @Test fun `forget before the config is loaded changes nothing`() {
        state.forget(setOf("gone/A"))
        runAll()
        assertTrue(written.isEmpty())
    }

    @Test fun `a rename stores the name cleaned, whoever commits it`() {
        started()
        state.rename(key("Alpha"), "Alpha", " Zu\u202Elu\u200B ", emptyList())
        assertEquals("Zulu", state.config.names[key("Alpha")])
    }

    @Test fun `renaming back to the system label clears the override`() {
        started()
        state.rename(key("Alpha"), "Alpha", "Zulu", emptyList())
        state.rename(key("Alpha"), "Alpha", "Alpha", emptyList())
        assertNull(state.config.names[key("Alpha")])
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `a rename with no tags removes the tag key`() {
        started()
        state.rename(key("Alpha"), "Alpha", "", listOf("work"))
        state.rename(key("Alpha"), "Alpha", "", emptyList())
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

    @Test fun `clearing a name during a load shows the system label once the load lands`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        started()
        state.refresh()
        runIo()
        state.rename(key("Alpha"), "Alpha", "", emptyList())   // blank: back to the app's own
        runAll()
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `forget of a listed app's name shows its own label again`() {
        disk = VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu"))
        started()
        state.forget(setOf(key("Alpha")))
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), labels())
    }

    @Test fun `a restore shows its names at once, and drops the names it does not have`() {
        disk = VersoConfig(names = linkedMapOf(key("Bravo") to "Mine"))
        started()
        state.adopt(VersoConfig(names = linkedMapOf(key("Alpha") to "Zulu")))
        assertEquals("before any refresh lands", listOf("Bravo", "Charlie", "Zulu"), labels())
    }

    @Test fun `a rename before the config is loaded is ignored and the first load still lands`() {
        disk = VersoConfig().apply { names[key("Bravo")] = "Mine" }
        state.refresh()
        runIo()
        state.rename(key("Alpha"), "Alpha", "Zulu", listOf("work"))  // a cached favorite
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
        state.toggleFavorite(key("Alpha"))
        runAll()
        state.toggleFavorite(key("Bravo"))
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
        state.toggleFavorite(key("Alpha"))
        state.toggleFavorite(key("Bravo"))
        state.toggleFavorite(key("Charlie"))
        runAll()
        // Only the last payload hits the disk — and it carries all three changes.
        assertEquals(listOf("fav=${key("Alpha")},${key("Bravo")},${key("Charlie")};hid=;names=;tags="), written)
    }

    @Test fun `save before the first load writes nothing`() {
        // The empty default in memory must never be written over the real file: it would
        // rotate the real config into .bak and publish an empty primary.
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        state.refresh()  // the load is queued, not yet run
        state.save()
        runAll()
        assertEquals(emptyList<String>(), written)
        assertEquals(setOf(key("Alpha")), state.config.favorites)
    }

    @Test fun `a refused save does not make the save queued ahead of it skip its write`() {
        started()
        state.toggleFavorite(key("Alpha"))   // queued
        ioOpen = false
        state.toggleFavorite(key("Bravo"))   // refused: it will never run
        runAll()
        assertEquals(listOf("fav=${key("Alpha")};hid=;names=;tags="), written)
    }

    // ---- toggles ----------------------------------------------------------------

    @Test fun `toggling twice restores the set, and a new favorite goes last`() {
        started()
        state.toggleFavorite(key("Charlie"))
        state.toggleFavorite(key("Alpha"))
        assertEquals(listOf(key("Charlie"), key("Alpha")), state.config.favorites.toList())
        state.toggleFavorite(key("Charlie"))
        assertEquals(listOf(key("Alpha")), state.config.favorites.toList())
    }

    @Test fun `favorites() follows the stored order and skips uninstalled ones`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Charlie"), "gone/Gone", key("Alpha")))
        started()
        assertEquals(listOf("Charlie", "Alpha"), state.favorites().map { it.label })
    }

    @Test fun `toggleTag adds, removes, and drops an emptied key`() {
        started()
        state.toggleTag(key("Alpha"), "Work")
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        state.toggleTag(key("Alpha"), "work")
        assertFalse(key("Alpha") in state.config.tags)
    }

    @Test fun `moveFavorite reorders and saves, an impossible move does neither`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha"), key("Bravo"), key("Charlie")))
        started()
        assertTrue(state.moveFavorite(key("Alpha"), key("Charlie")))
        assertEquals(listOf(key("Bravo"), key("Charlie"), key("Alpha")), state.config.favorites.toList())
        runAll()
        assertEquals(1, written.size)

        assertFalse(state.moveFavorite(key("Alpha"), "gone/Gone"))
        assertFalse(state.moveFavorite(key("Alpha"), key("Alpha")))
        runAll()
        assertEquals(1, written.size)
    }

    // ---- recents ----------------------------------------------------------------

    @Test fun `recents start from the process-wide list and report every change`() {
        val reported = mutableListOf<List<String>>()
        val s = LauncherState(
            io = { true }, main = {}, loadConfig = { VersoConfig() }, enumerateApps = { emptyList() },
            serialize = { "" }, writeConfig = { true }, onRefreshed = { _, _ -> },
            initialRecents = listOf("a", "b"),
            onRecentsChanged = { reported += it },
        )
        assertEquals(listOf("a", "b"), s.recentKeys)
        s.recordLaunch("c")
        assertEquals(listOf(listOf("c", "a", "b")), reported)
    }

    @Test fun `recents are newest first, de-duplicated and capped`() {
        for (i in 1..10) state.recordLaunch("k$i")
        state.recordLaunch("k5")
        assertEquals(LauncherState.RECENTS_LIMIT, state.recentKeys.size)
        assertEquals(listOf("k5", "k10", "k9", "k8", "k7", "k6", "k4", "k3"), state.recentKeys)
    }

    // ---- restore ------------------------------------------------------------------

    @Test fun `adopt replaces the config, saves it and re-renders without re-reading the file`() {
        started()
        val restored = VersoConfig(favorites = linkedSetOf(key("Bravo")))
        state.adopt(restored)
        assertEquals(setOf(key("Bravo")), state.config.favorites)
        runAll()
        assertEquals(listOf("fav=${key("Bravo")};hid=;names=;tags="), written)
        assertEquals(1, loadConfigCalls)
        assertEquals(listOf(false), refreshed)
    }

    @Test fun `a restore before the cold start finished is not overwritten by it`() {
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        state.refresh()
        runIo()
        state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo"))))
        runAll()
        assertEquals(setOf(key("Bravo")), state.config.favorites)
        assertTrue(state.appsLoaded)
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
        // E.g. a lone .tmp recovered: "fresh" (no primary, no .bak) but the user's own favorites.
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
        fresh = true  // e.g. the file vanished behind our back — memory is the truth now
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

    @Test fun `a restore before the fresh cold start finished is not seeded over`() {
        fresh = true
        state.refresh()
        runIo()
        state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo"))))
        runAll()
        assertEquals(setOf(key("Bravo")), state.config.favorites)
    }

    // ---- AppEntry --------------------------------------------------------------

    @Test fun `a personal app keeps the plain key, another profile's copy gets the serial`() {
        val personal = AppEntry.of("Gmail", "com.google.android.gm", "com.google.android.gm.Main", "Gmail")
        val work = AppEntry.of("Gmail (Work)", "com.google.android.gm", "com.google.android.gm.Main", "Gmail (Work)", profile = 10)
        assertEquals("com.google.android.gm/com.google.android.gm.Main", personal.key)
        assertEquals("com.google.android.gm/com.google.android.gm.Main#10", work.key)
        assertEquals(work.key, work.withLabel("Mail").key)  // a rename keeps the profile
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
        state.toggleFavorite(work.key)
        assertEquals(listOf("Charlie", "Charlie (Work)"), state.favorites().map { it.label })
    }

    @Test fun `the personal key is package slash class, the format old configs were written in`() {
        val e = AppEntry.of("Mail", "com.example.mail", "com.example.mail.MainActivity", "Mail")
        assertEquals("com.example.mail/com.example.mail.MainActivity", e.key)
        assertEquals("mail", e.labelLower)
        assertEquals("inbox", e.withLabel("Inbox").labelLower)
        assertEquals(e.key, e.withLabel("Inbox").key)
    }
}
