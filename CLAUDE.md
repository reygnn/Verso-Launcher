# CLAUDE.md

Project conventions for **Verso Launcher** — a plain-text Android launcher: a quiet
home page (clock, date, battery, favorites) in front, Sigil's command-line drawer
behind it.
Claude Code reads this file automatically at session start. Keep it short
and actionable — not a marketing description (that's `README.md`).

For the working agreement, see `WHY_CLAUDE.md`. For test conventions, see
`app/src/test/CLAUDE.md` and
`app/src/test/java/com/github/reygnn/verso_launcher/TESTING_CONVENTIONS.kt`.

Cross-project conventions (stack baseline, git workflow, test philosophy,
code language) live in `~/.claude/CLAUDE.md` and are loaded automatically.
This file only holds what is specific to this project.

---

## Scope

A launcher reduced to text. Front page ("recto"): clock, date and battery (each a
double-tap target), the favorites as large text, swipe up for the drawer, pull down for
the recently used apps (a HUD from the top). Back page ("verso"): Sigil Launcher's list
plus command line — search, the edit sigils (`-` hide, `!` favorite, `!!` reorder,
`#`/`##` tags, `?` recents, `%` drawer order A–Z ↔ most used) and the `~` commands
(`~restart`, `~backup`, `~restore`, `~restore-saf`, `~cleanup`; `~crash` in a daily-driver
build). Every change is saved at
once — there is no `~save`; `~restart` resets the process's state (`Core`) and recreates
the activity, so the config is read afresh (a recreation alone — a language, font or display-size
change — keeps what was loaded). Default favorites (phone, messenger, email, browser, camera) are seeded once, on a
fresh install.

Deliberately NOT: icons, widgets, notification badges, a settings screen (the look
is constants in `HomeStyle.kt`), left/right swipe actions, any runtime permission.
Apps come from `LauncherApps` across all profiles (work, clone); apps of a paused
profile and archived apps stay listed, dimmed (`AppStatus`), and a first tap brings
them back; private space is not reachable (no `ACCESS_HIDDEN_PROFILES`). See
`CAVEATS.md`.

**The feature freeze (in force 2026-10-18 to 2026-10-24, since 0.2.12) was lifted by the
user on 2026-10-25.** New user-facing features, sigils, commands and settings are allowed
again. What still bounds them is the scope above (the *Deliberately NOT* list, no production
dependencies per hard rule 1, no settings screen), and the standing bar that a change must
not regress anything.

The two features admitted during the freeze are now simply part of the app:

- **Guru**, the crash reporter. After a crash, the next start saves the report to
  `Download/Guru` without asking and shows Guru's alert saying so (hold: share the saved
  files with an app the user picks, double-tap or Back: close). It needs no permission and
  sends nothing on its own.
- **`~crash`**, a deliberate crash for testing Guru and the crash-only paths — only in a
  daily-driver build (debug, or a release built with `-PdailyDriver`), compiled out of a
  public release, never abbreviated (`~c` stays `~cleanup`).

Verso replaces Sigil Launcher, which it was cloned from. Sigil is discontinued (no
longer maintained, its GitHub repo removed, October 2026) — never port anything back,
and there is no Sigil compatibility to keep: its only user has moved over. Kolibri
Launcher is discontinued too (October 2026); Verso is the official successor of both.

---

## Stack particulars

This project deviates from the family baseline on purpose — the point of the app
is a release APK with **no dependencies**: Kotlin stdlib and platform APIs only.

- **No Compose, no Material 3, no AndroidX** in the APK. Plain platform Views,
  built in Kotlin code (no XML layouts). Theme parent is the platform
  `Theme.Material.NoActionBar` with the wallpaper shown through the window.
- **No coroutines / Flows / ViewModel.** One process-wide single-thread executor
  (`VersoApp.io`) does all disk and PackageManager work; results come back to the process's
  main thread (`VersoApp.onMain`) or, for what belongs to one window only, to that instance
  (`MainActivity.onUi`). `LauncherState` plays the ViewModel role as plain Kotlin, one per
  process (`Core`), shared by every activity instance.
