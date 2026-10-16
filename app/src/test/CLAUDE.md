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

JVM unit tests (`app/src/test`, `./gradlew testDebugUnitTest`, 530 tests):

```
LauncherLogicTest          144  modes, commands, search + foldLabel (Turkish, İ, NFC),
                                star prefix, drawer/favorites order, Collator sort,
                                custom names applied (named), tags, reorder, recents,
                                default favorites
LauncherStateTest           90  the concurrency rules: refresh/reload/rename/adopt
                                interleavings driven as manual IO/main queues, seeding,
                                save-before-load, needsRefresh, recents, reports that
                                come even when a collaborator throws, names applied
                                to a list that carries none, an unreadable config
                                (not loaded, edits off, ~load retries, restore way out),
                                ~reset (only while unreadable, starts over fresh)
CommandGateTest             16  one "~" command at a time: a run ends with its last step,
                                refusal, hand-over, exceptions, cancel of the SAF read
LauncherRowsTest            37  rowsFor per mode
ConfigStoreTest             45  verso.json on real temp files: .bak rotation, .tmp
                                recovery (published by rename, never rewritten),
                                heal-without-rotate, isFresh, unreadable is null — not empty,
                                set aside by renaming (never deleted, never overwritten, primary last),
                                the copy for Download (read in place, first non-blank file)
ConfigJsonTest              29  round-trip, parseForeign's refusal of foreign JSON,
                                hostile nesting, look-alike characters, size limits
LauncherActionsTest         26  Enter / tap decisions, which Enter call acts
HomeStyleTest               22  sizes, luminance, outline-colour thresholds, battery,
                                AppStatus and its dimming, the wallpaper palette
BackupFilesTest             19  backup naming: timestamps, patterns, restore pick, sweep
                                (never of a verso-broken copy)
SwipeGestureTest            19  the page drag (direction, offset, release, home fade) and
                                Kolibri's flick thresholds, top band, who claims what
SwipeFrameTest              12  Robolectric: real MotionEvents through the frame — delta and
                                velocity signs, limits, second finger, abortDrag, drag vs flick
RenameDialogFilterTest      10  Robolectric: the tag and name fields' InputFilters on a real widget
VersoConfigTest              9  snapshot deep copy, sameContent
TapGuardTest                 4  stale-click guard
HomeCacheTest                5  the cold-start favorites cache: format, parse, file
RestoreStepsTest             9  the restore's IO order: undo point, then config; no-op restore;
                                over an unreadable config: set aside first, never written over;
                                a failed config write is reported, not taken for a restore
BackupStoreTest              5  readCapped (the bounded restore read)
HomePageAccessibilityTest   10  Robolectric: TalkBack click on clock/date/battery, the double
                                tap, the "open all apps" action, the empty-favorites hint
HomePageLocaleTest           2  Robolectric (ar-EG): clock, date and battery in the language's
                                own digits
CleanupTest                  9  what ~cleanup may offer: complete enumeration, kept apps, parseKey
SourceGuardTest              8  source scans: no lenient ConfigJson.parse in MainActivity,
                                no density in configChanges, ~cleanup's IO guards, no
                                brace-less object templates, every command (incl. ~reset) through the gate,
                                no custom names in the enumeration, the broken copy
                                published before the renames, the prompt written via setPrompt only
```

Instrumented tests (`app/src/androidTest`, `./gradlew connectedDebugAndroidTest`
with a device attached, e.g. `ANDROID_SERIAL=R5GL51D5VHZ` for the A17):

```
BackupStoreInstrumentedTest  BackupStore against the REAL MediaStore: publish, replace,
                             names taken by rows we don't own, failed/crashed publish,
                             a crash between publish and sweep, a vanished temp
ConfigJsonInstrumentedTest   ConfigJson on Android's own org.json (no depth limit)
```

---

## What this set deliberately does NOT have

- **No MockK, no mocks at all.** Everything Android-bound is injected as a
  function (`LauncherState`'s `io`/`main`/`loadConfig`…, `ConfigStore`'s
  `syncDir`/`rename`, `BackupStore`'s `beforePublish`/`now`), so tests pass plain
  lambdas and fakes. Add MockK only if a real need appears — and then MockK, never
  Mockito.
- **No coroutines, so no `MainDispatcherRule` / `runTest` / Turbine.** Threading is
  modelled as two manual FIFO queues (`LauncherStateTest`), which makes every
  interleaving deterministic.
- **No Activity tests.** `MainActivity` is glue; its decisions live in `LauncherLogic`
  / `LauncherState`. Behaviour that only exists in the Activity is checked on a device;
  `SourceGuardTest` scans the source for the edits there that would be silently
  dangerous.
- **No Robolectric MediaStore fakes.** Robolectric cannot reproduce MediaStore's
  renaming and ownership rules — that is exactly what broke the backups — so the
  backup layer is tested on a device instead.

---

## When you come into this project

### Reading order

1. **First** `java/com/github/reygnn/verso_launcher/TESTING_CONVENTIONS.kt`
   — the technical reference. Mandatory before touching any test.
2. **Then** an existing test as a template. Pick by category:
   - Pure decision logic: `LauncherLogicTest` (the shared `OrderedFake`).
   - Anything that sets the default locale: hold a `DefaultLocaleRule`.
   - Reaching a widget built in code: `collect<T>()` from `ViewTrees.kt`.
   - Threading / state rules: `LauncherStateTest` (the queue harness at the top).
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
