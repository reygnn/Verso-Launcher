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
surprise, the app shows a one-time toast the first time `?` is opened empty after
a (re)start — see `toast_recents_empty` and `applyFilter` in `MainActivity.kt`.

If durable recents are ever wanted, persist `recentKeys` to a **separate** small
file (not `verso.json`) and load it on start.

## Private space is untested

Apps are listed through `LauncherApps` across every profile the launcher is shown:
the personal one plus a work profile or cloned apps (badged by the system, e.g.
"Calculator (Work)"). This was verified with a managed test profile. Android 15's
private space is a hidden profile with its own rules for which launcher sees it; it
has not been tried on a device, so whether and when its apps appear is open.

Apps of a paused work profile and archived apps stay listed, dimmed; the first tap
turns the profile back on (then a toast says the app is ready) or offers the system's
restore. Both were verified on the A17. Turning a profile back on needs Verso to be
the default home app; otherwise the system refuses and Verso shows "denied".

## Gestures on the front page

Swipe up (drawer) and swipe down (recent apps) are wired, as in Kolibri. Swipe
left/right and the home double-tap do nothing. Kolibri's alarm/calendar indicators
next to the clock are not ported (calendar events need the READ_CALENDAR
permission; Verso asks for none). There is no double-tap-to-lock either (that needs
an accessibility service).

The home page uses Kolibri's DEFAULT look (text size, spacing, start alignment,
white text with a tonal outline). There is no settings screen to change it; the
values are constants in `HomeStyle.kt`.

The swipe is not available to a screen reader, so the front page exposes an
"Open all apps" custom accessibility action (TalkBack's actions menu), and the
empty-favorites hint is tappable.

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
