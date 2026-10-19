package com.github.reygnn.verso_launcher

import android.app.Application
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What must outlive a recreated MainActivity: the one IO thread (two instances must never
 * write verso.json concurrently), the recents and the label/palette caches.
 */
class VersoApp : Application() {

    /** All disk and PackageManager work, in submission order. Never shut down (hard rule 3). */
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Badged labels ("Gmail (Work)"), a binder call each. [io] only. */
    internal val badgedLabels = HashMap<BadgeKey, String>()

    /** Last applied wallpaper palette, so a recreated activity starts in the right colours. Main thread only. */
    internal var palette: Palette? = null

    /** The "?" list, newest first, and whether recents.txt was read into it yet. Main thread only. */
    internal var recentKeys: List<String> = emptyList()
    internal var recentsLoaded = false

    /** The "%" launches, and whether usage.txt was read into them yet. Main thread only. */
    internal var usage: Map<String, Usage> = emptyMap()
    internal var usageLoaded = false

    /** Set on [io] when an unreadable config was set aside; taken by whichever instance's
     *  hand-off lands first, so the toast is never lost. */
    internal val startedOver = java.util.concurrent.atomic.AtomicBoolean()

    internal data class BadgeKey(val serial: Long, val label: String, val locale: String)

    companion object {
        fun from(activity: android.app.Activity) = activity.application as VersoApp
    }
}
