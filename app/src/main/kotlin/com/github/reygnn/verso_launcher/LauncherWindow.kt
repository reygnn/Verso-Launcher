package com.github.reygnn.verso_launcher

import android.os.UserHandle

/**
 * What the process (Core) needs of the window — MainActivity, the live one, as VersoApp.instance.
 * Through this alone, so no process code holds or calls an Activity (hard rule 16): a refresh is
 * handed to whichever window is there, and a window made later renders the state anew. In turn the
 * window tells the process when it is made, in front and behind ([Core.windowCreated] and its kin).
 */
internal interface LauncherWindow {

    /** The state's hand-back after a refresh ([from]: the state it came from). */
    fun refreshed(from: LauncherState, listChanged: Boolean)

    /** The profile of [user] was turned back on: a tap in this window may be waiting for it. */
    fun profileAvailable(user: UserHandle?)
}
