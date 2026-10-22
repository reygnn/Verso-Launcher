# CLAUDE.md

Project conventions for **Verso Launcher** — a plain-text Android launcher: a quiet
home page (clock, date, battery, favorites) in front, Sigil's command-line drawer
behind it.
Claude Code reads this file automatically at session start. Keep it short
and actionable — not a marketing description (that's `README.md`).

For the working agreement, see `WHY_CLAUDE.md`. For the (deliberately absent)
Material 3 rules, see `MATERIAL3RULES.md`. For test conventions, see
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
(`~restart`, `~backup`, `~restore`, `~restore-saf`, `~cleanup`). Every change is saved at
once — there is no `~save`; `~restart` recreates the activity, which reads the config
afresh. Default favorites (phone, messenger, email, browser, camera) are seeded once, on a
fresh install.

Deliberately NOT: icons, widgets, notification badges, a settings screen (the look
is constants in `HomeStyle.kt`), left/right swipe actions, any runtime permission.
Apps come from `LauncherApps` across all profiles (work, clone); apps of a paused
profile and archived apps stay listed, dimmed (`AppStatus`), and a first tap brings
them back; private space is not reachable (no `ACCESS_HIDDEN_PROFILES`). See
`CAVEATS.md`.

**Feature freeze since 0.2.12 (2026-10-18), for user-facing additions only.** No new
user-facing feature, sigil, command or setting — not even a small one — unless the user lifts
the freeze. Bugfixes, code improvements (simplification, refactoring, performance) and tests
are not frozen; they must not regress anything, and a change may alter behaviour only to make
it do what it already claims to do.

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
  (`VersoApp.io`) does all disk and PackageManager work; results come back via
  `runOnUiThread` (`MainActivity.onUi`). `LauncherState` plays the ViewModel role
  as plain Kotlin.
- **Persistence:** one JSON file, `filesDir/verso.json`, via `ConfigStore` — not
  SharedPreferences, not DataStore. Beside it only `home-cache.txt` (`HomeCache`), a
  disposable copy of the favorites for the cold-start frame, `usage.txt` (`UsageLog`), the
  launch counts behind the `%` order, and `recents.txt` (`RecentsLog`), the `?` list —
  disposable too, never in a backup. Off-device backups in `Download/Verso/` via
  MediaStore (`BackupStore`).
- **Tests:** JUnit 4, the real `org.json` on the JVM classpath (android.jar's is a
  stub), Robolectric where a real widget is needed, **no MockK / no mocks** (fakes
  and manual queues). An `androidTest` set (androidx.test, test APK only) covers
  MediaStore and Android's own `org.json`.
- **Warnings are errors** — Kotlin (`allWarningsAsErrors`, main and test), lint
  (`warningsAsErrors`) and detekt (default rules, adjusted with reasons in
  `config/detekt/detekt.yml`; part of `check`). A deliberate finding gets a `@Suppress` at the
  narrowest scope with its reason — e.g. an IO path's broad catch (hard rule 2):
  `@Suppress("TooGenericExceptionCaught")`. `app/lint-baseline.xml` and
  `app/detekt-baseline.xml` hold only the debt found at switch-on and only ever shrink.
- **Debug build** has `applicationIdSuffix = ".debug"`, so `connectedAndroidTest`
  installs beside the family-signed release on the device.

---

## Architecture

Single `:app` module, one package:

```
com.github.reygnn.verso_launcher/
  VersoApp            process-wide: the IO executor, the ProcessState (recents, launches,
                      the start-over and seed flags), palette and badge-label caches
  LauncherStateParts  what LauncherState is built from: ProcessState, ConfigFile,
                      RecentsFile, UsageFile
  MainActivity        the window: commands, backup/restore orchestration, edits, dialogs,
                      launching
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
  HomeCache           the favorites cached for an instant home page on a cold start
  UsageLog            the "%" order's launch counts (Kolibri's decay) and usage.txt
  RecentsLog          the "?" list's recents.txt
  SwipeFrame          window root; watches dispatchTouchEvent for the page-turn swipe
  SwipeGesture        pure gesture decisions: Unity's page drag + Kolibri's flick
  TapGuard            stale-click protection for list rows
  Guard               hard rule 2's backstop around every IO-thread and SAF-thread task
  RenameDialog        long-press dialog: custom name + tags
  LauncherState       config + app list + recents, and the concurrency rules (pure)
  CommandGate         the "~" commands one at a time: a run ends with its last step (pure)
  LauncherLogic       all pure decisions: modes, commands, search/fold, sort, rows,
                      Enter/tap actions, tags, default favorites
  AppEntry, VersoConfig   the data; a VersoConfig is immutable (an edit is a new one)
  ConfigJson          verso.json <-> VersoConfig (parse / parseForeign / serialize)
  ConfigStore         verso.json on disk: atomic write (.new + sync + rename), set-aside
  BackupStore         Download/Verso via MediaStore
  BackupFiles         pure backup naming rules
  RestoreSteps        the IO half of a restore, in its load-bearing order
  Cleanup             pure "~cleanup" decisions: what may be offered (CleanupPlan)
```

Data flow: a `LauncherApps.Callback` (install/uninstall/update) or a profile broadcast →
`MainActivity.appsMaybeChanged` (refresh now while in front, else mark dirty); `onResume`
refreshes when dirty or when `LauncherState.needsRefresh` → `LauncherState.refresh()` → IO
thread (load config once, enumerate apps of every profile) → main-thread hand-off guarded
by the load generation (newest wins) → `applyFilter` re-renders. An enumeration carries no
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
   and SAF-thread task, setting an unreadable `verso.json` aside) or protects
   `verso.json` (the atomic write); disposable work that is rebuilt on the next start may
   crash. The other catches are correctness or UX, not armour: the enumeration's (`Apps`)
   decide what `~cleanup` may trust and keep the last good list, the backup ones turn a
   failure into its own message. Main-thread catches always log, and where the user acted
   say what failed — never swallowed, no catch-all net.
