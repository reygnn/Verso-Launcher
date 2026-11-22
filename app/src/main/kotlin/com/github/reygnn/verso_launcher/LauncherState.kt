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
 * The launches (the "?" list, the counts behind "%") are the process's, not this state's
 * ([LaunchHistory]): a refresh only carries their files along, so one hand-back brings both.
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
 * @param history what outlives this instance: the launches, and their files until they are read.
 * @param writeOrder "%" changed: order.txt, on [io].
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
    private val history: LaunchHistory,
    private val writeOrder: (Boolean) -> Unit,
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

    /** The "?" list, newest first. */
    val recentKeys: List<String> get() = history.recents

    /** The launches behind the "%" order. */
    val usage: Map<String, Usage> get() = history.usage

    @Volatile private var loadGeneration = 0

    /** What "~verify" says about the user's file, against the last list ([ConfigReport.findings]). */
    fun report(): List<ConfigFinding> =
        ConfigReport.findings(fileStatus, text, enumerated.takeIf { appsLoaded }, complete = lastLoadCommitted)

    fun favorites(): List<AppEntry> = LauncherLogic.favoritesInDisplayOrder(apps, config.favorites)

    /** Re-enumerate the apps off the main thread (and, until they are read, recents.txt and usage.txt). */
    fun refresh() {
        val generation = ++loadGeneration
        val unread = history.unread()
        io(Runnable {
            if (generation != loadGeneration) return@Runnable  // superseded while queued
            val load = load(unread)
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
    private fun load(unread: LaunchHistory.Unread): Load {
        val files = history.read(unread)
        val enumeration = enumerateApps()
        // From any enumeration with a list, complete or not: one that never completes (a profile
        // that stays unreadable) must not starve the defaults.
        val defaults = if (useDefaults && enumeration != null) {
            LauncherLogic.pickDefaultFavorites(enumeration.apps, defaultFavoriteCandidates())
        } else null
        return Load(files, enumeration, defaults)
    }

    /**
     * [refresh]'s main half, for the newest [Load] only: the files merged in, then the list and the
     * defaults, and one [onRefreshed] for all of it.
     *
     * Thread: main.
     */
    private fun commit(load: Load) {
        val recentsChanged = history.merge(load.files)
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
     * What one refresh read on [io], handed to main as it is.
     *
     * @property files recents.txt and usage.txt, as far as they were still unread.
     * @property enumeration null if the enumeration failed: the last good list stays.
     * @property defaults the default favorites this list offers, null unless [useDefaults].
     */
    private class Load(
        val files: LaunchHistory.Read,
        val enumeration: Enumeration?,
        val defaults: List<String>?,
    )

    /** A launch that started: it counts for "?" and "%". */
    fun recordLaunch(key: String) = history.record(key)

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
}
