package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RestoreSteps] — the IO half of a restore in its load-bearing order (CLAUDE.md rule 7). Each
 * write is recorded, so the ORDER and the absence of writes are what the tests pin.
 */
class RestoreStepsTest {

    private val calls = mutableListOf<String>()
    private var undoName: String? = "verso-pre-restore-2026-10-13_120000.json"

    private var writeOk = true

    private fun run(current: VersoConfig?, restored: VersoConfig) = RestoreSteps.run(
        current = current,
        restored = restored,
        writeUndo = { payload -> calls += "undo:" + ConfigJson.parse(payload)!!.favorites.joinToString(); undoName },
        writeConfig = { payload -> calls += "config:" + ConfigJson.parse(payload)!!.favorites.joinToString(); writeOk },
    )

    @Test fun `the undo point is written first, then the restored config`() {
        val outcome = run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.Done("verso-pre-restore-2026-10-13_120000.json"), outcome)
        assertEquals(listOf("undo:old/Old", "config:new/New"), calls)
    }

    @Test fun `restoring the current config writes nothing, keeping the real undo point`() {
        val outcome = run(current = cfg("same/Same"), restored = cfg("same/Same"))
        assertEquals(RestoreSteps.Outcome.Unchanged, outcome)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun `no undo point, no restore`() {
        undoName = null
        val outcome = run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.UndoFailed, outcome)
        assertEquals(listOf("undo:old/Old"), calls)  // the config was not touched
    }

    @Test fun `over an unreadable config there is no undo point, the restore is written over it`() {
        assertEquals(RestoreSteps.Outcome.ReplacedUnreadable(saved = true), run(current = null, restored = cfg("new/New")))
        assertEquals(listOf("config:new/New"), calls)
    }

    @Test fun `over an unreadable config a failed write is reported`() {
        writeOk = false
        assertEquals(RestoreSteps.Outcome.ReplacedUnreadable(saved = false), run(current = null, restored = cfg("new/New")))
    }
}
