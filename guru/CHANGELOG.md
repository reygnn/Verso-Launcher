# Changelog

Every tag has an entry here, newest first. Versions follow SemVer; what counts as major,
minor or patch is in `CLAUDE.md`, "Versions and releases".

## 2.1.2 — 2026-10-25

From the device test of 2.1.1. Checked on the A17: the process killed while the alert was up,
One UI's dialog closed 45 s later — two exit records for the pid, one file saved; a plain crash
and "Crash twice" still give one and two reports; the on-device tests 5/5.

- Fix: One UI keeps a crashed process alive behind its "keeps stopping" dialog and, when it
  is closed, records a second crash for the same pid — a minute later, beyond the 10 s in
  which a Java crash meets the handler's report. That record became a second report, without
  a thread, its trace "Crash: crash", its own `REPORT_ID`. An exit with the pid of the exit
  just before it (any reason) is now no report; the mark still moves past it.
- 205 tests.

## 2.1.1 — 2026-10-25

From Verso's audit 2026-10-24, B.9 and the rest of B.10. Checked on the A17: the process killed
while the alert was up — the next start saved its report and showed no alert; the other steps
of the device test as before (TalkBack not listened to, the app picked for two files not
checked); the on-device tests 5/5.

- Fix: the prune knew Guru's files only by folder and owner — a file of the app's own moved
  into `Download/Guru` (a Verso backup) counted among the 32 and could be removed. Now only a
  name Guru gives counts: ending in the crash time and `.json`, MediaStore's ` (n)` allowed.
- Fix: `diedInGuru` sees only a Java crash on main; a native crash or an ANR caused by the
  alert came back at every start. While the alert is up, a mark (`alert-up`) lies in Guru's
  store; a start that finds it with new reports saves them and shows no alert. The mark is
  cleared on every way out, and an older alert's dismissal leaves a newer one's. An older Guru
  ignores the file.
- 200 tests.

## 2.1.0 — 2026-10-24

**The alert as on the Amiga.** Checked on the A17 with the sample and with Verso (daily-driver
build): before, a black full-screen dialog with the box at the top and nothing of the app; now a
black strip at the top — box, blinking border, hint — and the app slid down under it, seen
below; after a double-tap, a hold (chooser cancelled) and in landscape the app is back pixel for
pixel where it was; with "Remove animations" no slide and no blink; TalkBack reads the alert as
before.

- The alert is a black strip at the top, as high as box and hint need (below the top inset,
  at most the window); below it the dialog's window is see-through and undimmed, the app slid
  down by the strip's height (`decorView.translationY`, about 220 ms, a jump with animations
  off). The alert stays modal: the window still fills the screen, every touch and TalkBack
  stay with it. Gestures, texts, saving and sharing are unchanged.
- **For hosts:** while the alert is up, Guru owns the activity's `decorView.translationY` and
  puts back the value it found on every way out (README, "For hosts: the window slides").
- Fix: `diedInGuru` counted any Guru frame in the trace. In a host's R8 release, a host lambda
  can be merged into a Guru class — Verso's `~crash` went through `GuruScreen$show$5` — so a
  host's own crash lost its alert. Now the root cause's first frame that is not the platform's
  decides.
- 190 tests.

## 2.0.2 — 2026-10-24

From Verso's review (its audit 2026-10-24, B.8–B.13). Checked on the A17: the border blinks,
the on-device tests 5/5, a hidden row of ours cleared by the prune.

- Fix: `showPending` took the saved list on io even for an activity already finishing or
  destroyed — recreated during the save, the old instance took it from the new one, and the
  alert came only with a later `onCreate`. Such an activity now takes nothing; the next call
  gets the alert. A host's `onUi` must run every block it gets (README, KDoc).
- Fix: a save that died between insert and publish left a hidden MediaStore row that nothing
  ever removed — one per start in a crash loop. The prune now lists pending rows too and
  clears its own.
- Fix: a window narrower than the alert's padding (split screen) made a negative text size;
  every size is now at least 1 px.
- The border blinks only while the alert can be seen: a launcher in the background is no
  longer woken every 700 ms.
- A crash time that cannot be kept is logged.
- Tests: only the newest report decides whether Guru died; the rest above. 176 tests; on the
  device 5.

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
