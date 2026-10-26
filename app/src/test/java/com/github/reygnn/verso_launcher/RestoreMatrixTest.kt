package com.github.reygnn.verso_launcher

import com.github.reygnn.verso_launcher.RestoreMatrix.Cell
import com.github.reygnn.verso_launcher.RestoreMatrix.Cmd
import com.github.reygnn.verso_launcher.RestoreMatrix.Fault
import com.github.reygnn.verso_launcher.RestoreMatrix.Outcome
import com.github.reygnn.verso_launcher.RestoreMatrix.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalDateTime

/**
 * docs/spec/restore-matrix.md on the JVM: every cell of [RestoreMatrix], then every cell again with
 * the process killed before each step, then every sequence of up to four commands — each checked
 * against the hard rules after every step.
 *
 * The real LauncherState, ConfigStore (on real files), RestoreSteps, BackupFiles, Cleanup and
 * ConfigJson; Download/Verso a list of rows. What joins them is Commands' glue, retold in [run]:
 * RestoreMatrixFlowsTest holds the real Commands to the same answers.
 */
class RestoreMatrixTest {

    @get:Rule val tmp = TemporaryFolder()

    private class ProcessDeath(at: String) : Error("process killed before: $at")

    /** Dies before step number [deathAt] (0-based), once [armed]: the load before a command is no step. */
    private class Reaper(private val deathAt: Int) {
        var armed = false
        val passed = mutableListOf<String>()
        fun step(name: String, beforeDeath: () -> Unit = {}) {
            if (!armed) return
            if (passed.size == deathAt) {
                beforeDeath()
                throw ProcessDeath(name)
            }
            passed += name
        }
    }

    /** Download/Verso: rows outlive the process, a newer one has a larger _ID; one [fault] at a time. */
    private class Downloads(seeds: List<Pair<String, String>>, private val fault: Fault, private val reaper: Reaper) {
        class Row(val id: Long, val name: String, val payload: String)

        val rows = seeds.mapIndexed { i, (name, payload) -> Row(i + 1L, name, payload) }.toMutableList()

        fun list(): List<BackupFiles.Entry>? = if (fault == Fault.LIST) null else rows.map { BackupFiles.Entry(it.id, it.name) }

        fun read(id: Long): String? = if (fault == Fault.READ) null else rows.single { it.id == id }.payload

        fun write(base: String, payload: String): String? {
            reaper.step("publish $base")
            if (fault == Fault.DOWNLOAD_WRITE) return null
            val n = rows.size
            val name = BackupFiles.publishedName(base, LocalDateTime.of(2026, 10, 26, 12, n / 60 % 60, n % 60))
            rows += Row(rows.size + 1L, name, payload)
            return name
        }
    }

