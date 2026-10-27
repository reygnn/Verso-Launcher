package com.github.reygnn.verso_launcher

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The pure naming rules of the Download/Verso backups; [BackupStore] does the MediaStore I/O.
 *
 * Names are timestamped because MediaStore owns the namespace: a clash with any row, ours or not,
 * is resolved as "verso (1).json", and a name whose extension does not match the MIME type gets
 * ".json" appended. So a backup is recognised by pattern, and "newest" is the highest _ID
 * (DATE_MODIFIED has 1 s resolution).
 */
internal object BackupFiles {

    const val DIR = "Verso"

    const val BACKUP_BASE = "verso"
    const val PRE_RESTORE_BASE = "verso-pre-restore"
    /** Left by "~cleanup" up to 0.5.9; "~undo" still takes them. */
    const val PRE_CLEANUP_BASE = "verso-pre-cleanup"
    const val PRE_UNDO_BASE = "verso-pre-undo"

    /**
     * What a restore takes (the newest own row of [bases], hard rule 6) and the undo point it
     * leaves first (hard rule 7). "~restore-saf" leaves [BACKUP]'s: a picked file is a restore.
     */
    enum class Source(val bases: List<String>, val undoBase: String) {
        /** "~restore": the newest "~backup", never an undo point. */
        BACKUP(listOf(BACKUP_BASE), PRE_RESTORE_BASE),

        /** "~undo": the newest undo point of any kind — its own too, so a second "~undo" takes the first back. */
        UNDO(listOf(PRE_RESTORE_BASE, PRE_CLEANUP_BASE, PRE_UNDO_BASE), PRE_UNDO_BASE),
    }

    data class Entry(val id: Long, val name: String)

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun publishedName(base: String, now: LocalDateTime) = "$base-${STAMP.format(now)}.json"

    /** "HH:mm" for a toast (the full name gets cut off); the name itself if it has no stamp. */
    fun timeOf(name: String): String =
        Regex(""".*-\d{4}-\d{2}-\d{2}_(\d{2})(\d{2})\d{2}( \(\d+\))?\.json""").matchEntire(name)
            ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" } ?: name

    /** "verso-2026-10-13_120745.json", "… (1).json", and the old fixed name "verso.json". */
    fun isPublished(base: String, name: String) =
        Regex("""${Regex.escape(base)}(-\d{4}-\d{2}-\d{2}_\d{6})?( \(\d+\))?\.json""").matches(name)

    /** The newest backup of [base]: only published names count. */
    fun latestToRestore(base: String, own: List<Entry>): Entry? = latestToRestore(listOf(base), own)

    /** The newest file of any of [bases], by id across them all: only published names count. */
    fun latestToRestore(bases: List<String>, own: List<Entry>): Entry? =
        own.filter { e -> bases.any { isPublished(it, e.name) } }.maxByOrNull { it.id }
}
