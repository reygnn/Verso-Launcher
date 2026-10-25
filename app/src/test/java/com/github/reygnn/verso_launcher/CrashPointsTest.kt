package com.github.reygnn.verso_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/**
 * Crash-only: the process may die between any two steps of a restore or a "~cleanup", and the next
 * start must find a disk it can use, with nothing the user had lost. Each test runs the operation
 * once per crash point until a run gets through: verso.json is real files (ConfigStore on a
 * TemporaryFolder), Download/Verso a list of rows that outlives the process (Verso never deletes
 * one), and the death is a thrown Error — no catch in the code under test takes one, as none takes
 * a SIGKILL on a device. The cold start is a fresh ConfigStore's load.
 *
 * BackupStore's own crash point (a pending row left behind) is MediaStore behaviour and stays in
 * BackupStoreInstrumentedTest; here a backup write is one step.
 */
class CrashPointsTest {

    @get:Rule val tmp = TemporaryFolder()

    private class ProcessDeath(val at: String) : Error("process killed before: $at")

    /** Dies before step number [deathAt] (0-based); the steps are named so a failure says where. */
    private class Reaper(private val deathAt: Int) {
        val passed = mutableListOf<String>()
        fun step(name: String) {
            if (passed.size == deathAt) throw ProcessDeath(name)
            passed += name
        }
    }

    /** Download/Verso: rows outlive the process; a newer row has a larger id, like MediaStore's _ID. */
    private class Downloads {
        private class Row(val name: String, val payload: String)
        private var nextId = 1L
        private val rows = linkedMapOf<Long, Row>()
        lateinit var reaper: Reaper

        fun write(base: String, payload: String): String {
            reaper.step("publish $base")
            val id = nextId++
            val name = BackupFiles.publishedName(base, LocalDateTime.of(2026, 10, 19, 12, 0, id.toInt()))
            rows[id] = Row(name, payload)
            return name
        }

        fun configs(base: String): List<VersoConfig> =
            rows.values.filter { BackupFiles.isPublished(base, it.name) }.map { ConfigJson.parse(it.payload)!! }
    }

    private fun storeIn(dir: File, reaper: Reaper? = null) = ConfigStore(
        dir = dir,
        log = { _, _ -> },
        syncDir = { reaper?.step("fsync the directory") },
        rename = { from, to -> reaper?.step("rename verso.json.new"); from.renameTo(to) },
    )

    private fun coldStart(dir: File): VersoConfig {
        val loaded = storeIn(dir).load()
        assertNotNull("the next start could not read verso.json", loaded)
        return loaded!!
    }

    private fun List<VersoConfig>.holds(config: VersoConfig) = any { it == config }

    // ---- restore (hard rule 7) ------------------------------------------------

    private val outgoing = cfg("old/Old")
    private val previousUndo = cfg("prev/Prev")
    private val backup = cfg("backup/Backup")

    /** Runs a restore of [restored] once per crash point; [check] sees the disk after each death. */
    private fun restoreAtEveryCrashPoint(
        restored: VersoConfig,
        check: (point: String, onDisk: VersoConfig, undoPoints: List<VersoConfig>, backups: List<VersoConfig>) -> Unit,
    ): List<String> {
        val points = mutableListOf<String>()
        var deathAt = 0
        while (true) {
            val dir = tmp.newFolder()
            val downloads = Downloads().apply { reaper = Reaper(Int.MAX_VALUE) }
            storeIn(dir).write(ConfigJson.serialize(outgoing))
            downloads.write(BackupFiles.BACKUP_BASE, ConfigJson.serialize(backup))
            downloads.write(BackupFiles.PRE_RESTORE_BASE, ConfigJson.serialize(previousUndo))

            val reaper = Reaper(deathAt)
            downloads.reaper = reaper
            val store = storeIn(dir, reaper)
            val point = try {
                RestoreSteps.run(
                    current = store.load()!!,
                    restored = restored,
                    writeUndo = { payload -> downloads.write(BackupFiles.PRE_RESTORE_BASE, payload) },
                    writeConfig = { payload -> reaper.step("write verso.json.new"); store.write(payload) },
                )
                "no death"
            } catch (death: ProcessDeath) {
                death.at
            }
            points += point
            check(
                point,
                coldStart(dir),
                downloads.configs(BackupFiles.PRE_RESTORE_BASE),
                downloads.configs(BackupFiles.BACKUP_BASE),
            )
            if (point == "no death") return points
            deathAt++
        }
    }

    @Test fun `a restore killed at any step leaves either config whole, and the replaced one undoable`() {
        val points = restoreAtEveryCrashPoint(backup) { point, onDisk, undoPoints, _ ->
            assertTrue("$point: verso.json is neither config", onDisk == outgoing || onDisk == backup)
            assertTrue("$point: the replaced config is gone", onDisk == outgoing || undoPoints.holds(outgoing))
        }
        assertEquals(
            listOf(
                "publish verso-pre-restore", "write verso.json.new", "rename verso.json.new",
                "fsync the directory", "no death",
            ),
            points,
        )
    }

