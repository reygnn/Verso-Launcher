package com.github.reygnn.verso_launcher

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The pure naming rules of the Download/Verso backups; [BackupStore] does the MediaStore I/O.
 *
 * Names are timestamped because MediaStore owns the namespace: a clash with a row we cannot see
 * (or our previous backup, deleted only after the publish) is resolved as "verso (1).json", and
 * a name whose extension does not match the MIME type gets ".json" appended. So a backup is
 * recognised by pattern, and "newest" is the highest _ID (DATE_MODIFIED has 1 s resolution).
 */
internal object BackupFiles {

    const val DIR = "Verso"

    const val BACKUP_BASE = "verso"
    const val PRE_RESTORE_BASE = "verso-pre-restore"
    const val PRE_CLEANUP_BASE = "verso-pre-cleanup"
    const val UNREADABLE_BASE = "verso-unreadable"

    data class Entry(val id: Long, val name: String)

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun publishedName(base: String, now: LocalDateTime) = "$base-${STAMP.format(now)}.json"

    /** Ends in ".json" so MediaStore keeps the name as given. */
    fun tempName(base: String) = "$base.tmp.json"

    /** "HH:mm" for a toast (the full name gets cut off); the name itself if it has no stamp. */
    fun timeOf(name: String): String =
        Regex(""".*-\d{4}-\d{2}-\d{2}_(\d{2})(\d{2})\d{2}( \(\d+\))?\.json""").matchEntire(name)
            ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" } ?: name

    /** "verso-2026-10-13_120745.json", "… (1).json", and the old fixed name "verso.json". */
    fun isPublished(base: String, name: String) =
        Regex("""${Regex.escape(base)}(-\d{4}-\d{2}-\d{2}_\d{6})?( \(\d+\))?\.json""").matches(name)

    /** "verso.tmp.json", "verso.tmp (1).json", and the stranded old "verso.json.tmp.json". */
    fun isTemp(base: String, name: String) =
        Regex("""${Regex.escape(base)}(\.json)?\.tmp( \(\d+\))?\.json""").matches(name)

    /** The newest published backup, else the newest temp (a first backup cut off before its
     *  publish; a torn one is rejected by the restore's parse). */
    fun latestToRestore(base: String, own: List<Entry>): Entry? =
        own.filter { isPublished(base, it.name) }.maxByOrNull { it.id }
            ?: own.filter { isTemp(base, it.name) }.maxByOrNull { it.id }

    fun replacedBy(base: String, keepId: Long, own: List<Entry>): List<Entry> =
        own.filter { it.id != keepId && (isPublished(base, it.name) || isTemp(base, it.name)) }
}
