package com.github.reygnn.verso_launcher

/**
 * docs/spec/restore-matrix.md as code: the cells and what each must end in, written from the spec
 * and the hard rules, never from Commands. RestoreMatrixTest runs every cell on the JVM,
 * RestoreMatrixFlowsTest the reachable ones through the real Activity; both check these answers.
 *
 * Every row in Download/Verso holds a config of its own, so an adoption shows which row it took:
 * only the target of the command's source may ever show up in memory or verso.json.
 */
internal object RestoreMatrix {

    enum class Cmd(val typed: String) {
        BACKUP("~backup"), RESTORE("~restore"), UNDO("~undo"), RESTORE_SAF("~restore-saf"), CLEANUP("~cleanup")
    }

    enum class Role { DECOY, RESTORE_TARGET, UNDO_TARGET, LOOKALIKE }

    class Seed(val name: String, val role: Role)

    /**
     * What Download/Verso holds, oldest _ID first. The names' timestamps run against the _IDs on
     * purpose (hard rule 6: _ID decides); the look-alikes come last, so any looser pattern would
     * take one of them.
     */
    enum class Downloads(val seeds: List<Seed>) {
        EMPTY(emptyList()),
        LOOKALIKES(lookalikes()),
        BACKUPS(
            listOf(
                Seed("verso-2026-10-25_090000.json", Role.DECOY),
                Seed("verso-2026-10-01_090000 (1).json", Role.RESTORE_TARGET),
            ) + lookalikes()
        ),
        UNDO_POINTS(
            listOf(
                Seed("verso-pre-cleanup-2026-10-25_100000.json", Role.DECOY),
                Seed("verso-pre-undo.json", Role.DECOY),
                Seed("verso-pre-restore-2026-10-01_080000.json", Role.UNDO_TARGET),
            ) + lookalikes()
        ),

        /** The newest row is an undo point: "~restore" must pass over it. */
        BOTH_UNDO_NEWEST(
            listOf(
                Seed("verso-2026-10-25_090000.json", Role.DECOY),
                Seed("verso-pre-cleanup-2026-10-25_100000.json", Role.DECOY),
                Seed("verso-2026-10-01_090000.json", Role.RESTORE_TARGET),
                Seed("verso-pre-restore-2026-10-25_110000.json", Role.DECOY),
                Seed("verso-pre-undo-2026-10-01_080000.json", Role.UNDO_TARGET),
            ) + lookalikes()
        ),

        /** The newest row is a backup: "~undo" must pass over it. */
        BOTH_BACKUP_NEWEST(
            listOf(
                Seed("verso-pre-restore-2026-10-25_110000.json", Role.DECOY),
                Seed("verso-2026-10-25_090000.json", Role.DECOY),
                Seed("verso-pre-cleanup-2026-10-01_100000 (2).json", Role.UNDO_TARGET),
                Seed("verso-2026-10-01_090000.json", Role.RESTORE_TARGET),
            ) + lookalikes()
        ),
    }

    /** What the file a command takes holds: the source's newest row, or the picked file. */
    enum class Content { DIFFERENT, SAME_AS_CURRENT, NOT_A_CONFIG }

    /** One failure at a time, each where it can strike. */
    enum class Fault { NONE, LIST, READ, DOWNLOAD_WRITE, CONFIG_WRITE }

    /**
     * verso.json before the command. FRESH and UNREADABLE both load as the empty config (hard rule
     * 4); UNREADABLE stays on disk, torn, until something is written over it — "~restore" is the
     * way back the README names.
     */
    enum class Start { GOOD, FRESH, UNREADABLE }

    /** What the user is told — the toast's resource — and so how the command ended. */
    enum class Outcome(val message: Int) {
        NOT_LOADED(R.string.toast_not_loaded),
        BACKED_UP(R.string.toast_backup_ok),
        BACKUP_FAILED(R.string.toast_backup_failed),
        LIST_FAILED(R.string.toast_restore_list_failed),
        NO_BACKUP(R.string.toast_restore_none),
        NO_UNDO_POINT(R.string.toast_undo_none),
        UNREADABLE(R.string.toast_restore_failed),
        SAME_AS_BACKUP(R.string.toast_restore_same),
        SAME_AS_UNDO_POINT(R.string.toast_undo_same),
        UNDO_POINT_FAILED(R.string.toast_restore_undo_failed),
        WRITE_FAILED(R.string.toast_restore_write_failed),
        RESTORED(R.string.toast_restore_ok),
        UNDONE(R.string.toast_undo_ok),
        CLEANED(R.string.toast_cleanup_ok),
        NOTHING_TO_CLEAN(R.string.toast_cleanup_none),
    }

