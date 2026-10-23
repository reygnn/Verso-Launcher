# Guru Meditation

```
┌──────────────────────────────────────────────┐
│              Software Failure.               │
│     Press left mouse button to continue.     │
│      Guru Meditation #80000001.4A3F21C0      │
└──────────────────────────────────────────────┘
       double-tap: continue · hold: send · back: later   ← the host's words
```

A crash reporter for Android, vendored as source: no dependency, no permission, no
thread of its own. A crash is written to disk before the process dies; on the next
start the app shows the Amiga alert — black, red, the border blinking — and the user
decides: **double-tap** (left button) continues and discards, **hold** (right button) sends,
**Back** leaves it for the next start.
A single tap or a swipe does nothing: a finger laid on a dimmed screen to wake it must not
discard a report. Moving past the touch slop cancels a double-tap and a hold alike.

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
`adb`), written atomically (`.new` + fsync → rename → fsync dir). At most 8, the newest.
A crash loop cannot fill the disk. Beside them: `installation-id` and `exits-seen`.

## Sending

Chosen at install:

**`GuruSink.Share(mailTo = null)`** needs no permission. The reports, as ACRA JSON,
go via `ACTION_SEND` to an app the user picks. They are removed only once an app was
**picked**: `createChooser(intent, null, intentSender)` answers through an explicit
`PendingIntent` that carries the report ids, to a receiver that is not exported. A
cancelled chooser never answers; the reports stay for the next start. Nothing waits in
memory, so a process death in between keeps them too (safe side).

Since Android 15 the chooser also answers for its own actions, with a `ChooserResult`.
"Copy" answers, but does not count as handed over: only `CHOOSER_RESULT_SELECTED_COMPONENT`
removes the reports; Copy, Edit, unknown or no result keep them. That is why the
`PendingIntent` is mutable: the system fills the result in. A report kept wrongly costs a
tap on the next start; one removed wrongly is lost.

**`GuruSink.Acra(url, login, password)`** posts directly to an ACRA server. It needs
`<uses-permission android:name="android.permission.INTERNET"/>` and https. A report
stays until the server answers 2xx.

The server is the family's own ACRA backend (`~/PythonProjects/acra`, Flask + MariaDB).
Checked against its source (2026-10-23):

- Endpoint `POST <server>/report`, basic auth with the reporter user.
- Required (missing → 400): `PACKAGE_NAME` and `STACK_TRACE`. It also reads
  `APP_VERSION_CODE` (for the ProGuard mapping), `APP_VERSION_NAME`, `ANDROID_VERSION`,
  `PHONE_MODEL` and `BRAND`.
- Bugs are grouped by the exception class from the trace's first line plus the `at` frames
  among its first three lines; the message is left out. A known bug always gets 200,
  so a resend only raises its count.
- ANRs and native crashes have no frames there. For those the backend groups by
  `PACKAGE_NAME` and `CUSTOM_DATA.GURU_MEDITATION` (Guru's number) instead. That is why
  their trace starts `ApplicationNotResponding: …` / `NativeCrash: …` and why the number is
  sent.

The other fields (`REPORT_ID`, `INSTALLATION_ID`, `USER_CRASH_DATE` in
`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`) are what a stock ACRA server such as Acrarium requires.
Guru keeps sending them, so it also works with one of those.

The network call blocks the given `io` for up to 2 × 15 s per report.

## Texts

The Amiga lines are a quote and stay English. Everything else a user reads or hears
comes from the host: the hint under the box, the two TalkBack actions, "+N more", and
the toast. These are passed as `GuruTexts` from the app's resources:

```kotlin
internal class AppGuruTexts(private val context: Context) : GuruTexts {
    override val hint get() = context.getString(R.string.guru_hint)
    override val actionContinue get() = context.getString(R.string.guru_action_continue)
    override val actionSend get() = context.getString(R.string.guru_action_send)
    override fun more(count: Int) = context.resources.getQuantityString(R.plurals.guru_more, count, count)
    override fun sent(sent: Int, total: Int) = when { … }
}
```

They are read each time the screen is built or a toast is shown, so a language change in
between is picked up. Without `texts`: `GuruTexts.English`. A translated hint that is
longer shrinks to the width, like the alert lines.

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
git subtree add --prefix=guru https://github.com/reygnn/guru v1.0.0 --squash
```

The host compiles `guru/library/src/main/kotlin` as part of its app module (an extra
Kotlin source directory) and adds the same directory to detekt's `source`. Only the `.kt`
files arrive: Guru has no manifest and no resources. The host declares INTERNET itself if
it uses `GuruSink.Acra`, and brings its own words as `GuruTexts`.

An update is a pull of the next tag; `guru/` is never edited in the host. A fix goes
upstream first, gets a tag, and comes back with:

```
git subtree pull --prefix=guru https://github.com/reygnn/guru v1.0.1 --squash
```

No `consumer-rules.pro`: the host decides about `-dontobfuscate` in its own ProGuard file
(see "Release builds").

Everything is `internal`. The host has two lines:

```kotlin
// Application.onCreate — io: the app's own single IO thread; texts: its own words
Guru.install(this, GuruSink.Share(), io = io::execute, texts = AppGuruTexts(this))

// first Activity.onCreate — onUi: the app's own way back to the main thread
Guru.showPending(this, ::onUi)
```

`io` must run tasks in submission order (the exit look comes before the screen). The
dialog dismisses itself when its activity is destroyed.

## Tests

`library/src/test/java`: 123 tests, JUnit 4, no mocks, each mutation-checked.

- **83 plain JVM tests.** Real files in a `TemporaryFolder`, a real HTTP server on loopback
  for `AcraPost`, and the real desktop `org.json` for parsing the JSON back (the library
  Acrarium reads it with).
- **40 Robolectric tests** for what the JVM alone cannot reach:
  - `Guru.install`: one handler, the receiver registered only for `Share`.
  - Exit records via `ShadowActivityManager`.
  - `showPending`: tap, Back, a destroyed activity.
  - The chooser and its answer: only an app picked removes; Copy, Edit, unknown or no
    result keep the reports.
  - `GuruScreen`: continue and send act once, TalkBack labels, closing with the activity.
  - Touch: a double-tap continues, a still hold sends; a single tap or a swipe does nothing.

**On the device**, before every tag, with `sample/` — a tiny host that takes Guru as
source, like Verso. Its debug build has an ANR button and keeps the plain application
id, so install it with
`ANDROID_SERIAL=R5GL51D5VHZ ./gradlew :sample:installDebug`:

1. `adb shell am crash com.github.reygnn.guru.sample`, start the app: the alert shows
   `#80000001`.
2. Hold, cancel the chooser, restart: the alert comes back.
3. Hold, pick an app, restart: no alert.
4. Hold, "Copy", restart: the alert comes back. (One UI has its own share sheet: if
   picking an app never removes the reports there, that is the safe side, but it belongs
   in the caveats.)
5. Press "ANR" and touch the screen until the system kills the app, restart:
   `#80000002`, the thread dump in the report.
6. TalkBack in German: the hint and both actions are read in German.

## Verified

`./gradlew check` in this repo: the unit tests, lint (warnings as errors, no baseline)
and detekt (Verso's rules, no baseline), with Kotlin warnings as errors for main and test
sources, for `library/` and `sample/`. CI runs it on every push and pull request
(`.github/workflows/check.yml`). The build mirrors Verso's versions and rules, so what passes here compiles in a
host that pulls it.

## License

GPL-3.0, see `LICENSE`.
