# Changelog

Every tag has an entry here, newest first. Versions follow SemVer; what counts as major,
minor or patch is in `CLAUDE.md`, "Versions and releases".

## 2.0.1 — 2026-10-24

Checked on the A17: "2 Berichte in Download/Guru gespeichert" set a little smaller, the Amiga
lines at their full size; the hint visibly lighter.

- Fix: the host's "saved" line set the text size of the whole alert — a German "8 Berichte
  in Download/Guru gespeichert" is longer than "Press left mouse button to continue." and
  shrank every line. It now fits on its own, never above the Amiga lines.
- Fix: the hint's grey (0x666666, about 3.7 : 1 on black) was below WCAG's 4.5 : 1; now
  0x8C8C8C, about 6.2 : 1.
- Tests for the mutants audit 2026-10-23 #11 left open (a guarded io step, remove by whole
  id, the last "Caused by:", FNV over UTF-8, an ANR's main block, a space before the colon);
  the on-device test cleans its folder up again. 167 tests.

## 2.0.0 — 2026-10-24

**Breaking.** Save first, then tell.

Checked on the A17 (Samsung SM-A176B, One UI, Android 16): a crash saved to
`Download/Guru/` and announced; hold opens One UI's share sheet with the file named (the
chooser's read access), mail apps offered for `.json`; cancel and double-tap close, no alert
after a restart; the alert back after a dark-mode recreate; "Crash twice" gives "2 Berichte",
both files shared together; an ANR saved with its thread dump (#80000002); no crash saved
twice across starts; TalkBack reads the alert in German; `connectedAndroidTest` 4/4.

- At every start, before anything is shown, each pending report is saved as a `.json` file
  of its own in `Download/Guru/` (MediaStore, no permission), named after app, version and
  crash time (`Verso-0.3.1-2026-10-24_14-03-12.json`): the ACRA JSON, unchanged. The file is
  synced and shown only once complete; Guru's copy goes once it is saved, one that cannot be
  saved stays. Of its own files there Guru keeps the newest 32; no one else's are touched.
  Reports a 1.x left in its store are saved at the first start — the store's format is
  unchanged.
- The alert only tells: the newest number and how many were saved. Hold shares the saved
  files (`EXTRA_STREAM` and ClipData, read access for the chooser and the app picked,
  `FLAG_ACTIVITY_NEW_TASK`); double-tap or Back closes. Once seen it never comes again; lost
  before anyone answered (the activity destroyed or recreated), it comes back with the next
  `showPending`. There is no "discard" any more — delete the file.
- Nothing waits on the user: an app that dies before its alert keeps its reports in
  `Download/Guru/` (audit 2026-10-23 #21). An alert whose host texts throw is logged and left
  out; a crash on the main thread inside Guru's own package leaves the next alert out, so the
  alert cannot crash every start (#4).
- The times of crashes saved are kept, so an exit record the system lists only at a later
  start is no second crash.
- Also: an ANR dump that threw while being read stopped the exit look before the mark moved,
  so the same exits came back as new reports on every start; such an ANR is now reported
  without its dump.
- **Removed:** `GuruSink` (`Share` and `Acra`), `AcraPost` and the receiver with its
  `PendingIntent` and `ChooserResult` — Guru has no network code. `Guru.install(app, io,
  texts)` loses its `sink` parameter.
- **`GuruTexts`:** now `hint`, `actionClose`, `actionShare` and `saved(count)`; `actionContinue`,
  `actionSend`, `more` and `sent` are gone. Every member has its English text as the default in
  the interface; `GuruTexts.English` overrides nothing.
- Reports sit in shared storage from the next start on — README, "Privacy". Receivers get
  `application/json` files — README, "For receivers".
- 159 tests; on-device MediaStore tests (`:library:connectedAndroidTest`, 4), compiled by
  `check`. The sample's debug build has "Crash twice" for a start with two reports.
- Docs: the backend contract as deployed on 2026-10-23 — every Guru report grouped by
  package, class and Guru number; a `REPORT_ID` counted once; reporter and admin users
  separate.

**Host migration** (what a host changes when it pulls 2.0.0):

1. `Guru.install(app, GuruSink.Share(), io = …, texts = …)` → `Guru.install(app, io = …,
   texts = …)`; drop the `GuruSink` import.
2. Rewrite the host's `GuruTexts`: `hint` ("double-tap: close · hold: share"), `actionClose`,
   `actionShare`, `saved(count)` ("saved to Download/Guru"); drop `actionContinue`/`actionSend`,
   `more` and `sent` **and delete their strings** (lint's `UnusedResources` fails on them).
   The hint compiles unchanged and would still say "continue · send · later".
3. Don't fix the compile errors by deleting the overrides: every member has an English
   default now, so a deleted one silently turns English.
4. Tests: the host's test of its `GuruTexts` (Verso's `VersoGuruTextsTest`) and tests that pin
   the install line (Verso's `SourceGuardTest`).
5. INTERNET: drop it if it was only there for `GuruSink.Acra`.
6. Rewrite whatever in the host describes the alert — discard, "later", nothing leaving the
   app (Verso: `README.md`, the freeze exception and persistence in `CLAUDE.md`, comments in
   `VersoApp`/`SourceGuardTest`/`VersoGuruTexts`): reports land in `Download/Guru` without
   asking (README, "Privacy").
7. The host's `io` now does a MediaStore insert and an fsync per pending report at every
   start, before the host's own first load if that is queued after `install`.
8. Install in the main process only.

## 1.0.1 — 2026-10-23

Checked on the A17: single tap, swipe and slow drag do nothing; still hold opens the
chooser; double-tap continues.

- Fix: any touch ending on the alert counted as a tap — a swipe, or a finger laid on a dimmed
  screen to wake it, discarded the reports, and a longer touch opened the share chooser.
  Continue now takes a **double-tap**; a hold sends only if the finger stays within the touch
  slop; a single tap or a swipe does nothing. TalkBack is unchanged (its own double-tap and
  long press act through the click actions).
- The default hint reads "double-tap: continue · hold: send · back: later". **Hosts with their
  own `GuruTexts` must update their hint** (the sample: "doppeltippen: weiter · …").
- 10 touch tests with real MotionEvents (123 in all).

## 1.0.0 — 2026-10-23

First release. Device test on the A17 (Samsung SM-A176B, One UI, Android 16) complete:
crash, ANR, share cancelled / app picked / Copy, and TalkBack in German.

- Moved out of Verso into its own repo, at the state of Verso's Guru patches 1 to 3
  (2026-10-23). Sources and the 83 JVM tests unchanged, except for two lint findings:
  `Setup` holds the `Application` as such (StaticFieldLeak), `GuruView` suppresses
  ViewConstructor with its reason.
- 25 Robolectric tests for `Guru.kt` and `GuruScreen` (108 in all).
- Fix: since Android 15 the share chooser also answers for "Copy" and "Edit", and that
  removed the reports. Now only an app picked (`ChooserResult` `SELECTED_COMPONENT`)
  removes them; the callback `PendingIntent` is mutable so the system's result arrives.
  5 more tests (113). Checked on the A17 (One UI, Android 16, the AOSP intentresolver
  chooser): "Copy" keeps the report, picking an app removes it; the previous build
  removed it on "Copy".
- CI: `./gradlew check` with JDK 21 on every push and pull request.
- TalkBack no longer reads doubled periods ("Software Failure.. Press").
- `sample/`: a tiny host for the device test — crash button, ANR button in debug builds,
  `GuruSink.Share`, its own `GuruTexts` with `values-de`.