    data class Cell(
        val cmd: Cmd,
        val start: Start,
        val loaded: Boolean,
        val downloads: Downloads,
        val content: Content,
        val fault: Fault,
    ) {
        /** The config the user has: what the start loads as. */
        val current get() = if (start == Start.GOOD) GOOD_CONFIG else VersoConfig()

        override fun toString() =
            "${cmd.typed} start=${start.name} loaded=$loaded ${downloads.name} ${content.name} fault=${fault.name}"
    }

    /**
     * [newRow]: the base of the one row the command adds, or null for none; it always holds the
     * config the user had (an undo point's, or for "~backup" the one backed up).
     * [memory]: LauncherState's config afterwards. [disk]: what the next start loads — null for a
     * verso.json still unreadable.
     */
    data class Expected(val outcome: Outcome, val newRow: String?, val memory: VersoConfig, val disk: VersoConfig?)

    val alpha = AppEntry("Alpha", "com.alpha", "com.alpha.Main", "Alpha")
    const val GONE = "com.gone/com.gone.Main"

    /** A good verso.json: one installed app, one uninstalled ("~cleanup"'s orphan). */
    val GOOD_CONFIG = VersoConfig(
        favorites = listOf(alpha.key, GONE),
        hidden = setOf(GONE),
        names = mapOf(alpha.key to "Mine", GONE to "Old"),
    )
    val cleaned = VersoConfig(favorites = listOf(alpha.key), names = mapOf(alpha.key to "Mine"))
    val restoreTarget = favorite("restore/Target")
    val undoTarget = favorite("undo/Target")
    val picked = favorite("picked/Target")
    const val NOT_A_CONFIG = """{"name":"some package.json","version":"1.0"}"""
    const val TORN = """{"favorites":["com.alp"""

    fun favorite(key: String) = VersoConfig(favorites = listOf(key))

    /** Each seed's payload: a target holds the cell's content, every other row a config of its own. */
    fun payloads(cell: Cell): List<Pair<String, String>> =
        cell.downloads.seeds.mapIndexed { i, seed ->
            seed.name to when (seed.role) {
                Role.RESTORE_TARGET -> payloadOf(cell, restoreTarget)
                Role.UNDO_TARGET -> payloadOf(cell, undoTarget)
                Role.DECOY, Role.LOOKALIKE -> ConfigJson.serialize(favorite("row$i/${seed.role}"))
            }
        }

    fun payloadOf(cell: Cell, target: VersoConfig): String = when (cell.content) {
        Content.DIFFERENT -> ConfigJson.serialize(target)
        Content.SAME_AS_CURRENT -> ConfigJson.serialize(cell.current)
        Content.NOT_A_CONFIG -> NOT_A_CONFIG
    }

    /** The config a command would adopt, if the content is one. */
    private fun adopted(cell: Cell, target: VersoConfig) = when (cell.content) {
        Content.DIFFERENT -> target
        Content.SAME_AS_CURRENT -> cell.current
        Content.NOT_A_CONFIG -> null
    }

    fun cells(): List<Cell> = Cmd.entries.flatMap { cmd ->
        Start.entries.flatMap { start ->
            listOf(true, false).flatMap { loaded ->
                Downloads.entries.flatMap { d ->
                    Content.entries.flatMap { c -> Fault.entries.map { f -> Cell(cmd, start, loaded, d, c, f) } }
                }
            }
        }
    }

    /** What the next start loads when nothing was written. */
    fun diskBefore(cell: Cell): VersoConfig? = if (cell.start == Start.UNREADABLE) null else cell.current

