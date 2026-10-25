package com.github.reygnn.verso_launcher

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.window.BackEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The page turn on real views (hard rule 11). Gestures enter where [SwipeFrame] hands them on —
 * its claims/canDrag questions and the drag callbacks — and Back through [PageTurn.backCallback],
 * as the system calls it; the frame's own touch decoding is SwipeFrameTest's. The status bar and
 * the IME (hard rule 12, [PageTurn.resumed]) are not modelled by Robolectric: checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
class PageTurnTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private val alpha = AppEntry("Alpha", "com.alpha", "com.alpha.Main", "Alpha")
    private val bravo = AppEntry("Bravo", "com.bravo", "com.bravo.Main", "Bravo")

    private val state = testLauncherState(
        io = { task -> task.run(); true },
        main = { task -> task.run() },
        loadConfig = { VersoConfig() },
        enumerateApps = { Enumeration(listOf(alpha, bravo), true) },
        serialize = { "" },
        writeConfig = { true },
        onRefreshed = { _, _ -> },
        // The drawer sorts by usage against the wall clock; a launch counted at 0 has decayed away.
        now = System::currentTimeMillis,
    ).also { it.refresh() }

    private var dialogOpen = false
    private var byUsage = false
    private var opened = 0
    private var closed = 0

    private lateinit var pageTurn: PageTurn

    // Wired as MainActivity wires it: a mode change reaches the page turn.
    private val drawer = AppDrawer(
        activity = activity,
        state = state,
        palette = HomeStyle.palette(lightWallpaper = false),
        onAction = {},
        onLongPress = {},
        onFiltered = {},
        onModeChanged = { pageTurn.modeChanged(it) },
    )

    private val home = HomePage(activity, onLaunch = {}, onEdit = {}, onOpenDrawer = { pageTurn.open() }, onOpenRecents = { pageTurn.openRecents() })

    init {
        pageTurn = PageTurn(
            activity = activity,
            homePage = home,
            appDrawer = drawer,
            byUsage = { byUsage },
            dialogOpen = { dialogOpen },
            onOpen = { opened++ },
            onClose = { closed++ },
        )
        activity.setContentView(pageTurn.root)
        // Laid out once, so every height below is the frame's, not the display's fallback.
        settle()
    }

    /** Runs the turn and settle animations (≤ 220 ms) and anything they posted. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private val root: SwipeFrame get() = pageTurn.root
    private val height: Float get() = root.host.pageHeight()
    private val prompt: String get() = drawer.promptText

    private fun back(progress: Float) = BackEvent(0f, 0f, progress, BackEvent.EDGE_LEFT)

    private fun openSettled(prompt: String = "", fromTop: Boolean = false) {
        pageTurn.open(prompt, fromTop)
        settle()
    }

    // ---- open and close ---------------------------------------------------------------

    @Test fun `open turns to the drawer and says so once`() {
        openSettled()
        assertTrue(pageTurn.isOpen)
        assertEquals(View.VISIBLE, drawer.view.visibility)
        assertEquals(0f, drawer.view.translationY, 0.5f)
        assertEquals(View.INVISIBLE, home.view.visibility)
        pageTurn.open()
        assertEquals(1, opened)
    }

    /**
     * Each page carries a pane title for screen readers that announce one as it turns up: home, all
     * apps from below, the recents from the top — the same turn as the swipe down. (Samsung's
     * TalkBack speaks the focused element instead; AppDrawerTest pins what that says.)
     */
    @Test fun `each page carries the pane title of what turned up`() {
        assertEquals(activity.getString(R.string.a11y_home), home.view.accessibilityPaneTitle)
        openSettled()
        assertEquals(activity.getString(R.string.a11y_pane_drawer), drawer.view.accessibilityPaneTitle)
        pageTurn.close()
        settle()
        pageTurn.openRecents()
        settle()
        assertEquals(activity.getString(R.string.a11y_pane_recents), drawer.view.accessibilityPaneTitle)
        assertEquals("the recents", "?", prompt)
    }

    @Test fun `close turns back home, clears the prompt and says so once`() {
        openSettled()
        drawer.setPrompt("al")
        pageTurn.close()
        settle()
        assertFalse(pageTurn.isOpen)
        assertEquals(View.GONE, drawer.view.visibility)
        assertEquals(View.VISIBLE, home.view.visibility)
        assertEquals(1f, home.view.alpha, 0.01f)
        assertEquals("", prompt)
        pageTurn.close()
        assertEquals(1, closed)
    }

    /** onStop's and onPause's close: the next frame must already be home, no animation pending. */
    @Test fun `close without animation is home at once`() {
        openSettled()
        drawer.setPrompt("al")
        pageTurn.close(animate = false)
        assertEquals(View.GONE, drawer.view.visibility)
        assertEquals(View.VISIBLE, home.view.visibility)
        assertEquals(1f, home.view.alpha, 0.01f)
        assertEquals("", prompt)
    }

    @Test fun `the plain drawer rises from below and goes back down`() {
        pageTurn.open()
        assertEquals("starts below the page", height, drawer.view.translationY, 0.5f)
        settle()
        assertEquals(SwipeDir.DOWN, root.host.closeDirection())
        pageTurn.close()
        settle()
        assertEquals(height, drawer.view.translationY, 0.5f)
    }

    @Test fun `the recents come down from the top and go back up`() {
        pageTurn.open("?", fromTop = true)
        assertEquals("?", prompt)
        assertEquals("starts above the page", -height, drawer.view.translationY, 0.5f)
        settle()
        assertEquals(SwipeDir.UP, root.host.closeDirection())
        pageTurn.close()
        settle()
        assertEquals(-height, drawer.view.translationY, 0.5f)
    }

    @Test fun `leaving the recents makes it the plain drawer, which goes back down`() {
        openSettled("?", fromTop = true)
        drawer.setPrompt("a")
        assertEquals(SwipeDir.DOWN, root.host.closeDirection())
        pageTurn.close()
        settle()
        assertEquals(height, drawer.view.translationY, 0.5f)
    }

    /** Caught mid-close on the other side: it must not slide on from up there. */
    @Test fun `the plain drawer opened while the recents still slide up comes from below`() {
        openSettled("?", fromTop = true)
        pageTurn.close()
        // Halfway up, where the slide would be now: Robolectric runs a view animation to its end
        // in the first idle (even 10 ms), so the looper cannot stop it midway.
        drawer.view.translationY = -height / 2
        pageTurn.open()
        assertEquals(height, drawer.view.translationY, 0.5f)
        settle()
        assertEquals(SwipeDir.DOWN, root.host.closeDirection())
    }

    @Test fun `home has no way back to close`() {
        assertNull(root.host.closeDirection())
    }

    private fun rows(): List<String> {
        val list = drawer.view.collect<ListView>().single()
        return (0 until list.adapter.count).map { (list.adapter.getView(it, null, list) as TextView).text.toString() }
    }

    /** "%" most used first, then Bravo launched while the drawer is out of sight. */
    private fun bravoLaunchedOutOfSight() {
        byUsage = true
        state.toggleOrder(checkNotNull(state.editPermit(checkNotNull(CommandGate().idle()))))
        drawer.refilter()
        state.recordLaunch(bravo.key)
        assertEquals("nothing re-sorts a drawer out of sight", listOf("Alpha", "Bravo"), rows())
    }

    @Test fun `opened, a drawer sorted most used first is re-sorted before it shows`() {
        bravoLaunchedOutOfSight()
        openSettled()
        assertEquals(listOf("Bravo", "Alpha"), rows())
    }

    @Test fun `a drag up re-sorts a drawer sorted most used first as it starts`() {
        bravoLaunchedOutOfSight()
        root.host.onDragStart(SwipeDir.UP)
        assertEquals(listOf("Bravo", "Alpha"), rows())
    }

    // ---- who may start a drag or a flick ----------------------------------------------

    @Test fun `an open dialog blocks both drag and flick`() {
        dialogOpen = true
        assertFalse(root.host.canDrag(SwipeDir.UP, height / 2))
        assertFalse(root.host.claims(SwipeDir.UP, height / 2))
        dialogOpen = false
        assertTrue(root.host.canDrag(SwipeDir.UP, height / 2))
        assertTrue(root.host.claims(SwipeDir.UP, height / 2))
    }

    @Test fun `a pull down is the shade's in the top band, the recents' from its edge on`() {
        val band = SwipeGesture.TOP_EXCLUSION_DP * activity.resources.displayMetrics.density
        assertFalse(root.host.canDrag(SwipeDir.DOWN, band - 1f))
        assertTrue(root.host.canDrag(SwipeDir.DOWN, band))
        assertFalse(root.host.claims(SwipeDir.DOWN, band - 1f))
        assertTrue(root.host.claims(SwipeDir.DOWN, band))
    }

    /** The drawer takes no flick: closing it is a drag. */
    @Test fun `an open drawer claims no flick`() {
        openSettled()
        assertFalse(root.host.claims(SwipeDir.DOWN, height / 2))
    }

    /** The flick is the open fallback (hard rule 11): up opens the drawer, down the recents. */
    @Test fun `a flick on home opens the drawer upward and the recents downward`() {
        root.host.onSwipe(SwipeDir.UP)
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals("", prompt)
        pageTurn.close()
        settle()
        root.host.onSwipe(SwipeDir.DOWN)
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals("?", prompt)
    }

    // ---- the drag ----------------------------------------------------------------------

    @Test fun `a drag up from home follows the finger and commits to the drawer`() {
        assertEquals(0f, root.host.onDragStart(SwipeDir.UP), 0.5f)
        root.host.onDrag(SwipeDir.UP, height / 3)
        assertEquals(height - height / 3, drawer.view.translationY, 0.5f)
        assertTrue("home fades", home.view.alpha < 1f)
        root.host.onDragEnd(SwipeDir.UP, DragEnd.COMMIT)
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals(1, opened)
        assertEquals("", prompt)
    }

    @Test fun `a drag that does not commit falls back home and opens nothing`() {
        root.host.onDragStart(SwipeDir.UP)
        root.host.onDrag(SwipeDir.UP, height / 10)
        root.host.onDragEnd(SwipeDir.UP, DragEnd.CANCEL)
        settle()
        assertFalse(pageTurn.isOpen)
        assertEquals(View.GONE, drawer.view.visibility)
        assertEquals(1f, home.view.alpha, 0.01f)
        assertEquals(0, opened)
    }

    @Test fun `a pull down reveals the recents, and one that falls back leaves no question mark`() {
        root.host.onDragStart(SwipeDir.DOWN)
        assertEquals("?", prompt)
        assertEquals(-height, drawer.view.translationY, 0.5f)
        root.host.onDrag(SwipeDir.DOWN, height / 10)
        assertEquals(-height + height / 10, drawer.view.translationY, 0.5f)
        root.host.onDragEnd(SwipeDir.DOWN, DragEnd.CANCEL)
        settle()
        assertEquals("", prompt)
        assertEquals(View.GONE, drawer.view.visibility)
    }

    @Test fun `a pull down that commits opens the recents from the top`() {
        root.host.onDragStart(SwipeDir.DOWN)
        root.host.onDrag(SwipeDir.DOWN, height / 2)
        root.host.onDragEnd(SwipeDir.DOWN, DragEnd.COMMIT)
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals("?", prompt)
        assertEquals(SwipeDir.UP, root.host.closeDirection())
    }

    @Test fun `a drag toward its side closes the drawer`() {
        openSettled()
        assertEquals(0f, root.host.onDragStart(SwipeDir.DOWN), 0.5f)
        root.host.onDrag(SwipeDir.DOWN, height / 3)
        assertEquals(height / 3, drawer.view.translationY, 0.5f)
        root.host.onDragEnd(SwipeDir.DOWN, DragEnd.COMMIT)
        settle()
        assertFalse(pageTurn.isOpen)
        assertEquals(1, closed)
    }

    @Test fun `a closing drag that does not commit puts the drawer back`() {
        openSettled()
        root.host.onDragStart(SwipeDir.DOWN)
        root.host.onDrag(SwipeDir.DOWN, height / 10)
        root.host.onDragEnd(SwipeDir.DOWN, DragEnd.CANCEL)
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals(0f, drawer.view.translationY, 0.5f)
        assertEquals(0, closed)
    }

    /** The prompt is cleared only once the closing slide ends: a pull caught before must reset it. */
    @Test fun `a pull down caught during the closing slide shows the recents`() {
        openSettled()
        drawer.setPrompt("al")
        pageTurn.close()  // still sliding, the prompt still "al"
        root.host.onDragStart(SwipeDir.DOWN)
        assertEquals("?", prompt)
    }

    /** A finger pulling home up, as SwipeFrame sees it: the drag starts, the finger stays down. */
    private fun startSwipeUp() {
        val t = SystemClock.uptimeMillis()
        val from = height * 0.8f
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100f, from, 0))
        for (i in 1..10) {
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16, MotionEvent.ACTION_MOVE, 100f, from - i * height * 0.03f, 0))
        }
    }

    /** Opened some other way (the a11y action, a flick) while a finger holds the page. */
    @Test fun `open ends a drag still holding the page`() {
        startSwipeUp()
        assertTrue("the drag has started", root.isDragging)
        pageTurn.open()
        assertFalse(root.isDragging)
    }

    // ---- Back --------------------------------------------------------------------------

    @Test fun `Back on home does nothing`() {
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackInvoked()
        assertFalse(pageTurn.isOpen)
        assertEquals(0, closed)
        assertEquals(View.GONE, drawer.view.visibility)
    }

    @Test fun `Back with text in the prompt clears it and stays in the drawer`() {
        openSettled()
        drawer.setPrompt("al")
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackInvoked()
        assertTrue(pageTurn.isOpen)
        assertEquals("", prompt)
    }

    @Test fun `Back on the bare recents goes straight home`() {
        openSettled("?", fromTop = true)
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackInvoked()
        settle()
        assertFalse(pageTurn.isOpen)
        assertEquals(1, closed)
    }

    @Test fun `the Back preview pulls the drawer a little, and a cancel puts it back`() {
        openSettled()
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackProgressed(back(1f))
        val pulled = drawer.view.translationY
        assertTrue("pulled toward its side: $pulled", pulled > 0f)
        assertTrue("only a little: $pulled", pulled < height / 2)
        assertTrue("home shows through", home.view.alpha > 0f)
        pageTurn.backCallback.onBackCancelled()
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals(0f, drawer.view.translationY, 0.5f)
        assertEquals(0, closed)
    }

    @Test fun `a previewed Back on an empty prompt closes the drawer`() {
        openSettled()
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackProgressed(back(0.5f))
        pageTurn.backCallback.onBackInvoked()
        settle()
        assertFalse(pageTurn.isOpen)
        assertEquals(View.GONE, drawer.view.visibility)
        assertEquals(1, closed)
    }

    @Test fun `a Back that closes slides the drawer away`() {
        openSettled()
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackInvoked()
        assertFalse(pageTurn.isOpen)
        assertEquals("still sliding", View.VISIBLE, drawer.view.visibility)
        settle()
        assertEquals(View.GONE, drawer.view.visibility)
    }

    /** HOME (onStop's close) during the preview, then the gesture cancelled: home stays home. */
    @Test fun `a Back preview cancelled after the drawer was closed leaves home showing`() {
        openSettled()
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackProgressed(back(0.5f))
        pageTurn.close(animate = false)
        pageTurn.backCallback.onBackCancelled()
        settle()
        assertFalse(pageTurn.isOpen)
        assertEquals(1f, home.view.alpha, 0.01f)
        assertEquals(View.GONE, drawer.view.visibility)
    }

    /** A hardware key typed during the gesture: Back clears the prompt instead, the preview is undone. */
    @Test fun `Back whose prompt changed during the preview clears it and puts the drawer back`() {
        openSettled()
        pageTurn.backCallback.onBackStarted(back(0f))
        pageTurn.backCallback.onBackProgressed(back(0.5f))
        drawer.setPrompt("a")
        pageTurn.backCallback.onBackInvoked()
        settle()
        assertTrue(pageTurn.isOpen)
        assertEquals("", prompt)
        assertEquals(0f, drawer.view.translationY, 0.5f)
    }
}
