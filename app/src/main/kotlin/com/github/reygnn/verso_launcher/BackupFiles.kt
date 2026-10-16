package com.github.reygnn.verso_launcher

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The naming rules of the Download/Verso backups, as pure functions so they are JVM-tested.
 * [BackupStore] does the MediaStore I/O; this decides which of the app's own rows a backup
 * IS, which one a restore reads, and which ones a fresh backup replaces.
 *
 * A backup is named after the moment it was taken — "verso-2026-10-13_120745.json" — so the
 * file says when it is from, and names sort in time order. Why not one fixed name: MediaStore
 * owns the Downloads namespace, not us. A fixed name is taken by our own previous backup at
 * the moment the new one is published (the old one is only deleted afterwards), and can be
 * taken by a row we cannot see or delete — the backup an uninstalled install left behind, or
 * a file copied in over adb. MediaStore resolves such a clash by publishing ours as
 * "verso (1).json" instead of failing; a timestamp makes that practically never happen.
 * MediaStore also appends ".json" to a name whose extension does not match the MIME type
 * ("verso.json.tmp" became "verso.json.tmp.json"). Both were observed on a device. So a
 * backup is still identified by PATTERN (" (n)" included, for two backups in one second),
 * and a restore takes the newest of our own matching rows, whatever its exact name.
 *
 * Newest means highest MediaStore _ID: every write inserts a fresh row, a publish renames it
 * in place (keeping its id), and ids only grow — unlike DATE_MODIFIED, which has one-second
 * resolution and would tie two backups taken in the same second.
 */
internal object BackupFiles {

    /** Sub-folder of Downloads the backups go to. */
    const val DIR = "Verso"

    /** The base names, published as "<base>-<date>_<time>.json": the backup ("~backup"), the
     *  undo points written before a restore and before a cleanup, and the copy of an unreadable
     *  config set aside by "~reset" or a restore over it. Each base keeps its own one row and
     *  never claims another's ([isPublished]). */
    const val BACKUP_BASE = "verso"
    const val PRE_RESTORE_BASE = "verso-pre-restore"
    const val PRE_CLEANUP_BASE = "verso-pre-cleanup"
    const val BROKEN_BASE = "verso-broken"

    /** One of the app's own rows in the backup folder. */
    data class Entry(val id: Long, val name: String)

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    /** The name a backup of [base] taken at [now] is published under — MediaStore may add
     *  " (n)" to it. Local time: it is for the user reading the folder, not for ordering. */
    fun publishedName(base: String, now: LocalDateTime) = "$base-${STAMP.format(now)}.json"

    /** The name a backup of [base] is written under before it is published. It ends in
     *  ".json" so MediaStore keeps it as given (bar a " (n)" on a clash). */
    fun tempName(base: String) = "$base.tmp.json"

    /**
     * The time a backup [name] was taken, as "HH:mm" — what a toast shows instead of the whole
     * 40-character name, which a two-line toast would cut off. The name itself, unchanged, for
     * anything that carries no timestamp (an older fixed-name backup).
     */
    fun timeOf(name: String): String =
        Regex(""".*-\d{4}-\d{2}-\d{2}_(\d{2})(\d{2})\d{2}( \(\d+\))?\.json""").matchEntire(name)
            ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" } ?: name

    /** "verso-2026-10-13_120745.json", "…_120745 (1).json" — a published backup of [base].
     *  Also the earlier fixed names "verso.json" / "verso (3).json", so a backup written before
     *  the switch to timestamps is still restored, and swept by the next backup. */
    fun isPublished(base: String, name: String) =
        Regex("""${Regex.escape(base)}(-\d{4}-\d{2}-\d{2}_\d{6})?( \(\d+\))?\.json""").matches(name)

    /** "verso.tmp.json", "verso.tmp (1).json" — an unpublished one. Also the pre-fix temp
     *  "verso.json.tmp.json" (MediaStore's rewrite of "verso.json.tmp"), so a temp stranded
     *  by the old code is still found, recovered and swept like any other. */
    fun isTemp(base: String, name: String) =
        Regex("""${Regex.escape(base)}(\.json)?\.tmp( \(\d+\))?\.json""").matches(name)

    /**
     * The row a restore of [base] reads: the newest published backup, or — only when none was
     * ever published — the newest temp, i.e. a first backup interrupted before its publish.
     * A torn temp is no risk there: the restore's parse rejects anything that is not a config.
     */
    fun latestToRestore(base: String, own: List<Entry>): Entry? =
        own.filter { isPublished(base, it.name) }.maxByOrNull { it.id }
            ?: own.filter { isTemp(base, it.name) }.maxByOrNull { it.id }

    /** Everything a just-published backup [keepId] of [base] replaces: the older published
     *  copies and any stranded temps. Never [keepId] itself, never another base's rows. */
    fun replacedBy(base: String, keepId: Long, own: List<Entry>): List<Entry> =
        own.filter { it.id != keepId && (isPublished(base, it.name) || isTemp(base, it.name)) }
}