    /** "~restore-saf" of the last undo point. */
    @Test fun `a restore of the previous undo point killed at any step never loses that config`() {
        restoreAtEveryCrashPoint(previousUndo) { point, onDisk, undoPoints, _ ->
            assertTrue("$point: the restored config is gone", onDisk == previousUndo || undoPoints.holds(previousUndo))
            assertTrue("$point: the replaced config is gone", onDisk == outgoing || undoPoints.holds(outgoing))
        }
    }

    @Test fun `a restore that gets through adds its undo point and removes none`() {
        restoreAtEveryCrashPoint(backup) { point, onDisk, undoPoints, backups ->
            if (point != "no death") return@restoreAtEveryCrashPoint
            assertEquals(backup, onDisk)
            assertEquals(listOf(previousUndo, outgoing), undoPoints)
            assertEquals("the backup itself is never touched", backup, backups.single())
        }
    }

    // ---- ~cleanup (hard rule 14) ---------------------------------------------

    private val alpha = AppEntry("Alpha", "com.alpha", "com.alpha.Main", "Alpha")
    private val beforeCleanup = VersoConfig(
        favorites = listOf(alpha.key, "gone/A"),
        hidden = linkedSetOf("gone/A"),
        names = linkedMapOf("gone/A" to "Old name", alpha.key to "Mine"),
    )

    /**
     * Runs a confirmed "~cleanup" once per crash point, through the real LauncherState with two
     * manual queues: a death drops whatever is still queued, as the process takes it with it.
     */
    private fun cleanupAtEveryCrashPoint(
        check: (point: String, onDisk: VersoConfig, undoPoints: List<VersoConfig>) -> Unit,
    ): List<String> {
        val points = mutableListOf<String>()
        var deathAt = 0
        while (true) {
            val dir = tmp.newFolder()
            val downloads = Downloads().apply { reaper = Reaper(Int.MAX_VALUE) }
            storeIn(dir).write(ConfigJson.serialize(beforeCleanup))
            downloads.write(BackupFiles.PRE_CLEANUP_BASE, ConfigJson.serialize(previousUndo))

            val reaper = Reaper(deathAt)
            downloads.reaper = reaper
            val store = storeIn(dir, reaper)
            val ioQueue = ArrayDeque<Runnable>()
            val mainQueue = ArrayDeque<Runnable>()
            val state = testLauncherState(
                io = { ioQueue.addLast(it); true },
                main = { mainQueue.addLast(it) },
                loadConfig = { store.load() },
                enumerateApps = { Enumeration(listOf(alpha), true) },
                serialize = ConfigJson::serialize,
                writeConfig = { payload -> reaper.step("write verso.json.new"); store.write(payload) },
                onRefreshed = { _, _ -> },
            )
            fun runAll() {
                while (ioQueue.isNotEmpty() || mainQueue.isNotEmpty()) {
                    while (ioQueue.isNotEmpty()) ioQueue.removeFirst().run()
                    while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run()
                }
            }
            state.refresh()
            runAll()
            assertTrue("harness: the config did not load", state.configLoaded)

            var outcome: CleanupOutcome? = null
            val point = try {
                state.cleanup(
                    confirmed = setOf("gone/A"),
                    recheck = { reaper.step("recheck"); CleanupPlan.Offer(Orphans(setOf("gone/A"), 1, 1, 1, 0)) },
                    writeUndo = { payload -> downloads.write(BackupFiles.PRE_CLEANUP_BASE, payload) },
                ) { outcome = it }
                runAll()
                reaper.step("cleanup reported")
                "no death"
            } catch (death: ProcessDeath) {
                death.at
            }
            points += point
            if (point == "no death") assertTrue("harness: $outcome", outcome is CleanupOutcome.Done)
            check(point, coldStart(dir), downloads.configs(BackupFiles.PRE_CLEANUP_BASE))
            if (point == "no death") return points
            deathAt++
        }
    }

    @Test fun `a cleanup killed at any step leaves either config whole, and the removed entries undoable`() {
        val cleaned = beforeCleanup.copy(
            favorites = beforeCleanup.favorites - "gone/A",
            hidden = beforeCleanup.hidden - "gone/A",
            names = beforeCleanup.names - "gone/A",
        )
        val points = cleanupAtEveryCrashPoint { point, onDisk, undoPoints ->
            assertTrue("$point: verso.json is neither config", onDisk == beforeCleanup || onDisk == cleaned)
            assertTrue("$point: the removed entries are gone", onDisk == beforeCleanup || undoPoints.holds(beforeCleanup))
        }
        assertEquals(
            listOf(
                "recheck", "publish verso-pre-cleanup", "write verso.json.new",
                "rename verso.json.new", "fsync the directory", "cleanup reported", "no death",
            ),
            points,
        )
    }

    @Test fun `a cleanup that gets through adds its undo point and removes none`() {
        cleanupAtEveryCrashPoint { point, onDisk, undoPoints ->
            if (point != "no death") return@cleanupAtEveryCrashPoint
            assertEquals(listOf(alpha.key), onDisk.favorites)
            assertEquals(mapOf(alpha.key to "Mine"), onDisk.names)
            assertEquals(listOf(previousUndo, beforeCleanup), undoPoints)
        }
    }
}
