# Caveats

Deliberate design choices that can look like bugs, documented before they land in the issue
tracker.

## Recents (`?`) live in a disposable file

The `?` list is kept in `recents.txt` beside `verso.json`, not in it and not in a backup — like
`usage.txt` behind `%`. It survives a killed process; a lost or torn file only means the list
starts over. It keeps the last 32 launches, so up to 24 of them can be uninstalled before `?`
shows fewer than 8.

A file that is there but cannot be read is left alone, never written over: `?` and `%` work from
this process's launches until a later read succeeds — at the next refresh (a package or profile
change, a recreation, a resume while the app list is incomplete) or at once with `~restart`.
Launches meanwhile are merged in, unless the process ends first.

## A config that cannot be read loads as empty

Verso writes `verso.json` whole or not at all: a new file beside it, synced, then renamed over
it, so a crash or power cut leaves the config before the change or after it. A file that cannot
be read all the same — broken by something else, or a read error the system reports, which looks
the same — loads as an empty config (no favorites, nothing hidden, no default favorites either),
and a toast says so, once per load. It is not written over: it is set aside as
`verso.json.unreadable`. If the read error was passing, `~restart` (before you change anything)
reads it again and takes it back; otherwise `~restore` and pick your last backup. Only one file
waits aside: a second unreadable one replaces it.

## Default favorites can still be missed

On a fresh install Verso seeds the default favorites from the first app list it gets; with no list
at all it waits for the next one (also across `~restart`). A restore ends the wait: its favorites,
or none, are yours. The wait lives in memory, so the defaults are not seeded if the process ends
before any list came *and* an edit (say `%`) had already written `verso.json` — the next start is no
fresh install; pick your favorites with `!` then. A seeding whose own save is cut off halfway left
nothing behind: the next start is a fresh install again and seeds them anew.

## Private space is not reachable

Apps come through `LauncherApps` from every profile the launcher is shown: the personal one plus
a work profile or cloned apps, badged by the system ("Calculator (Work)"). Android 15's private
space is a hidden profile, shown only to a default launcher holding `ACCESS_HIDDEN_PROFILES`,
which Verso does not request: its apps never appear, and with Verso as home there is no way into
it. Supporting it would need that permission and hiding the apps while the space is locked —
listing them dimmed would reveal they exist.

Apps of a paused work profile and archived apps stay listed, dimmed; the first tap turns the
profile back on (a toast says when the app is ready) or offers the system's restore. Turning a
profile on needs Verso to be the default home app, else the system refuses and Verso says
"denied". The dimming is the only mark on screen; TalkBack says "paused" or "archived" with the
name.

## What the backups reveal, and moving to a new phone

`~backup` writes the whole config in plain JSON — favorites, custom names, tags and the apps you
HIDE — to the place you pick. In `Download` no other app reads it without an "all files"
permission or your pick in the file picker, but a file manager, a cloud-sync app that scans
Download or a PC over USB can, and the files outlive an uninstall. A cloud folder you pick holds
it too. Keep that in mind before sharing or syncing it.

