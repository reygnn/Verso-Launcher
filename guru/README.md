# Guru Meditation

```
┌──────────────────────────────────────────────┐
│              Software Failure.               │
│     Press left mouse button to continue.     │
│      Guru Meditation #80000001.4A3F21C0      │
│            saved to Download/Guru            │
└──────────────────────────────────────────────┘
          double-tap: close · hold: share        ← the host's words
```

A crash reporter for Android, vendored as source: no dependency, no permission, no
thread of its own. A crash is written to disk before the process dies. On the next start,
before anything is shown, Guru saves every report as a `.json` file in `Download/Guru/`;
then the app shows the Amiga alert to say so, once: as on the Amiga, a black strip at the
top — red text, the border blinking — and the app slid down under it, still to be seen, gliding
back up once the alert is closed.
**Hold** (right button) shares the saved files, **double-tap** (left button) or **Back**
closes the alert. It decides nothing: the reports are saved already.
A single tap or a swipe does nothing: a finger laid on a dimmed screen to wake it must not
close the alert unread. Moving past the touch slop cancels a double-tap and a hold alike.

**Save first, then tell.** Nothing waits on the user, so nothing is lost when no one
answers: an app that dies before its alert still has its reports in `Download/Guru/`. An
alert that cannot be built (the host's texts throw) is logged and left out; one that
crashes later, in layout or drawing, is left out at the next start — its crash, on the main
thread with its root cause thrown in Guru's own code, is saved like any other, but gets no
alert. No start loops on Guru's own alert. (A host's crash that merely passes through a Guru
class further down — R8 merges lambdas across packages — still gets its alert.)

Platform APIs and the Kotlin stdlib only, minSdk 36 like the rest of the family.

## What it catches

| Kind | Code | Source | Trace |
|---|---|---|---|
| Java/Kotlin crash | `#80000001` | `Thread.setDefaultUncaughtExceptionHandler`, at the moment of the crash | full stack trace |
| ANR | `#80000002` | `ApplicationExitInfo`, on the next start | the system's thread dump |
| Native crash | `#80000003` | `ApplicationExitInfo`, on the next start | description only (the tombstone is protobuf) |

A Java crash shows up in `ApplicationExitInfo` too; it is dropped when the handler wrote
a report within 10 s of it. On the very first start with Guru the exit history is only
marked, not reported: it predates Guru.

**The number.** `#80000001` is the kind with the Amiga's dead-end bit. The second half
is FNV-1a over the root cause's type and its first three frames, without messages and
line numbers: the same bug keeps its number from build to build. For an ANR, the main
thread's first three frames.

## The handler's rules

- One capture per process. A second crash meanwhile goes straight on.
- Whatever the capture throws (OOM, full disk) is dropped. The real crash reaches the
  previous handler unchanged: the runtime's own, which kills the process. Guru never
  decides how the process dies.
- Nothing but building the report and writing one file: the app's version and the
  device were read at install, so no binder call happens while dying.

## On disk

`filesDir/guru/`: one `guru-<time>-<id>.txt` per report (`key=value`, readable with
`adb`), written atomically (`.new` + fsync → rename → fsync dir) — only until the next
start saves it to `Download/Guru/`. At most 8, the newest. A crash loop cannot fill the
disk. Beside them: `installation-id`, `exits-seen` and `crashes-saved` (the times of the
crashes saved, the newest 8).

## Saving

At every start, in its second step on the host's `io` (after the look at the exit
records), Guru saves each pending report as a file of its own through
`MediaStore.Downloads`, in `Download/Guru/`: no permission, no manifest entry. The file is
the report's ACRA JSON, unchanged, named after the app, its version and the crash time in
local time — `Verso_Launcher-0.4.0-2026-10-24_14-03-12.json`. A character a file name should not
carry becomes `_`; the app's name is cut to 40 chars and the version to 24, so the name stays
under 255 bytes. MediaStore adds ` (1)` to a name already taken. The version is the crashed
app's, as `install` read it then.

