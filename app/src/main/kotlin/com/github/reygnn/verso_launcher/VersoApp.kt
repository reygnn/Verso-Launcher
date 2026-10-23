package com.github.reygnn.verso_launcher

import android.app.Application
import android.util.Log
import com.github.reygnn.guru.Guru
import com.github.reygnn.guru.GuruSink
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

    // Guru (guru/): writes a crash report while the process dies and shows it on the next
    // start (MainActivity.onCreate). Share: the report goes to an app the user picks — no
    // permission, no server. Its disk work runs on [io] like everything else (hard rule 3),
    // each task behind Guard (hard rule 2).
    override fun onCreate() {
        super.onCreate()
        Guru.install(
            this,
            GuruSink.Share(),
            io = { task -> io.execute(Guard.wrap("Guru", { msg, e -> Log.w("Verso", msg, e) }, task)) },
            texts = VersoGuruTexts(this),
        )
    }

    companion object {
        fun from(activity: android.app.Activity) = activity.application as VersoApp
    }
}
