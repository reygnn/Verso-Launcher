package com.github.reygnn.verso_launcher

/**
 * What the process (Core, Commands) needs of the window — MainActivity, the live one, as
 * VersoApp.instance. Through this alone, so no process code holds or calls an Activity: a result
 * is handed to whichever window is there, or waits for the next.
 */
internal interface LauncherWindow {

    /** Between onStart and onStop: it may show something without covering another app. */
    val inFront: Boolean

    /** The state's hand-back after a refresh or a new config ([from]: the state it came from). */
    fun refreshed(from: LauncherState, listChanged: Boolean)

    /** A toast; only called while [inFront]. */
    fun show(text: String, long: Boolean)
}
