package com.github.reygnn.guru

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.time.ZoneId
import java.util.UUID

/**
 * The module's front door. [install] once from Application.onCreate; [showPending] from the
 * first activity. Guru makes no thread of its own: all disk work goes through the host's `io`,
 * in submission order, and every task is wrapped so that nothing it throws reaches the host's
 * thread.
 *
 * Save first, then tell: at every start the reports go to Download/Guru before anything is
 * shown. The alert only says what this start saved, once; nothing waits on the user, so an app
 * that dies before its alert, or an alert that dies itself, loses nothing. A process that died
 * with its alert up gets none at the next start, so no alert can kill every start.
 */
internal object Guru {

    private const val DIR = "guru"

    private class Setup(
        // The Application, never an Activity: Setup lives in a static field for the process.
        val context: Application,
        val store: GuruStore,
        val process: GuruProcess,
        val texts: GuruTexts,
        val io: (Runnable) -> Unit,
        val downloads: GuruDownloads,
        /** The app's name for the files, read once at install. */
        val label: String,
    ) {
        /** What this start saved, for the alert: set and taken on the IO thread only. */
        var saved: List<Saved> = emptyList()
    }

    /** A report saved, and where. */
    private class Saved(val report: GuruReport, val file: Uri)

    // Replaced by a second install (a Robolectric test gets a new Application per test); the
    // handler reads it at crash time, so it always writes to the current one.
    @Volatile private var setup: Setup? = null

