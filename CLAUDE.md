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

A launcher reduced to text. Front page ("recto"): clock, date and battery
(each a double-tap target), the favorites as large text, swipe up for the drawer,
swipe down for the recently used apps. Back page ("verso"): Sigil Launcher's list
plus command line — search, the edit sigils (`-` hide, `!` favorite, `!!` reorder,
`#`/`##` tags, `?` recents) and the `~` commands (`~load`, `~save`, `~backup`,
`~restore`, `~restore-saf`, `~cleanup`). Default favorites (phone, messenger, email, browser,
camera) are seeded once, on a fresh install.

Deliberately NOT: icons, widgets, notification badges, a settings screen (the look
is constants in `HomeStyle.kt`), left/right swipe actions, any runtime permission.
Apps come from `LauncherApps` across all profiles (work, clone); apps of a paused
profile and archived apps stay listed, dimmed (`AppStatus`), and a first tap brings
them back; private space is not reachable (no `ACCESS_HIDDEN_PROFILES`). See
`KNOWN_LIMITATIONS.md`. A new feature category needs explicit user agreement before
it is built.

Verso replaces Sigil Launcher, which it was cloned from. Sigil is discontinued (no
longer maintained, its GitHub repo removed, October 2026) — never port anything back,
and there is no Sigil compatibility to keep: its only user has moved over.

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
- **Persistence:** one JSON file, `filesDir/verso.json` (+ `.tmp`, `.bak`), via
  `ConfigStore` — not SharedPreferences, not DataStore. Beside it only
  `home-cache.txt` (`HomeCache`), a disposable copy of the favorites for the cold-start
  frame. Off-device backups in `Download/Verso/` via MediaStore (`BackupStore`).
- **Tests:** JUnit 4, the real `org.json` on the JVM classpath (android.jar's is a
  stub), Robolectric where a real widget is needed, **no MockK / no mocks** (fakes
  and manual queues). An `androidTest` set (androidx.test, test APK only) covers
  MediaStore and Android's own `org.json`.
- **Debug build** has `applicationIdSuffix = ".debug"`, so `connectedAndroidTest`
  installs beside the family-signed release on the device.

---

## Architecture

Single `:app` module, one package:

```
com.github.reygnn.verso_launcher/
  VersoApp            process-wide: the IO executor, the recents, palette and
                      badge-label caches
  MainActivity        the window: both pages, commands, backup/restore orchestration,
                      app enumeration, launching
  HomePage            the front page (clock/date/battery, favorites, a11y actions)
  HomeStyle           pure look constants + outline colour, palette, BatteryInfo,
                      DoubleTapGate
  OutlinedViews       the outlined text/icon views home and drawer draw with
  HomeCache           the favorites cached for an instant home page on a cold start
  SwipeFrame          window root; watches dispatchTouchEvent for the page-turn swipe
  SwipeGesture        pure gesture decisions: Unity's page drag + Kolibri's flick
  TapGuard            stale-click protection for list rows
  RenameDialog        long-press dialog: custom name + tags
  LauncherState       config + app list + recents, and the concurrency rules (pure)
  CommandGate         the "~" commands one at a time: a run ends with its last step (pure)
  LauncherLogic       all pure decisions: modes, commands, search/fold, sort, rows,
                      Enter/tap actions, tags, default favorites
  AppEntry, VersoConfig   the data
  ConfigJson          verso.json <-> VersoConfig (parse / parseForeign / serialize)
  ConfigStore         verso.json on disk: atomic write, .tmp/.bak recovery
  BackupStore         Download/Verso via MediaStore
  BackupFiles         pure backup naming rules
  RestoreSteps        the IO half of a restore, in its load-bearing order
  Cleanup             pure "~cleanup" decisions: what may be offered (CleanupPlan)
```

Data flow: a `LauncherApps.Callback` (install/uninstall/update) or a profile broadcast
→ `MainActivity.appsMaybeChanged` (refresh now while in front, else mark dirty);
`onResume` refreshes when dirty, when `LauncherState.needsRefresh`, or after 5 min →
`LauncherState.refresh()` → IO thread (load config once,
enumerate apps of every profile) → main-thread hand-off guarded by generation
counters → `applyFilter` re-renders. An enumeration carries no config: it lists system
labels only, and the custom names are applied on the main thread from the live config
(`LauncherLogic.named`, at the hand-off and after every change to the names) — so no
edit can make an in-flight enumeration stale, and none is ever thrown away for one. Every mutation → `LauncherState.mutated()` → serialized on
main, written on the IO thread.

---

## Hard rules

1. **No production dependencies.** Anything added to `dependencies {}` must be
   `testImplementation` or `androidTestImplementation`. New deps need the user's OK.
2. **A HOME app must never throw.** Every IO-thread path (enumeration, default
   favorites, config and backup I/O) catches broadly and degrades; an uncaught
   exception on the executor kills the process on every start.
3. **One IO thread, process-wide** (`VersoApp.io`). Never create another executor,
   never shut it down. Main-thread state is mutated only on the main thread; hand-backs
   go through `onUi`, which drops them for a destroyed instance. The one exception: the
   document a `~restore-saf` pick points at is read on its own short-lived thread — a
   content provider (an offline cloud drive) can block that read indefinitely, and it
   touches no file of the app's own; the undo point and the adoption still go through
   `VersoApp.io`.