    private inner class World(
        seeds: List<Pair<String, String>>,
        val fault: Fault,
        start: Start,
        loaded: Boolean,
        deathAt: Int = Int.MAX_VALUE,
    ) {
        val dir = tmp.newFolder()
        val reaper = Reaper(deathAt)
        val downloads = Downloads(seeds, fault, reaper)
        var saveFailed = false
        var unusableSaid = false

        private val store = storeIn(reaper)
        private val io = ArrayDeque<Runnable>()
        private val main = ArrayDeque<Runnable>()

        val state = testLauncherState(
            io = { io.addLast(it); true },
            main = { main.addLast(it) },
            loadConfig = store::load,
            enumerateApps = { Enumeration(listOf(RestoreMatrix.alpha), true) },
            serialize = ConfigJson::serialize,
            writeConfig = store::write,
            onSaveFailed = { saveFailed = true },
            onConfigUnusable = { unusableSaid = true },
            onRefreshed = { _, _ -> },
        )

        init {
            when (start) {
                Start.GOOD -> storeIn(null).write(ConfigJson.serialize(RestoreMatrix.GOOD_CONFIG))
                Start.FRESH -> Unit
                Start.UNREADABLE -> dir.resolve(ConfigStore.CONFIG_FILE).writeText(RestoreMatrix.TORN)
            }
            if (loaded) {
                state.refresh()
                drain()
                check(state.configLoaded) { "harness: the config did not load" }
            }
            reaper.armed = true
        }

        /** A death drops whatever is still queued: the process takes it with it. */
        fun drain() {
            while (io.isNotEmpty() || main.isNotEmpty()) {
                while (io.isNotEmpty()) io.removeFirst().run()
                while (main.isNotEmpty()) main.removeFirst().run()
            }
        }

        private fun storeIn(reaper: Reaper?) = ConfigStore(
            dir = dir,
            log = { _, _ -> },
            rename = { from, to -> reaper?.step("move ${from.name} to ${to.name}"); from.renameTo(to) },
            delete = { file ->
                if (file.name.endsWith(".bak")) reaper?.step("delete ${file.name}")
                file.delete()
            },
            writeSynced = { file, bytes ->
                // Cut off halfway: a torn verso.json beside the .bak.
                reaper?.step("write ${file.name}") { file.writeText("{\"favorites\":[\"to") }
                if (reaper != null && fault == Fault.CONFIG_WRITE) throw IOException("disk full")
                file.writeBytes(bytes)
            },
        )

        /** The next start: a fresh ConfigStore's load; null for a verso.json that cannot be read. */
        fun coldStart(): VersoConfig? = ConfigStore(dir, log = { _, _ -> }).load()

        fun rowConfigs() = downloads.rows.map { ConfigJson.parse(it.payload) }
    }

    // ---- Commands' glue ------------------------------------------------------

    /**
     * Commands step for step, on the JVM. Before the load MainActivity refuses every command up
     * front; this goes past that on purpose, so the state's own last line of hard rule 5 is what
     * has to hold: nothing written, whatever the outcome says.
     */
    private fun World.run(cell: Cell): Outcome = when (cell.cmd) {
        Cmd.BACKUP -> state.loadedSnapshot()?.let { snapshot ->
            if (downloads.write(BackupFiles.BACKUP_BASE, ConfigJson.serialize(snapshot)) != null) Outcome.BACKED_UP
            else Outcome.BACKUP_FAILED
        } ?: Outcome.NOT_LOADED
        Cmd.RESTORE -> newest(BackupFiles.Source.BACKUP)
        Cmd.UNDO -> newest(BackupFiles.Source.UNDO)
        // The picked file, read on a thread of its own: a READ fault is a provider that fails it.
        Cmd.RESTORE_SAF -> (if (fault == Fault.READ) null else RestoreMatrix.payloadOf(cell, RestoreMatrix.picked))
            ?.let(ConfigJson::parseForeign)?.let { adopt(it, BackupFiles.Source.BACKUP) } ?: Outcome.UNREADABLE
        Cmd.CLEANUP -> cleanup()
    }

    private fun World.newest(source: BackupFiles.Source): Outcome {
        val rows = downloads.list() ?: return Outcome.LIST_FAILED
        val entry = BackupFiles.latestToRestore(source.bases, rows)
            ?: return if (source == BackupFiles.Source.UNDO) Outcome.NO_UNDO_POINT else Outcome.NO_BACKUP
        val restored = downloads.read(entry.id)?.let(ConfigJson::parseForeign) ?: return Outcome.UNREADABLE
        return adopt(restored, source)
    }

