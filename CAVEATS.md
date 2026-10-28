# Caveats

Deliberate design choices that can look like bugs, documented before they land in the issue
tracker.

## Recents (`?`) live in a disposable file

The `?` list is kept in `recents.txt` in the app's own files, not in your config file — like
`usage.txt` behind `%`. It survives a killed process; a lost or torn file only means the list
starts over. It keeps the last 32 launches, so up to 24 of them can be uninstalled before `?`
shows fewer than 8.

A file that is there but cannot be read is left alone, never written over: `?` and `%` work from
this process's launches until a later read succeeds — at the next refresh (a package or profile
change, a recreation, a resume while the app list is incomplete) or at once with `~restart`.
Launches meanwhile are merged in, unless the process ends first.

## The config file lives in Verso's own folder

Your config file is `config.txt` in `Android/data/com.github.reygnn.verso_launcher/files/` on the
phone's storage. Since Android 11 no app on the phone reaches that folder — not a file manager,
not an editor — so you write the file on a PC and put it there over USB or with `adb push`.
Uninstalling Verso, or clearing its storage, deletes the folder and the file with it: your copy on
the PC is the one that counts.

Verso reads the file when it starts and at every `~restart`, never at a return to the launcher:
a file you changed counts from the next `~restart`. When it cannot read it — it is not there, is
bigger than 1 MB, or cannot be opened — Verso shows the default favorites, and says so only in the
system log (tag `Verso`), not on screen. A line Verso does not understand is skipped and named in
the log the same way; the rest of the file still counts.

Neither the JSON of earlier versions (`verso.json`, a JSON backup) nor the file you linked with
`~open` up to 0.6.4 is read any more; put a copy of that file in the folder above. A JSON file put
there sets nothing: every line of it is skipped.

## Default favorites are shown, not written

With no config file Verso shows your default phone, messenger, email, browser and camera apps
as favorites, picked anew from every app list, so a default app you change shows up by itself.
They are written nowhere: your own file, put in Verso's folder, replaces them from the next
`~restart`. An empty file is a config of yours: no defaults are shown over it.

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

## What the config file reveals, and moving to a new phone

Your config file holds favorites, custom names, tags and the apps you HIDE, in plain text. On the
phone only Verso reads it, but a PC over USB can, and so can wherever you keep your copy. Keep that
in mind before sharing or syncing it.

Nothing is pruned: Verso cannot change your file, so a reinstalled app gets its place, name and
tags back, and a line still names a hidden app removed years ago. An entry of an app that is gone
costs a few bytes and shows nowhere: the `#` overview lists only the tags of installed apps.

The JSON backups of earlier versions (`verso-….json`, also the `verso-pre-restore-…`,
`verso-pre-undo-…` and `verso-pre-cleanup-…` files in `Download/Verso/`) are not read any more;
they can be deleted. There is no undo inside Verso: your editor's, and your copies of the file,
are the history.

Android's cloud backup leaves Verso out (`data_extraction_rules.xml`). A device-to-device
transfer copies the app's own files, and Verso does not keep your config file out of it; whether
the file arrives depends on how the phones transfer. Your copy on the PC is the sure way.
Entries of a work profile or clone carry its serial (`package/class#10`), which on the new phone
may belong to no profile or another one; they then match nothing. The same on one phone when a
work profile is removed and set up again: the new one gets a new serial, and its apps start
without favorites, names, tags or hidden marks — unless the file names them by label
(`Gmail (Work)`), which outlives a serial (though not a change of the device language: the badge
is translated). The personal profile's config moves over intact.

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

## Leaving the launcher resets to the front page

Whenever the launcher stops (an app launched, the screen off, the recents screen opened) the
drawer closes and its command line is cleared: a half-typed search does not survive a trip away.

## A hardware keyboard, two corners

Typing on the front page opens the drawer with that character in the command line; a dead key
that starts the typing (an accent waiting for its letter) loses its accent there. In the recent
apps (`?`) the command line has no focus, so keys go nowhere until you tap it.

## An app that renames its launcher activity loses its place

Favorites, names, tags and hidden marks belong to an app's launcher activity (`package/class`).
An app that renames it in an update — rare — comes back as a new entry; the old key keeps its
place, name, tags and mark. A file that names the app by label or package follows it by itself;
one with its key needs the new one.

## A stuck system call

Verso's own disk and system work runs on one thread: the app list and the app's small files. If a
system service never answers (the package manager; not seen so far), the list stays as it was —
on a cold start the drawer stays empty and home shows its cached favorites. Your config file is a
few KB on the phone's own storage, read as Verso starts. Guru saves its crash reports through
MediaStore on a thread of its own, so a stuck MediaStore holds only those.
Settings → Apps → Verso → Force stop ends a hang; the next start is clean.

## Portrait lock does not hold on tablets and foldables

The launcher is locked to portrait, but since Android 16 apps targeting SDK 36 have that ignored
on large screens (smallest width ≥ 600dp: tablets, the inner display of foldables), so there it
rotates. The layout handles both, and `configChanges` keeps a rotation from recreating the
activity.
