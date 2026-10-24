# CLAUDE.md (test source set)

Orientation for sessions touching the tests. Not a technical manual
— that's `java/com/github/reygnn/verso_launcher/TESTING_CONVENTIONS.kt`,
which holds the concrete patterns. This file answers the _meta_ questions:
what exists, what is deliberately left out, where to start.

The baseline philosophy (JVM-only by default, pure logic outside Android-runtime
classes) comes from `~/.claude/CLAUDE.md`. This project departs from the baseline
STACK on purpose: no coroutines, no MockK, no Turbine — see "deliberately not".

---

## What exists

JVM unit tests (`app/src/test`, `./gradlew testDebugUnitTest`, 616 tests):

```
LauncherLogicTest          152  modes, commands (~crash daily-driver only, never abbreviated),
                                search + foldLabel (Turkish, İ, NFC),
                                star prefix, drawer/favorites order, Collator sort,
                                custom names applied (named), tags, reorder, recents,
                                default favorites
LauncherStateTest           93  the concurrency rules: refresh/rename/adopt
                                interleavings driven as manual IO/main queues (adopt
                                refused before the first load, reports once in place, a
                                refresh still lands after it), seeding (also from an
                                incomplete list; a first load without a list waits — past
                                an edit and a recreation, held back by a running command and
                                then re-rendered, ended by a favorite, a restore written or
                                no candidate app),
                                a rename's untouched OK (a label longer than a name may be,
                                bidi isolates) is no custom name,
                                save-before-load, no edit permit before the load nor
                                past a command, a failed save reported, needsRefresh
                                (also after an incomplete enumeration, which a complete one
                                then fills in),
                                recents (recents.txt read once, never written over
                                unread, the read handed to a recreated instance, two instances
                                merging the files in once), reports that
                                come even when a step throws (a "~cleanup" waits for it),
                                an unreadable config (set aside, a fresh start, said once — also after a
                                dropped hand-off or a recreation;
                                if it cannot be moved: not loaded, no edit, retried)
CommandGateTest             10  one "~" command at a time: a run ends with its last step,
                                refusal, hand-over, exceptions, the edit permit
LauncherRowsTest            41  rowsFor per mode
ConfigStoreTest             17  verso.json on real temp files: .new + rename, no leftovers,
                                torn or unreadable is null — not empty, isFresh, set aside
                                (exported to Downloads, else renamed: one copy), a crash's .new ignored
ConfigJsonTest              32  round-trip (the "%" order too), parseForeign's refusal of foreign JSON,
                                hostile nesting, look-alike characters, lone surrogates, size limits
LauncherActionsTest         30  Enter / tap decisions, which Enter call acts, the "!!" tap
                                (pick up, let go, move)
HomeStyleTest               22  sizes, luminance, outline-colour thresholds, battery,
                                AppStatus and its dimming, the wallpaper palette
BackupFilesTest             17  backup naming: timestamps, patterns, restore pick, sweep
SwipeGestureTest            19  the page drag (direction, offset, release, home fade) and
                                Kolibri's flick thresholds, top band, who claims what
SwipeFrameTest              14  Robolectric: real MotionEvents through the frame — delta and
                                velocity signs, limits, second finger, abortDrag, drag vs flick
PageTurnTest                27  Robolectric: the page turn on real views — open/close (animated or
                                not), the side it comes from and returns to (also caught
                                mid-close), leaving "?", who may drag or flick (dialog, top band),
                                drag commit/cancel both ways, open ending a held drag, Back
                                (home, prompt, recents, preview, cancel — also after HOME closed
                                the drawer)
AppDrawerTest               12  Robolectric: the back page — rows per prompt, edit-mode markers,
                                the empty-list hint per mode, a palette switch, a tap handed on,
                                the reorder pick (held, dropped, let go, its row gone), clearing
                                the prompt
MainActivityFlowsTest       13  Robolectric, the real Activity: ~crash throws at once (before the
                                first load too); a second command and an edit
                                refused while one runs (prompt kept; the rename OK says why),
                                ~restart dropping a pick still being read (said; other
                                commands ran meanwhile) or one whose hand-back is already
                                queued (not adopted), and dropping nothing after it; ~restart
                                rebuilding the activity; the newest pick winning; a
                                ~restore-saf closing the rename dialog; no edit and no
                                command but ~restart before the first load; HOME ending a
                                running swipe
RenameDialogFilterTest      10  Robolectric: the tag and name fields' InputFilters on a real widget
VersoConfigTest              4  equality as file content: the favorites' and a tag list's order
                                count, other order does not, every field counts
WallpaperPaletteTest         6  the palette's question and the colour listener as manual queues:
                                an answer older than a report (even one that repaints nothing)
                                or than a newer question is dropped, one repaint per change
GuardTest                    2  hard rule 2's backstop: a checked exception logged, an Error not caught
TapGuardTest                 4  stale-click guard
HomeCacheTest                5  the cold-start favorites cache: format, parse, file
UsageLogTest                12  the "%" order: decay, a launch, merge, sort, a clock ahead, usage.txt (junk,
                                unreadable: not loaded, too large)
RecentsLogTest               5  the "?" list's recents.txt: merge, parse, round-trip, too large,
                                unreadable: null
RestoreStepsTest             4  the restore's IO order: undo point, config, then the old undo
                                point swept; no-op restore; a failed write
CrashPointsTest              5  crash-only: a restore and a "~cleanup" killed before every step
                                (real verso.json files, Download/Verso swept by BackupFiles' rules),
                                then a cold start: either config whole, nothing the user had lost,
                                one undo point once through
BackupStoreTest              5  readCapped (the bounded restore read)
HomePageAccessibilityTest   10  Robolectric: TalkBack click on clock/date/battery, the double
                                tap, the "open all apps" action, the empty-favorites hint
HomePageStartGuardTest       4  Robolectric: clock, date and battery each guarded; a refused intent
                                (no app, denied, any other) is a toast, never a crash
HomePageLocaleTest           2  Robolectric (ar-EG): clock, date and battery in the language's
                                own digits
EnumerationTest             13  the enumeration with faked profiles: null when the profiles or
                                the personal one cannot be read, is missing or empty,
                                incomplete for every skipped profile or app (hard rule 14),
                                empty work profiles, status
CleanupTest                 10  what ~cleanup may offer: complete enumeration, kept apps, parseKey,
                                a key with the launcher's own serial
SourceGuardTest             10  source scans: no lenient ConfigJson.parse in MainActivity,
                                no brace-less object templates, no density in configChanges,
                                no default in LauncherState's constructor, no EditPermit built
                                outside LauncherState (call or reference), MainActivity's
                                writeRestored and gate wiring; Guru installed once from VersoApp
                                behind Guard, showPending in onCreate (not through onUi),
                                guru/ out of backup and transfer, R8 keeping names
VersoGuruTextsTest           4  Robolectric: Guru's words from values and values-de, read each
                                time (a language change), every member Verso's own
GuruWiringTest               2  Robolectric: Guru runs in Verso — a report left behind saved on
                                the IO thread (a minimal MediaStore provider) and shown; an
                                alert a recreation took away shows on the new instance
```