Nothing is pruned: a reinstalled app gets its place, name and tags back, and a backup still names
a hidden app removed years ago. An entry of an app that is gone costs a few bytes and shows
nowhere — but its tags stay in the tag lists (`##` and the rename dialog's suggestions), so a tag
whose apps are all gone can be given to others; untick it there to be rid of it.

Verso never deletes a backup: each `~backup` is a file of a few KB where you put it, until you
delete it. `~restore` reads any of them through the picker, also the `verso-pre-restore-…`,
`verso-pre-undo-…` and `verso-pre-cleanup-…` files that versions up to 0.5.9 left in
`Download/Verso/`. The one undo point lives with the app (`verso.prev.json`), so an uninstall
takes it along; your backups are what outlives the app.

`verso.json` is kept out of Android's cloud backup (`data_extraction_rules.xml`) but copied by a
device-to-device transfer, on purpose. Entries of a work profile or clone carry its serial
(`package/class#10`), which on the new phone may belong to no profile or another one; they then
match nothing. The same on one phone when a work profile is removed and set up again: the new
one gets a new serial, and its apps start without favorites, names, tags or hidden marks. The
personal profile's config moves over intact, and no default favorites are seeded over it.

## Crash reports land in Download/Guru

After a crash, the next start saves the report to `Download/Guru/` without asking, then the alert
says so. A report holds the full trace (messages can carry names, paths or other apps' package
names), an ANR's dump of every thread, the device's brand, model and Android version, and an
installation ID that lasts until Verso is reinstalled or its storage cleared. Like the backups,
the files are readable by a file manager, a cloud-sync app, a PC or an app with access to all
files, and they outlive an uninstall; after a reinstall Verso can no longer see or remove them.
Every app with Guru shares the folder. Guru sends nothing; to be rid of a report, delete it.

## The front page

Swipe up opens the drawer, swipe down the recent apps. Swipe left or right and a double-tap on
empty space do nothing (clock, date and battery take a double-tap). No alarm or calendar line by
the clock (calendar events need a permission, and Verso asks for none), no double-tap to lock
(that needs an accessibility service), no settings screen: the look is constants in
`HomeStyle.kt`.

Light or dark text is Android's own verdict on the wallpaper (`WallpaperColors`' dark-text hint);
with none the text stays white. Samsung gives none for the home screen — One UI shows even a
still image through its own live wallpaper service, which reports no colours (One UI 8 and 8.5,
October 2026) — so on a Samsung phone the text is always white with its dark outline: readable on
a light wallpaper, but never black.

The home text sizes are fixed in dp and ignore the system font size, for a stable layout; the
drawer's rows follow it, and the display size setting scales both.

A screen reader cannot swipe, so the front page offers "Open all apps" and "Open recent apps" as
actions (TalkBack's actions menu), the empty-favorites hint is tappable, and the page itself is a
stop. Samsung's TalkBack speaks the element that gets focus after a page turn, not the page's
title: so the search field says "Search all apps" (its visible ">" would be read as "greater
than") and the list names itself ("Recent apps", "All apps"). The charge icons are read with the
battery line.

## A `~` command's result can come later

A `~backup` result is never shown over another app: a provider slow to take the file (a cloud
folder) may finish after you left Verso, and the toast waits until you come back. A file picked
with `~restore` is shown in a dialog first; read while you were away or while Verso was starting,
the dialog comes when you are back, and leaving it unanswered drops the pick (with a toast).
Meanwhile a second `~restore` and `~undo` say a file is still being restored. The pick lives in
memory only: if the process ends first — say while a provider is slow to hand the file over, or
never does — it is gone without a word; a `~restart` drops it too, with a toast. Pick it again.

## Leaving the launcher resets to the front page

Whenever the launcher stops (an app launched, the screen off, the recents screen opened) the
drawer closes and its command line is cleared: a half-typed search or an edit mode does not
survive a trip away.

## A hardware keyboard, two corners

Typing on the front page opens the drawer with that character in the command line; a dead key
that starts the typing (an accent waiting for its letter) loses its accent there. In the recent
apps (`?`) the command line has no focus, so keys go nowhere until you tap it.

## An app that renames its launcher activity loses its place

Favorites, names, tags and hidden marks belong to an app's launcher activity (`package/class`).
An app that renames it in an update — rare — comes back as a new entry; the old key keeps its
place, name, tags and mark (its tags still listed under `##`). Favorite, rename or tag it again: carrying them over would
need a migration of the stored keys.

## A stuck system call

Verso's own disk and system work runs on one thread: the app list, the saves, the disposable files.
If a system service never answers (the package manager; not seen so far), the list stays as it
was — on a cold start the drawer stays empty and home shows its cached favorites — and changes
wait to be written until you leave the launcher, when they are written at once. The commands go
on: `~backup` and `~restore` have threads of their own, `~undo` needs none. Guru saves its crash
reports through MediaStore on a thread of its own, so a stuck MediaStore holds only those.
Settings → Apps → Verso → Force stop ends a hang; the next start is clean.

## Portrait lock does not hold on tablets and foldables

The launcher is locked to portrait, but since Android 16 apps targeting SDK 36 have that ignored
on large screens (smallest width ≥ 600dp: tablets, the inner display of foldables), so there it
rotates. The layout handles both, and `configChanges` keeps a rotation from recreating the
activity.
