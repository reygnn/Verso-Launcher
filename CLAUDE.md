# CLAUDE.md

Project rules for **Verso Launcher**, a plain-text Android launcher. What it is and how it is
used: `README.md`; deliberate oddities: `CAVEATS.md`; tests: `app/src/test/CLAUDE.md` and
`TESTING_CONVENTIONS.kt`; the working agreement: `WHY_CLAUDE.md`. Family-wide conventions come
from `~/.claude/CLAUDE.md`. This file holds only the rules and where they are enforced; the why
is in the KDoc of the classes named.

---

## Scope

Home page ("recto"): clock, date and battery (double-tap targets), the favorites as large text;
swipe up for the drawer, pull down for the recent apps. Drawer ("verso"): Sigil's list and
command line — search, the sigils (`-` hide, `!` favorite, `!!` reorder, `#`/`##` tags, `?`
recents, `%` A–Z ↔ most used) and the `~` commands (`~restart`, `~backup`, `~restore`, `~undo`;
`~crash` only in a daily-driver build, never abbreviated).
Every change is saved at once. Default favorites are seeded once, on a fresh install.

Deliberately NOT: icons, widgets, notification badges, a settings screen (the look is constants
in `HomeStyle.kt`), left/right swipes, any runtime permission, private space (no
`ACCESS_HIDDEN_PROFILES`). Apps of every profile come from `LauncherApps`; paused and archived
ones stay listed, dimmed (`AppStatus`).

New features are allowed (the freeze ended 2026-10-25), bounded by this scope, hard rule 1 and
the bar that nothing regresses. Verso succeeds Sigil and Kolibri, both discontinued: never port
anything back, no compatibility to keep.

---

## Stack particulars

The point is a release APK with **no dependencies**: Kotlin stdlib and platform APIs only.

- **No Compose, Material 3, AndroidX, coroutines or ViewModel.** Platform Views built in code, no
  XML layouts. `LauncherState` is the ViewModel in plain Kotlin, one per process (`Core`).
