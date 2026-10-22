package com.github.reygnn.verso_launcher

/**
 * A [LauncherState] whose collaborators the test does not name are inert: no file, no profile,
 * no clock that moves. The production constructor has no defaults on purpose (a wire
 * MainActivity forgot must not compile into a no-op), so the defaults live here, in test code.
 * The parameters stay flat — a test names the one function it fakes — and are bundled here into
 * the [ConfigFile], [RecentsFile] and [UsageFile] the constructor takes. The seven without a
 * default are the ones every test of the class has to decide on. Pass the same [process] to two
 * instances to model a recreation.
 */
// one parameter per faked function, flat on purpose
@Suppress("LongParameterList")
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
    defaultFavoriteCandidates: () -> List<List<String>> = { emptyList() },
    process: ProcessState = ProcessState(),
    // A gate of its own that never runs a command: always a permit.
    idle: () -> CommandGate.Idle? = { CommandGate().idle() },
    loadRecents: () -> List<String>? = { emptyList() },
    writeRecents: (List<String>) -> Unit = {},
    loadUsage: () -> Map<String, Usage>? = { emptyMap() },
    writeUsage: (Map<String, Usage>, Long) -> Unit = { _, _ -> },
    now: () -> Long = System::currentTimeMillis,
) = LauncherState(
    io = io,
    main = main,
    configFile = ConfigFile(
        load = loadConfig,
        setAsideUnreadable = setAsideUnreadable,
        isFresh = isFreshInstall,
        serialize = serialize,
        write = writeConfig,
    ),
    enumerateApps = enumerateApps,
    defaultFavoriteCandidates = defaultFavoriteCandidates,
    recentsFile = RecentsFile(read = loadRecents, write = writeRecents),
    usageFile = UsageFile(read = loadUsage, write = writeUsage),
    process = process,
    idle = idle,
    now = now,
    onSaveFailed = onSaveFailed,
    onRefreshed = onRefreshed,
)
