package com.github.reygnn.guru

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.service.chooser.ChooserResult
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileDescriptor
import java.time.ZoneId
import java.util.UUID

/**
 * The module's front door. [install] once from Application.onCreate; [showPending] from the
 * first activity. Guru makes no thread of its own: all disk and network work goes through the
 * host's `io`, in submission order, and every task is wrapped so that nothing it throws reaches
 * the host's thread.
 */
internal object Guru {

    private const val DIR = "guru"
    private const val EXIT_LOOKBACK = 16
    private const val EXTRA_IDS = "guru.ids"

    private class Setup(
        // The Application, never an Activity: Setup lives in a static field for the process.
        val context: Application,
        val store: GuruStore,
        val process: GuruProcess,
        val sink: GuruSink,
        val texts: GuruTexts,
        val io: (Runnable) -> Unit,
    ) {
        /** Only this app can send it (the receiver is not exported): "a target was chosen". */
        val sharedAction = context.packageName + ".guru.SHARED"

        fun toast(text: String) = context.mainExecutor.execute { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    // The chooser's answer: the ids it carries are the ones handed over. Since Android 15 it also
    // answers for its own actions (Copy, Edit), with what happened in EXTRA_CHOOSER_RESULT. Only
    // an app picked counts as handed over; Copy, Edit, unknown or no result keep the reports
    // (hard rule 7: a copy may never be pasted anywhere). A cancelled chooser never answers.
    private val shared = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = setup ?: return
            val result = intent.getParcelableExtra(Intent.EXTRA_CHOOSER_RESULT, ChooserResult::class.java)
            if (result?.type != ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT) return
            val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toList() ?: return
            s.io(guarded("removing shared reports") { s.store.remove(ids) })
        }
    }

    /** The reports on screen, their ACRA JSON already built on the IO thread. */
    private class Pending(val reports: List<GuruReport>, val json: Map<String, String>)

    // Replaced by a second install (a Robolectric test gets a new Application per test); the
    // handler reads it at crash time, so it always writes to the current one.
    @Volatile private var setup: Setup? = null

    /**
     * Installs the handler and queues the look at the system's exit records on [io]. The
     * process start is taken as now. Installing twice keeps one handler. [texts]: the host's
     * words, from its resources; English when it has none.
     */
    fun install(app: Application, sink: GuruSink, io: (Runnable) -> Unit, texts: GuruTexts = GuruTexts.English) {
        val store = GuruStore(File(app.filesDir, DIR), ::log, syncDir = ::fsyncDir)
        val current = Setup(app, store, GuruProcess(environment(app), System.currentTimeMillis()), sink, texts, io)
        setup = current
        if (sink is GuruSink.Share) {
            app.registerReceiver(shared, IntentFilter(current.sharedAction), Context.RECEIVER_NOT_EXPORTED)
        }
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
    }

    /**
     * Shows the alert for the pending reports, if any. Queued on the IO thread behind the exit
     * look, so an ANR from the last run is among them. [onUi] brings the result to the main
     * thread the host's way (Verso's skips a destroyed activity).
     */
    fun showPending(activity: Activity, onUi: (() -> Unit) -> Unit) {
        val s = setup ?: return
        s.io(guarded("loading reports") {
            val reports = s.store.pending()
            if (reports.isNotEmpty()) {
                val installation = s.store.installationId(::newId)
                val zone = ZoneId.systemDefault()
                val pending = Pending(reports, reports.associate { it.id to AcraJson.serialize(it, installation, zone) })
                onUi { present(activity, s, pending) }
            }
        })
    }

    private fun present(activity: Activity, s: Setup, pending: Pending) {
        if (activity.isFinishing || activity.isDestroyed) return
        val discard = guarded("removing reports") { s.store.remove(pending.json.keys) }
        GuruScreen.show(
            activity,
            GuruText.screenLines(pending.reports.first().number, pending.reports.size, s.texts),
            s.texts,
            onContinue = { s.io(discard) },
            onSend = { send(activity, s, pending) },
        )
    }

    private fun send(activity: Activity, s: Setup, pending: Pending) {
        when (val sink = s.sink) {
            is GuruSink.Share -> share(activity, s, sink, pending)
            is GuruSink.Acra -> s.io(guarded("sending reports") {
                val sent = pending.reports.map { it.id }.filter { id ->
                    AcraPost.delivered(AcraPost.post(sink.url, sink.login, sink.password, pending.json.getValue(id), ::log))
                }
                s.store.remove(sent)
                s.toast(s.texts.sent(sent.size, pending.reports.size))
            })
        }
    }

    // The ids ride in the chooser's callback, so nothing waits in memory: whenever an app is
    // picked, [shared] removes exactly these. Mutable, or the system's fill-in — the ChooserResult
    // that tells an app picked from Copy — is dropped. The price: a fill-in could overwrite extras,
    // the ids too. Only the system chooser holds this sender, the intent stays explicit (package
    // set, as Android 14+ requires of a mutable one), and remove touches report files only.
    // A newer share cancels an older callback still waiting: its reports stay, the safe side.
    private fun share(activity: Activity, s: Setup, sink: GuruSink.Share, pending: Pending) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, GuruText.subject(pending.reports.first()))
            .putExtra(Intent.EXTRA_TEXT, GuruText.shareBody(pending.reports) { pending.json.getValue(it.id) })
        sink.mailTo?.let { intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        val answer = Intent(s.sharedAction)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_IDS, pending.json.keys.toTypedArray())
        val callback = PendingIntent.getBroadcast(
            activity,
            0,
            answer,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        try {
            activity.startActivity(Intent.createChooser(intent, null, callback.intentSender))
        } catch (e: ActivityNotFoundException) {
            log("no app to share with", e)
            callback.cancel()
            s.toast(s.texts.sent(0, pending.reports.size))
        }
    }

    // Reports are written before the mark moves: a death in between repeats an exit, never loses one.
    // Only an ANR's dump is text; a native crash's is a protobuf tombstone and stays out.
    private fun collectExits(s: Setup) {
        val am = s.context.getSystemService(ActivityManager::class.java) ?: return
        val records = am.getHistoricalProcessExitReasons(null, 0, EXIT_LOOKBACK).mapNotNull { info ->
            kindOf(info.reason)?.let { kind ->
                ExitRecord(kind, info.timestamp, info.description.orEmpty()) {
                    if (kind != GuruKind.ANR) null
                    else info.traceInputStream?.use { GuruText.readCapped(it, GuruText.MAX_DUMP_BYTES) }
                }
            }
        }
        val seen = s.store.exitsSeen()
        val handlerTimes = s.store.pending().filter { it.kind == GuruKind.CRASH }.map { it.crashedAt }
        val outcome = ExitSelection.select(records, seen, System.currentTimeMillis(), handlerTimes)
        outcome.report.forEach { s.store.write(s.process.exit(newId(), it)) }
        if (outcome.seen != seen) s.store.markExitsSeen(outcome.seen)
    }

    private fun kindOf(reason: Int): GuruKind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> GuruKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> GuruKind.NATIVE
        ApplicationExitInfo.REASON_ANR -> GuruKind.ANR
        else -> null
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
