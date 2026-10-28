package com.github.reygnn.verso_launcher

/**
 * What the process (Core) needs of the window — MainActivity, the live one, as VersoApp.instance.
 * Through this alone, so no process code holds or calls an Activity (hard rule 16): a refresh is
 * handed to whichever window is there, and a window made later renders the state anew.
 */
internal interface LauncherWindow {

    /** The state's hand-back after a refresh ([from]: the state it came from). */
    fun refreshed(from: LauncherState, listChanged: Boolean)
}