3. **One IO thread, process-wide** (`VersoApp.io`). Never create another executor,
   never shut it down. Main-thread state is mutated only on the main thread; hand-backs
   go through `onUi`, which drops them for a destroyed instance. The one exception: the
   document a `~restore-saf` pick points at is read on its own short-lived thread — a
   content provider (an offline cloud drive) can block that read indefinitely, and it
   touches no file of the app's own; the undo point and the adoption still go through
   `VersoApp.io`.
4. **One file, written atomically** (`ConfigStore.write`): `verso.json.new` + fsync →
   rename over `verso.json` → fsync dir. No `.bak`, no repair from older copies — the net
   is the user's `~backup`. `load()` is null — never an empty config — when `verso.json`
   is torn or cannot be read; then the FIRST LOAD (only it, never `load()` itself) moves
   it out of the way — published to `Download/Verso/verso-unreadable-…` and only then
   deleted; if its bytes cannot be read or the export fails, renamed to
   `verso.json.unreadable` (replacing an earlier one) — and starts over as a fresh
   install, with one toast (a process-wide flag, so a dropped hand-off does not lose it);
   `~restore` brings the last backup back. Only with no `verso.json` is the empty config
   real.
5. **No edit, no save, no `~` command (but `~restart`) before the config is loaded** — the
   empty default must never be written over the real file or a backup. `LauncherState`
   issues no `EditPermit` (every edit takes one, so an edit path without the check does not
   compile, and only `LauncherState.editPermit` builds one — `SourceGuardTest`) and refuses
   saves until then; the Activity says why (`editPermit()`,
   `editable()` for the commands). Backups and undo points come from memory only
   (`loadedSnapshot`). If the files cannot even be set aside, the config stays not loaded
   and every resume tries again. Home is drawn and its cache written only from a loaded
   config.
6. **Backups: never delete the old one before the new one is published.** MediaStore owns
   the namespace: names are timestamped (`verso-<date>_<time>.json`), a backup is
   recognised by pattern, a restore takes the newest own row by `_ID`, never a fixed name.
   Exactly one current backup and one undo point per kind (`verso-pre-restore-…`,
   `verso-pre-cleanup-…`), and one set-aside unreadable config (`verso-unreadable-…`). A
   restore's undo point is written without the sweep; the old one goes only once the
   restored config is saved (it may be the very file restored).
7. **Restore order:** read + `parseForeign` off the main thread (never the lenient `parse`
   for outside files) → if it equals the current config, stop (an undo point would replace
   the real one) → write the undo point → write the restored config to `verso.json` in the
   same IO task (the restore must survive a lost hand-back; a failed write adopts nothing)
   → sweep the old undo point → `adopt` (memory only, no second write). No undo point, no
   restore.
8. **Config keys are `package/class`** (`ComponentName.flattenToString()`) for the
   personal profile, so configs and backups written by earlier Verso versions stay
   readable; an app of another profile is `package/class#<profile serial>`
   (`AppEntry.keyOf`). Launch and app info go through `LauncherApps` with the entry's
   profile. Don't change the key or the JSON shape without a migration.
9. **`LauncherLogic.foldLabel` is the only fold** (NFC, `Locale.ROOT`, drop U+0307)
   for labels, needles and tags (`canonicalTag` builds on it). Lists are sorted with
   a `Collator` for the device locale (`sortedForDrawer`, `sortedTags`), never by
   code point.
10. **Default favorites only on a fresh install** (`ConfigStore.isFresh`: no `verso.json`)
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
15. **One `~` command at a time** (`CommandGate`): while one runs, a second is refused with
    a toast and stays in the prompt, and so is an edit — `LauncherState`'s edits take an
    `EditPermit`, made only from a `CommandGate.Idle` and void once a run has started since,
    so an edit path without the check does not compile — a save queued behind a restore's IO
    step would write the old config back over it. Nothing else is held back — no layer, no
    cancel; launching, typing and page turns go on. A run ends with its last step, never by
    a "done" call: every step holds it and releases it on every path (`CommandGate.io`
    catches what its work throws; `LauncherState.cleanup` reports from a `finally`). No run
    spans another activity or a dialog waiting on the user: the `~restore-saf` file is read
    before a run starts, only its adoption is one (`~restart` while it is read drops the
    pick, with a toast); the cleanup dialog ends its run, its OK starts a new one.

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
