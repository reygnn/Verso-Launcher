package com.github.reygnn.verso_launcher

/**
 * The naming rules of the Download/Verso backups, as pure functions so they are JVM-tested.
 * [BackupStore] does the MediaStore I/O; this decides which of the app's own rows a backup
 * IS, which one a restore reads, and which ones a fresh backup replaces.
 *
 * Why not one fixed file name: MediaStore owns the Downloads namespace, not us. A name can
 * be taken by a row we cannot see or delete — the backup an uninstalled install left behind,
 * or a file copied in over adb — and MediaStore then resolves the clash by publishing ours
 * as "verso (1).json" instead of failing. It also appends ".json" to a name whose extension
 * does not match the MIME type ("verso.json.tmp" became "verso.json.tmp.json"). Both were
 * observed on a device. So a backup is identified by PATTERN, a restore takes the newest of
 * our own matching rows, and the exact name is whatever MediaStore made of it.
 *
 * Newest means highest MediaStore _ID: every write inserts a fresh row, a publish renames it
 * in place (keeping its id), and ids only grow — unlike DATE_MODIFIED, which has one-second
 * resolution and would tie two backups taken in the same second.
 */
internal object BackupFiles {

    /** One of the app's own rows in the backup folder. */
    data class Entry(val id: Long, val name: String)

    /** The name a backup of [base] is published under — MediaStore may add " (n)". */
    fun publishedName(base: String) = "$base.json"

    /** The name a backup of [base] is written under before it is published. It ends in
     *  ".json" so MediaStore keeps it as given (bar a " (n)" on a clash). */
    fun tempName(base: String) = "$base.tmp.json"

    /** "verso.json", "verso (3).json" — a published backup of [base]. */
    fun isPublished(base: String, name: String) =
        Regex("""${Regex.escape(base)}( \(\d+\))?\.json""").matches(name)

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
