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

    private var aside: ConfigStore.SetAside = ConfigStore.SetAside.Moved

    private fun run(current: VersoConfig?, restored: VersoConfig) = RestoreSteps.run(
        current = current,
        restored = restored,
        writeUndo = { payload -> calls += "undo:" + ConfigJson.parse(payload)!!.favorites.joinToString(); undoName },
        writeConfig = { payload -> calls += "config:" + ConfigJson.parse(payload)!!.favorites.joinToString(); writeOk },
        sweepUndo = { calls += "sweep" },
        setAside = { calls += "aside"; aside },
        dropSetAside = { calls += "drop" },
    )

    @Test fun `the undo point is written first, then the restored config`() {
        val outcome = run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.Done("verso-pre-restore-2026-10-13_120000.json"), outcome)
        assertEquals(listOf("undo:old/Old", "config:new/New", "drop", "sweep"), calls)
    }

    /** The old undo point may be the very file being restored: it goes only once the restore is
     *  saved. A failed write is reported, and nothing is swept. */
    @Test fun `a failed config write is reported and keeps the previous undo point`() {
        writeOk = false
        val outcome = run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.WriteFailed, outcome)
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

    @Test fun `over an unreadable config it is set aside, not written over, then the restore is written`() {
        assertEquals(RestoreSteps.Outcome.ReplacedUnreadable(saved = true), run(current = null, restored = cfg("new/New")))
        assertEquals(listOf("aside", "config:new/New", "drop"), calls)
    }

    @Test fun `if the unreadable config cannot be set aside, nothing is written`() {
        aside = ConfigStore.SetAside.Failed
        assertEquals(RestoreSteps.Outcome.SetAsideFailed, run(current = null, restored = cfg("new/New")))
        assertEquals(listOf("aside"), calls)
    }

    @Test fun `if it reads after all, it is the undo point as usual`() {
        aside = ConfigStore.SetAside.Readable(cfg("old/Old"))
        assertEquals(RestoreSteps.Outcome.Done("verso-pre-restore-2026-10-13_120000.json"), run(current = null, restored = cfg("new/New")))
        assertEquals(listOf("aside", "undo:old/Old", "config:new/New", "drop", "sweep"), calls)
    }

    @Test fun `over an unreadable config a failed write is reported`() {
        writeOk = false
        assertEquals(RestoreSteps.Outcome.ReplacedUnreadable(saved = false), run(current = null, restored = cfg("new/New")))
    }
}
