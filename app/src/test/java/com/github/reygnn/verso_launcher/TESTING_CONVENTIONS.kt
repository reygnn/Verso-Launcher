package com.github.reygnn.verso_launcher

/**
 * ============================================================================
 * TESTING CONVENTIONS — VERSO LAUNCHER
 * ============================================================================
 *
 * The family template assumes Compose + coroutines + MockK. Verso has none of
 * those (no dependencies in the APK, see the project CLAUDE.md), so this file
 * keeps the template's discipline and replaces its coroutine/MockK sections
 * with what this project actually does. If coroutines or MockK ever arrive,
 * take the corresponding sections from
 * ~/AndroidStudio_Projects/_skeleton/.../TESTING_CONVENTIONS.kt verbatim.
 * ============================================================================
 */

/**
 * ============================================================================
 * SEAMS INSTEAD OF MOCKS
 * ============================================================================
 *
 * Production classes take their Android-bound collaborators as FUNCTIONS, so
 * a test passes a lambda — no mocking framework, no mocked-out machinery:
 *
 *   LauncherState(io, main, loadConfig, enumerateApps, serialize, writeConfig,
 *                 onRefreshed, isFreshInstall, defaultFavoriteCandidates,
 *                 setAsideConfig, initialRecents, onRecentsChanged)
 *   CommandGate(onBusyChanged)                    // the busy layer, as a lambda
 *   CommandGate.io(ticket, submit, post, …)       // an IO step: the queues, injected
 *   ConfigStore(dir, log, syncDir, rename, readText)  // force a refused rename, a read error
 *   BackupStore(resolver, subDir, log, beforePublish, now)
 *
 * The `log` parameter is the error channel of classes that never throw: a
 * test collects the messages and asserts on them.
 *
 * Pure decisions live in `object`s (LauncherLogic, SwipeGesture, BackupFiles,
 * HomeStyle, BatteryInfo, Cleanup) and are tested with plain values; the IO
 * halves of ~restore and ~backup are pure steps over injected functions
 * (RestoreSteps, BackupSteps), tested by recording the calls in order. Rows are faked
 * with the shared `OrderedFake(key, labelLower)` (OrderedFake.kt).
 *
 * MockK only if a real need appears — never Mockito. Mock variables are then
 * named after what they represent, without a `mock` prefix.
 * ============================================================================
 */

/**
 * ============================================================================
 * THREADING — TWO MANUAL QUEUES, NOT DISPATCHERS
 * ============================================================================
 *
 * No coroutines here, so no MainDispatcherRule / runTest. LauncherState gets
 * `io` and `main` as functions; LauncherStateTest feeds it two FIFO queues:
 *
 *   private val ioQueue = ArrayDeque<Runnable>()
 *   private val mainQueue = ArrayDeque<Runnable>()
 *   io = { task -> ioQueue.addLast(task); true }
 *   main = { task -> mainQueue.addLast(task) }
 *
 *   state.refresh()
 *   runIo()             // the load ran …
 *   state.rename(…)     // … a rename lands before the hand-off
 *   runAll()
 *
 * A test decides exactly when the background half runs and when its result
 * is handed back, and can put a tap in between. Every race worth fixing gets
 * its interleaving written out like this.
 * ============================================================================
 */

/**
 * ============================================================================
 * FILES, LOCALES, ORG.JSON
 * ============================================================================
 *
 * FILES — real files in a JUnit `TemporaryFolder`, never a mocked filesystem:
 * the interaction between rename, parse and the three files (.tmp, primary,
 * .bak) is the thing under test. Only the directory fsync is stubbed.
 *
 * LOCALE — a class whose tests set the default locale holds a DefaultLocaleRule, and the
 * test just sets it:
 *
 *   @get:Rule val locale = DefaultLocaleRule()
 *   …  Locale.setDefault(Locale.of("tr", "TR"))
 *
 * The rule restores all three defaults (main, FORMAT, DISPLAY); a hand-written
 * try/finally restoring only the main one leaks the other two into later tests.
 *
 * Prefer passing a Locale explicitly where the API allows it
 * (sortedForDrawer, sortedTags, allTags, tagsInUse) — no global state at all.
 * Search/fold changes get a Turkish case; sorting gets German AND Swedish
 * (Ä under A vs. after Z — "alphabetical" is per language).
 *
 * ORG.JSON — the JVM classpath carries the real desktop org.json because
 * android.jar's is a throwing stub. It is NOT Android's implementation (e.g.
 * the desktop one rejects deep nesting with an exception, Android's overflows
 * the stack). A JVM test pins the contract; behaviour that differs on Android
 * gets an instrumented test (ConfigJsonInstrumentedTest).
 * ============================================================================
 */

/**
 * ============================================================================
 * ROBOLECTRIC — SDK 36, JDK 21
 * ============================================================================
 *
 * Only when a test genuinely needs the Android runtime — a real widget's
 * InputFilter, View accessibility plumbing. Annotate the class with
 * `@RunWith(RobolectricTestRunner::class)`; the SDK is pinned to 36 once for
 * all classes in `src/test/resources/robolectric.properties` (no per-class
 * `@Config(sdk = …)`). The manifest's VersoApp is fine as the Application: it
 * only holds an idle executor and in-memory caches (recents, palette, badge labels).
 *
 * Drive a plain `Activity` with `Robolectric.buildActivity(Activity::class.java)
 * .setup()` — no androidx.test / ActivityScenario on the unit-test classpath.
 * Started intents are read back with `shadowOf(activity).nextStartedActivity`.
 * ============================================================================
 */

/**
 * ============================================================================
 * INSTRUMENTED TESTS (androidTest)
 * ============================================================================
 *
 * Reserved for behaviour only a device has: MediaStore (renaming of taken
 * names, pending rows, ownership) and Android's own libraries. Run with a
 * device attached:
 *
 *   ANDROID_SERIAL=R5GL51D5VHZ ./gradlew connectedDebugAndroidTest
 *
 * The debug build's ".debug" applicationId suffix lets it install beside the
 * family-signed release. Rules for BackupStoreInstrumentedTest-style tests:
 *  - each test works in its own Download/VersoTest-<uuid>/ folder, removed
 *    in @After through the shell (`rm -rf`), which also takes pending rows
 *    and rows the app doesn't own;
 *  - a row the app does NOT own is created by the shell
 *    (`uiAutomation.executeShellCommand("touch …")`), exactly like a file an
 *    older install or adb left behind;
 *  - inject the clock (`now`) for predictable names, and use the
 *    `beforePublish` seam to fail (throw an Exception) or "crash" (throw an
 *    Error, which BackupStore deliberately does not catch) mid-write.
 * ============================================================================
 */

/**
 * ============================================================================
 * BEHAVIOURAL DISCIPLINE
 * ============================================================================
 *
 * 1. No Mockito anywhere; MockK only if mocks become necessary.
 * 2. Read the implementation alongside the test. The two are designed to be
 *    read together; subtle asymmetries are invisible to a test author who
 *    only reads one side.
 * 3. Honest KDoc beats fake tests. If something is genuinely not testable on
 *    the JVM, document why in the production code and test it on a device —
 *    a pseudo-test that asserts on mocked-out machinery proves nothing.
 * 4. Mutation check every fix: revert it, watch the new test fail, restore.
 * 5. Pin boundaries from both sides (the neighbouring values just below and
 *    just above), and never let a fixture re-implement the production logic
 *    it is supposed to check.
 * 6. When a fix breaks an existing test, first ask whether the test encoded
 *    the bug.
 * ============================================================================
 */