    private fun World.adopt(restored: VersoConfig, source: BackupFiles.Source): Outcome {
        val outgoing = state.loadedSnapshot() ?: return Outcome.NOT_LOADED
        val undo = source == BackupFiles.Source.UNDO
        val outcome = RestoreSteps.run(
            current = outgoing,
            restored = restored,
            writeUndo = { payload -> downloads.write(source.undoBase, payload) },
            writeConfig = state::writeRestored,
        )
        if (outcome is RestoreSteps.Outcome.Done) state.adopt(restored)
        return when (outcome) {
            RestoreSteps.Outcome.Unchanged -> if (undo) Outcome.SAME_AS_UNDO_POINT else Outcome.SAME_AS_BACKUP
            RestoreSteps.Outcome.UndoFailed -> Outcome.UNDO_POINT_FAILED
            RestoreSteps.Outcome.WriteFailed -> Outcome.WRITE_FAILED
            is RestoreSteps.Outcome.Done -> if (undo) Outcome.UNDONE else Outcome.RESTORED
        }
    }

    private fun World.cleanup(): Outcome {
        var outcome: CleanupOutcome? = null
        state.cleanup(
            confirmed = setOf(RestoreMatrix.GONE),
            recheck = { config ->
                reaper.step("recheck")
                Cleanup.plan(config, Enumeration(listOf(RestoreMatrix.alpha), true)) { false }
            },
            writeUndo = { payload -> downloads.write(BackupFiles.PRE_CLEANUP_BASE, payload) },
        ) { outcome = it }
        drain()
        return when (val o = outcome) {
            CleanupOutcome.Refused -> Outcome.NOT_LOADED
            CleanupOutcome.UndoFailed -> Outcome.UNDO_POINT_FAILED
            is CleanupOutcome.Done -> Outcome.CLEANED
            CleanupOutcome.NothingToDo -> Outcome.NOTHING_TO_CLEAN
            else -> error("harness: cleanup ended as $o")
        }
    }

    // ---- the matrix ----------------------------------------------------------

    private fun world(cell: Cell, deathAt: Int = Int.MAX_VALUE) =
        World(RestoreMatrix.payloads(cell), cell.fault, cell.start, cell.loaded, deathAt)

    /** Hard rule 5's last lines, past every check a window makes: none may change memory or disk. */
    private fun World.pokeBeforeLoad() {
        assertTrue("no snapshot", state.loadedSnapshot() == null)
        assertTrue("no permit", state.editPermit(CommandGate().idle()!!) == null)
        assertTrue("no adoption", !state.adopt(RestoreMatrix.restoreTarget))
        state.save()
        drain()
    }

    /** Runs [check] for every cell, then fails once, naming every cell that broke (the first 25). */
    private fun forEachCell(cells: List<Cell>, check: (Cell) -> Unit) {
        val broken = cells.mapNotNull { cell -> runCatching { check(cell) }.exceptionOrNull()?.let { "$cell\n    ${it.message}" } }
        assertTrue("${broken.size} of ${cells.size} cells broke:\n" + broken.take(25).joinToString("\n"), broken.isEmpty())
    }

    @Test fun `every cell ends as the spec says, and keeps the hard rules`() = forEachCell(RestoreMatrix.cells()) { cell ->
        val expected = RestoreMatrix.expected(cell)
        val w = world(cell)
        val outcome = w.run(cell)
        w.drain()
        // Before the load only the window says NOT_LOADED (RestoreMatrixFlowsTest); here: nothing written.
        if (cell.loaded) assertEquals("outcome", expected.outcome, outcome) else w.pokeBeforeLoad()

        // 6: Verso never deletes or changes a row, and adds at most the one the spec names.
        val seeded = RestoreMatrix.payloads(cell)
        assertEquals("seeded rows", seeded, w.downloads.rows.take(seeded.size).map { it.name to it.payload })
        val added = w.downloads.rows.drop(seeded.size)
        if (expected.newRow == null) {
            assertEquals("added rows", emptyList<String>(), added.map { it.name })
        } else {
            assertEquals("added rows", 1, added.size)
            assertTrue("${added.single().name} is not a ${expected.newRow}", BackupFiles.isPublished(expected.newRow, added.single().name))
            assertEquals("the added row holds the config the user had", cell.current, ConfigJson.parse(added.single().payload))
        }

        // 4, 5, 7: memory and disk as the spec says; a difference only as a save the user is told of.
        assertEquals("verso.json", expected.disk, w.coldStart())
        assertEquals("memory", expected.memory, w.state.config)
        assertEquals("save failed said", expected.outcome == Outcome.CLEANED && cell.fault == Fault.CONFIG_WRITE, w.saveFailed)
        assertEquals("unreadable verso.json said", cell.start == Start.UNREADABLE && cell.loaded, w.unusableSaid)

        // 7, 14: verso.json never changes without the config it replaced in a row.
        if (w.coldStart() != RestoreMatrix.diskBefore(cell)) assertTrue("replaced config kept", cell.current in w.rowConfigs())
    }

