package com.github.reygnn.verso_launcher

/**
 * The launcher's data — the config, the enumerated apps, the recents — and the rules that keep it
 * consistent while a background load and the user's taps overlap. Plays the ViewModel role, in
 * plain Kotlin.
 *
 * The config is what the user's file says (hard rule 4) resolved against the apps: a label or a
 * package names an app only once a list that has it is in. So [config] is derived — anew whenever
 * the list changes ([resolve]) — and never written anywhere. The text itself is fixed for the
 * state's life: a changed file comes with the next state ("~restart", Core.reset).
 *
 * Threading: every public member is main-thread only; disk and LauncherApps work runs on [io],
 * results come back through [main]. The IO side reads only the @Volatile counter:
 *   loadGeneration — bumped by [refresh]: the newest requested enumeration wins, older ones bail.
 * An enumeration carries no config (system labels only); the config is resolved and the custom
 * names applied on main.
 *
 * Every collaborator is passed, none defaulted: a wire MainActivity forgot would otherwise be
 * a silent no-op. Tests build it through testLauncherState, which supplies inert ones.
 *
 * @param text the config as Core read it before the state exists (hard rule 5): the user's
 *   file, or nothing.
 * @param useDefaults no config at all, not even an empty file: the favorites are the default ones
 *   (hard rule 10), picked from every list anew and never written.
 * @param fileStatus how the read of [text] went, for [report].
 * @param byUsage "%" as order.txt has it.
 * @param enumerateApps null if the enumeration failed — the last good list is kept; an
 *   incomplete one is shown but retried on the next resume ([needsRefresh]).
 * @param writeOrder "%" changed: order.txt, on [io].
 * @param process what outlives this instance.
 * @param onRefreshed listChanged false: nothing to re-render (a re-render would void a tap in flight).
 */
