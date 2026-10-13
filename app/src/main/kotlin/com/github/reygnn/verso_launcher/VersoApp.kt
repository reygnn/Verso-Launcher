package com.github.reygnn.verso_launcher

import android.app.Application
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What must outlive a single MainActivity instance: the one disk thread and the recents.
 *
 * A configuration change the manifest does not handle (locale, font scale, a keyboard
 * attached, a display-size change) recreates the activity. With the executor per instance,
 * the old instance's thread kept draining its queued saves while the new one already read
 * verso.json on its own thread — two writers on one temp file, and a read that missed the
 * save still in flight. The recents lived in the instance too and were simply gone. Both
 * belong to the process, so they live here; the activity reaches them through [from].
 */
class VersoApp : Application() {

    /**
     * The single background thread that owns ALL disk I/O — config loads and saves, the app
     * enumeration, backups and restores — serialized against each other in submission order,
     * across activity instances. Never shut down: it lives exactly as long as the process.
     */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** "Gmail" -> "Gmail (Work)" per (profile serial, label): the system's badge for an app of
     *  another profile, which costs a binder call each. Read and written on [io] only. */
    internal val badgedLabels = HashMap<Pair<Long, String>, String>()

    /** The "?" list, newest first. In memory only, so it lasts until the process dies. */
    internal var recentKeys: List<String> = emptyList()

    companion object {
        fun from(activity: android.app.Activity) = activity.application as VersoApp
    }
}
