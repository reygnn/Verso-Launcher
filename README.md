# Verso Launcher

A **plain text launcher for Android with a hidden command line** — the child of Kolibri
Launcher and Sigil Launcher. Neither is maintained any more; **Verso is the official
successor of both.** Kolibri gives it the quiet home screen. Sigil was a terminal: one
prompt, keyboard always up. Verso keeps that whole command line but moves it behind a
page turn, so the front looks like an ordinary minimal text launcher.

The name is the idea: a sheet of paper has a **recto** (the front) and a **verso** (the
back). The front is your home screen; swipe up and you turn the page to the back, where
every app and every sigil command lives.

<p>
  <img src="docs/screenshots/home.png" alt="Front page: clock, date, battery and favorites" width="270">
  &nbsp;
  <img src="docs/screenshots/drawer.png" alt="Back page: #google lists the Google apps, the command line and keyboard below" width="270">
</p>

## The two pages

**Front (home)** — Kolibri Launcher's home layout, with Kolibri's default settings, no
keyboard, no prompt:

- The clock (60dp, light) and below it the short date and the battery level, with a
  bolt while charging and a shield while battery protection holds the charge. **Double
  tap** the clock for your alarms, the date for your calendar, the battery for battery
  usage — a single stray touch does nothing, as in Kolibri.
- Your favorites in large bold text, in your own order, top to bottom. Tap to launch,
  long-press for the rename/tags dialog (with *App info*). More favorites than fit
  simply scroll. Home text sizes are fixed (dp): they do not follow the system font size.
- All home text (and the two icons) carries Kolibri's thin tonal outline, so it stays
  readable on any wallpaper without darkening it.
- First start: Verso picks your default phone, messenger (default SMS app), email,
  browser and camera as starting favorites. Only once — remove them and they stay gone.
- No favorites yet? A short hint says how to add them (tap it to open the drawer).
- Text follows the wallpaper: white with a dark outline on a dark wallpaper, black with
  a light outline on a light one (the drawer and the status-bar icons too), switching
  by itself when you change the wallpaper.
- **Pull up** → the drawer comes up with your finger; let go past about a quarter of
  the screen (or flick) and it opens, otherwise it drops back. **Pull down** → your
  recently used apps come down from the top like a HUD, the same way with your finger
  (the drawer on its `?` view, without the keyboard); **pull up** to put them away, or
  Back, which goes straight home. Clear the `?` (×, or type) and it is the plain drawer
  again, closed with a pull down. A pull down that starts at the very top edge is left to
  the notification shade. A second finger cancels a pull.
- With a hardware keyboard, just type: the drawer opens with what you typed in the
  command line (the Search key opens it empty).

**Back (drawer)** — the original Sigil Launcher:

- Opens on **every app, A to Z**, with the command line at the bottom and the
  keyboard up. **Enter** on the empty line just drops the keyboard so you can scroll
  the whole list.
- Type to search; results gather right above the command line and **Enter** launches
  the nearest one.
- **Pull down** with the list at its top — the drawer follows your finger, back and
  forth, and closes past about a quarter of the screen or on a flick (a list that can
  still scroll scrolls first, then hands over) — **Back** on an empty line, or **HOME**
  turns back to the front page. Back on a non-empty line first
  clears it (a bare `?` excepted: it goes straight home). With gesture navigation the
  back swipe previews the close — the drawer slides down a little with your thumb and
  drops back if you change your mind.
- Launching an app (or leaving the launcher any other way) always brings you back to
  the front page next time.

## The command line

- Lists the launchable apps of every profile through `LauncherApps` — your own plus a work
  profile or cloned apps, which carry the system's badge ("Calculator (Work)") and start in
  their own profile — in the favorites' colour, right on the wallpaper. Installs, uninstalls and
  updates show up at once.
- Apps that cannot start right away stay listed, **dimmed**: those of a paused work profile and
  archived apps. The first tap on a paused one turns its work apps back on (a toast says when it
  is ready); an archived one offers the system's restore.
- The bottom command line drives everything; the leading character picks a mode:
  - *(empty)* — the full drawer, A to Z, without hidden apps (**a favorite always shows, even
    when hidden**)
  - `text` — substring search across **all** apps, hidden included, accents ignored (`uber`
    finds Über). Names that start with `text` sit nearest the command line, where **Enter**
    launches, then names with a word starting with it, then the rest
  - `*` — the drawer with your favorites nearest the command line; `*text` or `text*` — only
    names that **start with** `text`
  - `#` — the tags in use; tap one (or **Enter** on the nearest) for its apps. `#text` — the
    apps under every tag starting with `text`, hidden ones included. With no such tag the
    package names are searched instead: `#google` or `#samsung` lists a maker's apps
  - `##` — tags in bulk: pick a tag, then tap any app to toggle it. The long-press dialog
    sets several tags on one app. Tags are lowercased and trimmed; `#` and `,` are dropped
  - `-[text]` — toggle hidden; `![text]` — toggle favorite; `!!` — reorder favorites (tap
    one to pick it up, `»`, tap another to drop it there). All saved at once; the front page
    shows the favorites in this order
  - `?` — the last 8 launched apps, newest nearest the command line; **Enter** relaunches the
    most recent, `?text` narrows them
  - `%` — **Enter** switches the plain drawer between A to Z and **most used first** (each
    launch counts, fading with time: a week old about half), and keeps it
  - `~[text]` — commands. `~` lists them, typing narrows the list; tap one, or **Enter** once
    what you typed can only mean one (`~b` is `~backup`). Every change is saved at once, so
    there is no save command. One command at a time: a second one, or an edit, is refused
    meanwhile and stays on the line:
    - `~restart` — read the config again and rebuild the list (after editing `verso.json` by
      hand, or when something looks stale)
    - `~backup` — the config to `Download/Verso/` as a new file named after the moment
      (`verso-2026-10-13_120745.json`). Verso never deletes one. It survives an uninstall
      and is how a config moves to another phone
    - `~restore` — adopt the newest backup. The config it replaces is saved first as an
      undo point (`verso-pre-restore-…`); no undo point, no restore
    - `~restore-saf` — the same with a file you pick, e.g. from another phone or an earlier
      install (`~restore` sees only what this install wrote). A dialog shows what the file sets
      before anything changes
    - `~cleanup` — remove what the config still keeps for uninstalled apps (favorite slot,
      hidden mark, custom name, tags). It asks first and leaves an undo point
      (`verso-pre-cleanup-…`); what counts as uninstalled is in `CAVEATS.md`
    - `~undo` — take back the last `~restore`, `~restore-saf`, `~cleanup` or `~undo` (a
      second `~undo` redoes it). Older undo points: `~restore-saf`
    - `~crash` — only in a daily-driver build (`-PdailyDriver`, and every debug build): crash
      on purpose, to see Guru's report. Never abbreviated
