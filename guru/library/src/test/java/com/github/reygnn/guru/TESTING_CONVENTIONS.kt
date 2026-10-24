package com.github.reygnn.guru

/**
 * ============================================================================
 * TESTING CONVENTIONS — GURU
 * ============================================================================
 *
 * Guru is vendored into hosts as source with no dependency (CLAUDE.md, hard
 * rule 1), so like Verso it has no coroutines and no MockK. This file keeps
 * the family's discipline and says what this project does instead.
 * ============================================================================
 */

/**
 * ============================================================================
 * SEAMS INSTEAD OF MOCKS
 * ============================================================================
 *
 * Production classes take their Android-bound collaborators as FUNCTIONS:
 *
 *   GuruStore(dir, log, syncDir, rename, now, keep)  // rename: force a failure
 *   GuruHandler(previous, capture)                   // capture may throw
 *   GuruDownloads(resolver, log, owner, subDir, keep) // the device test's own folder
 *
 * The `log` parameter is the error channel of code that never throws: a test
 * collects the messages and asserts on them. Reports come from `report(...)`
 * and `testEnv` (GuruFixtures.kt).
 * ============================================================================
 */

/**
 * ============================================================================
 * THREADING — THE HOST'S IO AS A MANUAL QUEUE
 * ============================================================================
 *
 * Guru has no thread of its own; every task goes to the host's `io`. A test
 * passes a FIFO and runs it when the interleaving under test says so:
 *
 *   private val ioQueue = ArrayDeque<Runnable>()
 *   io = { task -> ioQueue.addLast(task) }
 *   …  while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
 *
 * Submission order is part of the contract (the exit look comes before the
 * screen) — a test that reorders the queue tests something the host never does.
 * ============================================================================
 */

/**
 * ============================================================================
 * FILES, MEDIASTORE, LOCALES, ORG.JSON
 * ============================================================================
 *
 * FILES — real files in a JUnit `TemporaryFolder`. Only the directory fsync is
 * stubbed; write, rename and parse together are the thing under test.
 *
 * MEDIASTORE — Robolectric has no MediaProvider. FakeMediaStore registers a
 * provider under the `media` authority that keeps rows in memory and their
 * bytes in real files, and can refuse or throw on an insert, a write, the
 * publish (update), a query or a delete, fail one file's write
 * (`failWriteOn`), and seed published rows of any owner and folder
 * (`published`) for the pruning tests. It rejects any query selection but
 * Guru's, so a wrong one fails here, not only on a device.
 *
 * LOCALE — a class whose tests set the default locale holds a GuruLocaleRule,
 * which restores main, FORMAT and DISPLAY.
 *
 * ORG.JSON — the real desktop org.json on the test classpath (android.jar's is
 * a throwing stub). Acrarium parses reports with it, so it is the right judge
 * of the ACRA JSON.
 * ============================================================================
 */

/**
 * ============================================================================
 * ROBOLECTRIC — SDK 36, JDK 21
 * ============================================================================
 *
 * Only for what needs the Android runtime: `Guru.install` / `showPending`,
 * saving through MediaStore, the alert and the chooser it offers, `GuruScreen`. Annotate the class with
 * `@RunWith(RobolectricTestRunner::class)`; the SDK is pinned once in
 * `src/test/resources/robolectric.properties`. Drive a plain `Activity` with
 * `Robolectric.buildActivity(...)`; no androidx.test API in the tests.
 *
 * Hold a GuruHostRule: Guru.install replaces the JVM-wide default handler, the
 * rule puts it back, and it gives `io` as a manual FIFO (runIo, queuedIo).
 *
 * Harness traps already paid for:
 *  - Back: idle the main looper first (the dialog's window attaches on a post),
 *    then dispatch KEYCODE_BACK down/up on the dialog.
 *  - MediaStore: `FakeMediaStore.install()` in @Before of every class that
 *    installs Guru — install saves at the start. ContentResolver reaches its
 *    insert/update/query/delete/openFile like the real provider's; a saved file is
 *    read back from `row.file`. A class that reads reports from the store after
 *    the start refuses every save (GuruInstallTest).
 *  - install queues two io steps (exit look, save); showPending a third.
 *  - A chooser start the system refuses: an Activity subclass whose startActivity
 *    throws (GuruShareTest.RefusingActivity).
 *  - Touch (GuruTouchTest): idle the main looper first — the dialog's window attaches
 *    and lays the view out (Robolectric: 320 px wide). Keep every move inside the
 *    view: View's own click handling drops a touch that leaves it, so a swipe off the
 *    edge passes even without the gesture rules. Times come from SystemClock and
 *    move with `idleFor`; use ViewConfiguration's timeouts, not literals.
 * ============================================================================
 */

/**
 * ============================================================================
 * BEHAVIOURAL DISCIPLINE
 * ============================================================================
 *
 * 1. No mocking framework; fakes, lambdas and manual queues.
 * 2. Read the implementation alongside the test.
 * 3. Honest KDoc beats fake tests: what the JVM cannot reach is tested on a
 *    device (README, "Tests"), not asserted against faked machinery.
 * 4. Mutation check every fix: revert it, watch the new test fail, restore.
 * 5. Pin boundaries from both sides: exactly the limit and one past it
 *    (cap, readCapped).
 * 6. When a fix breaks an existing test, first ask whether the test encoded
 *    the bug.
 * 7. Backtick names without Windows-forbidden characters (`"` `:` `?` `*`
 *    `<` `>` `|` `\` `/`).
 * ============================================================================
 */
