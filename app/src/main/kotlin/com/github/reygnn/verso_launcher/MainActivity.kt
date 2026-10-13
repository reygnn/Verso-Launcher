package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The entire Verso Launcher: a text launcher with two pages.
 *
 *  - The FRONT page (recto, "home") is what a normal text launcher shows: a clock, the date
 *    and your favorites as large plain text. No keyboard, no prompt. Tap a favorite to launch
 *    it, long-press it for the rename/tags dialog. Tap the clock for the alarms, the date for
 *    the calendar.
 *  - Swipe UP and the page turns to the BACK (verso, "drawer"): every app, A to Z, with the
 *    Sigil command line at the bottom and the keyboard up. Swipe DOWN (with the list at its
 *    top), press Back on an empty prompt or press HOME to turn back.
 *
 * The drawer is the original Sigil Launcher, sigils and all. It enumerates the current user's
 * launchable apps via a [android.content.pm.PackageManager] MAIN/LAUNCHER query, filters as
 * you type, launches on tap/Enter.
 *
 * Config lives in the app's own internal storage (verso.json in filesDir) — no
 * storage permission on any Android version, and the app owns it end to end, so
 * it is edited entirely through the in-app modes below, never a text editor.
 * Prompt grammar (leading sigil = mode):
 *   ""      normal: the full drawer, A to Z (every non-hidden app; favorites always kept).
 *           Enter on it just drops the keyboard so the whole list can be scrolled
 *   text    normal: substring search across ALL apps (hidden included)
 *   *       normal: the drawer with the favorites grouped nearest the prompt;
 *           "*text"/"text*" narrows it to labels starting with text
 *   #       tags: lists the tags in use; tap one to drill into its apps
 *   #text   tag filter: apps under every tag prefix-matching [text], tap/Enter
 *           launches. Tags are assigned via the long-press dialog, or in bulk with "##"
 *   ##      edit tags: lists the tags in use; tap one to get EVERY app with an
 *           [x]/[ ] for that tag, tap a row to toggle it. The long-press dialog stays
 *           the way to invent a tag and to set several on one app; this is the way to
 *           put one tag on many apps
 *   -[text] edit hidden:    tap a row to toggle its [x], persisted immediately
 *   ![text] edit favorites: tap a row to toggle its [x], persisted immediately — this is
 *           what fills the home page
 *   !!      reorder favorites: tap a row to pick it up (marked »), tap another
 *           row to drop it there; tap the picked row again to cancel. The home page
 *           lists the favorites in this order, top to bottom
 *   ?       recents: the last-launched apps (in memory only, empty after restart)
 *   ~[text] commands: lists the "~" commands still matching [text] (a bare "~"
 *           lists every one); tap a row to run it
 * The "~" commands. Tap one in that overview, or type enough of one to be
 * unambiguous ("~b" can only be "~backup") and press Enter. Each acts once and
 * clears the prompt:
 *   ~ / ~load  reload verso.json from disk (the config is cached otherwise)
 *   ~save      flush the in-memory config to disk (saves are automatic; this is
 *              the explicit "write it now" for peace of mind)
 *   ~backup    export the config to Download/Verso/verso-<date>_<time>.json,
 *              replacing the previous one once the new one is safely published —
 *              always just the one current backup
 *   ~restore   adopt the newest such backup again. No picker. The config being
 *              replaced is first written next to it as verso-pre-restore-<date>_<time>
 *              .json; if that fails the restore is aborted, so a restore never
 *              destroys the state it overwrote without a trace.
 *   ~restore-saf
 *              same, but PICK the file via the system document picker. The escape
 *              hatch for a config carried over from another phone — or a sigil.json
 *              from Sigil Launcher, whose format is identical (see [restoreConfig]).
 * Long-press any row (home or drawer) to set a custom name and its tags.
 */
class MainActivity : Activity() {

    // The row types (Row/AppRow/TagRow/CommandRow) now live top-level in LauncherLogic.kt,
    // beside the pure rowsFor that builds them, so both can be unit-tested off-device. Here
    // they are parameterised with AppEntry: `shown` is a List<Row<AppEntry>>.

    // NB: not named `foreground` — that collides with View.foreground (a
    // Drawable) inside the apply{} blocks below and hides this Int.
    private val fgColor = 0xFFD4D4D4.toInt()     // pleasant light gray
    private val fgColorDim = 0xFF808080.toInt()  // dimmer gray, for the hint

    private var mode = Mode.NORMAL

    // In FAV_REORDER: the component key of the favorite currently "picked up",
    // or null when nothing is held. First tap picks a row up (marked » in
    // getView), the next tap on another row drops it there; tapping the held row
    // again cancels. Reset whenever the mode is left (see applyFilter), so a stale
    // key from an old reorder session can never move the wrong row later.
    private var reorderPick: String? = null

    // In TAG_EDIT: the tag whose membership the checkboxes show, i.e. whatever follows
    // "##", folded. Null while the bare-"##" overview is up (nothing chosen yet), so the
    // row binder and the tap handler have one place to ask "which tag is this about?"
    // instead of re-parsing the prompt. Recomputed by applyFilter on every keystroke.
    private var tagEditTag: String? = null

    // How many times [shown] has been replaced, and what that counter read when the
    // finger last went down. AbsListView POSTS its click and long-press, and it only drops
    // a pending one while its own mDataChanged flag is up — once a layout pass has run the
    // flag is clear again and the gesture lands on whatever row has since moved into that
    // slot. Comparing the two makes a gesture act only on the list it was aimed at.
    private var shownGeneration = 0
    // The generation the list had when THIS gesture's finger went down. Read LIVE by the
    // long-press guard (CheckForLongPress fires before ACTION_UP, finger still down), then
    // reset to NO_TOUCH the moment the gesture ends so a later accessibility click is not
    // measured against a dead gesture.
    private var touchDownGeneration = TapGuard.NO_TOUCH

    // The down-generation handed over at ACTION_UP for the click the framework may still
    // POST. A SEPARATE field from touchDownGeneration precisely because that one is cleared
    // at UP: the click is delivered LATER, so it needs the down-generation preserved to
    // compare against the CURRENT shownGeneration at delivery — that live compare is what
    // catches a list replacement landing between ACTION_UP and the posted click. NO_TOUCH
    // means "no touch-originated click pending", so an accessibility/hardware click passes.
    private var clickDownGeneration = TapGuard.NO_TOUCH

    // What the ListView currently shows: app rows in every mode, or tag-name rows in
    // the bare-"#" overview. Reassigned only by applyFilter (from LauncherLogic.rowsFor).
    private var shown: List<Row<AppEntry>> = emptyList()

    // One-shot guard for the "recents are empty because we cold-started" toast. The
    // launcher process is killed under memory pressure (aggressively on low-RAM
    // devices), which silently empties the recents; the hint stops that reading as
    // lost data. Shown at most once per process — see applyFilter.
    private var recentsEmptyHintShown = false

