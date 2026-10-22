package com.github.reygnn.verso_launcher

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [LauncherState] whose collaborators the test does not name are inert: no file, no profile,
 * no clock that moves. The production constructor has no defaults on purpose (a wire
 * MainActivity forgot must not compile into a no-op), so the defaults live here, in test code.
 * The seven without one are the ones every test of the class has to decide on.
 */
@Suppress("LongParameterList")  // mirrors LauncherState's constructor, one parameter each
internal fun testLauncherState(
    io: (Runnable) -> Boolean,
    main: (Runnable) -> Unit,
    loadConfig: () -> VersoConfig?,
    setAsideUnreadable: () -> Boolean = { false },
    enumerateApps: () -> Enumeration?,
    serialize: (VersoConfig) -> String,
    writeConfig: (String) -> Boolean,
    onSaveFailed: () -> Unit = {},
    onRefreshed: (configReplaced: Boolean, listChanged: Boolean) -> Unit,
    isFreshInstall: () -> Boolean = { false },
    startedOverFlag: AtomicBoolean = AtomicBoolean(),
    defaultFavoriteCandidates: () -> List<List<String>> = { emptyList() },
    seedPendingFlag: AtomicBoolean = AtomicBoolean(),
    initialRecents: List<String> = emptyList(),
    initialRecentsLoaded: Boolean = false,
    onRecentsChanged: (List<String>, Boolean) -> Unit = { _, _ -> },
    loadRecents: () -> List<String>? = { emptyList() },
    writeRecents: (List<String>) -> Unit = {},
    loadUsage: () -> Map<String, Usage>? = { emptyMap() },
    writeUsage: (Map<String, Usage>, Long) -> Unit = { _, _ -> },
    now: () -> Long = System::currentTimeMillis,
    initialUsage: Map<String, Usage> = emptyMap(),
    initialUsageLoaded: Boolean = false,
    onUsageChanged: (Map<String, Usage>, Boolean) -> Unit = { _, _ -> },
) = LauncherState(
    io = io,
    main = main,
    loadConfig = loadConfig,
    setAsideUnreadable = setAsideUnreadable,
    enumerateApps = enumerateApps,
    serialize = serialize,
    writeConfig = writeConfig,
    onSaveFailed = onSaveFailed,
    onRefreshed = onRefreshed,
    isFreshInstall = isFreshInstall,
    startedOverFlag = startedOverFlag,
    defaultFavoriteCandidates = defaultFavoriteCandidates,
    seedPendingFlag = seedPendingFlag,
    initialRecents = initialRecents,
    initialRecentsLoaded = initialRecentsLoaded,
    onRecentsChanged = onRecentsChanged,
    loadRecents = loadRecents,
    writeRecents = writeRecents,
    loadUsage = loadUsage,
    writeUsage = writeUsage,
    now = now,
    initialUsage = initialUsage,
    initialUsageLoaded = initialUsageLoaded,
    onUsageChanged = onUsageChanged,
)
