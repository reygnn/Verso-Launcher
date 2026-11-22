package com.github.reygnn.verso_launcher

/**
 * A [DrawerData] whose fields the test does not name are empty: nothing hidden, no favorite, no
 * launch, a clock at zero, a list that is loaded. The production class defaults nothing on purpose,
 * so the defaults live here, in test code.
 */
// one parameter per field, flat on purpose
@Suppress("LongParameterList")
internal fun <T : Ordered> drawerData(
    apps: List<T>,
    hidden: Set<String> = emptySet(),
    favorites: Collection<String> = emptyList(),
    tags: Map<String, List<String>> = emptyMap(),
    recentKeys: List<String> = emptyList(),
    byUsage: Boolean = false,
    usage: Map<String, Usage> = emptyMap(),
    now: Long = 0L,
    report: () -> List<ConfigFinding> = { emptyList() },
    loaded: Boolean = true,
): DrawerData<T> = DrawerData(apps, loaded, hidden, favorites, tags, recentKeys, byUsage, usage, now, report)

/** [LauncherLogic.rowsFor] with the data given flat, for the tests that vary one field of it. */
// one parameter per field, flat on purpose
@Suppress("LongParameterList")
internal fun <T : Ordered> rowsFor(
    mode: Mode,
    trimmed: String,
    allApps: List<T>,
    hidden: Set<String>,
    favorites: Collection<String>,
    tags: Map<String, List<String>>,
    recentKeys: List<String>,
    byUsage: Boolean = false,
    usage: Map<String, Usage> = emptyMap(),
    now: Long = 0L,
    report: () -> List<ConfigFinding> = { emptyList() },
): List<Row<T>> =
    LauncherLogic.rowsFor(mode, trimmed, drawerData(allApps, hidden, favorites, tags, recentKeys, byUsage, usage, now, report))
