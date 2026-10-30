package com.github.reygnn.verso_launcher

/**
 * A [LauncherState] whose collaborators the test does not name are inert: no file, no profile,
 * no clock that moves. The production constructor has no defaults on purpose (a wire
 * MainActivity forgot must not compile into a no-op), so the defaults live here, in test code.
 * The parameters stay flat — a test names the one function it fakes — and are bundled here into
 * the [RecentsFile] and [UsageFile] the constructor takes. The four without a default are the ones
 * every test of the class has to decide on. Pass the same [process] to two instances to model a
 * recreation. [config] is a config of keys ([textOf]); [text] the user's file.
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
    process: ProcessState = ProcessState(),
    loadRecents: () -> List<String>? = { emptyList() },
    writeRecents: (List<String>) -> Unit = {},
    loadUsage: () -> Map<String, Usage>? = { emptyMap() },
    writeUsage: (Map<String, Usage>, Long) -> Unit = { _, _ -> },
    writeOrder: (Boolean) -> Unit = {},
    now: () -> Long = { 0L },
): LauncherState = LauncherState(
    io = io,
    main = main,
    text = text,
    useDefaults = useDefaults,
    fileStatus = fileStatus,
    byUsage = byUsage,
    enumerateApps = enumerateApps,
    defaultFavoriteCandidates = defaultFavoriteCandidates,
    recentsFile = RecentsFile(read = loadRecents, write = writeRecents),
    usageFile = UsageFile(read = loadUsage, write = writeUsage),
    writeOrder = writeOrder,
    process = process,
    now = now,
    onRefreshed = onRefreshed,
)