Each file shows only once complete and synced (`IS_PENDING`, `fsync`). Once it is saved,
the file is the user's and Guru removes its own copy, report by report. A report whose file
cannot be saved stays for the next start; if none can be saved (storage full), no alert
comes, and the reports wait (8 at most). A death between saving and removing — or a copy
Guru cannot remove — repeats that report as a second file with the same `REPORT_ID`, which
the backend counts once. A power cut right after a save is the one gap left: the file's
bytes are synced, but whether MediaStore's own record of it survives is MediaStore's.

The times of the crashes saved are kept (the newest 8), so that an exit record of one of
them, if the system lists it only at a later start, is no second crash.

Of its own files in `Download/Guru/`, Guru keeps the newest 32 and removes the rest after
saving, so a crash loop cannot fill the folder; a hidden file of ours left by a save that died
halfway goes too (its report is still in the store and saved anew). Only files this app made are ever listed or
removed: files the user put there, another app's, or an earlier install's are not touched.
A file of Guru's that the user renames inside the folder still counts, and so does one of this
app's own files moved into it; moved out, a file is the
user's alone.

**The alert** then names the newest report's number and how many were saved — only what this
start saved, and once it has been seen, never again: a second `showPending` shows nothing.
An alert the activity loses before anyone answered it (destroyed before it came, or
recreated while it was up — dark mode, wallpaper colours, language) comes back with the next
`showPending`, the recreated activity's.
A hold offers the saved files in the share chooser — `ACTION_SEND`, or `ACTION_SEND_MULTIPLE`
for several, read access for the chooser (its preview) and the app picked, in a task of its
own. Only the files' URIs travel, so there is no size limit, and nothing in the chooser
decides about a report.

