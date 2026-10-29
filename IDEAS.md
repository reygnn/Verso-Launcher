# Ideas

Not a plan — things seen and deliberately not built (yet). One line each, with where it came
from. The feature freeze is lifted (2026-10-25, see `CLAUDE.md`); new features are bounded by
the project scope, not by a freeze.

Release triage, 2026-10-25: every open point of the reviews and audits (`docs/audits/`, removed
2026-10-26, in the git history) was implemented, written into `CAVEATS.md`, or discarded below
with its reason — the performance points after measuring.

## Discarded, with the reason (2026-10-25)

- A crash count at the next start, from `ActivityManager.getHistoricalProcessExitReasons`
  (cloud review, 2026-10-29, after Guru's removal). It would tell nothing new. That Verso crashed,
  Android says itself: every crash of a home app that is no system app clears its preferred-home
  setting (AOSP, Android 16, `AppErrors.handleAppCrashLocked`), so the next Home press shows the
  launcher chooser — measured on the A17 with 0.6.10-daily, while the home page itself comes back
  in a new process within about 70 ms, with no dialog. Where it crashed, the record cannot say: a
  Java crash is kept as `REASON_CRASH` with the description "crash" and no stack trace, so the
  where stays in logcat and the DropBox, which retrace reads with the archived mapping. What is
  left, a count and a time, is no reason for a file and a message on screen.
- Measured on release builds (R8, not debuggable), A17 and Pixel 9a, throwaway patches:
  - The first wallpaper-colour query delays the first enumeration: by about 3 ms on the A17
    (Samsung answers no colours) and 0.35–0.65 ms on the Pixel, against an enumeration of
    roughly 110 and 70 ms. Not measured: the very first query after a boot.
  - Dimmed rows go through an offscreen layer every frame (`OutlinedViews`
    `hasOverlappingRendering`). Worst case, every row dimmed, scrolling, p90 frame time with the
    layer / without it / with a hardware layer per dimmed row instead: Pixel 7 / 7 / 5–6 ms, no
    difference; A17 fresh 7 / 6 / 7 ms, throttled 19–20 / 13 / 20 ms. Only dropping the layer
    helps, and only on the A17 when throttled with every row dimmed — while it lets the outline
    show through a dimmed row's fill, the reason the layer is there; a per-row hardware layer is
    redrawn as rows are rebound and saves nothing. A paused work profile dims some rows, not all.

- Checked on the devices (audits 2026-10-21 (2) #4, 2026-10-23 #17):
  - A profile that never reads makes every return home enumerate again (`needsRefresh`). Not seen:
    no "skipping profile" or "skipping app" in the A17's or the Pixel's log, and neither has a
    second profile. Throttle the retry only once a device shows it.
- Test gaps that would need what the JVM tests lack (audits 2026-10-21 (2) #14, 2026-10-22 #14
  and #15, 2026-10-22 (3) #4): `onResume` asking for a refresh on `needsRefresh` (Robolectric's
  `LauncherApps` is too thin; reflection only), `modeChanged` while a drag runs (real
  MotionEvents through the page turn), the list's top and end and scrolling it to the top (a
  laid-out list longer than the screen, or apps in `MainActivityFlowsTest`). The rules beneath are pinned (`LauncherStateTest`, `SwipeGestureTest`,
  `LauncherActionsTest`); the wiring is used on the devices every day.
- "First swipe after returning from an app does nothing" (A17): seen once, never reproduced. A
  late HOME intent (seen 495 ms after onStart) aborting the swipe is a candidate; a time-window
  workaround was tried and removed again. Discarded until a real reproduction.
- `~restart` gives no toast and lands on home, not in the drawer it was typed in: by design — it
  builds the window anew, and a new window shows home; the rebuilt page is the answer.
- One `uiAction` net for the main-thread catches (crash-only plan, 2026-10-18): each of them
  (`startGuarded`, the launch, `openAppInfo`) says its own message by
  exception type; one `onError` would lose those or rebuild the catch inside it. No gain.

## Kept as an idea

- Reading the config file again at a return to the launcher when its last-modified time changed,
  so a pushed file counts without `~restart`. Left out with the fixed place (suckless config,
  2026-10-28): `~restart` is the one reload, as SIGHUP is for a daemon.

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
  4.17 (only worth it with SDK 37).