Instrumented tests (`app/src/androidTest`, `./gradlew connectedDebugAndroidTest`
with a device attached, e.g. `ANDROID_SERIAL=R5GL51D5VHZ` for the A17):

```
BackupStoreInstrumentedTest  BackupStore against the REAL MediaStore: publish, replace,
                             names taken by rows we don't own, failed/crashed publish,
                             a crash between publish and sweep, a vanished temp, a write
                             whose sweep waits (a restore's undo point)
ConfigJsonInstrumentedTest   ConfigJson on Android's own org.json (no depth limit)
```

---

## What this set deliberately does NOT have

- **No MockK, no mocks at all.** Everything Android-bound is injected as a
  function (`LauncherState`'s `io`/`main`/`configFile`…, `ConfigStore`'s
  `syncDir`/`rename`, `BackupStore`'s `beforePublish`/`now`), so tests pass plain
  lambdas and fakes. Add MockK only if a real need appears — and then MockK, never
  Mockito.
- **No coroutines, so no `MainDispatcherRule` / `runTest` / Turbine.** Threading is
  modelled as two manual FIFO queues (`LauncherStateTest`), which makes every
  interleaving deterministic.
- **Few Activity tests, on purpose.** `MainActivity` is glue; its decisions live in
  `LauncherLogic` / `LauncherState`. `MainActivityFlowsTest` covers only flows that need
  no LauncherApps or MediaStore fake (Robolectric models both too thinly to test anything
  but the fake). The rest is checked on a device; rules an edit could silently break are
  types where possible (the gate's `Ticket`, the `EditPermit`), `SourceGuardTest` scans
  only what neither a type nor a test reaches.
- **No Robolectric MediaStore fakes.** Robolectric cannot reproduce MediaStore's
  renaming and ownership rules — that is exactly what broke the backups — so the
  backup layer is tested on a device instead. The one provider here, `GuruWiringTest`'s, models
  no MediaStore rule: it only takes Guru's save, so the test can see that Guru runs at all.

---

## When you come into this project

### Reading order

1. **First** `java/com/github/reygnn/verso_launcher/TESTING_CONVENTIONS.kt`
   — the technical reference. Mandatory before touching any test.
2. **Then** an existing test as a template. Pick by category:
   - Pure decision logic: `LauncherLogicTest` (the shared `OrderedFake`).
   - Anything that sets the default locale: hold a `DefaultLocaleRule`.
   - Reaching a widget built in code: `collect<T>()` from `ViewTrees.kt`.
   - Threading / state rules: `LauncherStateTest` (the queue harness at the top);
     build a `LauncherState` with `testLauncherState` (`TestLauncherState.kt`), which
     supplies the inert collaborators the production constructor deliberately does not.
   - File I/O: `ConfigStoreTest` (`TemporaryFolder`, injected `rename` seam).
   - A real widget: `RenameDialogFilterTest` / `HomePageAccessibilityTest`.
   - MediaStore / Android-only library behaviour: `androidTest/…InstrumentedTest`.

### Common tasks and where they go

- New `~` command or sigil mode → `LauncherLogicTest` (parse) + `LauncherActionsTest`
  (Enter/tap) + `LauncherRowsTest` (rows).
- Anything touching search, fold or sort → `LauncherLogicTest`, with a Turkish and
  a German/Swedish locale case.
- Config shape → `ConfigJsonTest` (and keep older `verso.json` files parsing).
- Save/load durability → `ConfigStoreTest`; backup naming → `BackupFilesTest`,
  backup I/O → `BackupStoreInstrumentedTest`.

---

## Anti-patterns we've already paid for

The full list lives in `TESTING_CONVENTIONS.kt`. The headline ones here:

- **A test that pins the bug.** `RenameDialogFilterTest` asserted that "İ" folds to
  "i" + combining dot — the very bug that made "iş" miss "İşCep". When a fix breaks a
  test, check whether the test encoded the defect before "fixing" the test.
- **A test that passes for any threshold.** The outline-colour test used greys at
  luminance 0.09 and 0.60 and so held for any threshold in between. Pin a boundary
  with the neighbouring values on BOTH sides.
- **A fixture that mirrors production.** `LauncherStateTest` once re-implemented the
  sort with the same `sortedBy` as production; a bug in it could never show.
- **Trusting the JVM `org.json`.** It refuses deep nesting with an exception;
  Android's overflows the stack. Library behaviour that differs on Android gets an
  instrumented test.
- **Do a mutation check** for every fix: revert the fix, see the new test fail,
  restore. A test that stays green against the bug proves nothing.
