package com.github.reygnn.verso_launcher

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Makes this view a double-tap target ([DoubleTapGate]) that a screen reader names by [label].
 *
 * Why: TalkBack's "double-tap to activate" arrives as ONE click, which the gate would swallow, so
 *   an accessibility click runs [onActivate] directly.
 */
internal fun View.onDoubleTap(label: String, onActivate: () -> Unit) {
    val gate = DoubleTapGate()
    setOnClickListener { if (gate.tap(SystemClock.uptimeMillis())) onActivate() }
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            // Same id as the plain click: this relabels it.
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label))
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                onActivate()
                return true
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }
}

/** Names what a double-tap does ("restore"); the click itself stays the view's own. */
internal fun labeledClick(label: String): View.AccessibilityDelegate = object : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label))
    }
}

/** The front page's swipes as actions in TalkBack's menu: up to all apps, down to the recent ones. */
internal class PageActions(
    private val context: Context,
    private val onOpenDrawer: () -> Unit,
    private val onOpenRecents: () -> Unit,
) : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_drawer, context.getString(R.string.a11y_open_drawer))
        )
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_recents, context.getString(R.string.a11y_open_recents))
        )
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
        R.id.action_open_drawer -> true.also { onOpenDrawer() }
        R.id.action_open_recents -> true.also { onOpenRecents() }
        else -> super.performAccessibilityAction(host, action, args)
    }
}

/** A dimmed app's state as a screen reader says it beside the name ([AppStatus]); null when ready. */
internal fun statusState(context: Context, status: AppStatus): String? = when (status) {
    AppStatus.READY -> null
    AppStatus.PAUSED -> context.getString(R.string.a11y_state_paused)
    AppStatus.ARCHIVED -> context.getString(R.string.a11y_state_archived)
}

/** What a double-tap on a dimmed app does; null when it just opens it. */
internal fun statusAction(context: Context, status: AppStatus): String? = when (status) {
    AppStatus.READY -> null
    AppStatus.PAUSED -> context.getString(R.string.a11y_do_turn_on_paused)
    AppStatus.ARCHIVED -> context.getString(R.string.a11y_do_unarchive)
}
