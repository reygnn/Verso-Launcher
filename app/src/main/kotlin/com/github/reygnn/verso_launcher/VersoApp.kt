package com.github.reygnn.verso_launcher

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
     * start). Not caught: [io] cannot refuse a task.
     *
     * Why: execute throws only once the executor is shut down, and nothing shuts it down (hard
     *   rule 3). The finalizer of newSingleThreadExecutor's wrapper would (Android's libcore, seen
     *   in Android 14), but this Application holds [io] for the life of the process. The queue is
     *   unbounded, so a long one refuses nothing either.
     */
    internal fun submitIo(task: Runnable) {
        io.execute(Guard.wrap("IO task", ::logWarning, task))
    }

    internal data class BadgeKey(val serial: Long, val label: String, val locale: String)

    companion object {
        fun from(activity: android.app.Activity) = activity.application as VersoApp
    }
}