    /** The spec's table. Hard rules 4, 5, 6, 7 and 14 decide every line; the comments name which. */
    fun expected(cell: Cell): Expected {
        val unchanged = { outcome: Outcome -> Expected(outcome, null, cell.current, diskBefore(cell)) }
        // 5: nothing written before the load; memory holds the empty default.
        if (!cell.loaded) return Expected(Outcome.NOT_LOADED, null, VersoConfig(), diskBefore(cell))
        return when (cell.cmd) {
            Cmd.BACKUP ->
                if (cell.fault == Fault.DOWNLOAD_WRITE) unchanged(Outcome.BACKUP_FAILED)
                else Expected(Outcome.BACKED_UP, BackupFiles.BACKUP_BASE, cell.current, diskBefore(cell))

            Cmd.CLEANUP -> when {
                cell.current != GOOD_CONFIG -> unchanged(Outcome.NOTHING_TO_CLEAN)
                // 14: no undo point, no cleanup.
                cell.fault == Fault.DOWNLOAD_WRITE -> unchanged(Outcome.UNDO_POINT_FAILED)
                // The removal is an edit: a failed save is said (hard rule 4) and retried by the next.
                cell.fault == Fault.CONFIG_WRITE -> Expected(Outcome.CLEANED, BackupFiles.PRE_CLEANUP_BASE, cleaned, GOOD_CONFIG)
                else -> Expected(Outcome.CLEANED, BackupFiles.PRE_CLEANUP_BASE, cleaned, cleaned)
            }

            Cmd.RESTORE, Cmd.UNDO, Cmd.RESTORE_SAF -> restore(cell, unchanged)
        }
    }

    private fun restore(cell: Cell, unchanged: (Outcome) -> Expected): Expected {
        val undo = cell.cmd == Cmd.UNDO
        val target = targetOf(cell)
        return when {
            cell.cmd != Cmd.RESTORE_SAF && cell.fault == Fault.LIST -> unchanged(Outcome.LIST_FAILED)
            target == null -> unchanged(if (undo) Outcome.NO_UNDO_POINT else Outcome.NO_BACKUP)
            cell.fault == Fault.READ -> unchanged(Outcome.UNREADABLE)
            // 7: parseForeign, never the lenient parse, for a file from outside.
            else -> when (val restored = adopted(cell, target)) {
                null -> unchanged(Outcome.UNREADABLE)
                // 7: equal to the current config: stop, before the undo point.
                cell.current -> unchanged(if (undo) Outcome.SAME_AS_UNDO_POINT else Outcome.SAME_AS_BACKUP)
                else -> written(cell, restored, unchanged)
            }
        }
    }

    /** 6: "~restore" takes only backups, "~undo" only undo points; the picker any file. */
    private fun targetOf(cell: Cell): VersoConfig? = when (cell.cmd) {
        Cmd.RESTORE_SAF -> picked
        Cmd.UNDO -> undoTarget.takeIf { cell.downloads.seeds.any { it.role == Role.UNDO_TARGET } }
        else -> restoreTarget.takeIf { cell.downloads.seeds.any { it.role == Role.RESTORE_TARGET } }
    }

    /** 7: the undo point, then verso.json, then memory — each only once the one before held. */
    private fun written(cell: Cell, restored: VersoConfig, unchanged: (Outcome) -> Expected): Expected {
        val undo = cell.cmd == Cmd.UNDO
        val undoBase = if (undo) BackupFiles.PRE_UNDO_BASE else BackupFiles.PRE_RESTORE_BASE
        return when (cell.fault) {
            // No undo point, no restore.
            Fault.DOWNLOAD_WRITE -> unchanged(Outcome.UNDO_POINT_FAILED)
            // A failed write adopts nothing; the undo point written before it stays (6).
            Fault.CONFIG_WRITE -> Expected(Outcome.WRITE_FAILED, undoBase, cell.current, diskBefore(cell))
            else -> Expected(if (undo) Outcome.UNDONE else Outcome.RESTORED, undoBase, restored, restored)
        }
    }

    private fun lookalikes() = listOf(
        Seed("verso.tmp.json", Role.LOOKALIKE),
        Seed("verso-pre-restore.tmp.json", Role.LOOKALIKE),
        Seed("verso-backup.json", Role.LOOKALIKE),
        Seed("Verso-2026-10-26_090000.json", Role.LOOKALIKE),
        Seed("verso-pre-undo-2026-10-26_090000.json.txt", Role.LOOKALIKE),
    )
}
