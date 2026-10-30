package com.github.reygnn.verso_launcher

/**
 * TESTING CONVENTIONS — how tests here are built. What exists, what is left out on purpose and
 * the lessons already paid for: app/src/test/CLAUDE.md.
 *
 * SEAMS, NOT MOCKS
 *
 * Android-bound collaborators come in as functions, so a test passes a lambda or a fake:
 *
 *   LauncherState(io, main, initial, useDefaults, byUsage, enumerateApps,
 *                 defaultFavoriteCandidates, recentsFile, usageFile, writeOrder, process, now,
 *                 onRefreshed)
 *     — built through testLauncherState (TestLauncherState.kt): flat parameters with inert
 *       defaults, the config given as the file's text (ConfigText) or as keys; the production
 *       constructor has none. One shared ProcessState between two instances models a recreation.
 *   ConfigFile, DrawerOrder, HomeCache, UsageLog, RecentsLog — real files in a dir
 *
 * `log` is the error channel of a class that never throws: collect it and assert on it. Pure
 * decisions live in objects (LauncherLogic, SwipeGesture,
 * TapGuard, HomeStyle, BatteryInfo), tested with plain values; rows are `OrderedFake(key,
 * labelLower)`.
 *
 * THREADS — MANUAL QUEUES
 *
 *   private val ioQueue = ArrayDeque<Runnable>()
 *   private val mainQueue = ArrayDeque<Runnable>()
 *   io = { task -> ioQueue.addLast(task) }
 *   main = { task -> mainQueue.addLast(task) }
 *
 *   state.refresh()
 *   runIo()             // the load ran …
 *   state.refresh()     // … a resume supersedes it before the hand-off
 *   runAll()
 *
 * The test decides when the background half runs and when it is handed back, and can put a tap
 * in between: every race worth fixing gets its interleaving written out like this.
 *
 * Where the real Activity runs, the process's IO executor is real: drain it with
 * `app.io.submit {}.get(…)` plus `shadowOf(Looper.getMainLooper()).idle()`, or hold it with a
 * latch to keep the first load waiting. recreate() is posted, as on a device: controller.get()
 * switches only once the main looper idles. The user's config file is a real file in the
 * application's getExternalFilesDir(null), written before the state is built (the first
 * MainActivity) or before a "~restart".
 *
 * FILES AND LOCALES
 *
 * Real files in a JUnit TemporaryFolder: write, rename and parse together are what is tested.
 *
 * A class whose tests set the default locale holds `@get:Rule val locale = DefaultLocaleRule()`;
 * it restores all three defaults (main, FORMAT, DISPLAY), a hand-written try/finally only one.
 * Better still, pass a Locale where the API takes one (sortedForDrawer, sortedTags,
 * tagsInUse). Search and fold get a Turkish case; sorting a German AND a Swedish one (Ä under A,
 * or after Z).
 *
 * ROBOLECTRIC — SDK 36, JDK 21
 *
 * Only for what needs the Android runtime: a real widget, accessibility, MotionEvents, the real
 * Activity. `@RunWith(RobolectricTestRunner::class)`; the SDK is pinned once in
 * src/test/resources/robolectric.properties — no `@Config(sdk = …)` (`qualifiers` for a locale
 * is fine). Robolectric makes a fresh VersoApp per test. Drive an Activity with
 * `Robolectric.buildActivity(…).setup()` (no androidx.test on this classpath), reach a widget
 * built in code with `view.collect<T>()` (ViewTrees.kt), read a started intent with
 * `shadowOf(activity).nextStartedActivity`.
 *
 * WRITING ONE
 *
 * Read the implementation beside the test: the asymmetries are invisible from one side. What the
 * JVM cannot test is said in KDoc and tested on a device, never faked. A rule in Activity glue
 * becomes a type where it can; only what no type or test reaches gets a scan in SourceGuardTest.
 * Comments are sparse — the backtick name says what is tested; comment only why a fixture or
 * boundary was chosen, a bug the name does not make clear, or a harness trap.
 */
