# CLAUDE.md (test source set)

Orientation for sessions touching Guru's tests. The concrete patterns live in
`java/com/github/reygnn/guru/TESTING_CONVENTIONS.kt`; this file says what exists, what is
deliberately left out, and where to start.

The baseline philosophy (JVM-only by default, pure logic outside Android-runtime classes)
comes from `~/.claude/CLAUDE.md`. Like Verso, Guru departs from the baseline stack on
purpose: no coroutines, no MockK — see "deliberately not".

---

## What exists

Unit tests (`library/src/test`, `./gradlew :library:testDebugUnitTest`, 167 tests):

```
GuruStoreTest       19  reports on real files: atomic write (.new, rename, dir fsync), newest
                        first, at most 8, a refused rename, ASCII names in any locale, remove
                        by whole id only,
                        exits-seen, installation-id, the saved crash times (newest, distinct,
                        unreadable = none)
GuruNumberTest      16  the number: kind with the dead-end bit, FNV-1a over the UTF-8 bytes of
                        the root cause's type (the last "Caused by:", a space before the colon
                        ignored) and first three frames, stable across messages and line
                        numbers; an ANR's main block ends at its blank line
ReportCodecTest     10  key=value round-trip (a hostile trace too); an unknown key from a newer
                        Guru skipped; missing field, bad value, bad escape or a cut-off file
                        is no report
GuruTextTest        10  cap and readCapped at their limit (surrogate pairs whole), the alert
                        lines with the host's "saved" line, the share's subject; died in
                        Guru: a crash on main with a frame right in Guru's package, not off
                        main, not another kind, not a package below
AcraJsonTest         7  the ACRA fields Acrarium requires, USER_CRASH_DATE's format, read back
                        with the real org.json
ExitSelectionTest    7  which exit records become reports: the first look only sets the
                        mark, oldest first, a Java crash the handler caught not twice, an ANR
                        beside it still reported, the mark moved even past a dropped crash
GuruDownloadsTest    7  the file's name: app, version, local crash time; unsafe characters
                        to `_`, dashes kept, empty parts left out, ASCII digits in any locale,
                        the calendar year, label and version cut under 255 bytes
GuruProcessTest      6  building a report from a crash (trace capped) or an exit record; the
                        kind codes are fixed
GuruHandlerTest      5  one capture per process, a failing capture swallowed, the previous
                        handler always reached with the original throwable
GuruTextsTest        3  the English defaults; a host overrides only what it translates
GuruSaveTest        26  Robolectric + FakeMediaStore: saving at the start, after the exit
                        look on io; one .json per report in the Downloads collection,
                        Download/Guru (name, MIME), hidden until all bytes are in, its own
                        report's ACRA JSON, the installation's one id, a trace beyond ASCII
                        exact, the crashed app's version in the name; removed from the store
                        once saved; named by the app's label, or the package without one; a
                        refused insert, a failed write, a refused publish or a throwing
                        provider keeps that report and saves the rest, and it comes again
                        next start; a crash the handler caught saved once, also when its
                        exit record shows up a start later — an ANR's time hides no crash;
                        pruning keeps the newest 32 own files, never another owner's or
                        another folder's, nothing when nothing was pending, also when
                        nothing could be saved, and a failing listing or delete stops no
                        save and no alert; an exit look that throws stops neither io nor the
                        save
GuruShowPendingTest 18  Robolectric: the alert only for what this start saved, after the save,
                        on the host's main; nothing saved queues nothing; the newest's number
                        and how many; once seen, never again; a double-tap or Back only
                        closes; a destroyed or finishing activity gets none, the files are
                        saved anyway; lost before an answer (activity gone first, or
                        recreated while up) it comes back, answered it does not; host texts
                        that throw cost the alert, not the host; Guru's own crash on main
                        leaves the next alert out, a crash below Guru's package or off main
                        does not
GuruShareTest        5  Robolectric: a hold offers one file (SEND) or several (SEND_MULTIPLE),
                        read access but no write for the chooser and the app picked, in a
                        new task, under the newest's subject; only what was saved; a chooser
                        that cannot start costs nothing
GuruInstallTest     11  Robolectric (every save refused, so reports stay readable): a second
                        install keeps one handler; no receiver; exit records
                        (ShadowActivityManager) — the look waits for io, the first start only
                        marks, an ANR with its dump, a caught Java crash not twice, old exits
                        and other reasons stay out, an unreadable dump (checked or unchecked)
                        still a report and the mark moved, a native tombstone stays out, an
                        ANR report hides no crash exit
GuruScreenTest       7  Robolectric: close shares nothing, share acts once (the hold
                        consumed); TalkBack's action labels and description from GuruTexts;
                        the alert closes with its activity; a long host line shrinks only
                        itself; the hint reads at WCAG contrast on black
GuruTouchTest       10  Robolectric, real MotionEvents: a double-tap closes, a still hold
                        shares; a single tap, two slow taps, a swipe, a slide past the slop
                        and a sliding second tap do nothing; TalkBack's click and long click
                        act
```

On a device (`library/src/androidTest`, `./gradlew :library:connectedAndroidTest`, 4 tests):
`GuruDownloadsInstrumentedTest` — real MediaStore: the file published under its name and
readable back, " (1)" for a name taken by ours or the shell's, pruning keeps the newest own
files and leaves the shell's. The only androidTest set: what Robolectric cannot show.

`sample/src/test`: `SampleGuruTextsTest` (2) — the sample's `GuruTexts` from `values` and
`values-de`.

Shared: `GuruFixtures.kt` (`testEnv`, `report(...)`, `GuruLocaleRule`) and, for the
Robolectric tests, `GuruHostRule.kt` (the Application, `io` as a manual FIFO, the store,
the default handler restored afterwards) and `FakeMediaStore.kt` (a provider under the
`media` authority: rows in memory, bytes in real files, the values as inserted; a refused
or throwing insert, a refused write (all, or one file's) or publish, a throwing query or
delete; only Guru's own query selection accepted).

Left to the device (README): the real MediaStore (and its on-device tests) and chooser, a
real process death, the blink. Not observable in any test: that the file is fsynced.

---

## What this set deliberately does NOT have

- **No MockK, no mocks at all.** Android-bound collaborators are constructor functions
  (`GuruStore`'s `syncDir` / `rename` / `now`, the `log` channel, the host's `io`), so a
  test passes a lambda or a fake.
- **No coroutines**, so no `MainDispatcherRule` / `runTest`. Threading is the host's `io`;
  a test passes a manual queue and decides when it runs.
- **No mocked filesystem.** Real files in a `TemporaryFolder`, the saved file's real bytes:
  what lands on disk is what is tested.

---

## Rules

- **Test names in backticks contain no character Windows forbids in file names**
  (`"`, `:`, `?`, `*`, `<`, `>`, `|`, `\`, `/`). The test class files are named after
  them, and under `allWarningsAsErrors` that breaks the build — Verso already failed on
  exactly this once.
- **Mutation check every fix:** revert the fix, watch the new test fail, restore.
- **Anything setting the default locale** holds a `GuruLocaleRule` (all three defaults).
- **The desktop org.json is not Android's.** It pins the format Acrarium reads (Acrarium
  uses it too); behaviour that differs on Android would need a device.
