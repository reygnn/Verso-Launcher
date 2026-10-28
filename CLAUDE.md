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
command line — search, the sigils (`#` tags, `?` recents, `%` A–Z ↔ most used) and the `~`
commands (`~restart`, `~open`, `~edit`; `~crash` only in a daily-driver build, never
abbreviated). The config — favorites, hidden apps, custom names, tags — is a text file of the
user's that Verso reads and never writes (`ConfigText`, hard rule 4); it is written and edited in
any editor (`~edit`), never in the app. With no file linked the default favorites show.

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
- **Persistence:** the config is the user's file, wherever the document picker found it (hard
  rules 4 and 6). The app keeps only small files of its own, each rewritten whole through a temp
  file and a rename, none of them precious: `config-link.txt` and `config-cache.txt` (where the
  file is, and its last text, `ConfigLink`), `order.txt` (`%`, `DrawerOrder`), `home-cache.txt`,
  `usage.txt`, `recents.txt`, and Guru's `filesDir/guru/`. `verso.json` of earlier versions is
  no longer read; their JSON backups link with `~open`. No cloud backup; a device transfer
  leaves out the home-cache and Guru's files (`data_extraction_rules.xml`). Guru's reports go to
  `Download/Guru/` through MediaStore.
- **Tests:** JUnit 4, the real `org.json` on the JVM classpath, Robolectric only where a real
  widget is needed, no mocks (fakes and manual queues); `androidTest` for Android's own `org.json`.
- **Warnings are errors** — Kotlin, lint and detekt (`config/detekt/detekt.yml`, part of
  `check`). A deliberate finding gets a `@Suppress` at the narrowest scope — on the `try` itself
  where the function does more (`val x = @Suppress(…) try`) — with its reason as a comment on the
  line above (one behind the annotation enters detekt's baseline IDs). A deliberate setting is
  configured, not baselined. `lint-baseline.xml` and `detekt-baseline.xml` only ever shrink.
- **Comments and KDoc** are written for the next session, which trusts them. English, like the
  code. `Commands.kt` is the model.
  - **Only a non-obvious why:** a thread or ordering contract, a platform fact (with the version
    or device it holds for), why the obvious fix is wrong, a hard-rule anchor. Never a list of
    call sites, the story of a change, or what the code already says.
  - **Whole sentences, the condition spelled out:** "If config-link.txt is lost, the user runs
    ~open once more", not "Lost, the link costs one ~open".
  - **Contracts on lines of their own**, each starting with a fixed prefix so a search finds
    them: `Thread:` (which thread may call, where a hand-back runs), `Invariant:` (what always
    holds, and what keeps it so), `Why:` (a reason the sentences before do not give). A
    continuation line is indented two spaces.
  - **In KDoc a name of code is a link**, `[Commands.adopt]`, never plain prose: the IDE follows
    and renames a link, a plain name outlives its declaration. Files, commands and other text
    in backticks or quotes.
  - **Each fact in one place:** a rule is its hard rule here or one KDoc, and everything else
    points to it ("hard rule 15", `[Commands.adopt]`). `CAVEATS.md` tells users in their words.
  - **A change of behaviour changes its comment in the same commit.** detekt's
    `OutdatedDocumentation` checks `@param`/`@property` names against the declaration, not their
    content. Older KDoc moves to this form when its file is touched; there is no sweep.
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
- Colours live in `HomeStyle.kt`, never as hex in views. No dialog is left; a new one would pin
  `Theme_Material_Dialog_Alert`, as the old ones did (DeviceDefault needs a check on a Pixel and
  a Samsung).
- Theme parent `Theme.Material.NoActionBar` on purpose (it only sets the cursor and handle
  accent); no `values-night/`, no splash attributes, no dynamic colour. One adaptive launcher
  icon, `drawable/ic_launcher.xml` — no `mipmap-anydpi-v26`, no round icon. No
  `MATERIAL3RULES.md`: the family's is Compose-only.

---

## Architecture

One module, one package. The process owns the data, an activity is a window (hard rule 16):

- **Process:** `VersoApp` (the IO executor, `Core`, `Commands`, `Results`, the live window,
  `ProcessState`), `Core` (the one `LauncherState`, the link to the user's file, the app list),
  `Commands` (the file linked and read again) and `Results` (their queued words).
- **Window:** `MainActivity` (refusals up front, the pickers, the editor, `%`; the process sees it
  only as a `LauncherWindow`), `AppLauncher` (a start or "App info" in the entry's profile, a
  paused profile turned on), `PageTurn` and `SwipeFrame`, `HomePage`, `AppDrawer`, `OutlinedViews`.
- **Pure:** `LauncherLogic` (every decision: modes, commands, search, sort, rows, actions, tags,
  default favorites), `LauncherState`, `ConfigText` (the file's lines, the apps they name, a new
  file), `ConfigJson` (the JSON backups of earlier versions), `SwipeGesture`, `WallpaperPalette`,
  `HomeStyle`; the data `AppEntry` and `VersoConfig` (immutable, resolved anew from the text).
- **IO:** `ConfigLink`, `DrawerOrder`, `Apps` (enumeration, default favorites),
  `HomeCache`, `UsageLog`, `RecentsLog`, and `Guard` (hard rule 2's backstop, beside it
  `logWarning`).

The rest are small helpers named for what they do. Data flow: a `LauncherApps` callback or a
profile broadcast → `MainActivity.appsMaybeChanged` → now if in front, else at `onResume` →
`LauncherState.refresh()` → IO thread (the apps of every profile, system labels only) → main
thread, the newest load generation wins; the file's text is resolved against the list
(`ConfigText.resolve`) and the custom names applied (`LauncherLogic.named`) → `AppDrawer.refilter`.
The user's file: `MainActivity.onStart` → `Commands.readLinked` → a thread of its own (read and
parsed) → main → a changed text is adopted (`Core.adoptLinked`) and copied for the next start.

---

## Hard rules

1. **No production dependencies.** `dependencies {}` holds only `testImplementation` and
   `androidTestImplementation`. New deps and build plugins need the user's OK.
2. **A HOME app must never throw.** Every IO- and SAF-thread task runs behind `Guard` and
   degrades. Beyond that, crash-only: a catch only where it breaks a crash loop or keeps the
   config file's read a message; disposable work may crash. Main-thread catches always log and say what failed where the user acted
   — never a silent catch-all.
3. **One IO thread for Verso, process-wide** (`VersoApp.io`): never another executor for Verso's
   own work, never shut down; main-thread state only on main. Hand-backs: the process's through `VersoApp.onMain` (never
   dropped), one window's through `MainActivity.onUi` (dropped once it is destroyed). Deliberate
   exceptions, not breaches to fix: the app's own small files read on main before the state
   exists (hard rule 5), the user's file read on a thread of its own
   (`Commands`, "verso-config-read": a provider may block for good), Guru's
   disk work on a thread of its own (`VersoApp.guruIo`: a MediaStore that hangs must not hold the
   first app list), its report on the dying thread and its alert via `runOnUiThread`.