internal class LauncherState(
    private val io: (Runnable) -> Unit,
    private val main: (Runnable) -> Unit,
    private val text: ConfigText,
    private val useDefaults: Boolean,
    private val fileStatus: ConfigStatus,
    byUsage: Boolean,
    private val enumerateApps: () -> Enumeration?,
    private val defaultFavoriteCandidates: () -> List<List<String>>,
    private val recentsFile: RecentsFile,
    private val usageFile: UsageFile,
    private val writeOrder: (Boolean) -> Unit,
    private val process: ProcessState,
    private val now: () -> Long,
    private val onRefreshed: (listChanged: Boolean) -> Unit,
) {
    private var byUsage = byUsage

    /** The default favorites the last list offered, while [useDefaults]. */
    private var defaults: List<String> = emptyList()

    private var enumerated: List<AppEntry> = emptyList()

    /** Immutable: a new one whenever the list or the text changes. */
    var config: VersoConfig = resolved()
        private set

    /** [enumerated] under the custom names, in drawer order. */
    var apps: List<AppEntry> = emptyList()
        private set

    /** Until then an empty [apps] means "not loaded yet": no no-favorites hint flash. */
    var appsLoaded = false
        private set

    /** Refresh on resume even without a package change: no package event retries a failed or incomplete load. */
    val needsRefresh: Boolean
        get() = !lastLoadCommitted

    private var lastLoadCommitted = false

    /** The "?" list, newest first. recents.txt is written only once it was read, like usage.txt. */
    val recentKeys: List<String> get() = process.recents.value

    /** The launches behind the "%" order. Written only once the file was read (or it was lost). */
    val usage: Map<String, Usage> get() = process.usage.value

    @Volatile private var loadGeneration = 0

    /** What "~verify" says about the user's file, against the last list ([ConfigReport.findings]). */
    fun report(): List<ConfigFinding> =
        ConfigReport.findings(fileStatus, text, enumerated.takeIf { appsLoaded }, complete = lastLoadCommitted)

    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, config.favorites)

    /** Re-enumerate the apps off the main thread (and, until they are read, recents.txt and usage.txt). */
    fun refresh() {
        val generation = ++loadGeneration
        val loadUsageNow = !process.usage.loaded
        val loadRecentsNow = !process.recents.loaded
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            val load = load(loadUsageNow, loadRecentsNow)
            main(Runnable {
                if (generation != loadGeneration) return@Runnable  // superseded meanwhile: the newer one brings the list
                commit(load)
            })
        })
    }

    /**
     * [refresh]'s IO half: the files not read yet and the enumeration, nothing of this state touched.
     *
     * Thread: [io].
     */
    private fun load(usageNow: Boolean, recentsNow: Boolean): Load {
        val usage = if (usageNow) usageFile.read() else null
        val recents = if (recentsNow) recentsFile.read() else null
        val enumeration = enumerateApps()
        // From any enumeration with a list, complete or not: one that never completes (a profile
        // that stays unreadable) must not starve the defaults.
        val defaults = if (useDefaults && enumeration != null) {
            LauncherLogic.pickDefaultFavorites(enumeration.apps, defaultFavoriteCandidates())
        } else null
        return Load(usage, recents, enumeration, defaults)
    }

    /**
     * [refresh]'s main half, for the newest [Load] only: the files merged in, then the list and the
     * defaults, and one [onRefreshed] for all of it.
     *
     * Thread: main.
     */
    private fun commit(load: Load) {
        val recentsChanged = mergeFiles(load)
        lastLoadCommitted = load.enumeration?.complete == true
        val defaultsChanged = load.defaults != null && load.defaults != defaults
        if (load.defaults != null) defaults = load.defaults
        val loaded = load.enumeration?.apps
        // Most refreshes bring the same list; AppEntry ==, status included, sees any change.
        val listChanged = loaded != null && (!appsLoaded || loaded != enumerated)
        if (loaded != null) {
            enumerated = loaded
            appsLoaded = true
        }
        if (listChanged || defaultsChanged) resolve()
        onRefreshed(listChanged || defaultsChanged || recentsChanged)
    }

    /**
     * usage.txt and recents.txt as [load] read them, each merged with this process's launches the
     * first time only, and written back when there were any. True if that changed the "?" list.
     *
     * Invariant: a file is merged once per process ([Kept.loaded]); a later read of it is dropped,
     *   since what the process holds already has the file in it.
     */
    private fun mergeFiles(load: Load): Boolean {
        val kept = process.usage
        if (load.usage != null && !kept.loaded) {
            val counted = kept.value
            kept.value = UsageLog.merged(load.usage, counted, now())
            kept.loaded = true
            if (counted.isNotEmpty()) persistUsage()
        }
        val recents = process.recents
        if (load.recents == null || recents.loaded) return false
        val launched = recents.value
        recents.value = RecentsLog.merged(load.recents, launched, RECENTS_KEPT)
        recents.loaded = true
        if (launched.isNotEmpty()) persistRecents()
        return recents.value != launched
    }

    /**
     * What one refresh read on [io], handed to main as it is.
     *
     * @property usage usage.txt, null if it was not asked for or is unreadable.
     * @property recents recents.txt, the same.
     * @property enumeration null if the enumeration failed: the last good list stays.
     * @property defaults the default favorites this list offers, null unless [useDefaults].
     */
    private class Load(
        val usage: Map<String, Usage>?,
        val recents: List<String>?,
        val enumeration: Enumeration?,
        val defaults: List<String>?,
    )

    fun recordLaunch(key: String) {
        process.recents.value = LauncherLogic.pushRecent(recentKeys, key, RECENTS_KEPT)
        if (process.recents.loaded) persistRecents()
        process.usage.value = UsageLog.recorded(usage, key, now())
        if (process.usage.loaded) persistUsage()
    }

    private fun persistRecents() {
        val keys = recentKeys
        io(Runnable { recentsFile.write(keys) })
    }

    private fun persistUsage() {
        val log = usage
        val at = now()
        io(Runnable { usageFile.write(log, at) })
    }

    /** "%": the plain drawer by usage, or back to A to Z; kept in order.txt, not in the user's file. */
    fun toggleOrder(): Boolean {
        byUsage = !byUsage
        config = config.copy(byUsage = byUsage)
        val ordered = byUsage
        io(Runnable { writeOrder(ordered) })
        return ordered
    }

    private fun resolved(): VersoConfig =
        if (useDefaults) VersoConfig(favorites = defaults, byUsage = byUsage)
        else ConfigText.resolve(text, enumerated).copy(byUsage = byUsage)

    /** The config anew from the list, and the apps under its names. */
    private fun resolve() {
        config = resolved()
        apps = LauncherLogic.named(enumerated, config.names)
    }

    companion object {
        /** How many "?" shows. */
        const val RECENTS_LIMIT = 8

        /** How many recents.txt keeps: an app that is gone takes no place among the shown. */
        const val RECENTS_KEPT = 4 * RECENTS_LIMIT
    }
}