**Privacy.** Every report goes into shared storage at the next start, without asking: the
full trace (exception messages can carry names, paths, other apps' package names), an ANR's
dump of every thread (up to 256 KB), the device's brand, model and Android version, and the
installation's lasting `INSTALLATION_ID`. The files outlive an uninstall; after a reinstall
Guru can no longer see or remove them. A file manager, a PC over USB, an app with access to
all files or a sync tool can read them, and every host with Guru shares the one
`Download/Guru/`. To be rid of a report, delete its file.

To get the reports into the backend, share the files to guru2acra, or run the backend's
`tools/import_share.py` on them: `cat Download/Guru/*.json | python3 tools/import_share.py`.

**For receivers** (guru2acra, or any app the user picks): Guru shares `ACTION_SEND` (one
file) or `ACTION_SEND_MULTIPLE` (several), type `application/json`. The files are content
URIs in `EXTRA_STREAM` and in the ClipData, with read access; there is no `EXTRA_TEXT`, and
`EXTRA_SUBJECT` names the newest report. Each file is exactly one ACRA JSON object, UTF-8,
nothing before or after it. The same files shared from a Files app arrive the same way.

## The backend contract

The JSON in each file is what the family's own ACRA backend
([`reygnn/acra-backend`](https://github.com/reygnn/acra-backend), Flask + MariaDB) takes.
Checked against its source (2026-10-23):

- Endpoint `POST <server>/report`, basic auth with the **reporter** user. That user may
  only report; the ProGuard-mapping routes take a separate admin user that never goes
  into an APK.
- Required (missing → 400): `PACKAGE_NAME` and `STACK_TRACE`. It also reads
  `APP_VERSION_CODE` (for the ProGuard mapping), `APP_VERSION_NAME`, `ANDROID_VERSION`,
  `PHONE_MODEL` and `BRAND`; anything longer than its column is clipped, never refused.
- Guru reports — crash, ANR and native crash alike — are grouped by `PACKAGE_NAME`, the
  exception class from the trace's first line and `CUSTOM_DATA.GURU_MEDITATION`
  (Guru's number: the root cause and three frames, stable from build to build). That is
  why the number is in the JSON, and why ANRs and native crashes start
  `ApplicationNotResponding: …` / `NativeCrash: …`. Reports without the number (the ACRA
  library) are grouped by the `at` frames in the trace's first three lines.
- A known bug always gets 200. A resend with the same `REPORT_ID` (the 200 got lost)
  is answered 200 and not counted again.

The other fields (`REPORT_ID`, `INSTALLATION_ID`, `USER_CRASH_DATE` in
`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`) are what a stock ACRA server such as Acrarium requires.
Guru keeps writing them, so a report also posts to one of those.

## Texts

The Amiga lines are a quote and stay English. Everything else a user reads or hears
comes from the host: the hint under the box, the two TalkBack actions, and the line that
says where the reports went. These are passed as `GuruTexts` from the app's resources:

```kotlin
internal class AppGuruTexts(private val context: Context) : GuruTexts {
    override val hint get() = context.getString(R.string.guru_hint)
    override val actionClose get() = context.getString(R.string.guru_action_close)
    override val actionShare get() = context.getString(R.string.guru_action_share)
    override fun saved(count: Int) = context.resources.getQuantityString(R.plurals.guru_saved, count, count)
}
```

They are read each time the screen is built, so a language change in between is picked
up. Without `texts`: `GuruTexts.English`. Every member has a default (the
English one), so a host overrides only what it translates. A translated hint that is
longer shrinks to the width, like the alert lines; a longer "saved" line shrinks only itself,
never the Amiga lines.

## Release builds

R8 renames classes and strips line numbers: every trace becomes `a.b.c(Unknown
Source)`, and the Guru number changes with every build. In `proguard-rules.pro`:

```
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
```

Shrinking and optimisation stay on. Alternatively, keep `mapping.txt` per release and
run traces through `retrace`; Acrarium can do that itself with an uploaded mapping.

## Installing

Guru is vendored as source with `git subtree`, pinned to a tag — never as a library
(everything stays `internal`) and never from `main`:

```
git subtree add --prefix=guru https://github.com/reygnn/guru v2.0.0 --squash
```

The host compiles `guru/library/src/main/kotlin` as part of its app module (an extra
Kotlin source directory) and adds the same directory to detekt's `source`. Only the `.kt`
files arrive: Guru has no manifest and no resources. The host brings its own words as
`GuruTexts`.

An update is a pull of the next tag; `guru/` is never edited in the host. A fix goes
upstream first, gets a tag, and comes back with:

```
git subtree pull --prefix=guru https://github.com/reygnn/guru <tag> --squash
```

No `consumer-rules.pro`: the host decides about `-dontobfuscate` in its own ProGuard file
(see "Release builds").

Everything is `internal`. The host has two lines:

```kotlin
// Application.onCreate — io: the app's own single IO thread; texts: its own words
Guru.install(this, io = io::execute, texts = AppGuruTexts(this))

// first Activity.onCreate — onUi: the app's own way back to the main thread; it must run every
// block it gets, also for a destroyed activity: Guru checks and keeps the alert for the next call
Guru.showPending(this, ::onUi)
```

`io` must run tasks in submission order: the exit look, then the save, then the alert. The
save runs at every start before the host's own first load, if the host queues that after
`install`: a MediaStore insert and an fsync per report, none when nothing is pending.
Install in the main process only — every process that installs saves. The dialog dismisses
itself when its activity is destroyed.

**For hosts: the window slides.** While the alert is up, Guru owns the activity's
`window.decorView.translationY`: it slides the app down by the strip's height (top inset,
box, hint; at most the window's height), animated for about 220 ms unless the system's
animations are off. On every way out — double-tap, Back, hold, the activity's end — it puts
back the value it found, not 0: a host that uses `decorView.translationY` itself gets its own
value back. The dialog's window still fills the screen, see-through and undimmed below the
strip, so the alert stays modal and TalkBack stays with it. The system wallpaper is a window
of its own and does not move.

## Tests

`library/src/test/java`: 190 tests, JUnit 4, no mocks, each mutation-checked.

- **91 plain JVM tests.** Real files in a `TemporaryFolder`, and the real desktop
  `org.json` for parsing the JSON back (the library Acrarium reads it with).
- **99 Robolectric tests** for what the JVM alone cannot reach:
  - `Guru.install`: one handler, no receiver.
  - Exit records via `ShadowActivityManager`, an unreadable ANR dump too.
  - Saving at the start through a fake MediaStore provider: one file per report in
    `Download/Guru`, hidden until complete, removed from Guru's store once saved; a refused,
    failed or throwing save keeps that report; the newest 32 of Guru's own files stay, no
    one else's are touched; a crash saved still meets its late exit record.
  - The alert: only what this start saved, the newest number, once seen; closing decides
    nothing; lost before an answer it comes back; host texts that throw cost the alert,
    not the host; Guru's own crash on main leaves the next alert out.
  - Sharing: the chooser offers the files with read access in a task of its own; one that
    cannot start costs nothing.
  - `GuruScreen`: share acts once, TalkBack labels, closing with the activity; a long host
    line shrinks only itself; the hint's contrast.
  - The strip: the app slid down by its height, below the top inset, never taller than
    the window; see-through and undimmed below; animated, or a jump with animations off;
    the value before put back after a double-tap, Back, a hold, the activity's end; only the
    strip drawn black (native graphics).
  - Touch: a double-tap closes, a still hold shares, below the strip too; a single tap or a
    swipe does nothing.

`library/src/androidTest/java`: `GuruDownloadsInstrumentedTest` (5), on a device — what
MediaStore really does: the file under its name and readable back, ` (1)` for a name taken
(ours or someone else's), pruning that keeps the newest own files, never touches a file the
shell made, and clears a hidden row of ours left by a save that died halfway. Run it with `ANDROID_SERIAL=R5GL51D5VHZ ./gradlew :library:connectedAndroidTest`.

**On the device**, before every tag, with `sample/` — a tiny host that takes Guru as
source, like Verso. Its debug build has the buttons "ANR" and "Crash twice" and keeps the
plain application id, so install it with
`ANDROID_SERIAL=R5GL51D5VHZ ./gradlew :sample:installDebug`:

1. Start the app, tap "Crash", start it again: the alert shows `#80000001` and "saved to
   Download/Guru". The Files app shows `Download/Guru/Guru_Sample-<version>-<time>.json`
   (`Guru-Beispiel-…` in German).
2. Hold: the alert closes, the chooser offers the file and shows its name (the chooser's
   own read access). Cancel it. Restart: no alert; the file is still there.
3. Crash, start; double-tap: the alert closes. Restart: no alert.
4. Crash, start; while the alert is up, switch dark mode (or rotate): the recreated screen
   shows the alert again.
5. "Crash twice", start (it crashes again before Guru saves), start again: "2 reports saved
   to Download/Guru"; hold: both files offered together; pick mail (or guru2acra, once it
   takes `.json`), it receives both.
6. Press "ANR" and touch the screen until the system kills the app, restart:
   `#80000002`, the thread dump in the file.
7. TalkBack in German: the hint, both actions and the "saved" line are read in German.
   In every step: the strip at the top, the app slid down under it and, once the alert is
   closed, back exactly where it was — in portrait and landscape, and with "Remove
   animations" without the slide and the blink.
8. `ANDROID_SERIAL=R5GL51D5VHZ ./gradlew :library:connectedAndroidTest`: green.

## Verified

`./gradlew check` in this repo: the unit tests, lint (warnings as errors, no baseline)
and detekt (Verso's rules, no baseline), with Kotlin warnings as errors for main and test
sources, for `library/` and `sample/`; it compiles the on-device tests too. CI runs it on every push and pull request
(`.github/workflows/check.yml`). The build mirrors Verso's versions and rules, so what passes here compiles in a
host that pulls it.

## License

GPL-3.0, see `LICENSE`.
