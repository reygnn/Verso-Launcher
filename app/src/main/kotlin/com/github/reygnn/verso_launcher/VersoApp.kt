package com.github.reygnn.verso_launcher

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.reygnn.guru.Guru
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What must outlive a recreated MainActivity: the one IO thread (two instances must never
 * write verso.json concurrently), the command gate, the [ProcessState] and the label/palette caches.
 */
class VersoApp : Application() {

    /** All disk and PackageManager work, in submission order. Never shut down (hard rule 3). */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Badged labels ("Gmail (Work)"), a binder call each. [io] only. */
    internal val badgedLabels = HashMap<BadgeKey, String>()

    /** Last applied wallpaper palette, so a recreated activity starts in the right colours. Main thread only. */
    internal var palette: Palette? = null

    /** What a recreated LauncherState takes over: the recents, the launches, the two flags, the messages. */
    internal val process = ProcessState()

    /**
     * The "~" commands, one at a time across activity instances (hard rule 15): a recreation
     * finds a run its predecessor left going still going, and is refused until it ends. Main
     * thread only. A run's end posts a look at a pick waiting for it ([pick]).
     */
    internal val gate = CommandGate(onIdle = { onMain { instance?.adoptPendingPick() } })

    /** The "~restore-saf" pick from the picker's answer until its adoption starts. Main thread only. */
    internal val pick = PendingPick()

    /**
     * The live MainActivity, between its onCreate and its onDestroy, for what the process hands
     * over: a pick to adopt. Main thread only; never held past onDestroy, so it keeps no instance alive.
     */
    internal var instance: MainActivity? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** On the main thread for the process, never dropped — unlike MainActivity.onUi, which is per instance. */
    internal fun onMain(block: () -> Unit) {
        mainHandler.post { block() }
    }

    /**
     * Main thread: the read of pick [number] is back. A dropped or superseded pick is ignored. An
     * unreadable file is a result of the user's pick — queued, shown once an instance is in front;
     * a config waits in [pick] for an idle gate and a loaded config (MainActivity.adoptPendingPick).
     */
    internal fun pickRead(number: Int, restored: VersoConfig?) {
        if (!pick.arrived(number, restored)) return
        if (restored == null) process.messages.add(getString(R.string.toast_restore_failed))
        instance?.pickArrived()
    }

    internal data class BadgeKey(val serial: Long, val label: String, val locale: String)

    // Guru (guru/): writes a crash report while the process dies; at the next start it saves
    // the report to Download/Guru (MediaStore, no permission, without asking) and the alert
    // says so (MainActivity.onCreate) — a hold shares the files with an app the user picks.
    // Its disk work runs on [io] like everything else (hard rule 3), each task behind Guard
    // (hard rule 2), queued ahead of Verso's first load.
    override fun onCreate() {
        super.onCreate()
        Guru.install(
            this,
            io = { task -> io.execute(Guard.wrap("Guru", { msg, e -> Log.w("Verso", msg, e) }, task)) },
            texts = VersoGuruTexts(this),
        )
    }

    companion object {
        fun from(activity: android.app.Activity) = activity.application as VersoApp
    }
}
