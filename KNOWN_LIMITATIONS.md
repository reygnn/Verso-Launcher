# Known limitations

Deliberate design choices that can look like bugs. Listed here so the behaviour
is documented before it lands in the issue tracker.

## Recents (`?`) reset on restart

The `?` recents list is held **in memory only** — it is never written to
`verso.json` (that file is the user's config: favorites, hidden apps, custom
names). The list therefore lives exactly as long as the launcher process.

Consequence: whenever Android kills the launcher process, `?` comes back empty.
On low-RAM / low-end devices the OS reclaims the launcher's memory
**aggressively**, often while another app is in the foreground, so the process is
killed and cold-started far more frequently than on a high-end phone. A normal
background→foreground cycle keeps the list; a process death clears it.

This is intended: recents are a convenience for the current session, not durable
state, and keeping them out of `verso.json` avoids write churn and keeps the
config file to genuine user intent. To avoid the "where did my recents go?"
surprise, an empty `?` says so in place of the list ("no recent apps yet — the list
resets on restart") — see `LauncherLogic.emptyHint` and `empty_no_recents`.

If durable recents are ever wanted, persist `recentKeys` to a **separate** small
file (not `verso.json`) and load it on start.

## Private space is not reachable

Apps are listed through `LauncherApps` across every profile the launcher is shown:
the personal one plus a work profile or cloned apps (badged by the system, e.g.
"Calculator (Work)"). This was verified with a managed test profile. Android 15's
private space is a hidden profile: the system shows it only to a default launcher
holding `ACCESS_HIDDEN_PROFILES`, which Verso does not request. Its apps therefore
never appear in Verso, and with Verso as home there is no way into the private space.
Supporting it would mean the permission plus hiding those apps while the space is
locked — listing them dimmed, like a paused work profile, would reveal they exist.

Apps of a paused work profile and archived apps stay listed, dimmed; the first tap
turns the profile back on (then a toast says the app is ready) or offers the system's
restore. Both were verified on the A17. Turning a profile back on needs Verso to be
the default home app; otherwise the system refuses and Verso shows "denied".

## What the backups reveal, and moving to a new phone

`~backup` writes the whole config to `Download/Verso/` in plain JSON: favorites, custom
names, tags — and the list of apps you chose to HIDE. No other app can read it without
an "all files" permission or your own pick in the file picker, but a file manager, a
cloud-sync app that scans Download, or a PC over USB can. The files outlive an
uninstall. Keep that in mind before sharing or syncing the folder.

The hidden list (like favorites, names and tags) also keeps the apps you have since
uninstalled: nothing is pruned on its own, so a reinstalled app gets its place, name and
tags back — but a backup still names a hidden app removed years ago until you run
`~cleanup`, which removes those entries (after an undo point).

"Exactly one backup and one undo point of each kind" holds per installation: Android lets an app
see and replace only the files it created itself. After a reinstall the old files are
no longer Verso's — they stay in `Download/Verso/`, invisible to `~restore` (reachable
with `~restore-saf`), and new ones are written beside them. A debug build is another
app to Android, so its backups sit there too. Delete stale ones with a file manager.

The app's own `verso.json` is excluded from Android's cloud backup
(`data_extraction_rules.xml`), but a device-to-device transfer copies it on purpose
(without its `.tmp`/`.bak` recovery copies and the home cache). Entries for apps in a
work profile or clone carry that profile's serial (`package/class#10`), which on the new
phone may belong to no profile or another one; such entries then simply match nothing.
The same happens on one phone when a work profile is removed and set up again: the new
profile gets a new serial, so its apps start without favorites, names, tags or hidden
state. The personal profile's config moves over
intact, and the default favorites are not seeded there (the install is not fresh).

## Gestures on the front page

Swipe up (drawer) and swipe down (recent apps) are wired, as in Kolibri. Swipe
left/right and a double-tap on empty space do nothing (the clock, date and battery
take a double-tap; see the README). Kolibri's alarm/calendar indicators
next to the clock are not ported (calendar events need the READ_CALENDAR
permission; Verso asks for none). There is no double-tap-to-lock either (that needs
an accessibility service).

The home page uses Kolibri's DEFAULT look (text size, spacing, start alignment, text
with a tonal outline — white on a dark wallpaper, black on a light one). There is no
settings screen to change it; the values are constants in `HomeStyle.kt`.

The home text sizes are fixed in dp, so they ignore the system font size (the drawer's
rows do follow it). Chosen for a stable layout like Kolibri's; someone who needs larger
text gets it in the drawer, or through the display size setting, which scales both.

The swipe is not available to a screen reader, so the front page exposes an
"Open all apps" custom accessibility action (TalkBack's actions menu), and the
empty-favorites hint is tappable.

## An unreadable config locks editing until it is read, restored or reset

If `verso.json` is there but cannot be read — and neither can `.bak` — the config
counts as **not loaded**: the drawer still lists and launches every app, but every
edit and save is refused with a toast, so the empty default in memory can never be
written over the real file (one bad read used to cost all favorites, names and tags
that way). It is retried on every return to the launcher and by `~load`, so a
passing read error heals by itself.

Meanwhile the drawer shows the hidden apps too: nothing is hidden in the empty
default that stands in for the unreadable config in memory (it is never saved).

If the files stay unreadable (both corrupt), `~restore` or `~restore-saf` with any
`verso*.json` is the way out — over an unreadable config without an undo point,
since there is nothing readable to keep; the unreadable files are set aside instead
(`verso.json.broken`, …). Without any backup, `~reset` starts over like a fresh
install, setting the files aside the same way. Set aside means renamed, never
deleted, and an earlier set-aside is never overwritten (`.broken-2`, …). The app's
files directory is private, though — out of reach without root on a release build —
so the set-aside config is also copied to `Download/Verso/verso-broken-<date>_<time>.json`
(the latest one; best effort). If it was only unreadable for a moment, `~restore-saf`
with that copy brings it back; if it is really damaged, the bytes are there to look at.

**After a reset, run `~restore` before anything else if you have a backup, and never
`~backup` first:** the fresh config is nearly empty, and a backup of it replaces the
real one in `Download/Verso/`. The same holds after clearing the app's storage.

## Leaving the launcher resets to the front page

Whenever the launcher stops (an app was launched, the screen went off, the recents
screen was opened) the drawer is closed and its command line cleared. Coming back
always lands on the front page, like most launchers — a half-typed search or an
edit mode does not survive a trip away.

## Portrait lock does not hold on tablets and foldables

The launcher is locked to portrait (`android:screenOrientation="portrait"`). Since
Android 16, apps targeting SDK 36 have orientation locks ignored on large screens
(smallest width ≥ 600dp — tablets, the inner display of foldables), so there the home
page and drawer still rotate. On phones the lock holds. The layout handles both, and
`configChanges` keeps a rotation from recreating the activity either way.
