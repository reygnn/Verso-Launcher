# CLAUDE.md (test source set)

What tests exist, what is left out on purpose, and where a new one goes. The patterns themselves
are in `java/com/github/reygnn/verso_launcher/TESTING_CONVENTIONS.kt` — read it before touching
any test. A test's backtick name says what it checks; this file only maps the classes.

The baseline (JVM first, pure logic outside Android classes) comes from `~/.claude/CLAUDE.md`;
this project leaves out its stack on purpose: no coroutines, no MockK, no Turbine.

---

## What exists

JVM tests: `./gradlew testDebugUnitTest`; only the source scans: `./gradlew sourceScanTest`.
Robolectric classes are marked (R).

```
Pure logic
  LauncherLogicTest        modes, commands and their abbreviations, search and foldLabel
                           (Turkish, İ, NFC, accents, other scripts' marks), the ranking,
                           Collator sort, tags, recents, default favorites
  LauncherRowsTest         rowsFor per mode
  LauncherActionsTest      Enter and tap decisions
  SwipeGestureTest         the page drag and Kolibri's flick thresholds
  HomeStyleTest            sizes, luminance, outline thresholds, battery, AppStatus
  VersoConfigTest          equality as file content
  ConfigTextTest           the config file the user writes: its lines, the apps they name
                           (key, package, folded label), readCapped (the file's
                           bounded read)
  ConfigFuzzTest           the file and the app labels at random, 500 seeds in six locales,
                           through what runs on main without a catch: no throw from the
                           parse to the rows, every line an entry or reported, every tag
                           drills in, every app found by its name, the home cache whole
  AppEntryTest             the config key: keyOf and parseKey
  EnumerationTest          the enumeration with faked profiles: missing, incomplete, status
  TapGuardTest, GuardTest  the stale-click guard; hard rule 2's backstop

State and threads (manual queues)
  LauncherStateTest        the concurrency rules: refreshes and taps interleaved; the
                           config resolved against each list; the default favorites;
                           recents.txt, usage.txt, the "%" order
  WallpaperPaletteTest     the palette's question against the colour listener

Files (real temp files)
  ConfigFileTest           the user's config file at its fixed place: none, empty, bad lines,
                           past the cap — never a throw
  DrawerOrderTest, HomeCacheTest, UsageLogTest, RecentsLogTest   the app's disposable files

The window (R)
  MainActivityFlowsTest    the real Activity: the config file from the first frame and read
                           again by ~restart only, "%", ~restart and recreation
  PageTurnTest, SwipeFrameTest   the page turn on real views and real MotionEvents
  AppLauncherTest          a launch or "App info" refused, gone or paused: a word, never a crash
  AppDrawerTest            the back page: rows, empty hints, TalkBack, the tap guard, Enter
                           and keys, the clear button, insets
  HomePageAccessibilityTest, HomePageStartGuardTest, HomePageLocaleTest
                           the home page: TalkBack, guarded intents, ar-EG digits

Source scans
  SourceGuardTest          only what neither a type nor a test reaches (hard rules 5, 7, 16,
                           configChanges, R8 keeping names)
  CommentLinkTest          links in comments, KDoc or not, and code names in the Markdown
                           files that name code which is gone, or a test from code that ships
```

When the scans run, and why, is described at `sourceScanTest` in `app/build.gradle.kts`. A new
scan goes into `sourceScans` there, and a file it reads into the inputs of `sourceScanTest`.

Helpers: `TestLauncherState.kt`, `TestConfigs.kt`, `OrderedFake.kt`, `ViewTrees.kt`,
`DefaultLocaleRule.kt`. The unreadable-file cases of `UsageLogTest` and `RecentsLogTest` use
`chmod`; run as root, which reads the file anyway, they are skipped.

No instrumented tests: everything runs on the JVM.

---

## Deliberately not

- **No mocks.** Android-bound collaborators are functions (`LauncherState`'s `io`/`main`/…);
  tests pass lambdas and fakes.
  If a real need appears: MockK, never Mockito.
- **No coroutines**, so no `MainDispatcherRule`, `runTest` or Turbine: two manual FIFO queues
  make every interleaving deterministic.
- **Few Activity tests.** `MainActivity` is glue; its decisions live in `LauncherLogic` and
  `LauncherState`; the config file's format is `ConfigText`'s, pure.
- **No MediaStore model.** The config file is a plain file at a fixed place; nothing of Verso
  uses MediaStore.

---

## Where a new test goes

- Start from a template: pure logic `LauncherLogicTest`; threads and state `LauncherStateTest`
  (its queue harness, `testLauncherState`); files `ConfigFileTest`; a widget `AppDrawerTest`.
- A new `~` command or sigil: `LauncherLogicTest`, `LauncherActionsTest`, `LauncherRowsTest`.
- Search, fold or sort: `LauncherLogicTest`, with a Turkish and a German/Swedish case.
- The config file's format: `ConfigTextTest`, and the files users already have must still read.
  A character that broke a fold, a cut or a key goes into the pieces of `ConfigFuzzTest` too.
- When the config file is read: `ConfigFileTest` for the read itself, `MainActivityFlowsTest` for
  when (the first state, `~restart`).

---

## Paid for already

- **A test that pins the bug:** "İ" asserted to fold to "i" + a combining dot — the bug that made
  "iş" miss "İşCep". When a fix breaks a test, ask first whether the test encoded the defect.
- **A test that passes for any threshold:** greys at 0.09 and 0.60 held for every threshold in
  between. Pin a boundary from both sides.
- **A fixture that mirrors production:** a re-implemented sort cannot show the sort's bug.
- **Mutation-check every fix:** revert it, watch the new test fail, restore.
