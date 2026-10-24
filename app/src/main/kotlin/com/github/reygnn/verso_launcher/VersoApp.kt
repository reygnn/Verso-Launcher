package com.github.reygnn.verso_launcher

import android.app.Application
import android.util.Log
import com.github.reygnn.guru.Guru
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What must outlive a recreated MainActivity: the one IO thread (two instances must never
 * write verso.json concurrently), the [ProcessState] and the label/palette caches.
 */
class VersoApp : Application() {

    /** All disk and PackageManager work, in submission order. Never shut down (hard rule 3). */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Badged labels ("Gmail (Work)"), a binder call each. [io] only. */
    internal val badgedLabels = HashMap<BadgeKey, String>()

    /** Last applied wallpaper palette, so a recreated activity starts in the right colours. Main thread only. */
    internal var palette: Palette? = null

    /** What a recreated LauncherState takes over: the recents, the launches, the two flags. */
    internal val process = ProcessState()

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
