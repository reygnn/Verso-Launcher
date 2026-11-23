package com.github.reygnn.verso_launcher

import android.app.Activity

/**
 * What an Enter or a tap in the drawer does, performed for one window: a launch, "%", a "~"
 * command, a prompt set. What it is was decided already ([DrawerView.enter], [DrawerView.tap]);
 * this is its side effect.
 *
 * Thread: main.
 *
 * @param state the state this window was made with ([replaced]).
 * @param launch starts an app, or says why not ([AppLauncher.launch]).
 * @param say a short toast.
 */
internal class DrawerActions(
    private val activity: Activity,
    private val core: Core,
    private val state: LauncherState,
    private val drawer: AppDrawer,
    private val launch: (AppEntry) -> Unit,
    private val say: (String) -> Unit,
) {
    // After "~restart", until the recreation replaces this window: the process's state is already
    // the fresh one, this window's is not, so it takes no more commands or "%". Asked, not kept: a
    // flag would have to be set wherever a state is replaced.
    private val replaced: Boolean get() = state !== core.state

    /** A tap on a row or an Enter on the prompt. */
    fun perform(action: DrawerAction) {
        when (action) {
            is DrawerAction.Launch -> launch(action.entry)
            is DrawerAction.Run -> runCommand(action.command)
            is DrawerAction.SetPrompt -> drawer.setPrompt(action.text)
            DrawerAction.ToggleOrder -> toggleOrder()
            DrawerAction.DismissKeyboard -> drawer.hideKeyboard()
            DrawerAction.None -> {}
        }
    }

    /** Run a "~" command. */
    private fun runCommand(command: Command) {
        // A deliberate crash, for the crash-only paths, at any time. The constant compiles
        // it out of a public release, where nothing parses to CRASH anyway.
        if (command == Command.CRASH) {
            if (DailyDriver.ENABLED) error("~crash: deliberate crash (daily-driver build)")
            return
        }
        // After "~restart" this window holds the old state until the recreation lands: it takes
        // nothing more. Nothing to say — the new window comes at once.
        if (replaced) return
        // Cleared BEFORE running: clearing fires the TextWatcher synchronously, so the mode is
        // back to NORMAL before the command runs.
        drawer.setPrompt("")
        when (command) {
            Command.RESTART -> restart()
            Command.VERIFY -> showReport()
            Command.CRASH -> Unit // returned above
        }
    }

    /** "~verify": its prompt is its view ([Commands.VERIFY]), read with the keyboard down. */
    private fun showReport() {
        drawer.setPrompt(Commands.VERIFY)
        drawer.hideKeyboard()
    }

    /** "~restart": the reload of the config file, and the repair by hand. */
    private fun restart() {
        // A fresh state (Core.reset) reads the config file again and loads the app list anew, and
        // the recreation builds every view anew around it. Until that recreation lands this window
        // still holds the old state, so it takes nothing more (replaced).
        core.reset()
        activity.recreate()
    }

    /** "%": kept at once (order.txt); the prompt clears onto the drawer in its new order. */
    private fun toggleOrder() {
        if (replaced) return
        val byUsage = state.toggleOrder()
        say(activity.getString(if (byUsage) R.string.toast_order_usage else R.string.toast_order_name))
        drawer.setPrompt("")
    }
}
