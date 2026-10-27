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
                           Collator sort, tags, reorder, recents,
                           default favorites
  CommandDialogTextTest    what the ~restore dialog says
  LauncherRowsTest         rowsFor per mode
  LauncherActionsTest      Enter and tap decisions, the "!!" tap
  SwipeGestureTest         the page drag and Kolibri's flick thresholds
  HomeStyleTest            sizes, luminance, outline thresholds, battery, AppStatus
  ConfigJsonTest           round trip, parseForeign's refusals, hostile input, size limits,
                           readCapped (a picked file's bounded read)
  VersoConfigTest          equality as file content
  AppEntryTest             the config key: keyOf and parseKey
  EnumerationTest          the enumeration with faked profiles: missing, incomplete, status
  TapGuardTest, GuardTest  the stale-click guard; hard rule 2's backstop

State and threads (manual queues)
  LauncherStateTest        the concurrency rules: refresh, edit, adopt and save interleaved;
                           seeding; saves failed and at onStop; an unreadable config;
                           replace (~restore, ~undo); recents.txt and usage.txt
  PendingPickTest          a "~restore" pick's states
  ResultsTest              results queued until a window is in front, shown once, in order
  WallpaperPaletteTest     the palette's question against the colour listener

Files (real temp files)
  ConfigStoreTest          verso.json whole or not at all: torn and unreadable files set
                           aside, a write killed mid-way, the legacy .bak
  UndoSlotTest             the one undo point: whole or not at all, read back as written
  HomeCacheTest, UsageLogTest, RecentsLogTest   the disposable files beside it

The window (R)
  MainActivityFlowsTest    the real Activity: ~backup and ~restore through their pickers,
                           ~undo, picks across ~restart and recreation, results that wait,
                           what happens before the first load
  PageTurnTest, SwipeFrameTest   the page turn on real views and real MotionEvents
  AppLauncherTest          a launch or "App info" refused, gone or paused: a word, never a crash
  AppDrawerTest            the back page: rows, TalkBack, the tap guard, the reorder pick
  HomePageAccessibilityTest, HomePageStartGuardTest, HomePageLocaleTest
                           the home page: TalkBack, guarded intents, ar-EG digits
  RenameDialogFilterTest   the dialog's InputFilters on a real widget
  RestoreReadLeakTest      a hung "~restore" read keeps no activity alive
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

- **No mocks.** Android-bound collaborators are functions (`LauncherState`'s `io`/`main`/…,
  `ConfigStore`'s seams); tests pass lambdas and fakes.
  If a real need appears: MockK, never Mockito.
- **No coroutines**, so no `MainDispatcherRule`, `runTest` or Turbine: two manual FIFO queues
  make every interleaving deterministic.
- **Few Activity tests.** `MainActivity` is glue; its decisions live in `LauncherLogic` and
  `LauncherState`. Rules an edit could break silently are types where possible (`EditPermit`).
- **No MediaStore model.** Verso's own backups go through the user's picker, a plain document
  stream. Guru's reports still use MediaStore, tested in Guru's repo; `GuruWiringTest.Downloads`
  only takes a save and keeps its name.

---

## Where a new test goes

- Start from a template: pure logic `LauncherLogicTest`; threads and state `LauncherStateTest`
  (its queue harness, `testLauncherState`); files `ConfigStoreTest`; a widget
  `RenameDialogFilterTest`; Android-only behaviour `androidTest`.
- A new `~` command or sigil: `LauncherLogicTest`, `LauncherActionsTest`, `LauncherRowsTest`.
- Search, fold or sort: `LauncherLogicTest`, with a Turkish and a German/Swedish case.
- Config shape: `ConfigJsonTest`, and older `verso.json` files must still parse.
- `~backup`, `~restore`, `~undo`: the swap and its undo point in `LauncherStateTest`, the slot in
  `UndoSlotTest`, the pickers and their threads in `MainActivityFlowsTest`.

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
