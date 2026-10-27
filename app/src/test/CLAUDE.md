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
  CommandDialogTextTest    what the ~restore-saf dialog says
  LauncherRowsTest         rowsFor per mode
  LauncherActionsTest      Enter and tap decisions, the "!!" tap
  SwipeGestureTest         the page drag and Kolibri's flick thresholds
  HomeStyleTest            sizes, luminance, outline thresholds, battery, AppStatus
  ConfigJsonTest           round trip, parseForeign's refusals, hostile input, size limits
  VersoConfigTest          equality as file content
  BackupFilesTest          backup names; which file ~restore and ~undo take
  AppEntryTest             the config key: keyOf and parseKey
  EnumerationTest          the enumeration with faked profiles: missing, incomplete, status
  TapGuardTest, GuardTest  the stale-click guard; hard rule 2's backstop
  IoWatchdogTest           the IO thread's watchdog: ticks, the executor's hooks, the loop guard

State and threads (manual queues)
  LauncherStateTest        the concurrency rules: load, edit, adopt and save interleaved;
                           seeding; saves before the load, failed, at onStop; an unreadable
                           config; recents.txt and usage.txt
  CommandGateTest          one "~" command at a time, the edit permit
  PendingPickTest          a "~restore-saf" pick's states
  ResultsTest              results queued until a window is in front, refusals never
  WallpaperPaletteTest     the palette's question against the colour listener

Files (real temp files)
  ConfigStoreTest          verso.json on SharedPreferences' terms (.bak, torn, unreadable)
  HomeCacheTest, UsageLogTest, RecentsLogTest   the disposable files beside it
  BackupStoreTest          readCapped, the bounded restore read
  RestoreStepsTest         the restore's IO order
  CrashPointsTest          a restore killed before every step, then a cold start

Restore matrix (docs/spec/restore-matrix.md, the table as code in RestoreMatrix.kt)
  RestoreMatrixTest        all 2 160 cells, each killed before every step; every sequence of
                           up to four commands against the spec's model
  RestoreMatrixFlowsTest   (R) the 55 cells a user can reach, through the real Activity

The window (R)
  MainActivityFlowsTest    the real Activity: commands refused, held or queued, picks,
                           ~restart, recreation, what happens before the first load
  PageTurnTest, SwipeFrameTest   the page turn on real views and real MotionEvents
  AppLauncherTest          a launch or "App info" refused, gone or paused: a word, never a crash
  AppDrawerTest            the back page: rows, TalkBack, the tap guard, the reorder pick
  HomePageAccessibilityTest, HomePageStartGuardTest, HomePageLocaleTest
                           the home page: TalkBack, guarded intents, ar-EG digits
  RenameDialogFilterTest   the dialog's InputFilters on a real widget
  RestoreReadLeakTest      a hung "~restore-saf" read keeps no activity alive
  VersoGuruTextsTest, GuruWiringTest   Guru's words and its wiring into Verso

Source scans
  SourceGuardTest          only what neither a type nor a test reaches (hard rules 5, 16,
                           the lenient parse, configChanges, Guru's wiring, R8 keeping names)
```

Helpers: `TestLauncherState.kt`, `TestConfigs.kt`, `OrderedFake.kt`, `ViewTrees.kt`,
`DefaultLocaleRule.kt`. The unreadable-file cases of `UsageLogTest`, `RecentsLogTest` and
`IoWatchdogTest` use `chmod`, so they fail when run as root.

Instrumented (`./gradlew connectedDebugAndroidTest`, e.g. `ANDROID_SERIAL=R5GL51D5VHZ` for the A17):
`BackupStoreInstrumentedTest` (the real MediaStore: publish, names it gives, rows we don't own,
crashed and vanished pending rows) and `ConfigJsonInstrumentedTest` (Android's own `org.json`).

---

## Deliberately not

- **No mocks.** Android-bound collaborators are functions (`LauncherState`'s `io`/`main`/…,
  `ConfigStore`'s seams, `BackupStore`'s `beforePublish`/`now`); tests pass lambdas and fakes.
  If a real need appears: MockK, never Mockito.
- **No coroutines**, so no `MainDispatcherRule`, `runTest` or Turbine: two manual FIFO queues
  make every interleaving deterministic.
- **Few Activity tests.** `MainActivity` is glue; its decisions live in `LauncherLogic` and
  `LauncherState`. Rules an edit could break silently are types where possible (`Ticket`,
  `EditPermit`).
- **No MediaStore model.** Robolectric cannot reproduce MediaStore's renaming and ownership —
  what broke the backups once — so those are tested on a device. The two providers here model no
  MediaStore rule: `GuruWiringTest.Downloads` only takes a save and keeps its name,
  `RestoreMatrixFlowsTest`'s `ListingDownloads` lists rows by `_ID` and name and fails on demand.

---

## Where a new test goes

- Start from a template: pure logic `LauncherLogicTest`; threads and state `LauncherStateTest`
  (its queue harness, `testLauncherState`); files `ConfigStoreTest`; a widget
  `RenameDialogFilterTest`; MediaStore or Android-only behaviour `androidTest`.
- A new `~` command or sigil: `LauncherLogicTest`, `LauncherActionsTest`, `LauncherRowsTest`.
- Search, fold or sort: `LauncherLogicTest`, with a Turkish and a German/Swedish case.
- Config shape: `ConfigJsonTest`, and older `verso.json` files must still parse.
- `~backup`, `~restore`, `~restore-saf`, `~undo`: first the table in
  `docs/spec/restore-matrix.md`, then `RestoreMatrix.kt`; both matrix tests follow.

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
