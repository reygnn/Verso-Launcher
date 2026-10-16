package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RestoreSteps] — the IO half of a restore in its load-bearing order (CLAUDE.md rule 7). Each
 * write is recorded, so the ORDER and the absence of writes are what the tests pin.
 */
class RestoreStepsTest {

    private val calls = mutableListOf<String>()
    private var undoName: String? = "verso-pre-restore-2026-10-13_120000.json"
    private var configWritten = true

    /** What setting the unreadable config aside does (ConfigStore.setAsideUnreadable). */
    private var aside: ConfigStore.SetAside = ConfigStore.SetAside.Moved(listOf("verso.json.broken"))

    private fun run(current: VersoConfig?, restored: VersoConfig) = RestoreSteps.run(
        current = current,
        restored = restored,
        writeUndo = { payload -> calls += "undo:" + ConfigJson.parse(payload)!!.favorites.joinToString(); undoName },
        writeConfig = { payload -> calls += "config:" + ConfigJson.parse(payload)!!.favorites.joinToString(); configWritten },
        setAside = { calls += "aside"; aside },
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

    @Test fun `an unreadable current config is set aside, then replaced without an undo point`() {
        val outcome = run(current = null, restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.ReplacedUnreadable(listOf("verso.json.broken")), outcome)
        assertEquals("set aside first, never written over; no undo point", listOf("aside", "config:new/New"), calls)
    }

    @Test fun `an unreadable config that cannot be set aside stops the restore`() {
        aside = ConfigStore.SetAside.Failed
        val outcome = run(current = null, restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.SetAsideFailed, outcome)
        assertEquals("nothing written", listOf("aside"), calls)
    }

    @Test fun `a config that reads after all becomes the undo point as usual`() {
        aside = ConfigStore.SetAside.Readable(cfg("old/Old"))
        val outcome = run(current = null, restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.Done("verso-pre-restore-2026-10-13_120000.json"), outcome)
        assertEquals(listOf("aside", "undo:old/Old", "config:new/New"), calls)
    }

    @Test fun `a readable current config is never set aside`() {
        run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertTrue("aside" !in calls)
    }

    /** The write is atomic: a failed one leaves the old config on disk, so the restore must not
     *  be adopted — the next start would take it back. */
    @Test fun `a failed config write is no restore`() {
        configWritten = false
        val outcome = run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.WriteFailed, outcome)
        assertEquals(listOf("undo:old/Old", "config:new/New"), calls)
    }

    /** Over an unreadable config the files are already set aside when the write fails: nothing
     *  published is left. Reported as not saved, so the Activity adopts it and saves again. */
    @Test fun `a failed write over an unreadable config is reported as not saved`() {
        configWritten = false
        val outcome = run(current = null, restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.ReplacedUnreadable(listOf("verso.json.broken"), saved = false), outcome)
        assertEquals(listOf("aside", "config:new/New"), calls)
    }

    @Test fun `no undo point, no restore`() {
        undoName = null
        val outcome = run(current = cfg("old/Old"), restored = cfg("new/New"))
        assertEquals(RestoreSteps.Outcome.UndoFailed, outcome)
        assertEquals(listOf("undo:old/Old"), calls)  // the config was not touched
    }
}
