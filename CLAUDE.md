# CLAUDE.md

Project rules for **Verso Launcher**, a plain-text Android launcher. What it is and how it is
used: `README.md`; deliberate oddities: `CAVEATS.md`; tests: `app/src/test/CLAUDE.md` and
`TESTING_CONVENTIONS.kt`; the working agreement: `WHY_CLAUDE.md`. Family-wide conventions come
from `~/.claude/CLAUDE.md`, which is not in the repo: a session without it (a cloud checkout) has
only the files named here. Where the two disagree, this repo wins (Stack particulars,
`app/src/test/CLAUDE.md`). This file holds only the rules and where they are enforced; the why is
in the KDoc of the classes named.

---

## Scope

Home page ("recto"): clock, date and battery (double-tap targets), the favorites as large text;
swipe up for the drawer, pull down for the recent apps. Drawer ("verso"): Sigil's list and
command line — search, the sigils (`#` tags, `?` recents, `%` A–Z ↔ most used) and the `~`
commands (`~restart`, which also reads the config again; `~crash` only in a daily-driver build,
never abbreviated). The config — favorites, hidden apps, custom names, tags — is a text file of
the user's at one fixed place that Verso reads and never writes (`ConfigFile`, hard rules 4 and
6); it is written in any editor on a PC and put there, never edited in the app. With no file
there the default favorites show.

Deliberately NOT: icons, widgets, notification badges, a settings screen (the look is constants
in `HomeStyle.kt`), left/right swipes, any runtime permission, private space (no
`ACCESS_HIDDEN_PROFILES`). Apps of every profile come from `LauncherApps`; paused and archived
ones stay listed, dimmed (`AppStatus`).

New features are allowed (the freeze ended 2026-10-25), bounded by this scope, hard rule 1 and
the bar that nothing regresses. Verso succeeds Sigil and Kolibri, both discontinued: never port
anything back, no compatibility to keep.

---

## Done means

- **`./gradlew check` is green.** It is what CI runs (`.github/workflows/check.yml`): the unit
  tests, lint, detekt and `sourceScanTest`. A change to a Markdown file alone needs only
  `./gradlew sourceScanTest`; a comment in code needs `check`, since detekt reads comments too
  (`MaxLineLength`, `OutdatedDocumentation`).