    /** Else a row of the table could never fail: every answer the spec gives is one some cell reaches. */
    @Test fun `the matrix reaches every outcome`() {
        val reached = RestoreMatrix.cells().map { RestoreMatrix.expected(it).outcome }.toSet()
        assertEquals(Outcome.entries.toSet(), reached)
    }

    // ---- crash points --------------------------------------------------------

    @Test fun `a command killed before any step leaves a config the user had, on disk or in a row`() {
        val cells = RestoreMatrix.cells().filter { it.loaded && it.fault == Fault.NONE && RestoreMatrix.expected(it).newRow != null }
        val stepsSeen = mutableSetOf<String>()
        forEachCell(cells) { cell ->
            val expected = RestoreMatrix.expected(cell)
            var deathAt = 0
            while (true) {
                val w = world(cell, deathAt)
                val died = try {
                    w.run(cell)
                    w.drain()
                    false
                } catch (_: ProcessDeath) {
                    true
                }
                stepsSeen += w.reaper.passed
                val onDisk = w.coldStart()
                val before = RestoreMatrix.diskBefore(cell)
                // The old config or the new one. Only a first write has no .bak to fall back on: torn,
                // it loads as the empty config the user had, and says so (spec: F1).
                val tornFirstWrite = cell.start == Start.FRESH && onDisk == null
                assertTrue("death $deathAt: verso.json is $onDisk", onDisk == before || onDisk == expected.disk || tornFirstWrite)
                assertTrue("death $deathAt: the config the user had is lost", onDisk == before || cell.current in w.rowConfigs())
                val seeded = RestoreMatrix.payloads(cell)
                assertEquals("death $deathAt: seeded rows", seeded, w.downloads.rows.take(seeded.size).map { it.name to it.payload })
                if (!died) break
                deathAt++
            }
        }
        // Else the reaper never reached the steps that matter.
        val needed = listOf("publish verso-pre-undo", "move verso.json to verso.json.bak", "write verso.json", "recheck")
        assertTrue(stepsSeen.toString(), stepsSeen.containsAll(needed))
    }

    // ---- sequences -----------------------------------------------------------

    /**
     * The spec's own model of a run of commands, told plainly: [rows] as (base, config) in _ID
     * order, the config the user has. Nothing in it comes from the code under test.
     */
    private class Model {
        val rows = mutableListOf(BackupFiles.BACKUP_BASE to RestoreMatrix.restoreTarget)
        var config = RestoreMatrix.GOOD_CONFIG

        fun step(cmd: Cmd) {
            when (cmd) {
                Cmd.BACKUP -> rows += BackupFiles.BACKUP_BASE to config
                Cmd.RESTORE -> adopt(newest(listOf(BackupFiles.BACKUP_BASE)), BackupFiles.PRE_RESTORE_BASE)
                Cmd.UNDO -> adopt(newest(UNDO_BASES), BackupFiles.PRE_UNDO_BASE)
                Cmd.CLEANUP -> if (RestoreMatrix.GONE in config.favorites) {
                    rows += BackupFiles.PRE_CLEANUP_BASE to config
                    config = config.copy(
                        favorites = config.favorites - RestoreMatrix.GONE,
                        hidden = config.hidden - RestoreMatrix.GONE,
                        names = config.names - RestoreMatrix.GONE,
                    )
                }
                Cmd.RESTORE_SAF -> error("not in a sequence")
            }
        }