- Tap a row or press **Enter** to launch the row nearest the command line. On an empty line
  **Enter** only drops the keyboard. The `×` at the end of the line clears it.
- Long-press any row to set a custom name and its tags (comma-separated).
- Registers as `HOME` + `LAUNCHER`, `singleTask` (package `com.github.reygnn.verso_launcher`).

What can go wrong around the backups, an unreadable config, a result that comes later and
moving to a new phone: `CAVEATS.md`.

## Lineage

The front page is a port of Kolibri Launcher's home
screen (`fragment_home.xml`, `HomeFavoritesAdapter`, `TextOutline`, the default layout
values) to plain platform widgets — no AndroidX, no RecyclerView. Kolibri's adjustable
settings (size, spacing, alignment, colours) are fixed at Kolibri's defaults; the numbers
live in `HomeStyle.kt`. Not ported: the alarm/calendar indicators next to the clock and
the double-tap events dialog (they need calendar permission), and the left/right swipe
actions.

Kolibri Launcher and Sigil Launcher are both discontinued, and Verso is the official
successor of both. It started as a copy of the Sigil 0.2.x line (favorites, hidden apps,
custom names, tags, `~` commands) with Kolibri's home screen as its front end. Everything the prompt can do
is unchanged except the empty line: in Sigil it showed your favorites, in Verso it
shows the full drawer, because the favorites now live on the front page.

## What was chopped

Inherited from Sigil, which drops **all** of Kolibri Launcher's stack:

- no Compose, no Material, **no AndroidX at all** — platform widgets only
- no Hilt / DI — a single `Activity` wires itself
- no Navigation / Fragments — one `Activity` (rename is a bare platform dialog)
- no DataStore / Room / SharedPreferences — the small amount of state (hidden
  apps, favorites, custom names, tags) is a single hand-rolled `verso.json` in
  `filesDir` (beside it only throwaway files: `home-cache.txt`, a copy of the favorites for an
  instant first frame, `usage.txt` and `recents.txt` behind `%` and `?`, and `stalls.txt` for the
  IO watchdog; and Guru's crash
  reports in `guru/` until the next start saves them, see "Crash reports"), kept the way
  Android keeps SharedPreferences (the last complete file waits as `verso.json.bak` until a
  write is done; a file that cannot be read loads as empty, and a toast says so) — your own `~backup`s are the
  copies to fall back on; and edited only through the in-app modes above
- no ACRA / Timber — `android.util.Log`; crash reports come from Guru, vendored as source
  (no library, no permission, no network — see "Crash reports")
- no ViewBinding / XML layouts — UI built in code

The launcher is one package of small files: `MainActivity.kt` is the window's framework wiring,
the rest is pure and unit-tested on the JVM, a thin view class, or the process's own parts that
outlive an activity. Each file's KDoc says what it is for.

## Crash reports

Verso carries [Guru](https://github.com/reygnn/guru), a crash reporter vendored as source in
`guru/` (a `git subtree`, never edited here). A crash is written to disk while the process
dies; at the next start, before the alert, Guru saves it without asking as a `.json` file in
`Download/Guru/` (MediaStore, no permission; Guru keeps its newest 32 there) and Verso shows
the Amiga alert saying so, a black strip at the top with Verso slid down beneath it until the
alert closes — unless the alert itself was what crashed: then the file is saved and the alert
left out. **Hold** hands the saved files to an app you pick (mail, a messenger,
guru2acra), **double-tap** or **Back** closes the alert; once seen it does not come again. To be
rid of a report, delete its file. Guru itself sends nothing; the files are readable by anything
that reads `Download` — see `CAVEATS.md`.

<p>
  <img src="docs/screenshots/guru-alert.gif" alt="Guru's alert after a crash: a black strip at the top with the blinking red box — Software Failure, the Guru Meditation number, saved to Download/Guru — and the hint, the home page slid down beneath it" width="270">
</p>

Guru adds about 30 KB of code and keeping class names (`-dontobfuscate`, for readable crash
traces) about 18 KB: the release APK went from about 180 to 231 KB. The only
*shipped* dependencies are the Kotlin stdlib (added automatically by AGP's
built-in Kotlin); JUnit, org.json and Robolectric are `testImplementation`,
androidx.test is `androidTestImplementation`, so the release stays dependency-free.

## Build

```bash
./gradlew bundleRelease   # unsigned release AAB (R8 full mode + resource shrink)
```

Stack matches the family baseline: AGP 9 built-in Kotlin, Gradle 9, JDK 21,
`minSdk = targetSdk = 36` (Android 16, no compat shims), `compileSdk = 37`.
