# Caveats

Deliberate design choices that can look like bugs. Listed here so the behaviour
is documented before it lands in the issue tracker.

## Recents (`?`) live in a disposable file

The `?` list is kept in `recents.txt` beside `verso.json`, not in it (that file is the
user's config: favorites, hidden apps, custom names) and not in a backup. It survives
the launcher's process being killed; a lost or torn file only means the
list starts over — the same as `usage.txt` behind `%`. It keeps the last 32 apps launched,
so up to 24 of them can be gone before `?` shows fewer than 8.

A file that is there but cannot be read is left alone, never written over: `?` and `%` work
from this process's launches only until a later read succeeds. Verso retries with the next
refresh (a package or profile change, a recreation, a resume while the app list is
incomplete), or at once with `~restart`; launches meanwhile are merged in, not lost (unless
the process ends first).

## A config that cannot be read loads as empty

Verso keeps `verso.json` the way Android keeps an app's SharedPreferences: a write cut off
halfway is undone from `verso.json.bak` at the next start. But a file that cannot be read —
broken later, or a read error the system reports, which looks the same — loads as an empty
config: no favorites, nothing hidden, and the next change writes over it. No default favorites
either, since it is no fresh install. A toast says so ("config unreadable, started empty"),
once per load, also when it comes back after a `~restart`. If it was a passing read error, the
file is still there until you change something: run `~restart` first, before anything else.
Otherwise your config comes back from your own backups (`~restore`; `~restore-saf` on another
phone). A crash or a power cut right after a change can also cost that change; the file before
it stays whole.

If you ran `~backup` after such an empty start, that empty backup is the newest, and `~restore`
takes it. The older backups are all still in `Download/Verso/` — Verso deletes none: pick the
one you want with `~restore-saf`.

## Default favorites can still be missed in two corners

On a fresh install Verso seeds the default favorites from the first app list it gets. If the
very first enumeration brings no list at all (the system answered nothing for your own
profile), it waits for the next one — also across `~restart`. A `~` command running when that list arrives holds the seeding back until the
next return to the launcher; a restore written meanwhile ends the wait, since the restored
favorites (or none) are yours. The wait lives in memory: if the process itself ends before any list arrived
*and* an edit (say `%`) had already written `verso.json`, the next start is no fresh install
and the defaults are not seeded. The same if the very first save — the seeding itself — is cut
off halfway: there is no earlier file to fall back on, so the next start finds a half-written
`verso.json`, loads it as empty and seeds nothing. Pick your favorites with `!` then.

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
the default home app; otherwise the system refuses and Verso shows "denied". The dimming is
the only mark on screen — no badge, no label; TalkBack says "paused" or "archived" with the name.

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

Verso never deletes a backup: every `~backup`, `~restore`, `~cleanup` and `~undo` (the
last three: their undo point) leaves a file of a few KB in `Download/Verso/`, and the folder grows until
you clear it with a file manager. Android lets an app see only the files it created itself:
after a reinstall the old files are no longer Verso's — invisible to `~restore` and `~undo`
(reachable with `~restore-saf`), and new ones are written beside them. A debug build is another app to
Android, so its backups sit there too.

The app's own `verso.json` is excluded from Android's cloud backup
(`data_extraction_rules.xml`), but a device-to-device transfer copies it on purpose
(without the home cache). Entries for apps in a
work profile or clone carry that profile's serial (`package/class#10`), which on the new
phone may belong to no profile or another one; such entries then simply match nothing.
The same happens on one phone when a work profile is removed and set up again: the new
profile gets a new serial, so its apps start without favorites, names, tags or hidden
state. The personal profile's config moves over
intact, and the default favorites are not seeded there (the install is not fresh).

## Crash reports land in Download/Guru

After a crash, the next start saves the report to `Download/Guru/` without asking, before the
alert says so. A report holds the full trace (exception messages can carry names, paths or
other apps' package names), an ANR's dump of every thread, the device's brand, model and
Android version, and an installation ID that stays the same until Verso is reinstalled or its
storage cleared (a move to a new phone starts a new one; the debug build has its own). Like
the backups, the files can be read by a file manager, a cloud-sync app that scans Download, a
PC over USB or an app with access to all files, and they outlive an uninstall; after a
reinstall Verso can no longer see or remove them. Every app with Guru shares the one folder.
Guru itself sends nothing; to be rid of a report, delete its file.

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

Light or dark is Android's own verdict on the wallpaper (`WallpaperColors`' dark-text hint);
with no verdict the text stays white. Samsung gives none for the home screen: One UI shows
even a still image from the gallery through its own live wallpaper service, which reports no
colours to other apps (seen on One UI 8 and 8.5, Android 16, October 2026). On a Samsung
phone the text is therefore always white with its dark outline, readable on a light
wallpaper too, but not black.

The home text sizes are fixed in dp, so they ignore the system font size (the drawer's
rows do follow it). Chosen for a stable layout like Kolibri's; someone who needs larger
text gets it in the drawer, or through the display size setting, which scales both.

The swipes are not available to a screen reader, so the front page exposes "Open all apps"
and "Open recent apps" as custom accessibility actions (TalkBack's actions menu), and the
empty-favorites hint is tappable; the home page itself is a stop, so a touch on an empty spot
reaches those actions. The pages carry titles for screen readers that announce them, but
Samsung's TalkBack (heard on the A17) speaks the element that gets focus after a turn instead:
so the search field says "Search all apps" (its visible hint starts with ">", read as "greater
than") and the list names itself ("Recent apps", "All apps"). The charge-state icons are read
with the battery line, not as stops of their own.

## A `~` command's result can come later

A `~backup`, `~restore` or `~cleanup` result is never shown over another app: if you leave
Verso before it ends, the toast waits and appears when you come back — sometimes much later.
A file picked with `~restore-saf` is shown in a dialog before it is restored; read while you
were away or while Verso was still starting, the dialog comes when you are back, and leaving it
unanswered drops the pick (a toast says so). Confirmed while a command is still running, the
file is restored right after it; meanwhile `~restore`, `~restore-saf` and `~undo` say a file is still
being restored. The pick lives in memory only: if the system ends Verso's process before
it is restored — say while a provider is slow to hand the file over or never does — it is gone
without a word. Pick it again.

## Leaving the launcher resets to the front page

Whenever the launcher stops (an app was launched, the screen went off, the recents
screen was opened) the drawer is closed and its command line cleared. Coming back
always lands on the front page, like most launchers — a half-typed search or an
edit mode does not survive a trip away.

## A hardware keyboard, two corners

Typing on a hardware keyboard on the front page opens the drawer with that character in the
command line. A dead key that starts the typing (an accent waiting for its letter) loses its
accent there: the drawer gets the plain letter. In the recent apps (`?`) the command line has
no focus, so keys go nowhere until you tap it.

## An app that renames its launcher activity loses its place

Favorites, custom names, tags and hidden marks belong to an app's launcher activity
(`package/class`). An app that renames that activity in an update — rare — comes back as a new
entry: its favorite slot, name, tags and hidden mark stay with the old key, and `~cleanup` later
offers to remove them. Favorite, rename or tag it again. Carrying them over would need a
migration of the stored keys, which Verso does not attempt for this.

## A stuck system call holds the `~` commands

The `~` commands run one at a time, their disk and system work on one thread. If a system
service never answers (MediaStore or the package manager, not seen so far), that command never
ends: every further `~` command — `~restart` too — and every edit says it is busy, while
launching, searching and turning pages go on. Only force-stopping Verso (Settings → Apps →
Verso → Force stop) ends it; the next start is clean.

## Portrait lock does not hold on tablets and foldables

The launcher is locked to portrait (`android:screenOrientation="portrait"`). Since
Android 16, apps targeting SDK 36 have orientation locks ignored on large screens
(smallest width ≥ 600dp — tablets, the inner display of foldables), so there the home
page and drawer still rotate. On phones the lock holds. The layout handles both, and
`configChanges` keeps a rotation from recreating the activity either way.
