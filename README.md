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
- Your favorites in large bold text, in the order of your config file, top to bottom. Tap to
  launch, long-press for *App info*. More favorites than fit
  simply scroll. Home text sizes are fixed (dp): they do not follow the system font size.
- All home text (and the two icons) carries Kolibri's thin tonal outline, so it stays
  readable on any wallpaper without darkening it.
- With no config file, Verso shows your default phone, messenger (default SMS app),
  email, browser and camera as favorites.
- No favorites yet? A short hint says where they come from (tap it to open the drawer).
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
    finds Über, `lodz` finds Łódź). Names that start with `text` sit nearest the command line,
    where **Enter** launches, then names with a word starting with it, then the rest
  - `*` — the drawer with your favorites nearest the command line; `*text` or `text*` — only
    names that **start with** `text`
  - `#` — the tags in use; tap one (or **Enter** on the nearest) for its apps. `#text` — the
    apps under every tag starting with `text`, hidden ones included. With no such tag the
    package names are searched instead: `#google` or `#samsung` lists a maker's apps
  - `?` — the last 8 launched apps, newest nearest the command line; **Enter** relaunches the
    most recent, `?text` narrows them
  - `%` — **Enter** switches the plain drawer between A to Z and **most used first** (each
    launch counts, fading with time: a week old about half), and keeps it
  - `~[text]` — commands. `~` lists them, typing narrows the list; tap one, or **Enter** once
    what you typed can only mean one (`~r` is `~restart`, `~v` is `~verify`; a bare `~` is none):
    - `~restart` — read your config file and everything else again and rebuild the list: the
      way to make a changed config file count; `~verify` then says what the read found
    - `~verify` — what the last read of your config file found, a line each: a file that is not
      there, too big or unreadable (and where it is looked for), each line that is no entry, and
      each line that names no installed app, with its number and text. It changes nothing; fix
      the file on the PC and `~restart`
    - `~crash` — only in a daily-driver build (`-PdailyDriver`, and every debug build): crash
      on purpose, to see the crash-only paths. Never abbreviated
- Tap a row or press **Enter** to launch the row nearest the command line. On an empty line
  **Enter** only drops the keyboard. The `×` at the end of the line clears it.
- Long-press any row for its *App info*.
- Registers as `HOME` + `LAUNCHER`, `singleTask` (package `com.github.reygnn.verso_launcher`).

## The config file

Favorites, hidden apps, custom names and tags live in a plain text file of yours, `config.txt`
in Verso's own folder on the phone's storage. Verso reads it when it starts and at every
`~restart`, and never writes it. Write it in any editor on a PC and put it there:

```
adb push config.txt /sdcard/Android/data/com.github.reygnn.verso_launcher/files/config.txt
```

then type `~restart` in the drawer (a debug build's folder ends in `.debug`). Since Android 11
no app on the phone reaches that folder, a file manager included, and uninstalling Verso deletes
it: keep your copy on the PC — it is your backup, and it takes your config to another phone.

```
# a comment
fav WhatsApp
fav org.mozilla.firefox
hide com.samsung.android.bixby.agent
name com.google.android.apps.maps = Maps
tag Firefox = web, browser
```

- `fav` — a favorite; home shows them in the file's order
- `hide` — not in the plain drawer (a search still finds it; a favorite always shows)
- `name … = …` — your own name for an app; the last one given wins
- `tag … = a, b` — its tags, lowercased and trimmed (`#` and `,` are dropped); they add up

An app is its name as the system shows it (`Gmail`, `Gmail (Work)` for a work copy), its package
(`com.google.android.gm`: every app of it in your own profile) or its key
(`package/class`, `#serial` for another profile). A name is matched ignoring case, never your own
names; it changes with the device language, a package does not, so a package outlasts a
language switch. Long-press an app for its *App info* if you need the package. A line Verso does
not understand is skipped (named in the system log, tag `Verso`, and by `~verify`); the rest
still counts. An app that is not installed is simply left out until it is; `~verify` names such
lines too, since a typo looks the same.

What can go wrong around the config file, a result that comes later and moving to a new phone:
`CAVEATS.md`.

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
- no Navigation / Fragments — one `Activity`, no dialogs
- no DataStore / Room / SharedPreferences — the config is your own text file, which Verso only
  reads; in `filesDir` it keeps only throwaway files: `order.txt` behind `%`, `home-cache.txt`,
  a copy of the favorites as shown, `usage.txt` and `recents.txt` behind `%` and `?`
- no ACRA / Timber / crash reporter — `android.util.Log`; a crash is in logcat and nowhere else
- no ViewBinding / XML layouts — UI built in code

The launcher is one package of small files: `MainActivity.kt` is the window's framework wiring,
the rest is pure and unit-tested on the JVM, a thin view class, or the process's own parts that
outlive an activity. Each file's KDoc says what it is for.

A release build renames classes (R8) and keeps line numbers; its mapping goes to
`~/apk/mappings/verso/<versionName>-<versionCode>/`, where
[retrace](https://github.com/reygnn/retrace) reads a crash in logcat back with it. The only *shipped* dependencies are the Kotlin stdlib (added
automatically by AGP's built-in Kotlin); JUnit and Robolectric are `testImplementation`, so the
release stays dependency-free.

## Build

```bash
./gradlew bundleRelease   # unsigned release AAB (R8 full mode + resource shrink)
```

Stack matches the family baseline: AGP 9 built-in Kotlin, Gradle 9, JDK 21,
`minSdk = targetSdk = 36` (Android 16, no compat shims), `compileSdk = 37`.
