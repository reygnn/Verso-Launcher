# Ideas

Not a plan — things seen and deliberately not built (yet). One line each, with where it came
from. A new feature category still needs the user's agreement (see `CLAUDE.md`).

## From refactor/command-gate (kept there as reference, not merged)

- `~cleanup`: a key carrying the launcher's own profile serial counts as gone (be2b277).
- Honest empty-list hints while the config is not loaded (`EmptyHint.NOT_LOADED`).

## Review round 4 (2026-10-17), left open on purpose

- A4: a cleanup whose hand-back is dropped (activity recreated) has already swept the previous
  cleanup undo point while removing nothing.
- B1: a recreation while `~restore` runs drops its result toast (a process-wide "last outcome").
- B2: `~backup`/`~save`/`~load`/restore toasts can appear over another app after onStop.
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

- A restore over a config whose primary hit a READ error (not a torn file) writes over it with
  no copy; that file may hold the newest save. (main healed over it from .bak on every load.)
  Telling "read error" from "torn" in load()'s result would let the restore refuse instead.
- With an unreadable config, `~load` can show the unreadable toast twice.
- Before the first load, a tap on a tag row in the `##` overview says "not loaded".
- An IO step stuck in a system service (no timeout) keeps the gate busy for that activity
  instance; on main the commands queued behind it silently.

## Seen on the A17

- The restore toast ("… rückgängig: ~restore-saf, verso-pre-restore wählen") is cut off.
- `BackupStoreInstrumentedTest` leaves empty `Download/VersoTest-…` folders behind.

## Deferred earlier

- RTL layouts.
- Intermittent "first swipe after returning from an app does nothing" (A17, never reproduced).
- An app that switches its launcher activity loses its favorite/name/tags.
- Split MainActivity; lint's version bumps (compileSdk 37, AGP, Gradle, Robolectric).
