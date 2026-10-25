package com.github.reygnn.verso_launcher

import android.util.Log

/** The one warning logger, tag "Verso": top-level, so a reference to it holds no activity. */
internal fun logWarning(msg: String, e: Throwable?) {
    if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg)
}

/** Hard rule 2's backstop: catches every Exception, checked ones too, and lets an Error crash. */
internal object Guard {
    // hard rule 2: the backstop itself
    @Suppress("TooGenericExceptionCaught")
    fun wrap(what: String, log: (String, Throwable?) -> Unit, task: Runnable) = Runnable {
        try {
            task.run()
        } catch (e: Exception) {
            log("$what failed", e)
        }
    }
}