- **A feature or a non-trivial change on its own branch** (hard rule 17).
- **A fix comes with a test that fails without it** (`app/src/test/CLAUDE.md`, "Paid for
  already"), and a change of behaviour with its comment (Comments and KDoc).
- **In a cloud session,** `CLAUDE-CLOUD.md` first.

---

## Stack particulars

The point is a release APK with **no dependencies**: Kotlin stdlib and platform APIs only.

- **No Compose, Material 3, AndroidX, coroutines or ViewModel.** Platform Views built in code, no
  XML layouts. `LauncherState` is the ViewModel in plain Kotlin, one per process (`Core`).
- **Persistence:** the config is the user's file, `config.txt` in the app's external files folder
  (hard rules 4 and 6). The app keeps only small files of its own, each rewritten whole through a
  temp file and a rename, none of them precious: `order.txt` (`%`, `DrawerOrder`),
  `home-cache.txt`, `usage.txt`, `recents.txt`. Neither the JSON of
  earlier versions (`verso.json` and its backups) nor the `config-link.txt` and
  `config-cache.txt` of 0.6.x, nor the `filesDir/guru/` of 0.6.7 and before, are read any more.
  No cloud backup; a device transfer leaves out the home-cache (`data_extraction_rules.xml`).
- **Tests:** JUnit 4 on the JVM, Robolectric only where a real widget is needed, no mocks (fakes
  and manual queues), no instrumented tests.
- **Warnings are errors** — Kotlin, lint and detekt (`config/detekt/detekt.yml`, part of
  `check`).
  - A deliberate finding gets a `@Suppress` at the narrowest scope. When the function does more
    than the `try`, that is the `try` itself: `val x = @Suppress(…) try`.
  - Its reason is a comment on the line above. A comment behind the annotation would enter
    detekt's baseline IDs.
  - A deliberate setting is configured, not baselined. `lint-baseline.xml` and
    `detekt-baseline.xml` only ever shrink.
- **Comments and KDoc** are written for the next session, which trusts them. English, like the
  code. `ConfigFile.kt` and `DisposableFile.kt` are the model.
  - **Only a non-obvious why:** a thread or ordering contract, a platform fact (with the version
    or device it holds for), why the obvious fix is wrong, a hard-rule anchor. Never a list of
    call sites, the story of a change, or what the code already says.
  - **Whole sentences, the condition spelled out:** "If order.txt is lost, the drawer is A to Z
    until the next %", not "Lost, it costs the order".
  - **Contracts on lines of their own**, each starting with a fixed prefix so a search finds
    them: `Thread:` (which thread may call, where a hand-back runs), `Invariant:` (what always
    holds, and what keeps it so), `Why:` (a reason the sentences before do not give). A
    continuation line is indented two spaces.
  - **In KDoc a name of code is a link**, `[ConfigFile.read]`, never plain prose. Why: the IDE
    follows and renames a link, `CommentLinkTest` fails on one whose name is gone, and a plain
    name outlives its declaration unseen.
  - **Code that ships links only to code that ships.** The IDE does not resolve a test from it,
    so a test is named in prose there.
  - **Files, commands and other text** go in backticks or quotes.
  - **`CommentLinkTest` checks more than KDoc:** a name in brackets in a line or block comment
    (the IDE does not follow it there), and in Markdown `Owner.member` and a Kotlin file's name
    in backticks.
  - **Each fact in one place:** a rule is its hard rule here or one KDoc, and everything else
    points to it ("hard rule 15", `[ConfigFile.read]`). `CAVEATS.md` tells users in their words.
  - **A change of behaviour changes its comment in the same commit.** detekt's
    `OutdatedDocumentation` checks `@param`/`@property` names against the declaration, not their
    content. Older KDoc moves to this form when its file is touched; there is no sweep.
- **Debug build:** `applicationIdSuffix = ".debug"`, so a debug build installs beside the release.
- **No crash reporter** (Guru was removed after 0.6.7): a crash is in logcat only. Release builds
  are obfuscated and keep source file and line numbers, so each frame names its mapping
  (`r8-map-id-<hash>`) for retrace: `./crash.sh` in `~/AndroidStudio_Projects/retrace`, not in
  this repo.
- **Daily-driver build:** `./gradlew bundleRelease -PdailyDriver` (and every debug build)
  compiles in `~crash`. `DailyDriver.ENABLED` comes from twin files in `src/dailyDriver` and
  `src/public`, not from BuildConfig: every buildFeature stays off. The public build has an even
  versionCode, the daily-driver build the odd one above it and `-daily` in its name (`0.4.2` = 24,
  `0.4.2-daily` = 25). **A release bump is versionName +0.0.1, versionCode +2.**
- **Release mapping:** kept in `~/apk/mappings/verso/<versionName>-<versionCode>/` (Gradle
  property `verso.mappingArchive`) by `archiveProguardMapping`, not in the repo; without it a
  release's crash cannot be read back.

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

- **Process:** `VersoApp` (the IO executor, `Core`, the live window, `ProcessState`) and `Core`
  (the one `LauncherState`, built from the user's file, and the app list).
- **Window:** `MainActivity` (refusals up front, `%`, `~restart`; the process sees it only as a
  `LauncherWindow`), `AppLauncher` (a start or "App info" in the entry's profile, a
  paused profile turned on), `PageTurn` and `SwipeFrame`, `HomePage`, `AppDrawer`, `OutlinedViews`.
- **Pure:** `LauncherLogic` (every decision: modes, commands, search, sort, rows, actions, tags,
  default favorites), `LauncherState`, `ConfigText` (the file's lines, the apps they name,
  `ConfigText.readCapped`), `SwipeGesture`, `WallpaperPalette`, `HomeStyle`; the data
  `AppEntry` and `VersoConfig` (immutable, resolved anew from the text).
- **IO:** `ConfigFile`, `DrawerOrder`, `Apps` (enumeration, default favorites),
  `HomeCache`, `UsageLog`, `RecentsLog`, and `Guard` (hard rule 2's backstop, beside it
  `logWarning`).

The rest are small helpers named for what they do.

Data flow of the app list:

1. A `LauncherApps` callback or a profile broadcast reaches `MainActivity.appsMaybeChanged`.
2. `LauncherState.refresh()` runs `MainActivity.CHANGE_SETTLE_MS` later if the window is in
   front, so a burst of changes is one refresh, else at the next `onResume`.
3. The IO thread reads the apps of every profile, system labels only.
4. On main the newest load generation wins. The file's text is resolved against the list
   (`ConfigText.resolve`) and the custom names are applied (`LauncherLogic.named`).
5. `AppDrawer.refilter` draws it.

Data flow of the user's file: the first `MainActivity` or a `~restart` (`Core.reset`) calls
`ConfigFile.read` on main, and a new `LauncherState` is built with that text, which it keeps for
its life.

---

## Hard rules

Code and KDoc cite these by number, so a rule is never renumbered: a new one goes at the end, a
dropped one keeps its number as "(removed)".

1. **No production dependencies.** `dependencies {}` holds only `testImplementation`. New deps
   and build plugins need the user's OK.
2. **A HOME app must never throw.**
   - On the IO thread, every task runs behind `Guard` and degrades.
   - Everywhere else the code is crash-only. Disposable work may crash. A catch belongs in three
     places alone:
     - Where it breaks a crash loop.
     - Around the config file's read (`ConfigFile.read`, on main as a state is built), so that a
       file that fails to read is a log line.
     - Where another app or the system refuses what the user asked for (a start, App info, a
       profile turned on).
   - A catch on main always logs, and when the user's action failed it tells the user what
     failed. Never a silent catch-all. The catches that only log are the reads on main before the
     state exists (hard rule 3). At the first start nothing is on screen yet to say it on. At
     `~restart` the user acted, and they still only log: a config file that cannot be read is no
     file (hard rule 4), and `order.txt` is disposable (`DrawerOrder`).
3. **One IO thread for Verso, process-wide** (`VersoApp.io`).
   - Never another executor for Verso's own work, and never shut down.
   - Main-thread state is touched only on main.
   - A hand-back for the process goes through `VersoApp.onMain` and is never dropped. A hand-back
     for one window goes through `MainActivity.onUi` and is dropped once that window is
     destroyed.
   - Deliberate exceptions, not breaches to fix: what is read on main before the state exists
     (hard rule 5). That is the user's config file and the app's own small files the state needs
     from the start (`order.txt`, `DrawerOrder`).
4. **Verso never writes the config.**
   - The user's file is read, never written, and no document is ever opened for writing
     (`SourceGuardTest`). The user makes the file in an editor.
   - A file that cannot be read counts as no file: the default favorites show, a log line is
     written, nothing appears on screen.
   - A line that is no entry is logged and skipped; the rest of the file still counts.
   - In-app editing stays out. Anything that changed the config here would bring back the saves,
     backups and undo points this rule removed.
5. **The config is there before the state exists** (`Core.newState`, on main: the user's file at
   its fixed place, a few KB on local storage), so there is no "not loaded yet" to guard. Home and
   its cache are drawn only once the first app list is in (`LauncherState.appsLoaded`).
6. **The config file is the user's** (`ConfigFile`): `config.txt` in the app's external files
   folder (`Android/data/<package>/files/`), put there from a PC or with `adb push`; Verso lists,
   renames or deletes nothing of theirs and needs no storage permission. Since Android 11 no app
   on the phone reaches that folder, and an uninstall deletes it: the user's copy on the PC is the
   backup (`CAVEATS.md`).
7. **A file from outside is read as the line format, and only as that** (`ConfigText.parse`),
   through `ConfigText.readCapped`: a file past its cap is refused whole, never cut. An old JSON
   backup put in its place sets nothing: every line of it is skipped.
8. **Config keys are `package/class`**, `package/class#<serial>` for another profile
   (`AppEntry.keyOf`); launch and app info go through `LauncherApps` with the entry's profile. The
   file may name an app by key, package or system label (`ConfigText`). No change to the key or
   the file's format without the old files still reading.
9. **`LauncherLogic.foldLabel` is the only fold** for labels, search text (needles) and tags.
   - Matching alone also drops Latin, Greek and Cyrillic accents (`LauncherLogic.matchForm`).
     What is stored keeps them.
   - Lists sort with the device locale's `Collator`, never by code point.
10. **Default favorites only with no config file** (`LauncherState.useDefaults`): picked from
    every list anew, never written. An empty file is a config: no defaults over it.
11. **Page turn** (`PageTurn`, `SwipeFrame`, `SwipeGesture`).
    - The drawer follows the finger both ways. `?` is the same drawer, coming from the top and
      closing toward it. The flick is the fallback.
    - Touch is handled only in `SwipeFrame.dispatchTouchEvent`, never in `onInterceptTouchEvent`.
    - Easy to break, each of these must keep holding: a second finger cancels a drag; a drag
      grabs a page that is still moving; a page turned some other way aborts a running drag
      (`SwipeFrame.abortDrag`).
12. **Status bar hidden on the home page only**; home pads for it regardless
    (`getInsetsIgnoringVisibility`).
13. **`density` stays out of `configChanges`**: a display-size change must recreate the activity.
14. **Entries of apps that are gone stay** in the file: Verso cannot remove them (hard rule 4), they
    show nowhere (the `#` overview lists only tags of installed apps, `LauncherLogic.tagsInUse`),
    and a key of one keeps naming it until it is back (`ConfigText.resolve`). Telling "gone" from
    "not readable right now" (a paused or unlisted profile, an unmounted card) is what made
    `~cleanup` (up to 0.5.9) the riskiest command.
15. **The config is read once per state** (`ConfigFile.read`), on main.
    - It is read when the first activity builds the state and at every `~restart`
      (`Core.reset`), never at a return to the launcher.
    - A state keeps its text for its life (`LauncherState`). A changed file counts from the next
      `~restart`.
    - The read has no thread, no copy and no read numbers of its own. Why: a fixed local file
      cannot hang the way a document provider can, and that is the point of the fixed place.
16. **The process owns the data; an activity is a window.** `Core` lives in `VersoApp`, sees the
    window only as a `LauncherWindow` (never an Activity, `SourceGuardTest`) and keeps none past a
    call. A recreation shares the loaded state; only `~restart` resets it (`Core.reset`).
17. **Every feature and every non-trivial change on its own branch**, never straight on `main`;
    when it is merged is the user's call (`WHY_CLAUDE.md`). No tool enforces it: it is the
    working agreement. Why: a branch can be dropped whole and reviewed alone.

---

Update this file when a rule changes or a hard-won lesson must be kept — not for TODOs, refactor
notes (they go in commit messages) or what the code already says.