- **Persistence:** `filesDir/verso.json` (`ConfigStore`, hard rule 4) and its one undo point
  `verso.prev.json` (`UndoSlot`). Beside them only disposable files, never in a backup:
  `home-cache.txt`, `usage.txt`, `recents.txt`, `stalls.txt`, and Guru's `filesDir/guru/` (also
  kept out of a device transfer, `data_extraction_rules.xml`). Backups go where the user picks
  (the system's document picker, hard rule 6); Guru's reports to `Download/Guru/` through MediaStore.
- **Tests:** JUnit 4, the real `org.json` on the JVM classpath, Robolectric only where a real
  widget is needed, no mocks (fakes and manual queues); `androidTest` for Android's own `org.json`.
- **Warnings are errors** — Kotlin, lint and detekt (`config/detekt/detekt.yml`, part of
  `check`). A deliberate finding gets a `@Suppress` at the narrowest scope — on the `try` itself
  where the function does more (`val x = @Suppress(…) try`) — with its reason as a comment on the
  line above (one behind the annotation enters detekt's baseline IDs). A deliberate setting is
  configured, not baselined. `lint-baseline.xml` and `detekt-baseline.xml` only ever shrink.
- **Comments and KDoc:** only a non-obvious why — a thread or ordering contract, a platform
  fact (with the version or device it holds for), why the obvious fix is wrong, a hard-rule
  anchor. Never a list of call sites, the story of a change, or what the code already says:
  they drift, and the next session trusts them. detekt's `OutdatedDocumentation` checks
  `@param`/`@property` names against the declaration, not their content.
- **Debug build:** `applicationIdSuffix = ".debug"`, so `connectedAndroidTest` installs beside the
  release.
- **Guru (`guru/`)**, the crash reporter, is a `git subtree` of reygnn/guru, compiled into `:app`
  and checked by detekt. **Never edit it here**: fix it upstream, tag it, then
  `git subtree pull --prefix=guru https://github.com/reygnn/guru <tag> --squash`; the commit names
  the tag. Its rules are in `guru/CLAUDE.md`, its tests run in its repo. Release builds keep names
  and line numbers (`-dontobfuscate`) for readable traces and a stable Guru number.
- **Daily-driver build:** `./gradlew bundleRelease -PdailyDriver` (and every debug build)
  compiles in `~crash`. `DailyDriver.ENABLED` comes from twin files in `src/dailyDriver` and
  `src/public`, not from BuildConfig: every buildFeature stays off. The public build has an even
  versionCode, the daily-driver build the odd one above it and `-daily` in its name (`0.4.2` = 24,
  `0.4.2-daily` = 25). **A release bump is versionName +0.0.1, versionCode +2.**
- **Release mapping:** kept in `~/apk/mappings/verso/<versionName>-<versionCode>/` (Gradle
  property `verso.mappingArchive`) and uploaded to the ACRA backend (`uploadProguardMapping`) by
  `app/acra-scripts/upload_mapping.sh` — not in the repo; ADMIN credentials from the environment
  or `~/.config/acra/admin.env`. No script: a warning. A failed upload: a failed build.

---

## Look

Text right on the wallpaper; colour comes from contrast, not from a scheme.

- One text colour for home and drawer, `HomeStyle.palette` (white or black from
  `WallpaperColors`' dark-text hint), with `HomeStyle.tonalOutlineColor`; hints in italics.
- Colours live in `HomeStyle.kt`, never as hex in views — except `MainActivity.DIALOG_TEXT` for
  the dark dialogs, which pin `Theme_Material_Dialog_Alert` (moving them to DeviceDefault needs
  that colour tied to the theme, and a check on a Pixel and a Samsung).
- Theme parent `Theme.Material.NoActionBar` on purpose (it only sets the cursor and handle
  accent); no `values-night/`, no splash attributes, no dynamic colour. One adaptive launcher
  icon, `drawable/ic_launcher.xml` — no `mipmap-anydpi-v26`, no round icon. No
  `MATERIAL3RULES.md`: the family's is Compose-only.

---

## Architecture

One module, one package. The process owns the data, an activity is a window (hard rule 16):

- **Process:** `VersoApp` (the IO executor, `Core`, `Commands`, `Results`, the pick, the live
  window, `ProcessState`), `Core` (the one `LauncherState`, its stores and the undo slot),
  `Commands` and `Results` (the `~` commands' work and their queued results).
- **Window:** `MainActivity` (refusals up front, pickers, dialogs, edits; the process sees it only
  as a `LauncherWindow`), `AppLauncher` (a start or "App info" in the entry's profile, a paused
  profile turned on), `PageTurn` and `SwipeFrame`, `HomePage`, `AppDrawer`, `RenameDialog`,
  `OutlinedViews`.
- **Pure:** `LauncherLogic` (every decision: modes, commands, search, sort, rows, actions, tags,
  default favorites), `LauncherState`, `PendingPick`, `SwipeGesture`, `ConfigJson`,
  `WallpaperPalette`, `HomeStyle`, `CommandDialogText`; the data `AppEntry` and `VersoConfig` (immutable: an edit is a new one).
- **IO:** `ConfigStore`, `UndoSlot`, `Apps` (enumeration, default favorites), `HomeCache`,
  `UsageLog`, `RecentsLog`, `IoWatchdog` (the IO thread's watchdog and its loop guard), and
  `Guard` (hard rule 2's backstop, beside it `logWarning`).

The rest are small helpers named for what they do. Data flow: a `LauncherApps` callback or a
profile broadcast → `MainActivity.appsMaybeChanged` → now if in front, else at `onResume` →
`LauncherState.refresh()` → IO thread (the config once, the apps of every profile, system labels
only) → main thread, the newest load generation wins; custom names come from the live config
(`LauncherLogic.named`) → `AppDrawer.refilter`. An edit is serialized on main, written on IO.

---

## Hard rules

1. **No production dependencies.** `dependencies {}` holds only `testImplementation` and
   `androidTestImplementation`. New deps and build plugins need the user's OK.
2. **A HOME app must never throw.** Every IO- and SAF-thread task runs behind `Guard` and
   degrades. Beyond that, crash-only: a catch only where it breaks a crash loop or protects
   `verso.json`'s write; disposable work may crash. Main-thread catches always log and say what failed where the user acted
   — never a silent catch-all.
3. **One IO thread, process-wide** (`VersoApp.io`): never another executor, never shut down; main-
   thread state only on main. Hand-backs: the process's through `VersoApp.onMain` (never
   dropped), one window's through `MainActivity.onUi` (dropped once it is destroyed). Deliberate
   exceptions, not breaches to fix: a picked document read or written on a thread of its own
   (`Commands.readPick`, `writeBackup`: a provider may block for good), the undo slot read and
   written on main in the step that swaps (`LauncherState.replace`), Guru's report on the dying
   thread and its alert via `runOnUiThread`, a pending save written on main at `onStop`
   (`LauncherState.flushSave`), and the IO thread's watchdog (`IoWatchdog`): a task stuck 30 s
   crashes the process so HOME starts again clean, at most once in 10 minutes (`stalls.txt`).
4. **`verso.json` is written whole or not at all** (`ConfigStore`): `verso.json.new`, fsynced,
   renamed over it, the directory synced. A file that cannot be read or parsed loads as empty, a
   toast says so (`onConfigUnusable`), and it is set aside as `verso.json.unreadable`, never
   written over; a load with no `verso.json` takes it back once it reads. A save is on disk once
   `onStop` is through, like SharedPreferences' `apply()` (`flushSave`). Fresh = nothing that
   stands for `verso.json` (`isFresh`). A `.bak` of versions up to 0.5.9 is taken back once. The
   net is the backups.
5. **Nothing is written before the config is loaded.** No `EditPermit` (`LauncherState.editPermit`,
   `SourceGuardTest`), no save, no `~` command but `~restart`; a backup comes from
   `loadedSnapshot`, a swap refuses (`replace`); home and its cache are drawn only from a loaded
   config.
6. **Backups are the user's files** (`Commands.writeBackup`, `readPick`): written and read through
   the document picker, wherever the user puts them; Verso lists, names after the fact or deletes
   none, and needs no storage permission. What lives with the app is the config and one undo point.
7. **Restore order** (`Commands`, `LauncherState.replace`): read and `parseForeign` off main
   (never the lenient `parse` for outside files) → the user's OK → equal to the current config:
   stop → the replaced config into the undo slot → adopt in memory → saved like any edit. No undo
   point, no restore; `~undo` is the same swap with the slot's config.
8. **Config keys are `package/class`**, `package/class#<serial>` for another profile
   (`AppEntry.keyOf`); launch and app info go through `LauncherApps` with the entry's profile. No
   change to the key or the JSON shape without a migration.
9. **`LauncherLogic.foldLabel` is the only fold** for labels, needles and tags; matching alone
   also drops Latin, Greek and Cyrillic accents (`matchForm`), never what is stored. Lists sort
   with the device locale's `Collator`, never by code point.
10. **Default favorites only on a fresh install** (`ConfigStore.isFresh`) and only into a config
    with none. The seeding may wait for a first list (`ProcessState.seedPending`).
11. **Page turn = Unity-Kolibri's** (`PageTurn`, `SwipeFrame`, `SwipeGesture`): the drawer follows
    the finger both ways, `?` is the same drawer from the top and closes toward it, the flick is
    the fallback; only in `SwipeFrame.dispatchTouchEvent`, never `onInterceptTouchEvent`. Easy to
    break: a second finger cancels a drag; a drag grabs a page still moving; an open dialog blocks
    drag and flick; a page turned some other way aborts a running drag (`abortDrag`).
12. **Status bar hidden on the home page only**; home pads for it regardless
    (`getInsetsIgnoringVisibility`).
13. **`density` stays out of `configChanges`**: a display-size change must recreate the activity.
14. **Entries of apps that are gone stay** in the config: they cost bytes and show nowhere but in
    the tag lists (`##` and the rename dialog's suggestions, `LauncherLogic.allTags`), where a tag
    whose apps are gone is one to give to others. Telling "gone" from "not readable right now" (a
    paused or unlisted profile, an unmounted card) is what made `~cleanup` (up to 0.5.9) the
    riskiest command; a new one needs a reason and the user's OK.
15. **No `~` command runs in the background against the config.** A swap (`~restore`, `~undo`) is
    one step on main; a backup writes a snapshot. So nothing needs to hold edits back, and no
    command waits for another. Results wait in `Results` for a window in front; refusals are never
    queued. A `~restore` pick is the process's (`PendingPick`) until its adoption, and holds back a
    second swap meanwhile.
16. **The process owns the data; an activity is a window.** `Core`, `Commands`, `Results` and the
    pick live in `VersoApp`, see the window only as a `LauncherWindow` (never an
    Activity, `SourceGuardTest`) and keep none past a call. A recreation shares the loaded state;
    only `~restart` resets it (`Core.reset`).

---

Update this file when a rule changes or a hard-won lesson must be kept — not for TODOs, refactor
notes (they go in commit messages) or what the code already says.
