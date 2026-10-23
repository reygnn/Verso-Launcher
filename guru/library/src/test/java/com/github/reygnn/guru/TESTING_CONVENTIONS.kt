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
 *   AcraPost.post(url, login, password, json, log)   // against a loopback server
 *   GuruHandler(previous, capture)                   // capture may throw
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
 * FILES, NETWORK, LOCALES, ORG.JSON
 * ============================================================================
 *
 * FILES — real files in a JUnit `TemporaryFolder`. Only the directory fsync is
 * stubbed; write, rename and parse together are the thing under test.
 *
 * NETWORK — the JDK's `com.sun.net.httpserver.HttpServer` on loopback, port 0.
 * The request is recorded as it arrived (method, path, headers, body).
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
 * Only for what needs the Android runtime: `Guru.install` / `showPending`, the
 * chooser's answer, `GuruScreen`. Annotate the class with
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
 *  - The chooser's callback: since Android 15, createChooser files it under
 *    EXTRA_CHOOSER_RESULT_INTENT_SENDER. Answer it as the system does, with a
 *    fill-in carrying EXTRA_CHOOSER_RESULT:
 *    `(sender as RoboIntentSender).pendingIntent.send(app, 0, fillIn)`, then idle
 *    the looper. ChooserResult's constructor is hidden: build it by reflection
 *    (GuruShareTest.answer). Robolectric drops the fill-in of an immutable
 *    PendingIntent, as a device does.
 *  - "No app at all": `shadowOf(app).checkActivities(true)`.
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