    /**
     * Installs the handler and queues two steps on [io]: the look at the system's exit records,
     * then saving every pending report to Download/Guru. The process start is taken as now.
     * Installing twice keeps one handler. [texts]: the host's words, from its resources;
     * English when it has none.
     */
    fun install(app: Application, io: (Runnable) -> Unit, texts: GuruTexts = GuruTexts.English) {
        val store = GuruStore(File(app.filesDir, DIR), ::log, syncDir = ::fsyncDir)
        val downloads = GuruDownloads(app.contentResolver, ::log, owner = app.packageName)
        val current = Setup(app, store, GuruProcess(environment(app), System.currentTimeMillis()), texts, io, downloads, label(app))
        setup = current
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous !is GuruHandler) {
            // On the crashing thread: no binder call, nothing beyond building and writing the report.
            Thread.setDefaultUncaughtExceptionHandler(
                GuruHandler(previous, capture = { thread, error ->
                    setup?.let { it.store.write(it.process.crash(newId(), thread.name, error, System.currentTimeMillis())) }
                }),
            )
        }
        io(guarded("reading exit records") { collectExits(current) })
        io(guarded("saving reports") { save(current) })
    }

    /**
     * Shows the alert for what this start saved, if anything — once it has been seen: a second
     * call shows nothing, but an alert the activity lost before it was answered (destroyed before
     * it came, or recreated while it was up) comes back with the next call. Queued on the IO
     * thread behind [install]'s steps; an [activity] already finishing or destroyed by then
     * takes nothing, and the next call gets the alert. [onUi] brings the result to the main
     * thread the host's way — and must run every block it is given: Guru checks the activity
     * itself and keeps the alert for the next call, a block dropped would lose it.
     *
     * Left out when the newest report is a crash on the main thread inside Guru itself — the
     * alert's own crash: showing it again would crash again, at every start. Left out, too, when
     * the last process died with its alert up (a native crash, an ANR) and reports came since. The
     * files are saved.
     */
    fun showPending(activity: Activity, onUi: (() -> Unit) -> Unit) {
        val s = setup ?: return
        s.io(guarded("loading saved reports") {
            // A recreated activity's old instance would take the list from the new one.
            if (activity.isFinishing || activity.isDestroyed) return@guarded
            val saved = s.saved
            s.saved = emptyList()
            when {
                saved.isEmpty() -> Unit
                GuruText.diedInGuru(saved.first().report) -> log("alert left out: the last crash was Guru's own", null)
                else -> {
                    // On disk before the alert can be seen; whatever kills the process from here on
                    // leaves it for the next start (save).
                    val token = newId()
                    s.store.markAlertUp(token)
                    onUi { present(activity, s, saved, token) }
                }
            }
        })
    }

    // Nothing the host's texts or the view throw here reaches the host's main: logged, and the
    // alert is left out — the files are saved. What throws later, in layout or drawing, crashes
    // like anything on main, and the next start leaves the alert out (diedInGuru).
    @Suppress("TooGenericExceptionCaught")
    private fun present(activity: Activity, s: Setup, saved: List<Saved>, token: String) {
        // The alert is gone: its mark goes, unless a newer alert's took its place.
        val release = { s.io(guarded("clearing the alert mark") { s.store.clearAlertUp(token) }) }
        if (activity.isFinishing || activity.isDestroyed) {
            keep(s, saved)
            release()
            return
        }
        try {
            GuruScreen.show(
                activity,
                GuruText.screenLines(saved.first().report.number, saved.size, s.texts),
                s.texts,
                onShare = { offer(activity, saved) },
                onUnanswered = { keep(s, saved) },
                onGone = release,
            )
        } catch (e: RuntimeException) {
            log("alert not shown", e)
            release()
        }
    }

    /** An alert not seen to its end waits for the next [showPending]; in memory only, it cannot loop. */
    private fun keep(s: Setup, saved: List<Saved>) = s.io(guarded("keeping the alert") { s.saved = saved + s.saved })

    // Each report saved is the user's file from then on, and Guru's copy goes at once, before the
    // next is saved: a death in between repeats at most the one just saved (a second file, the
    // same REPORT_ID). One that fails stays for the next start.
    private fun save(s: Setup) {
        // Taken at every start, reports or not: a mark left is the last process's.
        val alertDied = s.store.takeAlertUp()
        val reports = s.store.pending()
        if (reports.isEmpty()) return
        val installation = s.store.installationId(::newId)
        val zone = ZoneId.systemDefault()
        val saved = reports.mapNotNull { report ->
            val base = GuruDownloads.baseName(s.label, report, zone)
            s.downloads.save(base, AcraJson.serialize(report, installation, zone))?.let { file ->
                // Its time first: an exit record of this crash that shows up only at a later
                // start must still find the crash the handler caught (ExitSelection).
                if (report.kind == GuruKind.CRASH && !s.store.markCrashSaved(report.crashedAt)) {
                    log("crash time not kept: its exit record may come back as a second report", null)
                }
                s.store.remove(listOf(report.id))
                Saved(report, file)
            }
        }
        s.downloads.prune()
        // Its alert up, the last process died — a native crash or an ANR the alert may have caused,
        // which diedInGuru cannot see. Shown again, it could do so at every start.
        if (alertDied && saved.isNotEmpty()) {
            log("alert left out: the last process died with its alert up", null)
        } else {
            s.saved = saved + s.saved
        }
    }

    // A chooser that cannot start (no app, or the system refuses) costs nothing: the files stay.
    @Suppress("TooGenericExceptionCaught")
    private fun offer(activity: Activity, saved: List<Saved>) {
        val files = saved.map { it.file }
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
        }
        // The ClipData set here, not left to the start's migration: createChooser hands it and the
        // read grant on to the chooser too, so the chooser can preview the files, not only the
        // app picked can read them.
        val clip = ClipData.newRawUri(null, files.first())
        files.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.setType(GuruDownloads.MIME)
            .putExtra(Intent.EXTRA_SUBJECT, GuruText.subject(saved.first().report))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .clipData = clip
        // A task of its own: from a singleTask HOME the chooser and the app picked would otherwise
        // land in the launcher's task — a HOME press would clear a half-written mail.
        val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(chooser)
        } catch (e: RuntimeException) {
            log("chooser not started", e)
        }
    }

    // Reports are written before the mark moves: a death in between repeats an exit, never loses one.
    // Only an ANR's dump is text; a native crash's is a protobuf tombstone and stays out.
    private fun collectExits(s: Setup) {
        val am = s.context.getSystemService(ActivityManager::class.java) ?: return
        // 0 is every record the system keeps: a fixed number would lose a crash behind as many
        // other ends since the mark; the mark keeps the old ones out.
        val history = am.getHistoricalProcessExitReasons(null, 0, 0)
        // No pid (0) is no process: such an exit is never taken for a second end.
        val records = history.mapNotNull { info ->
            kindOf(info.reason)?.let { kind ->
                ExitRecord(kind, info.timestamp, info.description.orEmpty(), info.pid.takeIf { it > 0 }) {
                    if (kind == GuruKind.ANR) dump(info) else null
                }
            }
        }
        val seen = s.store.exitsSeen()
        val handlerTimes = s.store.pending().filter { it.kind == GuruKind.CRASH }.map { it.crashedAt } + s.store.crashesSaved()
        val ends = history.map { ProcessEnd(it.pid, it.timestamp) }
        val outcome = ExitSelection.select(records, seen, System.currentTimeMillis(), handlerTimes, ends)
        outcome.report.forEach { s.store.write(s.process.exit(newId(), it)) }
        if (outcome.seen != seen) s.store.markExitsSeen(outcome.seen)
    }

    // An unreadable dump still makes a report, without it; thrown, it would stop the loop before
    // the mark moves, and the same exits would come back on every start.
    @Suppress("TooGenericExceptionCaught")
    private fun dump(info: ApplicationExitInfo): String? = try {
        info.traceInputStream?.use { GuruText.readCapped(it, GuruText.MAX_DUMP_BYTES) }
    } catch (e: Exception) {
        log("ANR dump unreadable", e)
        null
    }

    private fun kindOf(reason: Int): GuruKind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> GuruKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> GuruKind.NATIVE
        ApplicationExitInfo.REASON_ANR -> GuruKind.ANR
        else -> null
    }

    // Read once at install, like the environment; no label, or none to be had, the package name.
    @Suppress("TooGenericExceptionCaught")
    private fun label(app: Application): String = try {
        app.applicationInfo.loadLabel(app.packageManager).toString().ifBlank { app.packageName }
    } catch (e: Exception) {
        log("label unknown", e)
        app.packageName
    }

    // Read once at install, so the handler never asks the PackageManager while dying.
    // an unknown version still makes a report
    @Suppress("TooGenericExceptionCaught")
    private fun environment(app: Application): GuruEnv {
        val (name, code) = try {
            val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            info.versionName.orEmpty() to info.longVersionCode
        } catch (e: Exception) {
            log("version unknown", e)
            "" to 0L
        }
        return GuruEnv(
            packageName = app.packageName,
            versionName = name,
            versionCode = code,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
        )
    }
}

private fun newId() = UUID.randomUUID().toString()

private fun log(message: String, e: Throwable?) {
    Log.w("Guru", message, e)
}

// nothing a Guru task throws may reach the host's IO thread
@Suppress("TooGenericExceptionCaught")
private fun guarded(what: String, task: () -> Unit) = Runnable {
    try {
        task()
    } catch (e: Exception) {
        log("$what failed", e)
    }
}

/** So a rename survives a power cut. Best effort: the report file itself is already synced. */
private fun fsyncDir(dir: File) {
    var fd: FileDescriptor? = null
    try {
        fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        Os.fsync(fd)
    } catch (e: ErrnoException) {
        log("fsync of ${dir.name} failed", e)
    } finally {
        if (fd != null) {
            @Suppress("SwallowedException") try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                // Nothing actionable at close time; the rename already happened.
            }
        }
    }
}