4. **`verso.json` write sequence is load-bearing** (`ConfigStore.write`): temp +
   fsync → rotate the primary to `.bak` only if it still parses → publish → fsync dir.
   A heal never rotates. `load()` prefers primary, then a parseable `.tmp`, then `.bak`,
   and returns **null — never an empty config — when a published copy (primary or `.bak`)
   exists but none parses**; only with no published copy at all is the empty config real.
5. **No edit, no save before the config is loaded** — the empty default must never be
   written over the real file. `LauncherState` refuses every edit and `save` until then;
   the Activity says why (`editable()`). An unreadable config (`load()` null,
   `configUnreadable`) counts as not loaded and stays so: retried on every resume and by
   `~load`, ended by a successful read or a restore. `~backup` before the load exports the
   disk copy — and writes nothing if that is unreadable, keeping the old backup. Home is
   drawn and its cache written only from a loaded config.
6. **Backups: never delete the old one before the new one is published.** MediaStore
   owns the namespace: names are timestamped (`verso-<date>_<time>.json`), a backup is
   recognised by pattern, a restore takes the newest own row by `_ID`, never a fixed
   name. Exactly one current backup and one undo point per kind (`verso-pre-restore-…`,
   `verso-pre-cleanup-…`).
7. **Restore order:** read + `parseForeign` off the main thread (never the lenient
   `parse` for outside files) → if it equals the current config, stop (an undo point
   would replace the real one) → write the undo point → write the restored config to
   `verso.json` in the same IO task (the restore must survive a lost hand-back) → `adopt`.
   No undo point, no restore — except over an unreadable config: nothing readable is there
   to keep, and the restore is the way out of that state (`Outcome.ReplacedUnreadable`).
8. **Config keys are `package/class`** (`ComponentName.flattenToString()`) for the
   personal profile, so configs and backups written by earlier Verso versions stay
   readable; an app of another profile is `package/class#<profile serial>`
   (`AppEntry.keyOf`). Launch and app info go through `LauncherApps` with the entry's
   profile. Don't change the key or the JSON shape without a migration.
9. **`LauncherLogic.foldLabel` is the only fold** (NFC, `Locale.ROOT`, drop U+0307)
   for labels, needles and tags (`canonicalTag` builds on it). Lists are sorted with
   a `Collator` for the device locale (`sortedForDrawer`, `sortedTags`), never by
   code point.
10. **Default favorites only on a fresh install** (`ConfigStore.isFresh`: neither
    primary nor `.bak` exists) and only into a config with no favorites (a lone `.tmp`
    recovered on a "fresh" install keeps its own). An existing config with no favorites
    is the user's choice.
11. **Page turn = Unity-Kolibri's.** Closing the drawer is a drag that follows the
    finger (Unity's `DragToDismissCore`: starts once the list is at its top, 28 % of the
    height or a 3× min-fling release commits, a flick back cancels, 220 ms settle);
    opening is the same drag upward from home (an extension — Unity only flicks it
    open). The flick (4× touch slop, 1.2 px/ms average, 1.5× dominance, 48 dp top band)
    remains for the recents swipe and as the open fallback. All of it lives in
    `SwipeFrame.dispatchTouchEvent`, never `onInterceptTouchEvent` (a scrolling list
    disables intercept mid-gesture); the decisions are pure in `SwipeGesture`. Details that
    are easy to break: taps are swallowed while a page turn runs (`swallowTaps`, ~200 ms);
    a second finger cancels a drag; a drag can grab a page still moving and continues from
    there; an open dialog (rename, `~cleanup`) or the busy layer of a running `~` command blocks
    both drag and flick; a page turned some other way
    (HOME, Back, screen off) aborts a running drag without a release decision (`abortDrag`).
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
15. **The `~` commands run one at a time, behind a busy layer** (`CommandGate`). A typed
    command (`runCommand`), the `~restore-saf` pick's answer and a confirmed `~cleanup` each
    start a run with `gate.start`; while it runs, a see-through full-window dialog takes every
    touch and key, so no edit, page turn or second command can come in between. A run ends
    with its last step, never by a "done" call: every step `hold`s the run before it starts
    (`commandIo` for an IO step) and `finish`es it back on main, whatever happened. The gate
    belongs to the activity, so a hand-back `onUi` drops dies with it; the next instance
    starts free. No run spans another activity or a dialog waiting on the user (the SAF picker,
    the cleanup confirmation): those end the run, and their answer starts a new one. Only a
    step with no time bound — the SAF document read — is `cancelable`: Back (or HOME) abandons
    it, and its late answer finds its ticket ended. Any other step may be stuck in a system
    service with no timeout, but abandoning it is not safe; after `BUSY_ESCAPE_MS` the layer
    offers a way out instead — Back takes it down, the run goes on, no other command starts
    until it ends. Edits made meanwhile interleave as before the gate, which is why
    `LauncherState` keeps its own guards (`configEpoch`, the cleanup's `Superseded`). Every
    step reports back on every path (IO steps from a `finally`, `LauncherState` included).
    `~load` is `LauncherState.reload`, its own task, not a refresh that a newer one could
    supersede.

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