- **Persistence:** one JSON file, `filesDir/verso.json`, via `ConfigStore` — not
  SharedPreferences, not DataStore, but on SharedPreferences' terms (hard rule 4; a
  `verso.json.bak` while a write is under way or after one failed). Beside it only `home-cache.txt` (`HomeCache`), a
  disposable copy of the favorites for the cold-start frame, `usage.txt` (`UsageLog`), the
  launch counts behind the `%` order, and `recents.txt` (`RecentsLog`), the `?` list —
  disposable too, never in a backup — and `filesDir/guru/`, Guru's: at most 8 crash
  reports waiting for the next start, `installation-id`, `exits-seen`, `crashes-saved` and, while
  its alert is up, `alert-up`, never in a backup and excluded from a device transfer (`data_extraction_rules.xml`). Off-device backups in
  `Download/Verso/` via MediaStore (`BackupStore`); Guru saves its reports to
  `Download/Guru/` the same way (its own newest 32 kept).
- **Tests:** JUnit 4, the real `org.json` on the JVM classpath (android.jar's is a
  stub), Robolectric where a real widget is needed, **no MockK / no mocks** (fakes
  and manual queues). An `androidTest` set (androidx.test, test APK only) covers
  MediaStore and Android's own `org.json`.
- **Warnings are errors** — Kotlin (`allWarningsAsErrors`, main and test), lint
  (`warningsAsErrors`) and detekt (default rules, adjusted with reasons in
  `config/detekt/detekt.yml`; part of `check`). A deliberate finding gets a `@Suppress` at the
  narrowest scope with its reason — e.g. an IO path's broad catch (hard rule 2):
  `@Suppress("TooGenericExceptionCaught")` — on the `try` itself (`val x = @Suppress(…) try`)
  where the function does more than the try, so a catch added later is still flagged (detekt
  ignores one behind `return`) — the reason as a comment on the line above (a comment behind
  the annotation becomes part of detekt's baseline IDs). A deliberate setting
  is configured, not baselined (detekt.yml; lint's `disable` and `tools:ignore`).
  `app/lint-baseline.xml` and `app/detekt-baseline.xml` hold only the debt found at switch-on
  and only ever shrink.
- **Debug build** has `applicationIdSuffix = ".debug"`, so `connectedAndroidTest`
  installs beside the family-signed release on the device.
- **Guru (`guru/`)** is a `git subtree` of [reygnn/guru](https://github.com/reygnn/guru),
  pinned to a tag, compiled into `:app` as an extra Kotlin source directory and checked by
  detekt like Verso's own code. **Never edit `guru/` here**: a fix goes upstream, gets a
  tag, and comes back with
  `git subtree pull --prefix=guru https://github.com/reygnn/guru <tag> --squash`; the
  commit names the tag. Its own rules are in `guru/CLAUDE.md`; its tests run in its repo,
  not here. Release builds keep names and line numbers (`-dontobfuscate`, `proguard-rules.pro`)
  so a report's trace is readable and its Guru number stable from release to release.
- **Daily-driver build:** `./gradlew bundleRelease -PdailyDriver` (debug builds always) compiles
  in the personal-only surfaces — today only `~crash`. `DailyDriver.ENABLED` comes from one of
  two twin files (`src/dailyDriver/kotlin`, `src/public/kotlin`) picked per build type in
  `app/build.gradle.kts`, not from BuildConfig: every buildFeature stays off. A plain
  `bundleRelease` is the public build (for GitHub). The two never share a version, so each
  mapping is filed under its own: the public build has an even versionCode, the daily-driver
  build of the same release the odd one above it and `-daily` in its name (`0.4.2` = 24,
  `0.4.2-daily` = 25). A release bump is versionName +0.0.1, versionCode +2.
- **Release mapping:** after every `assembleRelease`/`bundleRelease` the R8 mapping is kept in
  `~/apk/mappings/verso/<versionName>-<versionCode>/` (Gradle property `verso.mappingArchive`
  moves it) and uploaded to the ACRA backend (`uploadProguardMapping`), so a release trace can be
  retraced — R8 inlines even with `-dontobfuscate`, which stays (the Guru number is computed on
  the device from the frame names). The script, `app/acra-scripts/upload_mapping.sh`, is not in
  the repo; it takes the backend's ADMIN credentials from the environment or
  `~/.config/acra/admin.env`. Without it the build warns; a failed upload fails it.

---

## Look

No Compose, no Material 3, so no `MATERIAL3RULES.md` (the family's Part A is Compose-only and
contradicts choices made here). The UI is text right on the user's wallpaper; colour comes from
contrast, not from a scheme:

- **One text colour for home and drawer:** `HomeStyle.palette` (white on a dark wallpaper, black
  on a light one, from `WallpaperColors.HINT_SUPPORTS_DARK_TEXT`), with the tonal outline
  `HomeStyle.tonalOutlineColor` for any wallpaper. Rows, prompt, hints, ×, cursor and selection
  all use it; hints are set apart by italics; system-bar icons dark on a light wallpaper. Pressed
  favorites: `HomePage.pressableText`. Charging / battery-protection marks: outlined vector
  drawables (`ic_bolt_*`, `ic_shield_*`).
- **Colour constants live in `HomeStyle.kt`**, never as hex across views. The one exception is
  `MainActivity.DIALOG_TEXT`, the light field text of the dark dialogs.
- **Theme parent `@android:style/Theme.Material.NoActionBar`, on purpose** (2026-10-13): the
  window is a transparent wallpaper surface and every colour and typeface is set in code, so the
  parent only decides the command line's cursor and handle accent. The rename and `~cleanup`
  dialogs pin `Theme_Material_Dialog_Alert`, dark on every device, as `DIALOG_TEXT` requires —
  moving them to DeviceDefault needs that colour tied to the theme and a check on a Pixel and a
  Samsung.
- **No `values-night/`, no splash attributes, no dynamic colour:** the window is always the
  wallpaper, a HOME app shows it at once, and there is no surface to tint. The launcher icon is
  an adaptive icon (background, foreground, monochrome) in `drawable/ic_launcher.xml`, no
  `mipmap-anydpi-v26` and no separate round icon.

---

## Architecture

Single `:app` module, one package:

```
com.github.reygnn.verso_launcher/
  VersoApp            process-wide: the IO executor, Core, Commands, Results, the command
                      gate, the pending ~restore-saf pick, the live window (onCreate–onDestroy,
                      a LauncherWindow), the ProcessState (recents, launches, the seed flag,
                      the queued results of ~backup, restore, cleanup and a failed save),
                      palette and badge-label caches;
                      installs Guru (onCreate)
  VersoGuruTexts      Guru's words from Verso's resources (guru_* in values, values-de)
  Core                the process's data: the one LauncherState and its stores (ConfigStore,
                      BackupStore, Apps), built from the Application; reset by ~restart
  LauncherStateParts  what LauncherState is built from: ProcessState, ConfigFile,
                      RecentsFile, UsageFile
  MainActivity        the window: what a command is refused for up front, the picker,
                      ~restart, the cleanup dialog, edits, launching; reached by the process
                      only as a LauncherWindow (a private object, VersoApp.instance)
  Commands            the "~" commands' work, process-wide: ~backup, ~restore, a ~restore-saf
                      pick's read and adoption, the ~cleanup check and run (Commands.step)
  Results             their results, queued until a window is in front (ProcessState.messages)
  LauncherWindow      what the process needs of the window: in front, show, the cleanup offer
  PageTurn            the page turn (hard rule 11): window root, drag, settle, Back
                      preview, the status bar per page
  Apps                the apps on the device (IO side): enumeration of every profile,
                      ~cleanup's "still there?", default favorites
  HomePage            the front page (clock/date/battery, favorites, a11y actions)
  AppDrawer           the back page: list, empty-list hint, command line and its bar;
                      what the list shows (mode, rows) and the stale-tap guard; its widgets
                      are private, callers ask by intent (clearPrompt, scrollToTop, pickUp)
  HomeStyle           pure look constants + outline colour, palette, BatteryInfo,
                      DoubleTapGate
  OutlinedViews       the outlined text/icon views home and drawer draw with
  Dp                  Context.dp, the one dp → px conversion the views share
  WallpaperPalette    the palette for the wallpaper: asked once on the IO thread, then the
                      colour listener; the newer of the two wins (pure)
  WallpaperSystem     its system side: the question, watch/unwatch
  HomeCache           the favorites cached for an instant home page on a cold start
  UsageLog            the "%" order's launch counts (Kolibri's decay) and usage.txt
  RecentsLog          the "?" list's recents.txt
  DisposableFile      writeDisposable: temp + rename for the three files above, no fsync
  SwipeFrame          window root; watches dispatchTouchEvent for the page-turn swipe
  SwipeGesture        pure gesture decisions: Unity's page drag + Kolibri's flick
  TapGuard            stale-click protection for list rows
  Guard               hard rule 2's backstop around every IO-thread and SAF-thread task;
                      beside it logWarning, the one warning logger (tag "Verso")
  RenameDialog        long-press dialog: custom name + tags
  LauncherState       config + app list + recents, and the concurrency rules (pure)
  CommandGate         the "~" commands one at a time: a run ends with its last step (pure)
  PendingPick         a "~restore-saf" pick from the picker's answer to its adoption (pure)
  LauncherLogic       all pure decisions: modes, commands, search/fold, sort, rows,
                      Enter/tap actions, tags, default favorites
  AppEntry, VersoConfig   the data; a VersoConfig is immutable (an edit is a new one)
  ConfigJson          verso.json <-> VersoConfig (parse / parseForeign / serialize)
  ConfigStore         verso.json on disk, on SharedPreferences' terms (.bak)
  BackupStore         Download/Verso via MediaStore
  BackupFiles         pure backup naming rules
  RestoreSteps        the IO half of a restore, in its load-bearing order
  Cleanup             pure "~cleanup" decisions: what may be offered (CleanupPlan)
  DailyDriver         ENABLED: true in a daily-driver build, false in the public one (twin
                      files in src/dailyDriver and src/public, not in main)
```

Data flow: a `LauncherApps.Callback` (install/uninstall/update) or a profile broadcast →
`MainActivity.appsMaybeChanged` (refresh now while in front, else mark dirty); `onResume`
refreshes when dirty or when `LauncherState.needsRefresh` → `LauncherState.refresh()` → IO
thread (load config once, enumerate apps of every profile) → main-thread hand-off guarded
by the load generation (newest wins) → `AppDrawer.refilter` re-renders. An enumeration carries no
config: it lists system labels only, and the custom names are applied on the main thread
from the live config (`LauncherLogic.named`, at the hand-off and after every change to the
names). Every mutation → `LauncherState.mutated()` → serialized on main, written on the IO
thread.

---

## Hard rules

1. **No production dependencies.** Anything added to `dependencies {}` must be
   `testImplementation` or `androidTestImplementation`. New deps need the user's OK — so do
   new build plugins (detekt was approved), though they never reach the APK.
2. **A HOME app must never throw.** Every IO-thread path (enumeration, default
   favorites, config and backup I/O) catches broadly and degrades; an uncaught
   exception on the executor kills the process on every start. Crash-only beyond that:
   a catch is armour only where it breaks a crash loop (`Guard` around every IO-thread
   and SAF-thread task, an unreadable `verso.json` loaded as empty) or protects
   `verso.json` (its write); disposable work that is rebuilt on the next start may
   crash. The other catches are correctness or UX, not armour: the enumeration's (`Apps`)
   decide what `~cleanup` may trust and keep the last good list, the backup ones turn a
   failure into its own message. Main-thread catches always log, and where the user acted
   say what failed — never swallowed, no catch-all net.
3. **One IO thread, process-wide** (`VersoApp.io`). Never create another executor,
   never shut it down. Main-thread state is mutated only on the main thread. What belongs to
   the process is handed back with `VersoApp.onMain`, never dropped: the state's hand-backs
   (`Core`; dropped only once `~restart` replaced that state), every command step's
   (`Commands.step` — the gate must be released and a restore's `adopt` must reach the shared state,
   or memory and `verso.json` part ways), the gate's `onIdle` and a `~restore-saf` read's. What
   touches a window goes to the live one (`VersoApp.instance`, a `LauncherWindow`, if any —
   process code sees no Activity); results wait in `Results` until one is in front. Only what belongs to one window goes through `onUi`, which drops it
   for a destroyed instance (the home cache's first frame, the wallpaper palette). And Guru's alert
   (`showPending`), handed back with a plain `runOnUiThread`: Guru checks the destroyed
   activity itself and keeps the alert for the next call, which a dropped hand-back would lose. The one exception: the
   document a `~restore-saf` pick points at is read on its own short-lived thread — a
   content provider (one slow to hand the file over, or that never does) can block that read indefinitely, and it
   touches no file of the app's own, nor anything of the activity (the application's resolver,
   a hand-back to the process, `Commands.readPick`), so a read that never returns keeps no
   destroyed instance alive; the undo
   point and the adoption still go through
   `VersoApp.io`. The second exception: Guru's uncaught-exception handler writes its crash
   report on the dying thread itself — the process is going down, nothing queued on
   `VersoApp.io` would run. That write touches only `filesDir/guru/`; everything else Guru
   does (the exit records, saving each report to `Download/Guru` — a MediaStore insert and an
   fsync per report and the prune to its newest 32, queued at `install` and so ahead of Verso's
   first load —, the alert's list) goes through `VersoApp.io` — but for Verso's own version and
   label, which `install` asks once on the main thread (both caught), so the dying thread never
   asks the PackageManager. Not a breach to "fix".
4. **`verso.json` on SharedPreferences' terms** (`ConfigStore`). A write moves the last
   complete `verso.json` to `verso.json.bak` (a `.bak` already there is kept and `verso.json`
   deleted), writes and fsyncs `verso.json`, then deletes the `.bak`; a failed write deletes
   its half file and leaves the `.bak`. A load first takes a `.bak` back (the write before it
   was cut off; if that rename fails, the `.bak` is read in place), then reads and parses; a file that cannot be read or parsed loads as the
   empty config — logged, nothing more, and the next edit writes over it. The net is the
   backups, never deleted (hard rule 6). Fresh = neither file.
5. **No edit, no save, no `~` command (but `~restart`) before the config is loaded** — until
   then memory holds only the empty default, which must never be written over the real file
   or a backup. `LauncherState`
   issues no `EditPermit` (every edit takes one, so an edit path without the check does not
   compile, and only `LauncherState.editPermit` builds one — `SourceGuardTest`) and refuses
   saves until then; the Activity says why (`editPermit()`,
   `editable()` for the commands). Backups and undo points come from memory only
   (`loadedSnapshot`). Home is drawn and its cache written only from a loaded config.
6. **Backups on MediaStore's terms: every backup is a new file, and Verso never deletes
   one.** Each `~backup` and each undo point (`verso-pre-restore-…`, `verso-pre-cleanup-…`)
   is a pending row asking for its timestamped name (`verso-<date>_<time>.json`), written and
   fsynced, then published — MediaStore settles the name then (a taken one gets " (n)"); a
   failure removes only its own pending row. MediaStore owns the namespace: a backup is recognised by pattern,
   a restore takes the newest own row by `_ID`, never a fixed name. Clearing out old files is
   the user's, with a file manager.
7. **Restore order:** read + `parseForeign` off the main thread (never the lenient `parse`
   for outside files) → for a `~restore-saf` pick, the user's OK on what it sets (hard rule 15)
   → if it equals the current config, stop (nothing to undo) → write the undo point → write
   the restored config to `verso.json` in the same IO task (the restore must survive a lost
   hand-back; a failed write adopts nothing) → `adopt` (memory only, no second write). No undo
   point, no restore.
8. **Config keys are `package/class`** (`ComponentName.flattenToString()`) for the
   personal profile, so configs and backups written by earlier Verso versions stay
   readable; an app of another profile is `package/class#<profile serial>`
   (`AppEntry.keyOf`). Launch and app info go through `LauncherApps` with the entry's
   profile. Don't change the key or the JSON shape without a migration.
9. **`LauncherLogic.foldLabel` is the only fold** (NFC, `Locale.ROOT`, drop U+0307)
   for labels, needles and tags (`canonicalTag` builds on it). Lists are sorted with
   a `Collator` for the device locale (`sortedForDrawer`, `sortedTags`), never by
   code point.
10. **Default favorites only on a fresh install** (`ConfigStore.isFresh`: no `verso.json`, no
    `.bak`)
    and only into a config with no favorites. An existing config with no favorites is the
    user's choice. A fresh install whose first enumeration has no list waits for the first one
    that has (complete or not), process-wide (`ProcessState.seedPending`), so a recreation after
    an edit still seeds. The seeding is an edit: it takes an `EditPermit`, so while a `~` command
    runs it waits (a resume retries, `needsRefresh`). A favorite toggled or a restore written
    (`writeRestored`, in the restore's IO step) ends the wait.
11. **Page turn = Unity-Kolibri's.** Closing the drawer is a drag that follows the finger
    (Unity's `DragToDismissCore`: starts once the list is at its top, 28 % of the height
    or a 3× min-fling release commits, a flick back cancels, 220 ms settle); opening is
    the same drag upward from home (an extension — Unity only flicks it open). The recents
    (`?`) are the same drawer from the other side, a HUD: dragged down from home (not from
    the 48 dp top band), closed by a drag up once the list is at its end; the drawer
    remembers its side (`drawerFromTop`) and closes toward it — until the prompt leaves
    `?` (×, typing): then it is the plain drawer and goes back down. The flick (4× touch
    slop, 1.2 px/ms average, 1.5× dominance, 48 dp top band) remains as the open fallback.
    All of it lives in `SwipeFrame.dispatchTouchEvent`, never `onInterceptTouchEvent` (a
    scrolling list disables intercept mid-gesture); the decisions are pure in
    `SwipeGesture`. Details that are easy to break: a second finger cancels a drag; a drag
    can grab a page still moving and continues from there; an open dialog (rename,
    `~cleanup`) blocks both drag and flick; a page turned some other way (HOME, Back,
    screen off) aborts a running drag without a release decision (`abortDrag`).
12. **Status bar hidden on the home page only**, shown in the drawer. The home page
    pads for it regardless of visibility (`getInsetsIgnoringVisibility`).
13. **`density` stays out of `configChanges`**: a display-size change must recreate
    the activity so every dp-derived pixel value is recomputed.
14. **`~cleanup` removes only what a COMPLETE enumeration shows gone from the device**
    (`Cleanup.plan`): every profile and app read; a profile that answers empty keeps its
    entries; a key no app can have (`AppEntry.parseKey` null) is gone; otherwise the
    system is asked — the exact activity in the personal profile, the package in another
    one — and anything still there in any form (disabled, icon alias off, unmounted,
    removed only for the user, in a profile not listed) is never offered. Only after the
    user confirms the counts; then on the IO thread a re-check, the undo point
    (`verso-pre-cleanup-…`), and — unless the config was replaced meanwhile — the removal
    of what was confirmed AND is still gone (`LauncherState.cleanup`). No undo point, no
    cleanup.
15. **One `~` command at a time, process-wide** (`VersoApp.gate`, a `CommandGate`): while one
    runs — also one a recreated predecessor started — a second is refused with
    a toast and stays in the prompt, and so is an edit — `LauncherState`'s edits take an
    `EditPermit`, made only from a `CommandGate.Idle` and void once a run has started since,
    so an edit path without the check does not compile — a save queued behind a restore's IO
    step would write the old config back over it. Nothing else is held back — no layer, no
    cancel; launching, typing and page turns go on. A run ends with its last step, never by
    a "done" call: every step holds it and releases it on every path (`CommandGate.io`
    catches what its work throws; `LauncherState.cleanup` reports from a `finally`), and every
    step hands back to the process (`Commands.step`; a cleanup through its `LauncherState`, dropped
    only after a `~restart` reset, which starts only with no run going), so a run ends even when
    the instance that started it is gone; `CommandGate.io` queues nothing for a run that is over. A result is queued (`ProcessState.messages`) and shown once an instance
    is in front; a refusal ("busy", "not loaded") is shown in front or dropped, never queued. No run
    spans another activity or a dialog waiting on the user: a `~restore-saf` pick is the
    process's (`VersoApp.pick`, `PendingPick`) from the picker's answer until its adoption
    starts — read before any run, then asked about (a dialog with its counts, shown by the window
    in front once the config is loaded: a picked file may be one another app planted; Cancel ends
    the pick quietly, leaving the dialog unanswered ends it with a toast), then, once confirmed,
    waiting for an idle gate and a loaded config, adopted by whichever instance is alive; only
    the adoption is a run. Meanwhile `~restore` and
    `~restore-saf` are refused (the adoption right behind it would replace what it brought),
    everything else goes on; `~restart` drops the pick, with a toast. The cleanup dialog
    ends its run, its OK starts a new one.
16. **The process owns the data; an activity is a window.** `Core` (the one `LauncherState`
    and its stores), `Commands`, `Results`, the gate and the pending pick live in `VersoApp`,
    built from the Application alone. They reach the window only as a `LauncherWindow`
    (`VersoApp.instance`: a private object of `MainActivity`, set in `onCreate`, cleared in
    `onDestroy`) — never an Activity or its Context: their constructors take `VersoApp`, the
    window's own parts stay in `MainActivity`, and `SourceGuardTest` scans their files for
    `MainActivity` and `android.app.Activity`. Nor do they keep a `LauncherWindow` past the call
    it came with (a `~cleanup` answer compares a number, `Commands.pageTurns`), so nothing of the
    process keeps an instance alive or needs one to land. A recreation shares the loaded state
    and draws it in `onCreate`; only `~restart` resets it (`Core.reset`). What a window decides
    stays in `MainActivity`: refusing a command up front, the prompt, the picker, `~restart`,
    the dialogs, launching.

---

## What this file is NOT

- Not a description of the project (see `README.md`).
- Not a TODO list.
- Not the place for transient refactor notes — those belong in commit
  messages on short-lived feature branches.
- Not the full testing reference (see `app/src/test/CLAUDE.md` and
  `TESTING_CONVENTIONS.kt`).

Update this file when an architectural rule changes or a hard-won lesson
deserves to be future-proofed. Do not bloat it with details that are
obvious from reading the code.
