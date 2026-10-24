package com.github.reygnn.guru

import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * The reports in their own directory (filesDir/guru), one file each, written atomically
 * (.new + fsync → rename → fsync dir), at most [keep] — the newest — so a crash loop cannot
 * fill the disk. Beside them: the installation id, the exit mark and the times of the crashes
 * saved. Never throws; [log] is the error channel. [syncDir], [rename] and [now] are seams.
 *
 * Called from the IO thread, and from a crashing thread in the handler — each write goes to a
 * name of its own, so the two never touch the same file.
 */
// The store is the directory: a read and a write for each of its four kinds of file, plus the
// atomic write they share — split up, each part would hold one pair.
@Suppress("TooManyFunctions")
internal class GuruStore(
    private val dir: File,
    private val log: (String, Throwable?) -> Unit,
    private val syncDir: (File) -> Unit,
    private val rename: (File, File) -> Boolean = File::renameTo,
    private val now: () -> Long = System::currentTimeMillis,
    private val keep: Int = KEEP,
) {

    /** False when nothing was published; an older report is never touched by a failed write. */
    fun write(report: GuruReport): Boolean {
        if (!ReportCodec.ID.matches(report.id)) {
            log("refused report id ${report.id}", null)
            return false
        }
        // ROOT: under Arabic, %d writes Arabic-Indic digits and NAME would no longer find the file.
        val name = String.format(Locale.ROOT, "%s%013d-%s%s", PREFIX, report.crashedAt.coerceAtLeast(0), report.id, SUFFIX)
        val written = writeAtomically(File(dir, name), ReportCodec.encode(report))
        if (written) prune()
        return written
    }

    /**
     * Newest first. A file that does not decode is logged and removed: it would come back forever.
     * A .new is a write that died halfway — swept once old; a young one may be a write running
     * right now on a crashing thread.
     */
    fun pending(): List<GuruReport> {
        val cutoff = now() - LEFTOVER_AGE_MS
        dir.listFiles()?.filter { it.name.endsWith(PENDING) && it.lastModified() < cutoff }?.forEach { it.delete() }
        return reportFiles().sortedByDescending { it.name }.mapNotNull { file ->
            val report = read(file)?.let(ReportCodec::decode)
            if (report == null) {
                log("dropping unreadable ${file.name}", null)
                file.delete()
            }
            report
        }
    }

    fun remove(ids: Collection<String>) {
        val doomed = ids.toSet()
        reportFiles().filter { NAME.matchEntire(it.name)?.groupValues?.get(1) in doomed }.forEach { file ->
            if (!file.delete()) log("could not remove ${file.name}", null)
        }
    }

    /**
     * When the crashes Guru saved happened, the newest [keep]: an exit record of one of them, if
     * the system lists it only at a later start, is no new crash. Unreadable = none.
     */
    fun crashesSaved(): List<Long> = read(File(dir, CRASHES_SAVED))?.lines()?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    fun markCrashSaved(at: Long): Boolean =
        writeAtomically(File(dir, CRASHES_SAVED), (crashesSaved() + at).distinct().sortedDescending().take(keep).joinToString("\n"))

    /** The newest exit timestamp already looked at; null before the first look (or unreadable). */
    fun exitsSeen(): Long? = read(File(dir, EXITS_SEEN))?.trim()?.toLongOrNull()

    fun markExitsSeen(timestamp: Long): Boolean = writeAtomically(File(dir, EXITS_SEEN), timestamp.toString())

    /**
     * ACRA's INSTALLATION_ID (Acrarium requires it and counts affected installs by it): made once
     * with [create], kept until the app's data is cleared. If it cannot be stored, a fresh one
     * per call — the report still goes through.
     */
    fun installationId(create: () -> String): String {
        val file = File(dir, INSTALLATION)
        read(file)?.trim()?.takeIf(ReportCodec.ID::matches)?.let { return it }
        return create().also { writeAtomically(file, it) }
    }

    private fun reportFiles(): List<File> = dir.listFiles()?.filter { it.isFile && NAME.matches(it.name) }.orEmpty()

    private fun prune() {
        reportFiles().sortedByDescending { it.name }.drop(keep).forEach { file ->
            if (!file.delete()) log("could not prune ${file.name}", null)
        }
    }

    // unreadable = none, logged
    @Suppress("TooGenericExceptionCaught")
    private fun read(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        log("${file.name} unreadable", e)
        null
    }

    // never throws: the crashing thread calls this
    @Suppress("TooGenericExceptionCaught")
    private fun writeAtomically(target: File, text: String): Boolean {
        val pending = File(dir, target.name + PENDING)
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("cannot create ${dir.name}")
            FileOutputStream(pending).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!rename(pending, target)) throw java.io.IOException("rename of ${pending.name} refused")
            syncDir(dir)
            true
        } catch (e: Exception) {
            log("writing ${target.name} failed", e)
            pending.delete()
            false
        }
    }

    companion object {
        const val KEEP = 8
        const val LEFTOVER_AGE_MS = 60_000L
        const val EXITS_SEEN = "exits-seen"
        const val CRASHES_SAVED = "crashes-saved"
        const val INSTALLATION = "installation-id"
        private const val PREFIX = "guru-"
        private const val SUFFIX = ".txt"
        private const val PENDING = ".new"
        private val NAME = Regex("""guru-\d{13}-([A-Za-z0-9-]{1,64})\.txt""")
    }
}
