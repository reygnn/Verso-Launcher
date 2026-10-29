package com.github.reygnn.verso_launcher

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * What must outlive a recreated MainActivity: the one IO thread (two instances must never
 * write the same file concurrently), the launcher's data ([core]), the [ProcessState] and the
 * label/palette caches.
 */
class VersoApp : Application() {

    /** Verso's disk and PackageManager work, in submission order. Never shut down (hard rule 3). */
    val io: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "verso-io") }

    /** Badged labels ("Gmail (Work)"), a binder call each. [io] only. */
    internal val badgedLabels = HashMap<BadgeKey, String>()

    /** Last applied wallpaper palette, so a recreated activity starts in the right colours. Main thread only. */
    internal var palette: Palette? = null

    /** What a recreated LauncherState takes over: the recents and the launches. */
    internal val process = ProcessState()

    /**
     * The live window (MainActivity), between its onCreate and its onDestroy, for what the process
     * hands over: the state's refreshes (Core). Seen only as a [LauncherWindow]. Main thread only;
     * never held past onDestroy, so it keeps no instance alive.
     */
    internal var instance: LauncherWindow? = null

    /**
     * The launcher's data — one LauncherState and its stores — shared by every activity instance;
     * "~restart" resets it. Built on first use (the first activity), from this Application alone.
     */
    internal val core by lazy { Core(this) }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** On the main thread for the process, never dropped — unlike MainActivity.onUi, which is per instance. */
    internal fun onMain(block: () -> Unit) {
        mainHandler.post { block() }
    }

    /**
     * Queues [task] on [io] behind Guard (hard rule 2: an escape there kills the process on every
     * start). A refused task is reported as false, never thrown — the executor is never shut down,
     * but a HOME app must not crash.
     */
    internal fun submitIo(task: Runnable): Boolean =
        try {
            io.execute(Guard.wrap("IO task", ::logWarning, task))
            true
        } catch (e: RejectedExecutionException) {
            Log.w("Verso", "IO task rejected (executor shut down)", e)
            false
        }

    internal data class BadgeKey(val serial: Long, val label: String, val locale: String)

    companion object {
        fun from(activity: android.app.Activity) = activity.application as VersoApp
    }
}