        fun edit() {
            config = config.copy(favorites = if (EDITED in config.favorites) config.favorites - EDITED else config.favorites + EDITED)
        }

        private fun newest(bases: List<String>) = rows.lastOrNull { it.first in bases }?.second

        private fun adopt(restored: VersoConfig?, undoBase: String) {
            if (restored == null || restored == config) return
            rows += undoBase to config
            config = restored
        }

        companion object {
            val UNDO_BASES = listOf(BackupFiles.PRE_RESTORE_BASE, BackupFiles.PRE_CLEANUP_BASE, BackupFiles.PRE_UNDO_BASE)
        }
    }

    /** null: an edit (the favorite toggled) between commands. */
    private val ops: List<Cmd?> = listOf(Cmd.BACKUP, Cmd.RESTORE, Cmd.UNDO, Cmd.CLEANUP, null)

    private fun sequences(length: Int): List<List<Cmd?>> =
        if (length == 0) listOf(emptyList()) else sequences(length - 1).flatMap { s -> ops.map { s + it } }

    @Test fun `every sequence of up to four commands matches the spec's model after each step`() {
        val all = (1..4).flatMap(::sequences)
        val broken = all.mapNotNull { seq -> runCatching { runSequence(seq) }.exceptionOrNull()?.let { "${seq.label()}\n    ${it.message}" } }
        assertTrue("${broken.size} of ${all.size} sequences broke:\n" + broken.take(25).joinToString("\n"), broken.isEmpty())
    }

    private fun List<Cmd?>.label() = joinToString(" → ") { it?.typed ?: "edit" }

    private fun runSequence(seq: List<Cmd?>) {
        val w = World(
            listOf("verso-2026-10-20_090000.json" to ConfigJson.serialize(RestoreMatrix.restoreTarget)),
            Fault.NONE,
            Start.GOOD,
            loaded = true,
        )
        val model = Model()
        var beforeLast: VersoConfig? = null
        var lastChanged = false
        seq.forEachIndexed { i, cmd ->
            val before = model.config
            if (cmd == null) {
                w.state.toggleFavorite(w.state.editPermit(CommandGate().idle()!!)!!, EDITED)
                model.edit()
            } else {
                w.run(Cell(cmd, Start.GOOD, true, RestoreMatrix.Downloads.EMPTY, RestoreMatrix.Content.DIFFERENT, Fault.NONE))
                model.step(cmd)
            }
            w.drain()
            val at = "step ${i + 1} (${cmd?.typed ?: "edit"})"
            assertEquals("$at: memory", model.config, w.state.config)
            assertEquals("$at: verso.json", model.config, w.coldStart())
            assertEquals(
                "$at: rows",
                model.rows.toList(),
                w.downloads.rows.map { row -> baseOf(row.name) to ConfigJson.parse(row.payload) },
            )
            // The law ~undo is for: right after a restore, an undo or a cleanup, it brings back what was.
            if (cmd == Cmd.UNDO && lastChanged) assertEquals("$at: ~undo did not take the last step back", beforeLast, w.state.config)
            lastChanged = cmd in listOf(Cmd.RESTORE, Cmd.UNDO, Cmd.CLEANUP) && model.config != before
            beforeLast = before
        }
    }

    private fun baseOf(name: String) = listOf(
        BackupFiles.BACKUP_BASE, BackupFiles.PRE_RESTORE_BASE, BackupFiles.PRE_CLEANUP_BASE, BackupFiles.PRE_UNDO_BASE,
    ).single { BackupFiles.isPublished(it, name) }

    private companion object {
        const val EDITED = "com.edited/com.edited.Main"
    }
}
