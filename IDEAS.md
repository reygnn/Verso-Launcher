# Ideas

Not a plan — things seen and deliberately not built (yet). One line each, with where it came
from. A new feature category still needs the user's agreement (see `CLAUDE.md`).

## Review round 4 (2026-10-17), left open on purpose

- A4: a recreation mid-`~cleanup` (after its undo point) has already swept the previous cleanup
  undo point while removing nothing; the cleanup could persist its forget on the IO thread like
  the restore does.
- B1: a recreation mid-restore drops the result silently, toast included (a process-wide "last
  outcome").
- B2: `~backup`/restore toasts can appear over another app after onStop.
- B3: a stuck `~restore-saf` read thread keeps the destroyed activity in memory.
- C1: the rename dialog's tag filter drops the IME's composing span (Samsung: "wwo").
- C2: the first `!`/`-` restarts the keyboard (GO↔DONE); likely the "symbol page" jump.
- C5: pasted newlines stay in prompt/name; a dead key starting typing on home loses its
  accent; hardware keys in the `?` view go nowhere; `foldLabel` drops every U+0307.
- D4: `~cleanup`'s verdict for a profile that exists but is not listed is untested.
- D5: no source guard for hard rules 1–3 (no `implementation` deps, `guarded(` around IO,
  one executor).
- D6: home's "ready" (config loaded) has no test of its own.

## Review of refactor/simple-gate (2026-10-17), below the merge threshold

- An IO step stuck in a system service (no timeout) keeps the gate busy for that activity
  instance: every further command is refused with "busy" until it ends.

## Second review of refactor/simple-gate (2026-10-17), left open

- A `~restore-saf` pick read while a command runs is dropped with "busy" (by design); it could
  wait for the gate instead.
- `~restart` gives no toast and lands on home, not in the drawer it was typed in.
- The rows' outline alpha is not pinned by a test of its own.

## Review round 5 (2026-10-17), left open

- A wallpaper colour change while the config is not loaded leaves the cached favorites in the
  old colour (HomePage.applyTextColor re-renders from an empty render).
- The 5-minute safety-net refresh could be an hour: callbacks already flag real changes.
- Dimmed rows go through an offscreen layer every frame while scrolling; a row bind allocates a
  ColorStateList even when nothing changed; the first wallpaper-colour query after boot
  delays the first enumeration.
- A file another app plants in Download/Verso can be picked with ~restore-saf (hide or rename
  apps); a confirmation with counts before adopting a picked file would show it.
- TalkBack: edit modes announce no state change, page turns no pane title, the empty-list
  live region is likely silent, recents only by swipe, battery icons as separate stops.
- Home ignores the system font size (by design for the clock); paused/archived is shown by
  opacity alone.

## Seen on the A17

- `BackupStoreInstrumentedTest` leaves empty `Download/VersoTest-…` folders behind.

## Deferred earlier

- RTL layouts.
- "First swipe after returning from an app does nothing" (A17): seen once, not reproduced. A
  late HOME intent (seen 495 ms after onStart) aborting the swipe is a candidate; a time-window
  workaround was tried and removed again — only with a real reproduction.
- An app that switches its launcher activity loses its favorite/name/tags.
- Lint's version bumps (compileSdk 37, AGP, Gradle, Robolectric).