    // A single background thread owns ALL disk I/O — both the app reload and the
    // config write run here. Off the main thread (a tap or resume never blocks on
    // disk) and serialized against each other, so two rapid saves can't race on the
    // temp file and reloads can't pile up into an unbounded number of threads.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var listView: ListView
    private lateinit var prompt: EditText
    private lateinit var adapter: AppListAdapter

    // ---- the data ---------------------------------------------------------------
    /**
     * Config, app list and recents, and the rules for keeping them consistent while a
     * background load and the user's taps overlap — see [LauncherState] (and its tests). The
     * Activity hands it its IO thread, the main thread and the Android-side readers/writers,
     * and re-renders when a refresh lands.
     */
    private val state by lazy {
        LauncherState(
            io = ::submitIo,
            main = { task -> runOnUiThread(task) },
            loadConfig = { store.load() },
            enumerateApps = ::loadApps,
            serialize = ConfigJson::serialize,
            writeConfig = { payload -> store.write(payload, rotateBackup = true) },
            onRefreshed = { configReplaced ->
                // The rename dialog read the OLD config when it opened and writes back on
                // OK; left up over a wholesale replace it would persist stale fields into
                // the config that just arrived.
                if (configReplaced) renameDialog?.dismiss()
                applyFilter(prompt.text?.toString().orEmpty())
            },
            isFreshInstall = { store.isFresh() },
            defaultFavoriteCandidates = ::defaultFavoriteCandidates,
        )
    }

    // ---- the two pages ------------------------------------------------------
    // The front page (Kolibri's home layout), built once in onCreate — see HomePage.
    private lateinit var homePage: HomePage
    private lateinit var home: View
    // The back page: the Sigil list + command line. GONE while closed, and blocking focus
    // for its descendants, so the prompt can never grab focus (and raise the IME) on home.
    private lateinit var drawer: LinearLayout
    // THE source of truth for which page is up. The views' visibility trails it by the
    // length of the page-turn animation, so nothing may read visibility instead.
    private var drawerOpen = false
    // Uptime until which a page turn is still animating (see SwipeFrame.swallowTaps).
    private var pageTurnUntil = 0L

    // The drawer keeps monospace only where columns matter (the edit modes' [x]/[ ]
    // markers, the "~" commands) and for the command line itself.
    private val listTypeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    // The long-press rename dialog, tracked only so it can be dismissed if the
    // activity is torn down while it is open — an undismissed dialog leaks its
    // window (and would crash on the resulting bad token). configChanges keeps
    // rotation from recreating us, so process death is the realistic trigger.
    private var renameDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Under enforced edge-to-edge (Android 16) the system does NOT push our content
        // up for the keyboard on its own — adjustResize is a no-op and the IME just
        // covers the prompt. So we own the insets and pad for the IME ourselves (below).
        window.setDecorFitsSystemWindows(false)

        // No always-visible keyboard any more: the front page is a quiet text home screen,
        // and the IME belongs to the drawer only. The manifest asks for stateHidden; the
        // drawer raises the keyboard itself when it opens (see openDrawer).

