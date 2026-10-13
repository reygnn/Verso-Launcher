package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- harness -------------------------------------------------------------

    private val ioQueue = ArrayDeque<Runnable>()
    private val mainQueue = ArrayDeque<Runnable>()
    private var ioOpen = true

    /** What loadConfig() returns — "the file on disk". NOT updated by writeConfig (which only
     *  records payloads in [written]): a test that saves and then reloads must set it itself,
     *  or it reads a file a real device would no longer have. */
    private var disk = VersoConfig()
    private var loadConfigCalls = 0

    /** The installed apps (system labels); enumerate applies custom names from the snapshot. */
    private var installed = listOf(app("Alpha"), app("Bravo"), app("Charlie"))
    private var enumerationFails = false
    private val enumeratedWith = mutableListOf<VersoConfig>()

    /** Every payload handed to writeConfig, in order. */
    private val written = mutableListOf<String>()
    private var writeSucceeds = true

    private val refreshed = mutableListOf<Boolean>()

    private val state = LauncherState(
        io = { task -> if (ioOpen) { ioQueue.addLast(task); true } else false },
        main = { task -> mainQueue.addLast(task) },
        // A copy, like a real read: the file and the live config must never be one object,
        // or a test could pass only because a mutation also "changed the disk".
        loadConfig = { loadConfigCalls++; disk.snapshot() },
        enumerateApps = { cfg ->
            enumeratedWith += cfg
            if (enumerationFails) null
            else installed.map { a ->
                val name = cfg.names[a.key] ?: a.systemLabel
                AppEntry.of(name, a.packageName, a.className, a.systemLabel, a.profile)
            }.let { LauncherLogic.sortedForDrawer(it) }
        },
        // A readable stand-in for ConfigJson (org.json is not needed to test the rules).
        serialize = { c ->
            "fav=${c.favorites.joinToString(",")};hid=${c.hidden.joinToString(",")};" +
                "names=${c.names.entries.joinToString(",") { "${it.key}=${it.value}" }};" +
                "tags=${c.tags.entries.joinToString(",") { "${it.key}=${it.value.joinToString("+")}" }}"
        },
        writeConfig = { payload -> written += payload; writeSucceeds },
        onRefreshed = { replaced -> refreshed += replaced },
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

    @Test fun `a warm refresh enumerates from a snapshot and does not re-read the file`() {
        started()
        state.refresh()
        runAll()
        assertEquals(1, loadConfigCalls)
        assertEquals(listOf(false), refreshed)
        // The loader got a COPY, never the live config object.
        assertTrue(enumeratedWith.last() !== state.config)
    }

    @Test fun `reload forces a fresh read of the file`() {
        started()
        disk = VersoConfig(hidden = linkedSetOf(key("Bravo")))
        state.refresh(reloadConfig = true)
        runAll()
        assertEquals(2, loadConfigCalls)
        assertEquals(setOf(key("Bravo")), state.config.hidden)
    }

    @Test fun `a reload superseded by a resume refresh still reads the file`() {
        started()
        disk = VersoConfig(hidden = linkedSetOf(key("Bravo")))
        state.refresh(reloadConfig = true)
        state.refresh()  // onResume before the reload's IO ran: supersedes it
        runAll()
        assertEquals(2, loadConfigCalls)
        assertEquals(setOf(key("Bravo")), state.config.hidden)
    }

    @Test fun `a reload superseded at its hand-off still reads the file`() {
        started()
        disk = VersoConfig(hidden = linkedSetOf(key("Bravo")))
        state.refresh(reloadConfig = true)
        runIo()            // the reload read the file…
        state.refresh()    // …but a resume superseded it before the hand-off
        runAll()
        assertEquals(setOf(key("Bravo")), state.config.hidden)
    }

    @Test fun `a completed reload is not repeated by later refreshes`() {
        started()
        state.refresh(reloadConfig = true)
        runAll()
        state.refresh()
        runAll()
        assertEquals(2, loadConfigCalls)
    }

    @Test fun `a restore cancels a pending reload instead of re-reading the file`() {
        started()
        state.refresh(reloadConfig = true)
        state.adopt(VersoConfig(favorites = linkedSetOf(key("Bravo"))))
        runAll()
        assertEquals(1, loadConfigCalls)
        assertEquals(setOf(key("Bravo")), state.config.favorites)
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
        val before = enumeratedWith.size
        state.refresh()
        state.refresh()
        runAll()
        assertEquals("only the newest load enumerates", before + 1, enumeratedWith.size)
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

    @Test fun `a toggle during a reload keeps the live config, not the older disk copy`() {
        started()
        disk = VersoConfig()                  // the file does not have the favorite yet
        state.refresh(reloadConfig = true)
        runIo()                               // disk read + enumeration done
        state.toggleFavorite(key("Alpha"))    // user taps before the hand-off
        runAll()

        assertEquals(setOf(key("Alpha")), state.config.favorites)
        assertEquals(listOf(false), refreshed)  // not replaced
    }

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

    @Test fun `a rename during a load drops that load's stale labels`() {
        started()
        state.refresh()
        runIo()                                            // enumerated with the OLD names
        state.rename(key("Alpha"), "Alpha", "Zulu", emptyList())
        runAll()
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
    }

    @Test fun `rename relabels and re-sorts in memory without re-enumerating`() {
        started()
        val before = enumeratedWith.size
        state.rename(key("Alpha"), "Alpha", "Zulu", listOf("work"))
        assertEquals(listOf("Bravo", "Charlie", "Zulu"), labels())
        assertEquals("Zulu", state.config.names[key("Alpha")])
        assertEquals(listOf("work"), state.config.tags[key("Alpha")])
        assertEquals(before, enumeratedWith.size)
    }

    @Test fun `a rename re-sorts in the device language's alphabet`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            started()
            state.rename(key("Charlie"), "Charlie", "Ärzte", emptyList())
            assertEquals(listOf("Alpha", "Ärzte", "Bravo"), labels())
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test fun `a rename re-sorts by the device language, not a fixed alphabet`() {
        // Swedish files Ä after Z — a test with German alone could not tell that apart from a
        // locale-blind sort.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.of("sv", "SE"))
            started()
            state.rename(key("Alpha"), "Alpha", "Ärzte", emptyList())
            assertEquals(listOf("Bravo", "Charlie", "Ärzte"), labels())
        } finally {
            java.util.Locale.setDefault(original)
        }
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

    @Test fun `a load dropped by a rename leaves the launcher needing a refresh`() {
        started()
        installed = installed + app("Delta")
        state.refresh()
        runIo()
        state.rename(key("Alpha"), "Alpha", "Zulu", emptyList())
        runAll()
        assertFalse("Delta" in labels())   // the stale list was dropped, Delta with it
        assertTrue(state.needsRefresh)
        state.refresh()
        runAll()
        assertTrue("Delta" in labels())
        assertFalse(state.needsRefresh)
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

    @Test fun `an explicit save is written even when a newer save follows`() {
        started()
        var result: Boolean? = null
        state.save { result = it }
        state.toggleFavorite(key("Alpha"))
        runAll()
        assertEquals(true, result)
        assertEquals(2, written.size)
    }

    @Test fun `save reports the real write result on the main thread`() {
        started()
        writeSucceeds = false
        var result: Boolean? = null
        state.save { result = it }
        assertNull("nothing before the write ran", result)
        runIo()
        assertNull("delivered via the main thread", result)
        runMain()
        assertEquals(false, result)
    }

    @Test fun `save before the first load writes nothing and reports failure`() {
        // The empty default in memory must never be written over the real file: it would
        // rotate the real config into .bak and publish an empty primary.
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        state.refresh()  // the load is queued, not yet run
        var result: Boolean? = null
        state.save { result = it }
        assertEquals(false, result)
        runAll()
        assertEquals(emptyList<String>(), written)
        assertEquals(setOf(key("Alpha")), state.config.favorites)
    }

    @Test fun `save refused by a shut-down IO thread reports failure at once`() {
        started()
        ioOpen = false
        var result: Boolean? = null
        state.save { result = it }
        assertEquals(false, result)
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
            serialize = { "" }, writeConfig = { true }, onRefreshed = {},
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

    @Test fun `a reload in flight cannot clobber a restore`() {
        started()
        disk = VersoConfig(favorites = linkedSetOf(key("Alpha")))
        state.refresh(reloadConfig = true)
        runIo()                                     // read the OLD file
        state.adopt(VersoConfig(favorites = linkedSetOf(key("Charlie"))))
        runAll()
        assertEquals(setOf(key("Charlie")), state.config.favorites)
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
