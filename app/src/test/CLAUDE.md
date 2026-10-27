# CLAUDE.md (test source set)

What tests exist, what is left out on purpose, and where a new one goes. The patterns themselves
are in `java/com/github/reygnn/verso_launcher/TESTING_CONVENTIONS.kt` — read it before touching
any test. A test's backtick name says what it checks; this file only maps the classes.

The baseline (JVM first, pure logic outside Android classes) comes from `~/.claude/CLAUDE.md`;
this project leaves out its stack on purpose: no coroutines, no MockK, no Turbine.

---

## What exists

JVM tests: `./gradlew testDebugUnitTest`. Robolectric classes are marked (R).

```
Pure logic
  LauncherLogicTest        modes, commands and their abbreviations, search and foldLabel
                           (Turkish, İ, NFC, accents, other scripts' marks), the ranking,
                           Collator sort, tags, recents, default favorites
  LauncherRowsTest         rowsFor per mode
  LauncherActionsTest      Enter and tap decisions
  SwipeGestureTest         the page drag and Kolibri's flick thresholds
  HomeStyleTest            sizes, luminance, outline thresholds, battery, AppStatus
  ConfigJsonTest           round trip, parseForeign's refusals, hostile input, size limits,
                           readCapped (a picked file's bounded read)
  VersoConfigTest          equality as file content
  ConfigTextTest           the config file the user writes: its lines, the apps they name
                           (key, package, folded label), a new file written from a config
  AppEntryTest             the config key: keyOf and parseKey
  EnumerationTest          the enumeration with faked profiles: missing, incomplete, status
  TapGuardTest, GuardTest  the stale-click guard; hard rule 2's backstop

State and threads (manual queues)
  LauncherStateTest        the concurrency rules: refresh and a new text interleaved; the
                           config resolved against each list; the default favorites;
                           recents.txt, usage.txt, the "%" order
  ResultsTest              results queued until a window is in front, shown once, in order
  WallpaperPaletteTest     the palette's question against the colour listener

Files (real temp files)
  ConfigLinkTest           the app's own files around the config: the link, the copy, order.txt,
                           verso.json of earlier versions (read only, the .bak, a torn one)
  HomeCacheTest, UsageLogTest, RecentsLogTest   the disposable files beside them

The window (R)
  MainActivityFlowsTest    the real Activity: ~open, ~new and ~edit, the file read again at
                           every start, an unreadable one, verso.json of earlier versions, "%",
                           ~restart and recreation, results that wait
  PageTurnTest, SwipeFrameTest   the page turn on real views and real MotionEvents
  AppLauncherTest          a launch or "App info" refused, gone or paused: a word, never a crash
  AppDrawerTest            the back page: rows, TalkBack, the tap guard
  HomePageAccessibilityTest, HomePageStartGuardTest, HomePageLocaleTest
                           the home page: TalkBack, guarded intents, ar-EG digits
  ConfigReadLeakTest       a hung read of the config file keeps no activity alive
  VersoGuruTextsTest, GuruWiringTest   Guru's words and its wiring into Verso

Source scans
  SourceGuardTest          only what neither a type nor a test reaches (hard rules 5, 16,
                           the lenient parse, configChanges, Guru's wiring, R8 keeping names)
```

Helpers: `TestLauncherState.kt`, `TestConfigs.kt`, `OrderedFake.kt`, `ViewTrees.kt`,
`DefaultLocaleRule.kt`. The unreadable-file cases of `UsageLogTest` and `RecentsLogTest` use
`chmod`, so they fail when run as root.

Instrumented (`./gradlew connectedDebugAndroidTest`, e.g. `ANDROID_SERIAL=R5GL51D5VHZ` for the A17):
`ConfigJsonInstrumentedTest` (Android's own `org.json`).

---

## Deliberately not

- **No mocks.** Android-bound collaborators are functions (`LauncherState`'s `io`/`main`/…);
  tests pass lambdas and fakes.
  If a real need appears: MockK, never Mockito.
- **No coroutines**, so no `MainDispatcherRule`, `runTest` or Turbine: two manual FIFO queues
  make every interleaving deterministic.
- **Few Activity tests.** `MainActivity` is glue; its decisions live in `LauncherLogic` and
  `LauncherState`; the config file's format is `ConfigText`'s, pure.
- **No MediaStore model.** The config file goes through the user's picker, a plain document
  stream. Guru's reports still use MediaStore, tested in Guru's repo; `GuruWiringTest.Downloads`
  only takes a save and keeps its name.

---

## Where a new test goes

- Start from a template: pure logic `LauncherLogicTest`; threads and state `LauncherStateTest`
  (its queue harness, `testLauncherState`); files `ConfigLinkTest`; a widget `AppDrawerTest`;
  Android-only behaviour `androidTest`.
- A new `~` command or sigil: `LauncherLogicTest`, `LauncherActionsTest`, `LauncherRowsTest`.
- Search, fold or sort: `LauncherLogicTest`, with a Turkish and a German/Swedish case.
- The config file's format: `ConfigTextTest`, and the files users already have must still read;
  older `verso.json` files and backups: `ConfigJsonTest`.
- `~open`, `~new`, `~edit` and the file read again: a new text in `LauncherStateTest`, the
  pickers, the editor and the threads in `MainActivityFlowsTest`.

---

## Paid for already

- **A test that pins the bug:** "İ" asserted to fold to "i" + a combining dot — the bug that made
  "iş" miss "İşCep". When a fix breaks a test, ask first whether the test encoded the defect.
- **A test that passes for any threshold:** greys at 0.09 and 0.60 held for every threshold in
  between. Pin a boundary from both sides.
- **A fixture that mirrors production:** a re-implemented sort cannot show the sort's bug.
- **The JVM's `org.json` is not Android's:** deep nesting throws on one and overflows the stack on
  the other. Behaviour that differs gets an instrumented test.
- **Mutation-check every fix:** revert it, watch the new test fail, restore.