4. **Verso never writes the config.** The user's file is read, never written, and no document
   is ever opened for writing (`SourceGuardTest`); the user makes the file in an editor. A file
   that cannot be read keeps the last text read (`config-cache.txt`) and is said once; a line that
   is no entry is named and skipped, the rest counts. In-app editing stays out: anything that
   changed the config here would bring back the saves, backups and undo points this rule removed.
5. **The config is there before the state exists** (`Core.newState`, on main: the copy of the
   linked file's text, a few KB once per process and per `~restart`), so there is
   no "not loaded yet" to guard. The file itself is read at every `onStart`, off main; a changed
   text replaces the config at once (`LauncherState.adopt`). Home and its cache are drawn only
   once the first app list is in (`appsLoaded`).
6. **The config file is the user's** (`Commands`): picked with the document picker ("~open")
   wherever the user keeps it, its grant kept (`takePersistableUriPermission`); Verso
   lists, renames or deletes nothing of theirs and needs no storage permission. Backups are copies
   of that file, made by the user.
7. **A file from outside is read as text or as a JSON config** (`ConfigText.read`): JSON only
   through `parseForeign`, which wants at least one known section. A picked file
   with lines and not one entry is no config and is not linked ("~open" is read first).
8. **Config keys are `package/class`**, `package/class#<serial>` for another profile
   (`AppEntry.keyOf`); launch and app info go through `LauncherApps` with the entry's profile. The
   file may name an app by key, package or system label (`ConfigText`). No change to the key or
   the file's format without the old files still reading.
9. **`LauncherLogic.foldLabel` is the only fold** for labels, needles and tags; matching alone
   also drops Latin, Greek and Cyrillic accents (`matchForm`), never what is stored. Lists sort
   with the device locale's `Collator`, never by code point.
10. **Default favorites only with no file linked** (`LauncherState.useDefaults`): picked from
    every list anew, never written. An empty file is a config: no defaults over it.
11. **Page turn = Unity-Kolibri's** (`PageTurn`, `SwipeFrame`, `SwipeGesture`): the drawer follows
    the finger both ways, `?` is the same drawer from the top and closes toward it, the flick is
    the fallback; only in `SwipeFrame.dispatchTouchEvent`, never `onInterceptTouchEvent`. Easy to
    break: a second finger cancels a drag; a drag grabs a page still moving; a page turned some
    other way aborts a running drag (`abortDrag`).
12. **Status bar hidden on the home page only**; home pads for it regardless
    (`getInsetsIgnoringVisibility`).
13. **`density` stays out of `configChanges`**: a display-size change must recreate the activity.
14. **Entries of apps that are gone stay** in the file: Verso cannot remove them (hard rule 4), they
    show nowhere (the `#` overview lists only tags of installed apps, `LauncherLogic.tagsInUse`),
    and a key of one keeps naming it until it is back (`ConfigText.resolve`). Telling "gone" from
    "not readable right now" (a paused or unlisted profile, an unmounted card) is what made
    `~cleanup` (up to 0.5.9) the riskiest command.
15. **One read of the linked file at a time** (`Commands.readLinked`): one asked for meanwhile runs
    after it; one that has not answered after `READ_STUCK_MS` is said once and left to finish,
    never read again beside it — only "~open" of the file reads it afresh and leaves it behind;
    of one file, a text never replaces one from a read started later (`Commands.adopt`), while a
    file newly linked counts whatever was read of the old one; a read whose link was replaced
    meanwhile is dropped, and so is an "~open" answer that lands after a newer one
    (`Commands.superseded`): the user's last choice stands. Nothing else runs against the config,
    so nothing holds the user back and no command waits for another. Results wait in `Results`
    for a window in front.
16. **The process owns the data; an activity is a window.** `Core`, `Commands` and `Results` live
    in `VersoApp`, see the window only as a `LauncherWindow` (never an Activity,
    `SourceGuardTest`) and keep none past a call. A recreation shares the loaded state; only
    `~restart` resets it (`Core.reset`).

---

Update this file when a rule changes or a hard-won lesson must be kept — not for TODOs, refactor
notes (they go in commit messages) or what the code already says.
