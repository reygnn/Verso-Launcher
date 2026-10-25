# Ideas

Not a plan — things seen and deliberately not built (yet). One line each, with where it came
from. The feature freeze is lifted (2026-10-25, see `CLAUDE.md`); new features are bounded by
the project scope, not by a freeze.

Release triage, 2026-10-25: every open point of the reviews was implemented, written into
`CAVEATS.md`, or discarded below with its reason. Still open: the two points to measure.

## To measure, then build or discard (review round 5, 2026-10-17)

- Dimmed rows go through an offscreen layer every frame while scrolling (`OutlinedViews`
  `hasOverlappingRendering`): only with many dimmed apps (a paused work profile).
- The first wallpaper-colour query after boot delays the first enumeration (both on the one IO
  thread). Measure on a release build, not a debug one (that distorts about fourfold).

## Discarded, with the reason (2026-10-25)

- A4 rest: a process killed mid-`~cleanup`, after its undo point and before `forget`, has swept
  the previous cleanup undo point while removing nothing. A successful cleanup replaces the older
  undo point as well (one per kind, hard rule 6), and the new one holds the config from before
  the cleanup: against success only the removal is missing — run `~cleanup` again. (A recreation
  no longer does this at all: the run's hand-backs go to the process.)
- C2: entering an edit mode (`!`/`-`) restarts the keyboard once (GO↔DONE), the likely "symbol
  page" jump. Guarded to fire only on the action change, not per keystroke
  (`AppDrawer.applyFilter`). Discarded until a reproduction shows the single restart hurts.
- "First swipe after returning from an app does nothing" (A17): seen once, never reproduced. A
  late HOME intent (seen 495 ms after onStart) aborting the swipe is a candidate; a time-window
  workaround was tried and removed again. Discarded until a real reproduction.
- `~restart` gives no toast and lands on home, not in the drawer it was typed in: by design — it
  builds the window anew, and a new window shows home; the rebuilt page is the answer.

## Kept as an idea

- A `~restore-saf` pick lives in memory (`VersoApp.pick`) until its adoption starts: a process
  that dies before then loses it (documented in `CAVEATS.md`). Only a pick on disk (a small
  journal) would survive that.

## Taken out (2026-10-21)

- The 5-minute safety-net refresh on resume (`REFRESH_STALE_MS`, from 149a36a): the LauncherApps
  callbacks and profile broadcasts (registered onCreate–onDestroy, so also while behind) flag
  every known change, a locale change recreates, `~restart` repairs by hand. It guarded against
  a lost event (freezer, OEM quirk, archiving if it fires none) — never observed; if a list ever
  goes stale without an event, bring it back (an hour would do). It also retried two things
  that now have their own way: an incomplete enumeration (`needsRefresh`, on every resume until
  one is complete) and an unreadable `recents.txt` / `usage.txt` (the next refresh or
  `~restart`, see `CAVEATS.md`).

## Deferred earlier

- RTL layouts.
- Lint's version bumps: targetSdk 37 (with the whole family, tested on a device), Robolectric
  4.17 (only worth it with SDK 37), androidx.test runner 1.7.0.
