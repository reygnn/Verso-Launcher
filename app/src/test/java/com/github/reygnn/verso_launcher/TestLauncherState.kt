package com.github.reygnn.verso_launcher

/**
 * A [LauncherState] whose collaborators the test does not name are inert: no file, no profile,
 * no clock that moves. The production constructor has no defaults on purpose (a wire
 * MainActivity forgot must not compile into a no-op), so the defaults live here, in test code.
 * The parameters stay flat — a test names the one function it fakes — and the four file functions
 * are bundled into a [LaunchHistory] of the state's own ([testLaunchHistory]). The four without a
 * default are the ones every test of the class has to decide on. To model a recreation or a
 * "~restart", build one [testLaunchHistory] and pass it as [history] to both instances; the file
 * functions given here then count for nothing. [config] is a config of keys ([textOf]); [text] the
 * user's file.
 */
// one parameter per faked function, flat on purpose
@Suppress("LongParameterList")
internal fun testLauncherState(
    io: (Runnable) -> Unit,
    main: (Runnable) -> Unit,
    enumerateApps: () -> Enumeration?,
    onRefreshed: (listChanged: Boolean) -> Unit,
    config: VersoConfig = VersoConfig(),
    text: ConfigText = textOf(config),
    useDefaults: Boolean = false,
    fileStatus: ConfigStatus = if (useDefaults) ConfigStatus.MISSING else ConfigStatus.READ,
    byUsage: Boolean = config.byUsage,
    defaultFavoriteCandidates: () -> List<List<String>> = { emptyList() },
    loadRecents: () -> List<String>? = { emptyList() },
    writeRecents: (List<String>) -> Unit = {},
    loadUsage: () -> Map<String, Usage>? = { emptyMap() },
    writeUsage: (Map<String, Usage>, Long) -> Unit = { _, _ -> },
    writeOrder: (Boolean) -> Unit = {},
    now: () -> Long = { 0L },
    history: LaunchHistory = testLaunchHistory(io, loadRecents, writeRecents, loadUsage, writeUsage, now),
): LauncherState = LauncherState(
    io = io,
    main = main,
    text = text,
    useDefaults = useDefaults,
    fileStatus = fileStatus,
    byUsage = byUsage,
    enumerateApps = enumerateApps,
    defaultFavoriteCandidates = defaultFavoriteCandidates,
    history = history,
    writeOrder = writeOrder,
    onRefreshed = onRefreshed,
)

/** A [LaunchHistory] on files that are there and empty unless the test says otherwise. */
internal fun testLaunchHistory(
    io: (Runnable) -> Unit,
    loadRecents: () -> List<String>? = { emptyList() },
    writeRecents: (List<String>) -> Unit = {},
    loadUsage: () -> Map<String, Usage>? = { emptyMap() },
    writeUsage: (Map<String, Usage>, Long) -> Unit = { _, _ -> },
    now: () -> Long = { 0L },
): LaunchHistory = LaunchHistory(
    io = io,
    recentsFile = RecentsFile(read = loadRecents, write = writeRecents),
    usageFile = UsageFile(read = loadUsage, write = writeUsage),
    now = now,
)
