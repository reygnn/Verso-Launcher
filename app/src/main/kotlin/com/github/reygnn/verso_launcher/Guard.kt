package com.github.reygnn.verso_launcher

/** Hard rule 2's backstop: catches every Exception, checked ones too, and lets an Error crash. */
internal object Guard {
    fun wrap(what: String, log: (String, Throwable?) -> Unit, task: Runnable) = Runnable {
        try {
            task.run()
        } catch (e: Exception) {
            log("$what failed", e)
        }
    }
}