        // The back page. It keeps the Sigil layout exactly — list above, command line below.
        // No background: the system wallpaper (window is transparent + windowShowWallpaper,
        // see VersoTheme) shows through. A semi-transparent scrim flickered on the wallpaper
        // surface in Sigil, so instead of covering the home page the drawer HIDES it (see
        // openDrawer) — the two pages never overlap once a turn has finished.
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = false
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }

        // Results grow upward, sitting right above the command line — except the bare,
        // empty-prompt drawer, which reads top-down A to Z (see applyFilter).
        listView = ListView(this).apply {
            divider = null  // setDivider(null) already zeroes the divider height
            isVerticalScrollBarEnabled = false
            isStackFromBottom = true
            // Record which list the finger went down on. Returns false without fail: this
            // must observe the gesture, never consume it — the ListView still has to do
            // its own scrolling and click detection.
            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownGeneration = shownGeneration
                        clickDownGeneration = TapGuard.NO_TOUCH  // new gesture, nothing to deliver yet
                    }
                    MotionEvent.ACTION_UP -> {
                        // Hand this gesture's down-generation to the click field and clear the
                        // live one. The click compares it against shownGeneration when it is
                        // actually delivered, so a replacement AFTER this UP is still caught.
                        clickDownGeneration = touchDownGeneration
                        touchDownGeneration = TapGuard.NO_TOUCH
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touchDownGeneration = TapGuard.NO_TOUCH
                        clickDownGeneration = TapGuard.NO_TOUCH
                    }
                }
                false
            }
            setOnItemClickListener { _, _, position, _ ->
                if (!clickStillValid()) return@setOnItemClickListener
                // getOrNull, not [position]: a background load can complete on the main
                // thread between the frame the user tapped and this click message running,
                // shrinking shown — a stale position would then throw (tapAction maps null
                // to None). The per-mode dispatch is the pure LauncherLogic.tapAction (see
                // LauncherActionsTest); the Activity keeps only the side effects.
                when (val action = LauncherLogic.tapAction(mode, shown.getOrNull(position))) {
                    is TapAction.Launch -> launch(action.entry)
                    is TapAction.ToggleHidden -> {
                        state.toggleHidden(action.entry.key)
                        afterToggle()
                    }
                    is TapAction.ToggleFavorite -> {
                        state.toggleFavorite(action.entry.key)
                        afterToggle()
                    }
                    is TapAction.ReorderPick -> reorderTap(action.entry.key)
                    is TapAction.ToggleTag -> toggleTagOn(action.entry.key)
                    // Clear FIRST, exactly as the Enter path does: setText fires the
                    // TextWatcher synchronously, so the mode is back to NORMAL before the
                    // command runs and the re-render can't land on a stale overview.
                    is TapAction.Run -> { prompt.setText(""); runCommand(action.command) }
                    // A tag row drills into that tag's apps by rewriting the prompt — the
                    // TextWatcher then re-filters. The same row type serves both overviews;
                    // which sigil it drills into is decided by the mode it was rendered in.
                    is TapAction.SetPrompt -> setPrompt(action.text)
                    TapAction.None -> {}  // stale position, or a row this mode ignores
                }
            }
            // Long-press: set/clear a custom name (and tags) for an app. Tag rows have
            // no long-press action.
            setOnItemLongClickListener { _, _, position, _ ->
                // Deliberately NOT the click's helper: a long-press fires while the
                // finger is still down, so it must compare live rather than read a verdict
                // that only exists after ACTION_UP. Same exposure though — AbsListView
                // gates it on the mDataChanged flag an intervening layout clears — and a
                // stale one opens the rename dialog for whichever app moved in underneath.
                // Not consumed when stale: let the framework do nothing.
                val row = shown.getOrNull(position) as? AppRow
                    ?: return@setOnItemLongClickListener false  // tag row or stale: not consumed
                // Row type FIRST, guard second: a long-press on a tag row does nothing, and
                // consuming the marker there would leave the click that follows it
                // unguarded.
                if (!longPressStillValid()) return@setOnItemLongClickListener false
                promptRename(row.entry)
                true
            }
        }
        drawer.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // A clear button for the prompt, sitting at the end of the command line. Shown
        // only while there IS something to erase (see the TextWatcher), it wipes the line
        // back to the plain drawer without touching the keyboard — so the user can retype right
        // away, unlike Back, which also drops the IME.
        val clearButton = TextView(this).apply {
            text = "×"  // × — a clear glyph that sits right in the monospace line
            typeface = Typeface.MONOSPACE
            textSize = 24f
            setTextColor(fgColorDim)
            gravity = Gravity.CENTER
            minWidth = 48.dp()  // a comfortable tap target next to the narrow glyph
            contentDescription = getString(R.string.clear_prompt)
            isFocusable = false  // keep focus (and the IME) on the prompt when tapped
            visibility = View.GONE
            setOnClickListener { prompt.setText("") }  // TextWatcher -> applyFilter resets to NORMAL
        }

        // The prompt: a bare monospace command line at the bottom.
        prompt = EditText(this).apply {
            hint = getString(R.string.hint_search)
            setHintTextColor(fgColorDim)
            setTextColor(fgColor)
            typeface = Typeface.MONOSPACE
            textSize = 20f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    // Track the button off the actual text, so it appears/vanishes whether
                    // the change came from typing or from a setText("") elsewhere.
                    clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                    applyFilter(text)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                // Act once per Enter. A soft-keyboard action arrives once with a
                // null event; a hardware/Bluetooth Enter invokes this on BOTH the
                // key-down and the key-up, so without this guard "~", a launch and
                // the edit-mode "done" gesture would each fire twice. Handle the GO
                // action or the key-DOWN edge only; ignore (don't consume) the rest.
                val enterDown = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId != EditorInfo.IME_ACTION_GO && !enterDown) {
                    return@setOnEditorActionListener false
                }
                // A "~" command is one-shot, not a view, so it lives on Enter rather
                // than on a live sigil — nothing happens while it is being typed.
                val command = LauncherLogic.resolveCommand(
                    prompt.text?.toString()?.trim().orEmpty()
                )
                // The whole "what does Enter do?" decision is one pure, exhaustively-tested
                // function (see LauncherLogic.enterAction and LauncherActionsTest); the branch
                // ORDER lives there, load-bearing and pinned. The Activity keeps only the side
                // effects. lastOrNull, not firstOrNull: with isStackFromBottom the list fills
                // upward from the command line, so the LAST row sits directly above the prompt —
                // the natural Enter target.
                when (
                    val action = LauncherLogic.enterAction(
                        mode = mode,
                        command = command,
                        promptBlank = prompt.text.isNullOrBlank(),
                        lastRow = shown.lastOrNull(),
                    )
                ) {
                    // Clear the prompt BEFORE running it ("" parses to no command), so a
                    // second Enter can't fire the same command again.
                    is EnterAction.Run -> { prompt.setText(""); runCommand(action.command) }
                    is EnterAction.LaunchApp -> launch(action.entry)
                    // Drill a tag ("#tag"/"##tag") or clear back to NORMAL ("").
                    // setPrompt drives the TextWatcher exactly as typing does, so
                    // the drawer/filter and the visible command line stay one source of truth.
                    is EnterAction.SetPrompt -> setPrompt(action.text)
                    // Empty prompt: the list already shows every app, so Enter only moves
                    // the keyboard out of the way of scrolling it.
                    EnterAction.DismissKeyboard -> hideKeyboard()
                    EnterAction.None -> {}
                }
                true
            }
        }
        // The prompt takes the row's width (weight 1); the clear button rides at its end,
        // as tall as the row so its glyph lines up with the command line.
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prompt, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clearButton, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        drawer.addView(promptRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Pad the drawer for the system bars AND the IME together. getInsets(systemBars |
        // ime) returns the max per edge, so the bottom padding is the keyboard height
        // when it's up and the nav-bar height otherwise: the weight-1 ListView shrinks
        // from the bottom — staying FULLY on-screen and scrollable (translating it
        // instead pushed the top of a long list off the top edge) — and the prompt
        // rides up just above the keyboard.
        //
        // Deliberately NO WindowInsetsAnimation callback: applying the inset once, when
        // it settles, keeps the ListView from re-laying-out on every animation frame —
        // that per-frame relayout is what made the list blink/redraw before. The prompt
        // moves in a single step rather than tracking the slide; a fair trade for a
        // launcher's command line, and it never clips or flickers.
        val pad = 12.dp()
        drawer.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or
                    WindowInsets.Type.displayCutout()
            )
            v.setPadding(i.left + pad, i.top + pad, i.right + pad, i.bottom + pad)
            insets
        }

        adapter = AppListAdapter()
        listView.adapter = adapter

        homePage = HomePage(
            activity = this,
            onLaunch = ::launch,
            onEdit = ::promptRename,
            onOpenDrawer = { openDrawer() },
        )
        home = homePage.view

        // The window root: both pages stacked, home underneath, plus the swipe that turns
        // between them. Which swipes it takes is the pure SwipeGesture.claims — content
        // that can still scroll the swiped way (a long favorites list, a scrolled drawer)
        // keeps the gesture until it reaches its end.
        val root = SwipeFrame(this).apply {
            addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
            claims = claims@{ dir ->
                // A long-press that opened the rename dialog can carry on into a drag with the
                // finger still down; that must not turn the page under the dialog.
                if (renameDialog != null) return@claims false
                val scroller: View = if (drawerOpen) listView else homePage.scroller
                val canScroll = when (dir) {
                    SwipeDir.UP -> scroller.canScrollVertically(1)
                    SwipeDir.DOWN -> scroller.canScrollVertically(-1)
                    SwipeDir.SIDEWAYS -> false
                }
                SwipeGesture.claims(drawerOpen, dir, canScroll)
            }
            // While a page turn is still running, the outgoing page is VISIBLE (just fading)
            // and on top for hit-testing: a quick tap would land on a row the user can no
            // longer really see. Swallow taps for that moment; swipes are still tracked.
            swallowTaps = { SystemClock.uptimeMillis() < pageTurnUntil }
            // Kolibri's home gestures: up = the app drawer, down = the recently used apps
            // (here: the drawer opened on the "?" recents view). In the drawer, down closes.
            onSwipe = { dir ->
                when {
                    dir == SwipeDir.UP && !drawerOpen -> openDrawer()
                    dir == SwipeDir.DOWN && !drawerOpen -> openDrawer(RECENTS_PROMPT)
                    dir == SwipeDir.DOWN && drawerOpen -> closeDrawer()
                }
            }
        }
        setContentView(root)

        // Back on a HOME launcher must not finish the activity — we're the home
        // screen, so the platform's default callback (routed here on targetSdk 36
        // whether or not we opt in) would just finish us and bounce straight back
        // via HOME. Intercept it. On the home page it is a deliberate no-op. In the
        // drawer, a non-empty prompt (a search or an edit mode) clears back to the plain
        // drawer first; Back on an empty prompt turns the page back to home (which also
        // drops the keyboard). Lives for the whole activity.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) {
            if (!drawerOpen) return@registerOnBackInvokedCallback
            // The recents view is what a swipe down on home opened, so Back leaves it the way
            // it came — straight home, like closing Kolibri's recents list.
            if (prompt.text?.isNotEmpty() == true && mode != Mode.RECENTS) {
                prompt.setText("")   // TextWatcher -> applyFilter resets mode to NORMAL
            } else {
                closeDrawer()
            }
        }
    }

    /** Live battery level on the home page while the launcher is visible. */
    override fun onStart() {
        super.onStart()
        homePage.startBattery()
    }

    // ---- turning the page ---------------------------------------------------

    /**
     * Turn to the drawer: fade/slide it in over a fading home page, then hide home entirely
     * (no translucent overlap left on the wallpaper surface). The prompt is always empty here
     * — [closeDrawer] leaves it so — and is then set to [initialPrompt]: "" opens the full
     * A-to-Z list with the keyboard coming up for typing; "?" (swipe down on home) opens the
     * recently used apps WITHOUT the keyboard, like Kolibri's recents list — tap the command
     * line to type after all.
     */
    private fun openDrawer(initialPrompt: String = "") {
        if (drawerOpen) return
        drawerOpen = true
        showStatusBar(true)
        renameDialog?.dismiss()
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.translationY = PAGE_SLIDE_DP.dp().toFloat()
        drawer.requestApplyInsets()
        if (initialPrompt.isNotEmpty()) setPrompt(initialPrompt)
        else if (shown.isNotEmpty()) listView.setSelection(0)
        pageTurnUntil = SystemClock.uptimeMillis() + PAGE_TURN_MS
        drawer.animate().alpha(1f).translationY(0f).setDuration(PAGE_TURN_MS).start()
        home.animate().alpha(0f).setDuration(PAGE_TURN_MS).withEndAction {
            if (drawerOpen) home.visibility = View.INVISIBLE
        }.start()
        // Posted: the drawer was GONE until a moment ago, so let it be laid out once before
        // the prompt takes focus and the IME is asked for.
        if (initialPrompt.isEmpty()) drawer.post { if (drawerOpen) showKeyboard() }
    }

    /**
     * Turn back to home: drop the keyboard, clear the prompt (so the next open starts clean
     * and no edit mode lingers), and swap the pages. [animate] = false is the instant
     * version for when the launcher is going to the background anyway.
     */
    private fun closeDrawer(animate: Boolean = true) {
        if (!drawerOpen) return
        drawerOpen = false
        showStatusBar(false)
        hideKeyboard()
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        prompt.clearFocus()
        if (prompt.text?.isNotEmpty() == true) prompt.setText("")
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        if (!animate) {
            pageTurnUntil = 0L
            home.alpha = 1f
            drawer.visibility = View.GONE
            return
        }
        pageTurnUntil = SystemClock.uptimeMillis() + PAGE_TURN_MS
        home.animate().alpha(1f).setDuration(PAGE_TURN_MS).start()
        drawer.animate().alpha(0f).translationY(PAGE_SLIDE_DP.dp().toFloat())
            .setDuration(PAGE_TURN_MS).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
    }

    /**
     * The status bar belongs to the drawer only: the home page is a quiet full screen, the
     * drawer (a working list with a command line) shows it. Hidden, a swipe from the top edge
     * still brings it — and the notification shade — in transiently, so nothing is out of reach.
     */
    private fun showStatusBar(show: Boolean) {
        val controller = window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) controller.show(WindowInsets.Type.statusBars())
        else controller.hide(WindowInsets.Type.statusBars())
    }

    override fun onResume() {
        super.onResume()
        state.refresh()
        // Re-assert on every return: the bar's visibility is a request on our window, and
        // coming back from an app that showed it must not leave it up on the home page.
        showStatusBar(drawerOpen)
        // Only the drawer is keyboard-first. It is normally closed by the time we come
        // back (onStop turns the page home), but a resume WITHOUT a stop — a dialog or a
        // translucent activity on top — returns to an open drawer, and there the
        // keyboard belongs up again.
        // The recents view ("?", from a swipe down) deliberately opens without it.
        if (drawerOpen && mode != Mode.RECENTS) showKeyboard()
    }

    /**
     * Leaving the launcher (an app was started, the screen went off, the user went to
     * recents) turns the page back home, instantly — coming back to the launcher should
     * land on the home screen, like any launcher, not on a half-typed search.
     */
    override fun onStop() {
        closeDrawer(animate = false)
        homePage.stopBattery()
        super.onStop()
    }

    override fun onDestroy() {
        // Tear down an open rename dialog first — an undismissed dialog leaks its
        // window once the activity's context is gone. setOnDismissListener nulls the
        // field; guarding here is belt-and-suspenders.
        renameDialog?.dismiss()
        renameDialog = null
        // A HOME activity is rarely destroyed, but shut the IO thread down cleanly
        // if it is. shutdown() (not shutdownNow) lets an in-flight save finish.
        ioExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Submit disk work to [ioExecutor] without ever letting a post-shutdown
     * submission crash the app. Every caller today runs on the main thread — the
     * same thread that calls shutdown() in onDestroy — so the isShutdown check is
     * race-free as written; the catch is belt-and-suspenders for any future
     * refactor that adds an off-main caller and could lose that guarantee.
     * Dropping a task once the executor is down is fine: the activity is going
     * away, so an unwritten save or a skipped reload no longer matters.
     */
    private fun submitIo(task: Runnable): Boolean {
        if (ioExecutor.isShutdown) return false
        return try {
            ioExecutor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            Log.w("Verso", "IO task rejected (executor shutting down)", e)
            false
        }
    }

    /** HOME pressed while already home: turn back to the home page, scrolled to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // keep getIntent() in sync with the latest launch intent
        // HOME is a "back to a clean home screen" gesture, so a rename dialog left open has
        // no business surviving it: confirming it afterwards would apply a rename the user
        // has visually left behind.
        renameDialog?.dismiss()
        // Also clears the prompt (closeDrawer always does), so the next swipe up opens on
        // the plain drawer rather than whatever mode was last typed.
        closeDrawer()
        homePage.scrollToTop()
    }

    // ---- config -------------------------------------------------------------

    /**
     * The verso.json file layer. Everything Android-specific it needs is injected here:
     * filesDir, android.util.Log and the directory fsync. Loading, rotating and publishing
     * itself lives in [ConfigStore], where it is unit-tested away from the Activity.
     */
    private val store by lazy {
        ConfigStore(
            dir = filesDir,
            log = { msg, e -> if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg) },
            syncDir = ::fsyncDir,
        )
    }

    /**
     * The shared-storage backup layer (Download/[BACKUP_DIR]). The MediaStore mechanics live
     * in [BackupStore]; the Activity keeps only the orchestration that needs it — backup,
     * restoreConfig and the "~restore-saf" picker.
     */
    private val backupStore by lazy {
        BackupStore(
            resolver = contentResolver,
            subDir = BACKUP_DIR,
            log = { msg, e -> if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg) },
        )
    }

    /**
     * Run a one-shot "~" command. RELOAD stays silent — its effect is the list
     * redrawing, which is its own feedback. The other three do their work off-screen
     * (or in another app entirely), so each one reports back with a toast; a save you
     * cannot see happen is a save you do not trust.
     */
    private fun runCommand(command: Command) {
        when (command) {
            Command.RELOAD -> state.refresh(reloadConfig = true)
            // Toast from the IO callback, on the real result — NOT straight after
            // submitting. save only queues the write; announcing success before the
            // write is even attempted is exactly the "save you cannot see happen" this
            // command exists to rule out.
            Command.SAVE -> state.save { ok ->
                toast(getString(if (ok) R.string.toast_saved else R.string.toast_save_failed))
            }
            Command.BACKUP -> backup()
            Command.RESTORE -> restoreConfig()
            Command.RESTORE_SAF -> pickRestoreFile()
        }
    }

    /**
     * "~backup": export the config to Download/[BACKUP_DIR] as a backup of [BACKUP_BASE],
     * replacing the previous one only once the new one is published (see [BackupStore.write]).
     * There is one current backup and one undo point — no timestamps, no pile to sift through —
     * which is what lets [restoreConfig] work without a file picker.
     *
     * Different from the .bak mirror [ConfigStore] keeps: that one rotates away after two
     * saves and lives in filesDir, so it dies with the app. This one is an ordinary file in
     * shared storage that can be copied off the device.
     *
     * The config in memory once it is loaded — serialized here on the main thread, where it is
     * only ever mutated, so the read is race-free. Before the first load lands (a fast "~b" on a
     * cold start) memory still holds the empty default, and exporting THAT would replace the
     * real backup with nothing; the file on disk is the truth then, read on the IO thread.
     */
    private fun backup() {
        val payload = if (state.configLoaded) ConfigJson.serialize(state.config) else null
        val accepted = submitIo {
            val name = backupStore.write(BACKUP_BASE, payload ?: ConfigJson.serialize(store.load()))
            runOnUiThread {
                // Name the file actually written: its name carries the time it was taken.
                if (name != null) toast(getString(R.string.toast_backup_ok, name), Toast.LENGTH_LONG)
                else toast(getString(R.string.toast_backup_failed))
            }
        }
        if (!accepted) toast(getString(R.string.toast_backup_failed))
    }

    /**
     * "~restore": read the one backup back in. No picker — there is a single file under
     * a known name, so there is nothing to choose.
     *
     * The read runs on [ioExecutor]; only the adoption returns to the main thread, where
     * the config lives.
     */
    private fun restoreConfig() {
        submitIo {
            // The newest backup of ours, whatever MediaStore named it — or, if none was ever
            // published, an interrupted first one (see BackupFiles.latestToRestore).
            val uri = backupStore.findLatest(BACKUP_BASE)
            val text = uri?.let { backupStore.readText(it) }
            // Parsed here, off the main thread: a hostile document is parse work the UI must
            // not wait on (and the parse is where a torn temp gets rejected).
            val restored = text?.let { ConfigJson.parseForeign(it) }
            runOnUiThread {
                // Nothing there at all is its own message: "~backup was never run" is a
                // different problem from "the backup is broken", and saying so saves the
                // user hunting for a file that does not exist.
                when {
                    uri == null -> toast(getString(R.string.toast_restore_none), Toast.LENGTH_LONG)
                    restored == null -> toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                    else -> adoptRestored(restored)
                }
            }
        }
    }

    /**
     * Ask the system picker for a config, then adopt it — the "~restore-saf" command.
     *
     * Plain "~restore" reads the app's OWN MediaStore row, which is invisible after a
     * reinstall and on a different phone; ACTION_OPEN_DOCUMENT has no such blind spot,
     * because the user granting the pick IS the permission. So this is the way a config
     * moves between devices, at the cost of one dialog.
     */
    private fun pickRestoreFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            // "*/*" plus a hint list, not a strict "application/json": a .json arrives
            // typed as text/plain or octet-stream depending on who wrote or copied it,
            // and a strict filter would grey out the very file being looked for.
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            )
            // Open ON the backup folder rather than wherever the picker last was — the
            // backups live in a SUB-folder of Downloads, so a picker landing in Downloads
            // root shows everything EXCEPT them. Only a hint; DocumentsUI ignores it when
            // it cannot honour it (folder not created yet, no backup ever written).
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIR",
                ),
            )
        try {
            startActivityForResult(intent, REQ_RESTORE)
        } catch (e: ActivityNotFoundException) {
            Log.w("Verso", "no document picker on this device", e)
            toast(getString(R.string.toast_restore_no_picker))
        }
    }

    /** The "~restore-saf" picker's answer. Reading stays off the main thread; only the
     *  adoption comes back to it, exactly as in [restoreConfig]. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        submitIo {
            val restored = backupStore.readText(uri)?.let { ConfigJson.parseForeign(it) }
            runOnUiThread {
                // Unlike [restoreConfig] a null here is not "no backup yet" — the user
                // pointed at a file and it could not be read or is no config. Either way
                // nothing was changed.
                if (restored == null) toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                else adoptRestored(restored)
            }
        }
    }

    /**
     * Adopt a config that [restoreConfig] or the "~restore-saf" picker read and parsed — with
     * parseForeign, NOT parse: the lenient parser would turn any JSON object at all into an
     * empty config and wipe everything without so much as an error. A document that did not
     * parse never gets here, so a malformed backup leaves the live config untouched.
     *
     * Then the undo point — the config being replaced — is written out as a backup of
     * [PRE_RESTORE_BASE], and ONLY once that write succeeded is [restored] adopted. A restore
     * whose undo point could not be written is aborted: replacing the config with nothing to
     * go back to is exactly the "destroyed without a trace" the undo point exists to prevent.
     * The outgoing config is taken from memory here on the main thread, or — if it was never
     * loaded in this process (the picker's result delivered to a fresh process after Android
     * killed the launcher) — read from disk on the IO thread, where an empty default would
     * otherwise replace a real undo point with nothing.
     */
    private fun adoptRestored(restored: VersoConfig) {
        // What a restore actually adopted, so a "my favorites are gone" report can be
        // settled from the log instead of guessed at.
        Log.i(
            "Verso",
            "restore: ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        val outgoing = if (state.configLoaded) ConfigJson.serialize(state.config) else null
        val accepted = submitIo {
            val undo = backupStore.write(PRE_RESTORE_BASE, outgoing ?: ConfigJson.serialize(store.load()))
            runOnUiThread {
                if (undo == null) {
                    toast(getString(R.string.toast_restore_undo_failed), Toast.LENGTH_LONG)
                    return@runOnUiThread
                }
                // Wholesale replace, save and re-render — with the epoch guard that keeps an
                // enumeration already in flight from handing the OLD config back over this one.
                state.adopt(restored)
                // A dialog opened against the pre-restore config would write its stale name
                // and tags over what the restore just brought in.
                renameDialog?.dismiss()
                toast(getString(R.string.toast_restore_ok, undo), Toast.LENGTH_LONG)
            }
        }
        if (!accepted) toast(getString(R.string.toast_restore_undo_failed), Toast.LENGTH_LONG)
    }

    /** Toasts are this launcher's only chrome — one helper so they stay uniform. */
    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, text, duration).show()

    /**
     * fsync a *directory* so a preceding rename() is durable, not merely visible.
     * The rename publishes atomically, but the directory entry it rewrites isn't on
     * disk until the directory itself is synced — so a power-loss just after a rename
     * can silently roll the publish back to the previous file. Platform syscalls only
     * (no AndroidX): open the dir read-only, fsync the fd, close it. Best-effort — a
     * failure here only weakens durability, it never corrupts, so it is logged, not
     * thrown, exactly like the rest of the save path.
     */
    private fun fsyncDir(dir: File) {
        var fd: FileDescriptor? = null
        try {
            fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            Os.fsync(fd)
        } catch (e: ErrnoException) {
            Log.w("Verso", "dir fsync failed (non-fatal): ${dir.path}", e)
        } finally {
            if (fd != null) {
                try {
                    Os.close(fd)
                } catch (e: ErrnoException) {
                    // Nothing actionable at close time; the publish already happened.
                }
            }
        }
    }

    /**
     * "##tag": put the chosen tag on [key] or take it off, persisted immediately like the
     * other edit modes' checkboxes.
     *
     * Deliberately not routed through [toggle]: that one flips membership in a flat
     * collection, while a tag lives in a per-app LIST inside a map, and an app left with
     * no tags must lose its KEY rather than keep an empty list (ConfigJson drops empty
     * lists on both sides, so an empty one would vanish on the next round trip and the
     * in-memory shape would stop matching the file). The epoch/save/notify tail is
     * identical, and identical for the same reasons — see [toggle].
     */
    /**
     * May the posted click still act on [shown]?
     *
     * False only when the list was replaced between the finger going down and this click
     * being DELIVERED — then the row at that position is not the one that was aimed at, and
     * acting on it would toggle, launch, re-tag or rename an app the user never touched.
     *
     * The comparison is made live against the CURRENT [shownGeneration] here, not against a
     * verdict frozen at ACTION_UP: [clickDownGeneration] preserves the down-generation past
     * UP for exactly this reason. That closes the window a UP-time snapshot left open — a
     * replacement landing between ACTION_UP and this posted delivery (a background app-load
     * can, which is also why the branches below re-check via getOrNull). Nothing runs
     * between reading the generation here and reading [shown] in the same callback, so the
     * two stay consistent.
     *
     * Consumes the down-generation (resets it to NO_TOUCH), so it guards exactly the one
     * click that touch leads to and a following accessibility click passes. A NO_TOUCH
     * value passes anyway: accessibility services and hardware keys invoke the click action
     * directly, with no MotionEvent at all, and vetoing those would make the launcher
     * unusable with a screen reader.
     *
     * This reads [clickDownGeneration]; the long-press reads the LIVE [touchDownGeneration]
     * via [longPressStillValid]. The pure arithmetic in [TapGuard.stillValid] is shared,
     * but the FIELD each passes must not be — feeding a long-press the click's field (or
     * vice versa) is the mistake that made the long-press guard a no-op once already.
     */
    private fun clickStillValid(): Boolean {
        val downGeneration = clickDownGeneration
        clickDownGeneration = TapGuard.NO_TOUCH
        return TapGuard.stillValid(downGeneration, shownGeneration)
    }

    /**
     * The same question for a LONG-PRESS, which needs a different answer — and getting that
     * wrong is what made the previous version of this guard a no-op.
     *
     * AbsListView posts CheckForLongPress on ACTION_DOWN and fires it about half a second
     * later WHILE THE FINGER IS STILL DOWN. It therefore arrives strictly BEFORE this
     * gesture's own ACTION_UP — so there is no verdict for it to read, and reading one
     * would only ever return a leftover from some earlier gesture. Instead the comparison
     * is made live: touchDownGeneration still holds THIS gesture's value, because the
     * gesture has not ended yet.
     *
     * NO_TOUCH means no touch is in progress — an accessibility service invoking the
     * long-click action directly — and passes, for the same reason [clickStillValid] lets
     * an unattributed click through.
     */
    private fun longPressStillValid(): Boolean =
        TapGuard.stillValid(touchDownGeneration, shownGeneration)

    private fun toggleTagOn(key: String) {
        val tag = tagEditTag ?: return
        state.toggleTag(key, tag)
        adapter.notifyDataSetChanged()
    }

    /** After a hidden/favorite toggle: the membership glyphs changed (the row set did not),
     *  and a favorite toggle changes the home page. */
    private fun afterToggle() {
        adapter.notifyDataSetChanged()
        renderHome()
    }

    /**
     * One tap in FAV_REORDER. Nothing held yet -> pick this row up. The held row
     * tapped again -> cancel. Any other row -> drop the held favorite there (see
     * [moveFavorite]). A pick/cancel only flips the » marker, so notifyDataSetChanged
     * is enough; a drop reorders the set, so it re-renders via applyFilter.
     */
    private fun reorderTap(key: String) {
        when (reorderPick) {
            null -> { reorderPick = key; adapter.notifyDataSetChanged() }
            key  -> { reorderPick = null; adapter.notifyDataSetChanged() }
            else -> moveFavorite(reorderPick!!, key)
        }
    }

    /**
     * Drop the picked favorite into [targetKey]'s slot (see [LauncherState.moveFavorite]).
     * Either way the » marker goes; an impossible move (a key vanished mid-session, or the
     * same row) leaves the order untouched.
     */
    private fun moveFavorite(pickedKey: String, targetKey: String) {
        reorderPick = null
        state.moveFavorite(pickedKey, targetKey)
        applyFilter(prompt.text?.toString().orEmpty())  // re-render in the (new) order
    }

    private fun promptRename(entry: AppEntry) {
        val key = entry.key
        // Dismiss any dialog already up (rapid long-presses) before opening a new one, and
        // keep the reference so onDestroy can tear it down. The view construction lives in
        // RenameDialog; the Activity keeps only the config decisions the commit implies.
        renameDialog?.dismiss()
        renameDialog = RenameDialog.show(
            context = this,
            title = entry.label,
            initialName = state.config.names[key] ?: entry.systemLabel,
            initialTags = state.config.tags[key]?.joinToString(", ").orEmpty(),
            allTags = LauncherLogic.allTags(state.config.tags),
            fgColor = fgColor,
            onAppInfo = { openAppInfo(entry) },
        ) { name, tags ->
            // Name override, tags, save, in-memory relabel + re-sort — and the counters that
            // keep an in-flight load from undoing it: LauncherState.rename.
            state.rename(key, entry.systemLabel, name, tags)
            applyFilter(prompt.text?.toString().orEmpty())
        }
        // Clear the field on dismiss so we never hold a stale, already-gone dialog.
        renameDialog?.setOnDismissListener { renameDialog = null }
    }

    // ---- apps ---------------------------------------------------------------

    /** LauncherState's enumerateApps: runs on the IO thread with a config SNAPSHOT. */
    private fun loadApps(cfg: VersoConfig): List<AppEntry>? {
        val pm = packageManager
        val self = packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // This runs on a background thread, and a HOME app that throws on resume
        // loops forever — so nothing here may let an exception escape. The query
        // and loadLabel() surface plain RuntimeExceptions (e.g. a
        // DeadSystemRuntimeException if system_server restarts): catch broadly and
        // return null (NOT an empty list) so the caller keeps the last-known-good
        // list rather than blanking the home screen on a transient hiccup. A query
        // that SUCCEEDS returns its result even when empty — the rare genuinely
        // app-less device (only this launcher installed) still commits its emptiness.
        val resolved = try {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } catch (e: RuntimeException) {
            Log.w("Verso", "app enumeration failed", e)
            return null  // failure sentinel: keep the current list, don't wipe it
        }
        return resolved.mapNotNull { ri ->
            try {
                val ai = ri.activityInfo
                // A component without a class name cannot be launched (and would key as
                // "pkg/null"): skip it, as the ComponentName-based code effectively did.
                if (ai.name.isNullOrEmpty()) return@mapNotNull null
                if (ai.packageName == self) return@mapNotNull null  // don't list ourselves
                // Identity is the full launcher component, not the package — a
                // package can expose several launcher activities, each its own row.
                // "package/class", exactly ComponentName.flattenToString().
                val key = "${ai.packageName}/${ai.name}"
                // The app's own label, before any override — kept on the entry
                // (systemLabel) so a later rename can rebuild in-memory.
                val systemLabel = ri.loadLabel(pm).toString()
                // A custom name overrides the app's own label; otherwise the label
                // as the system reports it. labelLower keys off the displayed text,
                // so filtering matches what's on screen (a custom name included).
                // LauncherLogic.foldLabel folds with Locale.ROOT — the same fold
                // search's needle uses — so the I/i mapping stays locale-invariant.
                val base = cfg.names[key] ?: systemLabel
                AppEntry.of(
                    label = base,
                    packageName = ai.packageName,
                    className = ai.name,
                    systemLabel = systemLabel,
                )
            } catch (e: RuntimeException) {
                Log.w("Verso", "skipping app", e)
                null
            }
        }.let { LauncherLogic.sortedForDrawer(it) }
    }

    /**
     * LauncherState's defaultFavoriteCandidates: phone, messenger (the default SMS app), email,
     * browser, camera — the favorites a fresh install starts with. IO thread, fresh install only.
     *
     * Each slot is resolved by what the app DOES, not by a package list, so it works on any
     * vendor: the user's default handler for the job comes first, then every other handler in
     * the system's own order (covers "no default chosen yet"). A handler package becomes its
     * launcher activity key via getLaunchIntentForPackage — the job's activity (a dial pad, a
     * compose screen) is usually not the one the drawer lists. Never throws: a failing slot is
     * just empty, and LauncherLogic.pickDefaultFavorites skips it.
     */
    private fun defaultFavoriteCandidates(): List<List<String>> {
        val pm = packageManager
        fun launcherKey(pkg: String): String? =
            pm.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
        fun handlers(intent: Intent, preferred: String? = null): List<String> = try {
            // resolveActivity answers the ResolverActivity ("android") when no default is set —
            // that is no app, so it is dropped by the launcherKey lookup below like any other
            // package without a launcher entry.
            val default = preferred
                ?: pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(
                    PackageManager.MATCH_DEFAULT_ONLY.toLong()))?.activityInfo?.packageName
            val all = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName }
            (listOfNotNull(default) + all).distinct().mapNotNull(::launcherKey)
        } catch (e: RuntimeException) {
            Log.w("Verso", "resolving default favorite failed: $intent", e)
            emptyList()
        }
        return listOf(
            handlers(Intent(Intent.ACTION_DIAL)),
            handlers(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                preferred = Telephony.Sms.getDefaultSmsPackage(this),
            ),
            handlers(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
            handlers(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            ),
            handlers(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        )
    }

    private fun applyFilter(raw: String) {
        val q = raw.trim()
        mode = LauncherLogic.parseMode(q)
        // The bare, empty-prompt drawer is a plain A-to-Z list and reads top-down like any
        // app drawer; every other view (search results, sigil modes) keeps Sigil's
        // bottom-anchoring, so results sit right above the command line where Enter acts.
        val topAnchored = mode == Mode.NORMAL && q.isEmpty()
        val anchorFlipped = listView.isStackFromBottom == topAnchored
        if (anchorFlipped) listView.isStackFromBottom = !topAnchored
        // A pickup belongs to a single reorder session: drop it the moment we're no
        // longer in FAV_REORDER, so nothing stale survives into another mode.
        if (mode != Mode.FAV_REORDER) reorderPick = null
        // Whatever follows "##" IS the tag; blank means the overview is showing and no tag
        // is chosen yet. The overview-vs-chosen-tag decision (and the canonicalisation that
        // makes "##Work"/"##work"/"##  work" one tag) is the pure LauncherLogic.tagEditTagFor,
        // tested in LauncherRowsTest — so nothing downstream has to remember to normalise.
        tagEditTag = LauncherLogic.tagEditTagFor(mode, q)
        // The entire "given the prompt, what rows does the list show?" decision now lives
        // as one pure, exhaustively-tested function in LauncherLogic (see rowsFor and
        // LauncherRowsTest). The Activity keeps only the SIDE EFFECTS around it: the
        // reorderPick reset and tagEditTag above, the shownGeneration bump and the empty-
        // recents toast below. tagEditTag is passed in rather than recomputed so the value
        // the adapter reads and the value rowsFor uses can never drift.
        shown = LauncherLogic.rowsFor(
            mode = mode,
            trimmed = q,
            allApps = state.apps,
            hidden = state.config.hidden,
            favorites = state.config.favorites,
            tags = state.config.tags,
            recentKeys = state.recentKeys,
            tagEditTag = tagEditTag,
        )
        // Any replacement of shown invalidates a tap already in flight (see the click
        // listener). Bumped here, in the ONE place shown is assigned.
        shownGeneration++
        // An empty "?" means nothing has been launched since this process started —
        // i.e. we just cold-started (or it's a fresh install). The recents cache is
        // in memory only and low-RAM devices kill the launcher process often, so warn
        // once per process that the list resets on restart, lest an empty "?" look
        // like the app forgot the user's recents. Guarded so the per-keystroke
        // TextWatcher can't repeat it.
        // shown, not state.recentKeys: a recent whose app has since been uninstalled is filtered
        // out of the rendered list but still sits in the recents, so testing the cache would
        // leave the user staring at an unexplained empty "?" — the one case the hint exists
        // for.
        if (mode == Mode.RECENTS && shown.isEmpty() && !recentsEmptyHintShown) {
            recentsEmptyHintShown = true
            Toast.makeText(this, getString(R.string.toast_recents_empty), Toast.LENGTH_LONG).show()
        }
        adapter.notifyDataSetChanged()
        // Back to the empty prompt: start the A-to-Z list at A, not wherever the
        // bottom-anchored search left the scroll position.
        if (anchorFlipped && topAnchored && shown.isNotEmpty()) listView.setSelection(0)
        // ...and the reverse: leaving the empty prompt, the ListView would otherwise sync to
        // the old first position (0) and show the TOP of a long result list — hiding the row
        // nearest the prompt, which is exactly what Enter acts on. Pin it to the bottom.
        if (anchorFlipped && !topAnchored && shown.isNotEmpty()) listView.setSelection(shown.lastIndex)
        // Favorites, labels or the app set may have changed (reload, rename, reorder,
        // restore): keep the home page in step. A no-op when nothing it shows changed.
        renderHome()
    }

    /** Keep the home page in step with favorites, labels and the app set. Cheap when nothing
     *  it shows changed, so it is fine to call from every applyFilter. */
    private fun renderHome() = homePage.render(state.favorites(), state.appsLoaded)

    private fun launch(entry: AppEntry) {
        // A launcher starts the app in its own task, not nested in this one.
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(entry.packageName, entry.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            // Only a launch that actually started counts as "recent" — the catch
            // branches below leave the list untouched so a dead component never
            // lingers at the top of "?".
            state.recordLaunch(entry.key)
        } catch (e: ActivityNotFoundException) {
            // Component gone since the list loaded (uninstall race).
            Log.w("Verso", "launch unavailable: ${entry.key}")
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w("Verso", "launch denied: ${entry.key}", e)
            Toast.makeText(this, getString(R.string.toast_denied, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            // Dead system_server or similar — don't crash HOME.
            Log.w("Verso", "launch failed: ${entry.key}", e)
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open the system "App info" screen for this component's package (uninstall, permissions,
     * force-stop live there). Reached from the long-press dialog's neutral button. Launched in
     * its own task like [launch], and guarded the same way so a missing Settings activity or a
     * component gone mid-session can never crash HOME.
     */
    private fun openAppInfo(entry: AppEntry) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", entry.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("Verso", "app info unavailable: ${entry.packageName}")
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.w("Verso", "app info denied: ${entry.packageName}", e)
            Toast.makeText(this, getString(R.string.toast_denied, entry.label), Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            Log.w("Verso", "app info failed: ${entry.packageName}", e)
            Toast.makeText(this, getString(R.string.toast_not_found, entry.label), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Set the prompt programmatically and leave the caret at the END, ready to keep typing —
     * [android.widget.EditText.setText] on its own parks it at 0, which would make the next
     * keystroke land in FRONT of a drilled "#tag".
     *
     * Nothing depends on the caret sitting anywhere in particular: the "*" drawer filter reads
     * a star at either end (see [LauncherLogic.starPrefix]), precisely so it survives whatever
     * the IME does with the caret when setText restarts the input connection. setText fires the
     * TextWatcher synchronously, exactly as typing does.
     */
    private fun setPrompt(text: String) {
        prompt.setText(text)
        prompt.setSelection(text.length)
    }

    private fun showKeyboard() {
        // requestFocus makes the prompt the input target; the window insets controller is
        // the modern IME show that cooperates with the app-owned insets above. The legacy
        // InputMethodManager.showSoftInput wants the view already laid out and the window
        // focused, so it is unreliable straight from onResume — the controller is not.
        prompt.requestFocus()
        prompt.windowInsetsController?.show(WindowInsets.Type.ime())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(prompt.windowToken, 0)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Tag rows and app rows are the same monospace TextView, so a recycled view
            // is reused across both freely.
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(fgColor)
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            when (val row = shown[position]) {
                // Tag overview (bare "#"): plain tag name; a screen reader reads it fine,
                // so clear any stale edit-mode description from the recycled view.
                is TagRow -> {
                    tv.typeface = listTypeface
                    tv.text = row.name
                    tv.contentDescription = null
                }
                // "~" overview: the command's own spelling is the label, and it reads
                // aloud as-is, so clear any stale edit-mode description off the recycled view.
                is CommandRow -> {
                    tv.typeface = Typeface.MONOSPACE  // commands are typed text: show them so
                    tv.text = row.name
                    tv.contentDescription = null
                }
                is AppRow -> bindAppRow(tv, row.entry)
            }
            return tv
        }

        private fun bindAppRow(tv: TextView, entry: AppEntry) {
            val key = entry.key
            // The edit-mode marker glyph is chosen by the pure LauncherLogic.rowPrefix
            // (unit-tested in LauncherRowsTest); the read modes return "" so the label
            // stands alone. "[ ] "/"[x] " and "» "/"  " are equal-width, so labels stay
            // column-aligned.
            val marker = LauncherLogic.rowPrefix(
                mode,
                isHidden = key in state.config.hidden,
                isFavorite = key in state.config.favorites,
                isPicked = key == reorderPick,
                isTagged = tagEditTag in state.config.tags[key].orEmpty(),
            )
            // A marker column ("[x] ", "» ") only lines up in a monospace face; the plain
            // read modes carry none and use the drawer's ordinary sans.
            tv.typeface = if (marker.isEmpty()) listTypeface else Typeface.MONOSPACE
            tv.text = marker + entry.label
            // Accessibility: the "[x]"/"[ ]" glyph reads as literal punctuation to a
            // screen reader, so in the edit modes give the row a spoken description of
            // its state and what a tap does. NORMAL rows read their label fine, so the
            // description is cleared (null falls back to the text) — also stops a stale
            // edit-mode description clinging to a recycled convertView.
            tv.contentDescription = when (mode) {
                Mode.HIDDEN_EDIT -> getString(
                    if (key in state.config.hidden) R.string.a11y_hidden_on else R.string.a11y_hidden_off,
                    entry.label,
                )
                Mode.FAV_EDIT -> getString(
                    if (key in state.config.favorites) R.string.a11y_fav_on else R.string.a11y_fav_off,
                    entry.label,
                )
                Mode.FAV_REORDER -> getString(
                    when {
                        reorderPick == null -> R.string.a11y_reorder_pick   // nothing held
                        key == reorderPick  -> R.string.a11y_reorder_picked // this row held
                        else                -> R.string.a11y_reorder_drop   // a drop target
                    },
                    entry.label,
                )
                Mode.TAG_EDIT -> getString(
                    if (tagEditTag in state.config.tags[key].orEmpty()) R.string.a11y_tag_on
                    else R.string.a11y_tag_off,
                    entry.label,
                    tagEditTag.orEmpty(),
                )
                Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND -> null
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Sub-folder of Downloads that "~backup" writes into. */
        const val BACKUP_DIR = "Verso"
        // Fixed names, not timestamped ones: there is exactly ONE backup and ONE undo
        // point, each overwritten in place. That is what lets "~restore" skip a file
        // picker — it already knows the name of the only file it could mean.
        /** The backups' base names: published as "<base>-<date>_<time>.json" (see BackupFiles). */
        const val BACKUP_BASE = "verso"
        const val PRE_RESTORE_BASE = "verso-pre-restore"
        const val REQ_RESTORE = 1
        /** Duration of the home <-> drawer page turn. */
        const val PAGE_TURN_MS = 160L
        /** How far the drawer slides while fading, in dp. */
        const val PAGE_SLIDE_DP = 32
        /** What a swipe down on home opens the drawer on: the recently used apps. */
        const val RECENTS_PROMPT = "?"
    }
}
