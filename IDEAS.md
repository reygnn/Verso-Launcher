# Ideas

Not a plan — things seen and deliberately not built (yet). One line each, with where it came
from. The feature freeze is lifted (2026-10-25, see `CLAUDE.md`); new features are bounded by
the project scope, not by a freeze.

Reconciled against `cd0d505` on 2026-10-25: resolved points removed (D5 source guard; the
Samsung-`null` wallpaper NPE — never was one, nullable receiver; the name field's control-char
drop; `foldLabel` dropping U+0307, which is hard rule 9 by design), partial ones trimmed to
what is still open.

## Review round 4 (2026-10-17), left open on purpose

- A4 rest: a process killed mid-`~cleanup`, after its undo point and before `forget` lands on
  main, has swept the previous cleanup undo point while removing nothing; `forget` could persist
  on the IO thread like the restore does. (A recreation no longer does this since 2026-10-25:
  the run's hand-backs go to the process, Core/Commands.)
- C2: entering an edit mode (`!`/`-`) restarts the keyboard once (GO↔DONE), the likely "symbol
  page" jump. Already guarded to fire only on the action change, not per keystroke
  (`AppDrawer.applyFilter`); only with a reproduction is the single restart worth removing.
- C5: pasted newlines stay in the prompt; a dead key starting typing on home loses its accent;
  hardware keys in the `?` view go nowhere.
- D4: `~cleanup`'s verdict for a profile that exists but is not listed is untested
  (`CleanupTest` covers "answered empty", not "not listed").

## Review of refactor/simple-gate (2026-10-17), below the merge threshold

- An IO step stuck in a system service (no timeout) keeps the gate busy for the process
  (`VersoApp.gate`, also across a recreation): every further command is refused with "busy"
  until it ends. Before the gate was process-wide a recreation got a fresh one, but its commands
  then queued behind the same stuck IO thread and never ran either.

## Second review of refactor/simple-gate (2026-10-17), left open

- A `~restore-saf` pick lives in memory (`VersoApp.pick`) until its adoption starts: a process
  that dies before then loses it, silently — the user picks again. Only a pick on disk (a small
  journal) would survive that. (Since 2026-10-25 it no longer meets "busy" or "not loaded": it
  waits for both; a hanging read holds back only a second restore, until `~restart`.)
- `~restart` gives no toast and lands on home, not in the drawer it was typed in.
- The drawer rows' outline alpha is not pinned by a test of its own (home-favorite alpha is,
  in `HomePageAccessibilityTest`).

## Review round 5 (2026-10-17), left open

- Dimmed rows go through an offscreen layer every frame while scrolling (`OutlinedViews`
  `hasOverlappingRendering`); the first wallpaper-colour query after boot delays the first
  enumeration (both on the one IO thread).
- TalkBack: still missing a pane title on page turns (`setAccessibilityPaneTitle`), the charging
  / battery-protection icons as separate stops, and reaching recents other than by swipe. (The
  empty-list live region, row/battery/favorite contentDescriptions and the home a11y actions
  already exist.)
- Home ignores the system font size (by design for the clock); paused/archived is shown by
  opacity alone.

## Taken out (2026-10-21)

- The 5-minute safety-net refresh on resume (`REFRESH_STALE_MS`, from 149a36a): the LauncherApps
  callbacks and profile broadcasts (registered onCreate–onDestroy, so also while behind) flag
  every known change, a locale change recreates, `~restart` repairs by hand. It guarded against
  a lost event (freezer, OEM quirk, archiving if it fires none) — never observed; if a list ever
  goes stale without an event, bring it back (an hour would do). It also retried two things
  that now have their own way: an incomplete enumeration (`needsRefresh`, on every resume until
  one is complete) and an unreadable `recents.txt` / `usage.txt` (the next refresh or
  `~restart`, see `CAVEATS.md`).

## Seen on the A17

- `BackupStoreInstrumentedTest` may leave empty `Download/VersoTest-…` folders behind. A
  `@After tearDown()` now deletes the file rows (`content delete … _data='$dir'`); only an empty
  directory may survive.

## Deferred earlier

- RTL layouts.
- "First swipe after returning from an app does nothing" (A17): seen once, not reproduced. A
  late HOME intent (seen 495 ms after onStart) aborting the swipe is a candidate; a time-window
  workaround was tried and removed again — only with a real reproduction.
- An app that switches its launcher activity loses its favorite/name/tags (would need a key
  migration, hard rule 8).
- Lint's version bumps: targetSdk 37 (with the whole family, tested on a device), Robolectric
  4.17 (only worth it with SDK 37), androidx.test runner 1.7.0.
