package com.github.reygnn.verso_launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.RejectedExecutionException

/**
 * The entire Verso Launcher: a text launcher with two pages.
 *
 *  - The FRONT page (recto, "home") is what a normal text launcher shows: a clock, the date
 *    and your favorites as large plain text. No keyboard, no prompt. Tap a favorite to launch
 *    it, long-press it for the rename/tags dialog. Double-tap the clock for the alarms, the
 *    date for the calendar, the battery for its usage screen.
 *  - Pull UP and the page turns to the BACK (verso, "drawer") — it follows the finger: every
 *    app, A to Z, with the Sigil command line at the bottom and the keyboard up. Pull DOWN
 *    (with the list at its top), press Back on an empty prompt or press HOME to turn back.
 *
 * The drawer is the original Sigil Launcher, sigils and all. It lists the launchable apps of
 * every profile (personal, work, clone) through [android.content.pm.LauncherApps], filters as
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
 *              hatch for a config carried over from another phone or surviving a
 *              reinstall (see [restoreConfig]).
 * Long-press any row (home or drawer) to set a custom name and its tags.
 */
class MainActivity : Activity() {

    // The row types (Row/AppRow/TagRow/CommandRow) live top-level in LauncherLogic.kt, beside
    // the pure rowsFor that builds them. Here they are parameterised with AppEntry: `shown` is
    // a List<Row<AppEntry>>.

    // The colours for the wallpaper behind (HomeStyle.palette): light text on a dark wallpaper,
    // dark text on a light one — switched live when the wallpaper changes (wallpaperColors).
    private var palette = HomeStyle.palette(lightWallpaper = false)
    // The drawer sits on the bare wallpaper like the home page, so its rows get the same tonal
    // outline (palette.listOutline) — readable on a bright or busy wallpaper too; its width:
    private val outlinePx get() = HomeStyle.OUTLINE_DP * resources.displayMetrics.density

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

    // Whether the next onResume must re-enumerate the apps (see onResume / appsMaybeChanged):
    // true at first, then set by the change callbacks while the launcher is not in front.
    private var appsDirty = true
    private var lastRefresh = 0L
    private var resumed = false
    // Between onStart and onStop: the launcher can be seen (see cleanup).
    private var started = false
    // Whether the unreadable config was reported already (once per episode, see onRefreshed).
    private var unreadableShown = false

    // What outlives this instance: the one disk thread and the recents (see VersoApp).
    private val app get() = VersoApp.from(this)

    // A single background thread owns ALL disk I/O — both the app reload and the
    // config write run here. Off the main thread (a tap or resume never blocks on
    // disk) and serialized against each other, so two rapid saves can't race on the
    // temp file and reloads can't pile up into an unbounded number of threads. It is the
    // PROCESS's thread, not this instance's: a recreated activity queues behind whatever
    // its predecessor left in flight instead of racing it on a thread of its own.
    private val ioExecutor get() = app.io

    // The window root, which also runs the page drag (see SwipeFrame).
    private lateinit var root: SwipeFrame
    private lateinit var clearButton: TextView
    private lateinit var emptyView: OutlinedTextView
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
    // Typed explicitly: onRefreshed below reads state itself (configUnreadable), which an
    // inferred type would make recursive.
    private val state: LauncherState by lazy {
        LauncherState(
            io = ::submitIo,
            main = { task -> onUi { task.run() } },
            loadConfig = { store.load() },
            enumerateApps = ::loadApps,
            serialize = ConfigJson::serialize,
            writeConfig = { payload -> store.write(payload, rotateBackup = true) },
            onRefreshed = { configReplaced ->
                // The rename dialog read the OLD config when it opened and writes back on
                // OK; left up over a wholesale replace it would persist stale fields into
                // the config that just arrived.
                if (configReplaced) {
                    renameDialog?.dismiss()
                    // Its counts were for the config just replaced.
                    commandDialog?.dismiss()
                }
                // The file is there but unreadable: say so once per episode — the drawer works,
                // edits are off, and ~load / ~restore are the ways out. Every resume retries.
                if (!state.configUnreadable) unreadableShown = false
                else if (!unreadableShown && started) {
                    unreadableShown = true
                    toast(getString(R.string.toast_config_unreadable), Toast.LENGTH_LONG)
                }
                refilter()
            },
            isFreshInstall = { store.isFresh() },
            defaultFavoriteCandidates = ::defaultFavoriteCandidates,
            setAsideConfig = ::setAsideUnreadableConfig,
            initialRecents = app.recentKeys,
            onRecentsChanged = { app.recentKeys = it },
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
    // rotation from recreating us; a language, font-size or display-size change still does.
    private var renameDialog: AlertDialog? = null
    // The "~cleanup" confirmation, tracked for the same reason.
    private var commandDialog: AlertDialog? = null
    // Bumped whenever the page turns away (closeDrawer, HOME, onStop): a "~cleanup" check must
    // still match the value it started under to show its dialog (see cleanup).
    private var cleanupTicket = 0

    // The running "~" command, one at a time (CommandGate, hard rule 15), and the busy layer
    // that covers the window while it runs (showBusy). Both belong to this instance: a
    // recreated activity starts free — see CommandGate for why that is enough.
    private val gate = CommandGate(onBusyChanged = ::showBusy)
    private var busyLayer: Dialog? = null
    // True while this class itself writes the prompt (setPrompt): the busy layer's input filter
    // lets those through and drops only what the keyboard types.
    private var promptByCode = false
    // The busy layer's delayed wheel and way out, removed with it.
    private var busyTimers: List<Pair<View, Runnable>> = emptyList()
    // The busy layer's way-out hint, while the layer is up (see updateBusyHint).
    private var busyHint: TextView? = null
    // The run the layer was last shown for, and since when (uptime): what the wheel's and the
    // way out's delays count from (see showBusy).
    private var busyRun: CommandGate.Ticket? = null
    private var busySince = 0L
    // A run whose layer the user stepped out of (Back, once the way out was open): it runs on
    // without one, and onStart does not put it back.
    private var waived: CommandGate.Ticket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Under enforced edge-to-edge (Android 16) the window never fits the system bars and
        // the system does NOT push our content up for the keyboard on its own — the IME just
        // covers the prompt. So we own the insets and pad for the IME ourselves (below).
        // No grey scrim behind 3-button navigation: the wallpaper runs to the bottom edge, and
        // the bar icons follow the wallpaper's palette (applyPalette).
        window.isNavigationBarContrastEnforced = false

        // The front page is a quiet text home screen, and the IME belongs to the drawer only. The manifest asks for stateHidden; the
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
            // Arrow keys or Tab can move a hardware keyboard's focus into the list, which has no
            // type-to-filter: send what is typed there back to the command line.
            setOnKeyListener { _, _, event ->
                val c = if (event.action == KeyEvent.ACTION_DOWN) printable(event) else null
                if (c != null) typeIntoPrompt(c)
                c != null
            }
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
                    is TapAction.ToggleHidden -> if (editable()) {
                        state.toggleHidden(action.entry.key)
                        afterToggle()
                    }
                    is TapAction.ToggleFavorite -> if (editable()) {
                        state.toggleFavorite(action.entry.key)
                        afterToggle()
                    }
                    is TapAction.ReorderPick -> if (editable()) reorderTap(action.entry.key)
                    is TapAction.ToggleTag -> if (editable()) toggleTagOn(action.entry.key)
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
        // The list and, in its place while it is empty, a hint that says why (see emptyHint) —
        // ListView.emptyView swaps the two. Outlined like the rows; a polite live region so a
        // screen reader hears the hint when it appears.
        emptyView = OutlinedTextView(this).apply {
            textSize = 18f
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(0, 0, 0, 10.dp())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        listView.emptyView = emptyView
        val listFrame = FrameLayout(this).apply {
            addView(listView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        drawer.addView(listFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // A clear button for the prompt, sitting at the end of the command line. Shown
        // only while there IS something to erase (see the TextWatcher), it wipes the line
        // back to the plain drawer without touching the keyboard — so the user can retype right
        // away, unlike Back, which also drops the IME.
        clearButton = TextView(this).apply {
            text = "×"  // × — a clear glyph that sits right in the monospace line
            typeface = Typeface.MONOSPACE
            textSize = 24f
            setTextColor(palette.listDim)
            legibleOnWallpaper()
            gravity = Gravity.CENTER
            minWidth = 48.dp()  // a comfortable tap target next to the narrow glyph
            contentDescription = getString(R.string.clear_prompt)
            isFocusable = false  // keep focus (and the IME) on the prompt when tapped
            visibility = View.GONE
            setOnClickListener {
                prompt.setText("")  // TextWatcher -> applyFilter resets to NORMAL
                // Clearing is for retyping — so the keyboard belongs up, also after the recents
                // view, which opened without it.
                showKeyboard()
            }
        }

        // The prompt: a bare monospace command line at the bottom.
        prompt = EditText(this).apply {
            hint = getString(R.string.hint_search)
            setHintTextColor(palette.listDim)
            setTextColor(palette.listText)
            legibleOnWallpaper()
            typeface = Typeface.MONOSPACE
            textSize = 20f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            // The busy layer leaves the soft keyboard up (FLAG_ALT_FOCUSABLE_IM, see showBusy) and
            // normally takes its keys itself; the keyboard stays bound to this view, though, so
            // whatever it might still send while the layer is up is dropped here — a running
            // command takes no input (hard rule 15). Not isEnabled = false —
            // that takes the focus and with it the keyboard, the very flicker the flag avoids.
            filters = arrayOf(InputFilter { _, _, _, dest, dstart, dend ->
                if (busyLayer != null && !promptByCode) dest.subSequence(dstart, dend) else null
            })
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
                // The keyboard's Enter while the busy layer is up (see the filter above): swallowed.
                if (busyLayer != null) return@setOnEditorActionListener true
                // Act once per Enter: a key Enter calls this for its down, its repeats and its
                // up, each with the GO action — only the first down acts (LauncherLogic.enterEdge).
                val imeAction = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                val key = event?.let {
                    EnterKey(
                        down = it.action == KeyEvent.ACTION_DOWN,
                        repeatCount = it.repeatCount,
                        isEnterKey = it.keyCode == KeyEvent.KEYCODE_ENTER || it.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER,
                    )
                }
                when (LauncherLogic.enterEdge(imeAction, key)) {
                    EnterEdge.PASS -> return@setOnEditorActionListener false
                    EnterEdge.CONSUME -> return@setOnEditorActionListener true
                    EnterEdge.ACT -> Unit
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
            // 48dp: the minimum touch target — the bare EditText (no background, no padding)
            // and the × were only ~30dp tall.
            minimumHeight = 48.dp()
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

        // The window root: both pages stacked, home underneath, plus the gestures that turn
        // between them (see SwipeFrame): a page drag that follows the finger once the content
        // under it is at its end, and Kolibri's flick for the recents and as the open fallback.
        // The decisions are the pure SwipeGesture.
        root = SwipeFrame(this).apply {
            addView(home, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
            claims = claims@{ dir, downY ->
                // A long-press that opened the rename dialog can carry on into a drag with the
                // finger still down; that must not turn the page under the dialog — nor under
                // the busy layer of a running "~" command.
                if (renameDialog != null || commandDialog != null || busyLayer != null) return@claims false
                // Read live: cheap, and never stale (a display-size change recreates the
                // activity anyway — "density" is not in configChanges).
                val topExclusion = SwipeGesture.TOP_EXCLUSION_DP * resources.displayMetrics.density
                SwipeGesture.claims(drawerOpen, dir, downY, topExclusion)
            }
            // While a page turn is still running, the outgoing page is VISIBLE (just fading)
            // and on top for hit-testing: a quick tap would land on a row the user can no
            // longer really see. Swallow taps for that moment; swipes are still tracked.
            swallowTaps = { SystemClock.uptimeMillis() < pageTurnUntil }
            // Kolibri's home flicks: up = the app drawer, down = the recently used apps (here:
            // the drawer opened on the "?" recents view). Up is normally a drag (below); the
            // flick opens the drawer when the favorites could still scroll and no drag started.
            onSwipe = { dir ->
                when {
                    dir == SwipeDir.UP && !drawerOpen -> openDrawer()
                    dir == SwipeDir.DOWN && !drawerOpen -> openDrawer(RECENTS_PROMPT)
                }
            }
            // The page follows the finger: pull home up to open the drawer, pull the drawer
            // down to close it — once the content under the finger is at its end that way.
            isDrawerOpen = { this@MainActivity.drawerOpen }
            canDrag = canDrag@{ dir ->
                if (renameDialog != null || commandDialog != null || busyLayer != null) return@canDrag false
                when (dir) {
                    SwipeDir.UP -> !homePage.scroller.canScrollVertically(1)
                    SwipeDir.DOWN -> !listView.canScrollVertically(-1)
                }
            }
            pageHeight = ::pageHeight
            onDragStart = ::beginPageDrag
            onDrag = ::dragPage
            onDragEnd = ::endPageDrag
        }
        setContentView(root)

        // Back on a HOME launcher must not finish the activity — we're the home
        // screen, so the platform's default callback (routed here on targetSdk 36
        // whether or not we opt in) would just finish us and bounce straight back
        // via HOME. Intercept it. On the home page it is a deliberate no-op. In the
        // drawer, a non-empty prompt (a search or an edit mode) clears back to the plain
        // drawer first; Back on an empty prompt turns the page back to home (which also
        // drops the keyboard). Lives for the whole activity.
        //
        // Predictive back: a back gesture that will close the drawer previews it — the drawer
        // follows the gesture down a little (BACK_PREVIEW_FRACTION of the page) over a home
        // fading in, the same pages a drag moves. Released, the close runs on from there;
        // cancelled, the drawer settles back. 3-button Back skips the preview and just closes.
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : OnBackAnimationCallback {
                // The drawer offset the preview started from; null while no preview runs.
                private var previewFrom: Float? = null

                // The bare recents view ("?") is what a swipe down on home opened, so Back
                // leaves it the way it came — straight home, like closing Kolibri's recents
                // list. Anything typed after it ("?gm") is a search like any other: Back clears
                // it first.
                private fun closes(): Boolean {
                    val text = prompt.text?.toString().orEmpty()
                    return drawerOpen && (text.isEmpty() || text == RECENTS_PROMPT)
                }

                override fun onBackStarted(backEvent: BackEvent) {
                    previewFrom = if (closes()) beginPageDrag(SwipeDir.DOWN) else null
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    val from = previewFrom ?: return
                    if (!drawerOpen) return
                    dragPage(SwipeDir.DOWN, from + backEvent.progress * pageHeight() * BACK_PREVIEW_FRACTION)
                    // The drag's own fade only starts once the drawer is two thirds down — far
                    // past the preview. Home shows through with the gesture instead, faintly:
                    // the drawer still covers most of the page.
                    home.alpha = backEvent.progress * BACK_PREVIEW_HOME_ALPHA
                }

                override fun onBackCancelled() {
                    // Only if the drawer is still the page in front: HOME or the screen going
                    // off may have closed it meanwhile.
                    if (previewFrom != null && drawerOpen) settle(open = true)
                    previewFrom = null
                }

                override fun onBackInvoked() {
                    val previewed = previewFrom != null
                    previewFrom = null
                    if (!drawerOpen) return
                    if (closes()) closeDrawer()
                    else {
                        // The prompt changed during the gesture (a hardware key): the preview
                        // showed a close that is not happening — put the drawer back first.
                        if (previewed) settle(open = true)
                        prompt.setText("")   // TextWatcher -> applyFilter resets mode to NORMAL
                    }
                }
            },
        )

        // Live updates for installs/uninstalls and profile changes (see appsChanged).
        watchApps()

        // A cold start draws the favorites from the cache at once (HomeCache) — queued on the IO
        // thread ahead of the first load, so it lands long before the full enumeration does —
        // and only while the real list has not arrived yet.
        submitIo {
            val cached = HomeCache.read(filesDir)
            onUi {
                if (cached.isNotEmpty() && !state.appsLoaded) {
                    cachedFavorites = cached.map { it.key to it.label }
                    homePage.render(cached, loaded = true)
                }
            }
        }

        // Text colours for the wallpaper behind, and live when it changes (see applyPalette).
        // Asking the system is a binder call that may extract the colours from the bitmap
        // right then (the first start after boot), so it runs on the IO thread; until it
        // answers, the last palette this process saw (a recreated activity) or the dark one.
        applyPalette(app.palette ?: HomeStyle.palette(lightWallpaper = false))
        val asked = ++paletteGeneration
        submitIo {
            val light = wallpaperIsLight()
            onUi {
                // A listener call since the question is newer than this answer.
                val answer = HomeStyle.palette(light)
                if (asked == paletteGeneration && answer != palette) applyPalette(answer)
            }
        }
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperColors, Handler(Looper.getMainLooper()))
    }

    /** Live battery level on the home page while the launcher is visible. */
    override fun onStart() {
        super.onStart()
        started = true
        homePage.startBattery()
        // A command still running from before onStop: its busy layer went with the stop
        // (showBusy leaves out a run the user stepped out of).
        if (gate.busy) showBusy(true)
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
        // A drag still holding the page must not act on it after this turn (see abortDrag).
        root.abortDrag()
        showStatusBar(true)
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        home.animate().cancel()
        drawer.animate().cancel()
        drawer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val fromHidden = drawer.visibility != View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.alpha = 1f
        // Kolibri's page turn: the drawer slides up over the full height of the window (its
        // slide_in_up, 100%p) while home fades out underneath. The parent's height, not the
        // drawer's: a drawer that was GONE until now has not been measured yet. A drawer that
        // is already showing — pulled up by a drag, or caught mid-close — slides on from
        // where it is instead of jumping to the bottom first.
        if (fromHidden) drawer.translationY = pageHeight()
        drawer.requestApplyInsets()
        if (initialPrompt.isNotEmpty()) setPrompt(initialPrompt)
        else if (shown.isNotEmpty()) listView.setSelection(0)
        pageTurnUntil = SystemClock.uptimeMillis() + PAGE_TURN_MS
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        drawer.animate().translationY(0f).setDuration(DRAWER_SLIDE_MS).withLayer()
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
        home.animate().alpha(0f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).withEndAction {
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
        if (!drawerOpen) {
            // Home is in front, but a finger may be pulling the drawer up: HOME or the screen
            // going off ends that drag here, or its release would still open the drawer.
            if (root.isDragging) {
                root.abortDrag()
                if (animate) settle(open = false) else showHomeNow()
            }
            return
        }
        drawerOpen = false
        cleanupTicket++
        // HOME, Back or the screen going off while a finger drags the drawer: that drag is over
        // and must not settle the drawer back open over a home that is now in front.
        root.abortDrag()
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        showStatusBar(false)
        hideKeyboard()
        drawer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        prompt.clearFocus()
        if (prompt.text?.isNotEmpty() == true) setPrompt("")
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        if (!animate) {
            showHomeNow()
            return
        }
        // Kolibri's way back: the drawer slides down off the screen (slide_out_down) while home
        // fades in (fade_in).
        pageTurnUntil = SystemClock.uptimeMillis() + PAGE_TURN_MS
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // a drag's layer; withLayer takes over
        home.animate().alpha(1f).setDuration(HOME_FADE_MS).withLayer()
            .setInterpolator(AccelerateInterpolator()).start()
        drawer.animate().translationY(pageHeight()).setDuration(DRAWER_SLIDE_MS).withLayer()
            .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
    }

    /** Home in front at once, the drawer gone — no animation. */
    private fun showHomeNow() {
        home.animate().cancel()
        drawer.animate().cancel()
        pageTurnUntil = 0L
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        home.visibility = View.VISIBLE
        home.alpha = 1f
        drawer.translationY = 0f
        drawer.visibility = View.GONE
    }

    /**
     * The status bar belongs to the drawer only: the home page is a quiet full screen, the
     * drawer (a working list with a command line) shows it. Hidden, a swipe from the top edge
     * still brings it — and the notification shade — in transiently, so nothing is out of reach.
     */
    private fun showStatusBar(show: Boolean) {
        // The busy layer too, while it is up: it is the focused window then, and the focused
        // window's request is the one the system follows.
        for (w in listOfNotNull(window, busyLayer?.window)) {
            val controller = w.insetsController ?: continue
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (show) controller.show(WindowInsets.Type.statusBars())
            else controller.hide(WindowInsets.Type.statusBars())
        }
    }

    /**
     * A page drag takes over (see SwipeFrame): stop whatever turn is running and put both pages
     * where the drag starts from. Returns the offset already travelled — non-zero when the
     * drawer is caught mid-turn, so the page continues from where it is instead of jumping.
     */
    private fun beginPageDrag(dir: SwipeDir): Float {
        home.animate().cancel()
        drawer.animate().cancel()
        home.visibility = View.VISIBLE
        // Home fades with every move of the drag: a full-screen view group at partial alpha is
        // composed offscreen each frame unless it has a layer — cache it once in a hardware
        // layer for the drag (open/close/settle drop it again).
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val height = pageHeight()
        return if (dir == SwipeDir.UP) {
            // Opening from home: the drawer comes up from below, focus still blocked — it is not
            // the page in front until the drag commits.
            if (drawer.visibility != View.VISIBLE) drawer.translationY = height
            drawer.visibility = View.VISIBLE
            drawer.alpha = 1f
            height - drawer.translationY
        } else {
            drawer.translationY
        }
    }

    /** The drawer follows the finger; home fades out over the first third of its travel. */
    private fun dragPage(dir: SwipeDir, offset: Float) {
        if ((dir == SwipeDir.DOWN) != drawerOpen) return
        val height = pageHeight()
        val open = if (dir == SwipeDir.UP) offset else height - offset  // how far the drawer is up
        drawer.translationY = height - open
        home.alpha = SwipeGesture.homeAlpha(open, height)
    }

    /** The finger lifted: finish the turn from where the page is, or settle back. */
    private fun endPageDrag(dir: SwipeDir, end: DragEnd) {
        // Belt and braces next to abortDrag: a drag only ever ends on the page it grabbed.
        if ((dir == SwipeDir.DOWN) != drawerOpen) return
        when {
            dir == SwipeDir.UP && end == DragEnd.COMMIT -> openDrawer()
            dir == SwipeDir.DOWN && end == DragEnd.COMMIT -> closeDrawer()
            dir == SwipeDir.UP -> settle(open = false)
            else -> settle(open = true)
        }
    }

    /**
     * A drag that did not commit falls back to the page it started on (Unity's settle-back:
     * 220 ms, decelerating). Nothing else changes — the drawer was never opened by an opening
     * drag that fell back, nor closed by a closing one.
     */
    private fun settle(open: Boolean) {
        pageTurnUntil = SystemClock.uptimeMillis() + DRAG_SETTLE_MS
        home.setLayerType(View.LAYER_TYPE_NONE, null)  // the drag's layer; withLayer takes over
        drawer.animate().translationY(if (open) 0f else pageHeight()).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
        home.animate().alpha(if (open) 0f else 1f).setDuration(DRAG_SETTLE_MS).withLayer()
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                if (drawerOpen) home.visibility = View.INVISIBLE
            }.start()
    }

    /** The full height a page slides over: the window root's, falling back to the display's
     *  before the first layout. */
    private fun pageHeight(): Float =
        ((drawer.parent as? View)?.height?.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels).toFloat()

    override fun onResume() {
        super.onResume()
        resumed = true
        // Re-enumerate only when something changed since the last load — the LauncherApps
        // callback and the profile broadcasts report every install, removal, update and
        // profile change (appsMaybeChanged) — or the last load is old enough that a missed
        // change would matter. Not on every return home: that was a full enumeration of every
        // profile (a binder call each, a label per app) competing with the return animation.
        val now = SystemClock.uptimeMillis()
        // Also while the launcher is not current (state.needsRefresh): a cold start whose load
        // has not landed, or a load that failed or was dropped — no package event will come
        // to retry it.
        if (appsDirty || state.needsRefresh || now - lastRefresh > REFRESH_STALE_MS) {
            appsDirty = false
            lastRefresh = now
            state.refresh()
        }
        // Re-assert on every return: the bar's visibility is a request on our window, and
        // coming back from an app that showed it must not leave it up on the home page.
        showStatusBar(drawerOpen)
        // Only the drawer is keyboard-first. It is normally closed by the time we come
        // back (onStop turns the page home), but a resume WITHOUT a stop — a dialog or a
        // translucent activity on top — returns to an open drawer, and there the
        // keyboard belongs up again.
        // The recents view ("?", from a swipe down) deliberately opens without it.
        if (drawerOpen && prompt.text?.toString() != RECENTS_PROMPT) showKeyboard()
    }

    /** Not in front any more: package changes from now on only mark the list dirty (see
     *  appsMaybeChanged). The page stays as it is — a dialog or the shade on top pauses
     *  without leaving; [onStop] turns it home. */
    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /**
     * The installed apps may have changed (see appsChanged / profilesChanged). In front: reload
     * now. Behind another app: only note it — a burst of updates (a store updating 20 apps)
     * would otherwise run 20 full enumerations for a page nobody sees; onResume reloads once.
     */
    private fun appsMaybeChanged() {
        if (resumed) {
            lastRefresh = SystemClock.uptimeMillis()
            state.refresh()
        } else {
            appsDirty = true
        }
    }

    /**
     * Leaving the launcher (an app was started, the screen went off, the user went to
     * recents) turns the page back home, instantly — coming back to the launcher should
     * land on the home screen, like any launcher, not on a half-typed search.
     */
    override fun onStop() {
        started = false
        cleanupTicket++
        // Also a dialog opened from the home page, which closeDrawer does not touch.
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        // The busy layer only: the command itself runs on, and onStart puts the layer back if
        // it is still running then (and the user did not step out of it).
        dismissBusyLayer()
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
        commandDialog?.dismiss()
        commandDialog = null
        dismissBusyLayer()
        // No executor shutdown: the IO thread is the process's (VersoApp.io), and work this
        // instance queued — a save above all — must still run for the next one. What it
        // would hand back to this instance's main thread is dropped by onUi instead.
        unwatchApps()
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperColors)
        super.onDestroy()
    }

    /**
     * Run [block] on the main thread for THIS instance — or not at all once it is destroyed.
     * IO work outlives the instance that queued it (the thread is the process's); its
     * hand-back must not then act on a dead activity: adopt a restore into a state nobody
     * shows, toast from a gone window, re-render views that are no longer attached.
     */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed) block() }

    /**
     * [task] behind a last-resort net. Hard rule 2 — a HOME app must never throw — is kept by
     * every IO function catching its own errors; this is the backstop for the one that someday
     * does not: an exception escaping on the executor (or the SAF read thread) reaches the
     * thread's uncaught handler and kills the process on every start. Logged, not swallowed
     * silently.
     */
    private fun guarded(what: String, task: Runnable) = Runnable {
        try {
            task.run()
        } catch (e: RuntimeException) {
            Log.w("Verso", "$what failed", e)
        }
    }

    /**
     * Submit disk work to [ioExecutor]. The executor is the process's and never shut down, so
     * a refusal is not expected; the check and the catch keep a future change that does shut
     * it down from crashing the HOME app — a refused task is reported as false, never thrown.
     */
    private fun submitIo(task: Runnable): Boolean {
        if (ioExecutor.isShutdown) return false
        return try {
            ioExecutor.execute(guarded("IO task", task))
            true
        } catch (e: RejectedExecutionException) {
            Log.w("Verso", "IO task rejected (executor shutting down)", e)
            false
        }
    }

    /**
     * A hardware keyboard on the home page: typing opens the drawer with that character in
     * the command line — the keyboard's way to what a swipe up does. Search does the same
     * without a character. Keys the focused view takes (Enter on a favorite) never get here.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!drawerOpen) {
            val c = printable(event)
            if (c != null || keyCode == KeyEvent.KEYCODE_SEARCH) {
                openDrawer()
                if (c != null) typeIntoPrompt(c)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** The character a plain key press types, or null — see [LauncherLogic.typedChar]. */
    private fun printable(event: KeyEvent): String? = LauncherLogic.typedChar(
        unicodeChar = event.unicodeChar,
        chord = event.isCtrlPressed || event.isMetaPressed ||
            event.metaState and KeyEvent.META_ALT_LEFT_ON != 0,
    )

    /** Append [c] to the command line and put the typing back there. */
    private fun typeIntoPrompt(c: String) {
        prompt.requestFocus()
        prompt.append(c)
        prompt.setSelection(prompt.length())
    }

    /** HOME pressed while already home: turn back to the home page, scrolled to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)  // keep getIntent() in sync with the latest launch intent
        // HOME is a "back to a clean home screen" gesture, so a rename dialog left open has
        // no business surviving it: confirming it afterwards would apply a rename the user
        // has visually left behind.
        renameDialog?.dismiss()
        commandDialog?.dismiss()
        cleanupTicket++
        // HOME abandons what Back would (the "~restore-saf" read): back to a clean home screen.
        if (gate.cancel()) toast(getString(R.string.toast_command_cancelled))
        // Also clears the prompt (closeDrawer always does), so the next swipe up opens on
        // the plain drawer rather than whatever mode was last typed.
        closeDrawer()
        homePage.scrollToTop()
    }

    // ---- config -------------------------------------------------------------

    /** The stores' logger: android.util.Log, with or without a cause. */
    private fun logWarning(msg: String, e: Throwable?) {
        if (e != null) Log.w("Verso", msg, e) else Log.w("Verso", msg)
    }

    /**
     * The verso.json file layer. Everything Android-specific it needs is injected here:
     * filesDir, android.util.Log and the directory fsync. Loading, rotating and publishing
     * itself lives in [ConfigStore], where it is unit-tested away from the Activity.
     */
    private val store by lazy {
        ConfigStore(
            dir = filesDir,
            log = ::logWarning,
            syncDir = ::fsyncDir,
        )
    }

    /**
     * The shared-storage backup layer (Download/[BackupFiles.DIR]). The MediaStore mechanics live
     * in [BackupStore]; the Activity keeps only the orchestration that needs it — backup,
     * restoreConfig and the "~restore-saf" picker.
     */
    private val backupStore by lazy {
        BackupStore(
            resolver = contentResolver,
            subDir = BackupFiles.DIR,
            log = ::logWarning,
        )
    }

    /**
     * Run a one-shot "~" command — one at a time, behind the busy layer (hard rule 15): the
     * command holds the [gate] until its last step is over, and while it does, nothing the user
     * does can come in between (see CommandGate). The commands do their work off-screen (or in
     * another app entirely), so each one reports back with a toast, on its real result; a save
     * you cannot see happen is a save you do not trust.
     *
     * Behind the busy layer no second start can come from the UI; one can once the user stepped
     * out of a stalled run (showBusy), and then this refusal is what keeps it one at a time.
     */
    private fun runCommand(command: Command) {
        val accepted = gate.start(command) { t ->
            when (command) {
                Command.RELOAD -> reload(t)
                Command.SAVE -> save(t)
                Command.BACKUP -> backup(t)
                Command.RESTORE -> restoreConfig(t)
                // Only the picker: it is another activity, and Android may kill this process
                // while it is up, so no command can span it. Its answer starts a run of its own
                // (onActivityResult).
                Command.RESTORE_SAF -> pickRestoreFile()
                Command.CLEANUP -> cleanup(t)
                Command.RESET -> reset(t)
            }
        }
        if (!accepted) toast(getString(R.string.toast_busy))
    }

    /**
     * One IO step of the running command [t]: [work] on the IO thread, then [then] with its result
     * back on the main thread — still as a step of [t], so whatever [then] starts in turn (the next
     * IO step, a LauncherState call that reports back) keeps the command running, and the command
     * ends once nothing does (CommandGate). [refused] instead, at once, when the IO thread refused
     * the task: nothing ran. A [work] that THROWS an Exception is a bug — every IO function here
     * catches its own errors (hard rule 2) — but it must not leave the launcher locked behind the
     * busy layer, so it is logged and reported, and the step ends like any other. An Error is not
     * caught: it ends the process, and the gate with it.
     *
     * A command abandoned meanwhile ([CommandGate.cancel]) gets nothing back here; a destroyed
     * instance gets nothing at all (onUi), and its gate is gone with it.
     */
    // The result type is T, not R: R is the resource class, which a type parameter would shadow.
    private fun <T> commandIo(
        t: CommandGate.Ticket,
        work: () -> T,
        refused: () -> Unit,
        then: (T) -> Unit,
    ) {
        if (!gate.hold(t)) return
        val accepted = submitIo {
            val result = try {
                Result.success(work())
            } catch (e: Exception) {
                Log.w("Verso", "~${t.command.name.lowercase()} failed", e)
                Result.failure(e)
            }
            onUi {
                gate.finish(t) {
                    result.fold(onSuccess = then, onFailure = { toast(getString(R.string.toast_command_error)) })
                }
            }
        }
        if (!accepted) {
            gate.release(t)
            refused()
        }
    }

    /**
     * "~load": read verso.json again and adopt it ([LauncherState.reload]). Confirmed once the
     * file is adopted: if nothing changed on disk, nothing visible changes either, and a silent
     * command looks like a dead one. Every other outcome says what happened instead — above all
     * an unreadable file, which leaves the config in memory untouched. While the config is
     * unreadable, this is the way to try again at once.
     */
    private fun reload(t: CommandGate.Ticket) {
        if (!gate.hold(t)) return
        state.reload { outcome ->
            gate.finish(t) {
                if (outcome == ReloadOutcome.Adopted) {
                    // Normally none can be open (the busy layer), but one may be after the user
                    // stepped out of a stalled run (showBusy): opened against the old config, it
                    // would write its stale name and tags over the file just read.
                    renameDialog?.dismiss()
                    commandDialog?.dismiss()
                }
                when (outcome) {
                    ReloadOutcome.Adopted -> {
                        // Drawn now, with the toast: the names are already applied (relabel), and
                        // the refresh's hand-off would come later — until then old rows could be
                        // tapped.
                        refilter()
                        toast(getString(R.string.toast_reloaded))
                    }
                    ReloadOutcome.Unreadable -> toast(getString(R.string.toast_reload_unreadable), Toast.LENGTH_LONG)
                    ReloadOutcome.ChangedMeanwhile -> toast(getString(R.string.toast_reload_changed))
                    ReloadOutcome.NotLoaded -> toast(getString(R.string.toast_not_loaded))
                    ReloadOutcome.Failed -> toast(getString(R.string.toast_command_failed))
                }
            }
        }
    }

    /**
     * Edits only on a loaded config (hard rule 5): before it, an edit could not be saved — and
     * while the config is unreadable, an edit on the empty default in memory must not happen at
     * all. Says why when it refuses.
     */
    private fun editable(): Boolean {
        if (state.configLoaded) return true
        toastNotLoaded()
        return false
    }

    /** Why the config cannot be used yet: still loading, or there but unreadable. */
    private fun toastNotLoaded() {
        if (state.configUnreadable) toast(getString(R.string.toast_config_unreadable), Toast.LENGTH_LONG)
        else toast(getString(R.string.toast_not_loaded))
    }

    /**
     * "~save": toast from the write's own callback, on the real result — NOT straight after
     * submitting. save only queues the write; announcing success before the write is even
     * attempted is exactly the "save you cannot see happen" this command exists to rule out.
     * Before the first load there is nothing of the user's in memory to write — say so rather
     * than report a failed write.
     */
    private fun save(t: CommandGate.Ticket) {
        if (!state.configLoaded) {
            toastNotLoaded()
            return
        }
        if (!gate.hold(t)) return
        state.save { ok ->
            gate.finish(t) {
                toast(getString(if (ok) R.string.toast_saved else R.string.toast_save_failed))
            }
        }
    }

    /**
     * The busy layer of a running "~" command ([gate]'s onBusyChanged): a see-through dialog
     * over the whole window. Being a window of its own on top, it takes every touch and every
     * key — no tap, no typing, no page turn, no second command reaches the activity while it is
     * up — which is what lets a command run without guarding against the user at each step.
     *
     * The wheel only shows after [BUSY_WHEEL_DELAY_MS]: most commands are done long before, and a
     * wheel flashing up for a moment would only be noise. Back abandons the command if it allows
     * that right now (the "~restore-saf" read, see readPicked); after [BUSY_ESCAPE_MS] it takes the
     * layer down while the run goes on (the way out, below); before that it is swallowed.
     *
     * Only while the activity is started: onStop takes the layer down (a dialog must not outlive
     * the visible activity's window), onStart puts it back for a command still running — unless
     * the user stepped out of that run.
     */
    private fun showBusy(busy: Boolean) {
        if (!busy) {
            waived = null
            takeBusyLayerDown()
            return
        }
        val running = gate.current ?: return
        if (busyLayer != null || !started || running === waived) return
        // Timed from the run's first layer, not each layer: one re-shown by onStart picks up
        // where the run is (wheel at once, the way out as soon as it is due). A run that starts
        // while stopped (a SAF answer before onStart) is timed from that first layer.
        if (running !== busyRun) {
            busyRun = running
            busySince = SystemClock.uptimeMillis()
        }
        val elapsed = SystemClock.uptimeMillis() - busySince
        val wheel = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.listText)
            contentDescription = getString(R.string.a11y_busy)
            visibility = View.INVISIBLE
        }
        val hint = TextView(this).apply {
            setTextColor(palette.listText)
            typeface = listTypeface
            gravity = Gravity.CENTER
            legibleOnWallpaper()
            // A long German line must not touch the screen edge.
            setPadding(dp(24), 0, dp(24), 0)
            // Spoken when it appears: the way out is no use to a TalkBack user who is never told.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.INVISIBLE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Announced when the layer appears, so a screen-reader user knows why taps do nothing.
            accessibilityPaneTitle = getString(R.string.a11y_busy)
            addView(wheel, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(hint, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
        }
        val layer = Dialog(this, R.style.VersoBusyLayer)
        layer.setContentView(content)
        layer.setTitle(getString(R.string.a11y_busy))
        // Not cancelable: Back, a tap outside — nothing takes the layer down but the command's
        // end, Back on a step that may be abandoned, or Back once the way out is open (below).
        layer.setCancelable(false)
        layer.setCanceledOnTouchOutside(false)
        // See-through means see-through: no dim flashing over the page with every command.
        layer.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Focused but no input-method target: without this the system hides the drawer's keyboard
        // the moment the layer takes the focus and takeBusyLayerDown shows it again — a jump of
        // the list with every command, however short. A window with this flag sits above the
        // input method, so the keyboard stays visible but its keys land on the layer (seen on
        // One UI, API 36). Should a device order it differently, the keyboard is still bound to
        // the prompt, whose input filter and Enter handler drop what it sends while the layer is up.
        layer.window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        busyLayer = layer
        // The layer is the focused window once shown, and the system follows the focused window's
        // requests for the bars: ask the way the page under it does — the status bar hidden on
        // home (hard rule 12), the bar icons in the wallpaper's contrast — BEFORE show(), so the
        // window is added asking for them and nothing flashes.
        showStatusBar(drawerOpen)
        applyBarAppearance(layer.window)
        // A finger dragging the page when the layer comes up (a hardware Enter mid-drag) must not
        // turn it underneath: that drag is over, and the page settles back where it was, as
        // closeDrawer does after an abort.
        if (root.isDragging) {
            root.abortDrag()
            settle(open = drawerOpen)
        }
        layer.show()
        // The way out once a run takes too long. Every step but the "~restore-saf" read waits on
        // the one IO thread, whose calls into system services (MediaStore above all) have no
        // timeout; abandoning such a step is not safe (a restore's config may already be written),
        // but holding the HOME screen hostage to it is worse. So the layer steps aside — the run
        // goes on, and no other command starts until it ends (gate.start refuses) — and the user
        // has the launcher back. Edits they make meanwhile interleave with the run as they did
        // before the gate. LauncherState's own guards (configEpoch, the cleanup's Superseded) keep
        // a "~load" or a cleanup from overwriting them, which is why they stay. A restore that
        // lands afterwards does replace them, and they are in neither its undo point nor its
        // config: the restore was asked for, and its config is already on disk (hard rule 7).
        var escapable = elapsed >= BUSY_ESCAPE_MS
        layer.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            when {
                gate.cancel() -> toast(getString(R.string.toast_command_cancelled))
                escapable -> {
                    waived = gate.current
                    takeBusyLayerDown()
                    toast(getString(R.string.toast_busy_waived), Toast.LENGTH_LONG)
                }
            }
        }
        val showWheel = Runnable { wheel.visibility = View.VISIBLE }
        val openWayOut = Runnable {
            escapable = true
            hint.visibility = View.VISIBLE
            updateBusyHint()
        }
        wheel.postDelayed(showWheel, (BUSY_WHEEL_DELAY_MS - elapsed).coerceAtLeast(0))
        hint.postDelayed(openWayOut, (BUSY_ESCAPE_MS - elapsed).coerceAtLeast(0))
        busyTimers = listOf<Pair<View, Runnable>>(wheel to showWheel, hint to openWayOut)
        busyHint = hint
    }

    /**
     * The way-out hint says what Back does NOW: a step Back may abandon (the "~restore-saf" read)
     * is cancelled, anything else is stepped out of. Called when the hint appears and when the
     * read ends (readPicked), so it never promises a cancel the run no longer allows.
     */
    private fun updateBusyHint() {
        val hint = busyHint ?: return
        if (hint.visibility != View.VISIBLE) return
        hint.text = getString(
            if (gate.current?.cancelable == true) R.string.busy_cancel_hint else R.string.busy_escape_hint,
        )
    }

    /** Take the busy layer down, and make sure the drawer has its keyboard. */
    private fun takeBusyLayerDown() {
        val wasUp = busyLayer != null
        dismissBusyLayer()
        // The keyboard normally stayed up under the layer (FLAG_ALT_FOCUSABLE_IM, see showBusy),
        // and this changes nothing. Should a device have hidden it anyway, give it back the way
        // onResume does — once the focus is back (posted), and not under a dialog the command
        // left up (the cleanup and reset confirmations).
        if (wasUp) prompt.post {
            if (started && drawerOpen && busyLayer == null && renameDialog == null &&
                commandDialog == null && prompt.text?.toString() != RECENTS_PROMPT
            ) showKeyboard()
        }
    }

    private fun dismissBusyLayer() {
        // The layer's pending timers go with it (harmless otherwise, but they need not linger).
        for ((view, timer) in busyTimers) view.removeCallbacks(timer)
        busyTimers = emptyList()
        busyHint = null
        busyLayer?.dismiss()
        busyLayer = null
    }

    /**
     * "~backup": export the config to Download/[BackupFiles.DIR] as a backup of [BackupFiles.BACKUP_BASE],
     * replacing the previous one only once the new one is published (see [BackupStore.write]).
     * There is one current backup and one undo point, each named after the moment it was
     * taken (see BackupFiles) — no pile to sift through, which is what lets [restoreConfig]
     * work without a file picker: it reads the newest one.
     *
     * Different from the .bak mirror [ConfigStore] keeps: that one rotates away after two
     * saves and lives in filesDir, so it dies with the app. This one is an ordinary file in
     * shared storage that can be copied off the device.
     *
     * The config in memory once it is loaded — serialized here on the main thread, where it is
     * only ever mutated, so the read is race-free. Before the first load lands (a fast "~b" on a
     * cold start) memory still holds the empty default, and exporting THAT would replace the
     * real backup with nothing; the file on disk is the truth then, read on the IO thread. If
     * that file cannot be read either, no backup is written at all: the previous one — the only
     * copy off the device — stays, rather than being replaced by an empty config.
     */
    private fun backup(t: CommandGate.Ticket) {
        val payload = if (state.configLoaded) ConfigJson.serialize(state.config) else null
        commandIo(
            t,
            work = {
                val text = payload ?: store.load()?.let { ConfigJson.serialize(it) }
                // (name written or null, whether there was a config to write at all)
                (text?.let { backupStore.write(BackupFiles.BACKUP_BASE, it) }) to (text != null)
            },
            refused = { toast(getString(R.string.toast_backup_failed)) },
        ) { (name, readable) ->
            when {
                !readable -> toast(getString(R.string.toast_backup_unreadable), Toast.LENGTH_LONG)
                // Name the file actually written: its name carries the time it was taken.
                name != null -> toast(getString(R.string.toast_backup_ok, name), Toast.LENGTH_LONG)
                else -> toast(getString(R.string.toast_backup_failed))
            }
        }
    }

    /**
     * "~cleanup": offer to remove what the config keeps for apps that are no longer installed.
     * The check ([cleanupCheck]) runs on the IO thread: a fresh, COMPLETE enumeration, and only
     * entries whose package is gone from the device altogether. Nothing is removed without the
     * dialog's OK, and then [LauncherState.cleanup] checks again and writes an undo point first.
     *
     * Two commands, not one: the check ends when its dialog comes up, and the OK starts the
     * removal as a run of its own ([runCleanup]). The dialog is modal itself, and a command held
     * across it would keep the busy layer waiting on the user's answer for as long as they like.
     */
    private fun cleanup(t: CommandGate.Ticket) {
        if (!state.configLoaded) {
            toastNotLoaded()
            return
        }
        val ticket = cleanupTicket
        val snapshot = state.config.snapshot()
        commandIo(
            t,
            work = { safeCleanupCheck(snapshot) },
            refused = { toast(getString(R.string.toast_cleanup_not_run)) },
        ) { plan ->
            // Left meanwhile — the drawer closed, HOME, an app started, the screen off: no
            // dialog popping up over a page that moved on, no toast over another app.
            if (!started || ticket != cleanupTicket) return@commandIo
            when (plan) {
                CleanupPlan.Incomplete -> toast(getString(R.string.toast_cleanup_incomplete))
                CleanupPlan.NothingToDo -> toast(getString(R.string.toast_cleanup_none))
                is CleanupPlan.Offer -> confirmCleanup(plan.orphans)
            }
        }
    }

    /** [cleanupCheck] that never throws: anything unexpected is an incomplete check — the
     *  hand-back must always come (or "~cleanup" would stay "checking" for good). */
    private fun safeCleanupCheck(cfg: VersoConfig): CleanupPlan = try {
        cleanupCheck(cfg)
    } catch (e: RuntimeException) {
        Log.w("Verso", "cleanup check failed", e)
        CleanupPlan.Incomplete
    }

    /** The IO half of a cleanup check: enumerate afresh, then ask [Cleanup.plan]. */
    private fun cleanupCheck(cfg: VersoConfig): CleanupPlan {
        val found = enumerate()
        val listed = try {
            launcherApps.profiles.mapTo(HashSet()) { userManager.getSerialNumberForUser(it) }
        } catch (e: RuntimeException) {
            Log.w("Verso", "cleanup: profiles unreadable", e)
            return CleanupPlan.Incomplete
        }
        return Cleanup.plan(cfg, found) { parts -> stillThere(parts, listed) }
    }

    /**
     * Whether the app behind a key is still on the device although not listed: disabled, its
     * launcher icon (an activity alias) switched off, uninstalled with its data kept, on storage
     * that is not mounted, or in a profile that exists but is not among those [listed]. In the
     * personal profile the exact activity is looked up, so one renamed by an update counts as
     * gone; another profile only allows a package lookup. Unsure counts as there — a cleanup
     * keeps what it cannot rule out.
     */
    private fun stillThere(key: KeyParts, listed: Set<Long>): Boolean = try {
        val serial = key.profile
        if (serial == null) {
            try {
                packageManager.getActivityInfo(
                    ComponentName(key.packageName, key.className),
                    PackageManager.ComponentInfoFlags.of(STILL_THERE_FLAGS.toLong()),
                )
                true
            } catch (e: PackageManager.NameNotFoundException) {
                // No such activity loaded — but the package may still be there with its code
                // not loaded (unmounted storage, data kept). A missing package throws again.
                val info = packageManager.getPackageInfo(
                    key.packageName,
                    PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or STILL_THERE_FLAGS).toLong()),
                )
                Cleanup.activityStillThere(key.className, info.activities?.map { it.name })
            }
        } else {
            val user = userManager.getUserForSerialNumber(serial)
            when {
                user == null -> false             // the profile is gone, and its apps with it
                serial !in listed -> true         // there, but not one Verso can list
                else -> {
                    launcherApps.getApplicationInfo(key.packageName, STILL_THERE_FLAGS, user)
                    true
                }
            }
        }
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        Log.w("Verso", "cleanup: cannot check ${key.packageName}", e)
        true
    }

    private fun confirmCleanup(orphans: Orphans) {
        commandDialog?.dismiss()
        // One line per section that has entries — no "0" lines, and no plural to get wrong.
        val lines = buildList {
            add(getString(R.string.cleanup_apps, orphans.keys.size))
            add("")
            if (orphans.favorites > 0) add(getString(R.string.cleanup_favorites, orphans.favorites))
            if (orphans.hidden > 0) add(getString(R.string.cleanup_hidden, orphans.hidden))
            if (orphans.names > 0) add(getString(R.string.cleanup_names, orphans.names))
            if (orphans.tags > 0) add(getString(R.string.cleanup_tags, orphans.tags))
            add("")
            add(getString(R.string.cleanup_undo_note))
        }
        commandDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.cleanup_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.cleanup_remove) { _, _ -> runCleanup(orphans.keys) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { setOnDismissListener { d -> if (commandDialog === d) commandDialog = null } }
        commandDialog?.show()
    }

    /**
     * OK: [LauncherState.cleanup] re-checks, writes the undo point, then removes — the confirmed
     * removal of a "~cleanup", as a command run of its own (see [cleanup]).
     */
    private fun runCleanup(confirmed: Set<String>) {
        val accepted = gate.start(Command.CLEANUP) { t ->
            if (!gate.hold(t)) return@start
            state.cleanup(
                confirmed = confirmed,
                recheck = ::safeCleanupCheck,
                writeUndo = { payload -> backupStore.write(BackupFiles.PRE_CLEANUP_BASE, payload) },
            ) { outcome ->
                gate.finish(t) {
                    if (outcome is CleanupOutcome.Done) refilter()
                    // Gone meanwhile (an app in front, the screen off): the result stands, but no
                    // toast over another app.
                    if (!started) return@finish
                    when (outcome) {
                        CleanupOutcome.Refused -> toast(getString(R.string.toast_cleanup_not_run))
                        CleanupOutcome.Superseded -> toast(getString(R.string.toast_cleanup_superseded))
                        CleanupOutcome.Incomplete -> toast(getString(R.string.toast_cleanup_incomplete))
                        CleanupOutcome.NothingToDo -> toast(getString(R.string.toast_cleanup_none))
                        CleanupOutcome.UndoFailed -> toast(getString(R.string.toast_restore_undo_failed))
                        is CleanupOutcome.Done -> {
                            toast(getString(R.string.toast_cleanup_ok, BackupFiles.timeOf(outcome.undoName)), Toast.LENGTH_LONG)
                        }
                    }
                }
            }
        }
        if (!accepted) toast(getString(R.string.toast_busy))
    }

    /**
     * "~reset": start over when the config stays unreadable — the way out when there is no
     * backup to restore. Two runs, like "~cleanup": this one checks and asks, the dialog's OK
     * starts the set-aside as a run of its own ([runReset]).
     *
     * Only while the config is unreadable: a readable config is never touched, whatever is typed
     * (LauncherState.reset refuses as well). The check looks for an own backup on the IO thread,
     * so the dialog can point to "~restore" first — the better way out — and warn about the one
     * trap after a reset: a "~backup" of the fresh config would replace that backup. When the
     * folder cannot be listed, a backup may exist all the same: the warning stays, worded so.
     */
    private fun reset(t: CommandGate.Ticket) {
        if (!state.configUnreadable) {
            toast(getString(if (state.configLoaded) R.string.toast_reset_readable else R.string.toast_not_loaded))
            return
        }
        // The page-turn ticket ~cleanup uses: no dialog popping up over a page that moved on.
        val ticket = cleanupTicket
        commandIo(
            t,
            work = { backupStore.lookupLatest(BackupFiles.BACKUP_BASE) },
            refused = { toast(getString(R.string.toast_command_failed)) },
        ) { latest ->
            if (!started || ticket != cleanupTicket) return@commandIo
            confirmReset(latest)
        }
    }

    private fun confirmReset(latest: BackupStore.Latest) {
        commandDialog?.dismiss()
        val note = when (latest) {
            is BackupStore.Latest.Found -> R.string.reset_backup_note
            BackupStore.Latest.Failed -> R.string.reset_backup_unknown
            else -> null
        }
        val lines = buildList {
            add(getString(R.string.reset_message))
            if (note != null) {
                add("")
                add(getString(note))
            }
        }
        commandDialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.reset_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.reset_confirm) { _, _ -> runReset() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { setOnDismissListener { d -> if (commandDialog === d) commandDialog = null } }
        commandDialog?.show()
    }

    /**
     * Set an unreadable config aside (ConfigStore.setAsideUnreadable: renamed, never deleted) —
     * for "~reset" and a restore over it, on the IO thread — with a copy in
     * Download/[BackupFiles.DIR] as "verso-broken-<date>_<time>.json". The set-aside files stay
     * in the app's private directory, out of the user's reach on a release build; the copy is
     * where they can get at the bytes, and where "~restore-saf" brings the config back if it was
     * only unreadable for a moment. Every copy is kept (BackupFiles.replacedBy).
     *
     * The copy FIRST, while the files still lie where they were (ConfigStore.readForCopy): once
     * they are renamed, the next start is a fresh install's, and a process killed before the copy
     * was published would leave the config only in the private files. Best effort: a failed copy
     * changes nothing about the set-aside (logged). Should setAsideUnreadable find the config
     * readable after all (its own re-check), the copy just made stays as a harmless extra.
     *
     * The names in the outcome FIRST name where the user can find the config — the copy's path,
     * or without one the set-aside primary (moved last) — since the toasts show only that one:
     * a toast holds two lines, and the private names help nobody there (ConfigStore logs them).
     */
    private fun setAsideUnreadableConfig(): ConfigStore.SetAside {
        // A config that reads after all is no case for a copy in a public folder.
        store.load()?.let { return ConfigStore.SetAside.Readable(it) }
        val copy = store.readForCopy()
            ?.let { backupStore.write(BackupFiles.BROKEN_BASE, it) }
            ?.let { "${Environment.DIRECTORY_DOWNLOADS}/${BackupFiles.DIR}/$it" }
        val aside = store.setAsideUnreadable()
        if (aside !is ConfigStore.SetAside.Moved) return aside
        return ConfigStore.SetAside.Moved(listOfNotNull(copy) + aside.names.reversed())
    }

    /** OK: [LauncherState.reset] sets the unreadable config aside and starts over. */
    private fun runReset() {
        val accepted = gate.start(Command.RESET) { t ->
            if (!gate.hold(t)) return@start
            state.reset { outcome ->
                gate.finish(t) {
                    // Whatever the next read finds, report it afresh: an unreadable config found
                    // again after this must say so again (see onRefreshed).
                    unreadableShown = false
                    when (outcome) {
                        is ResetOutcome.Done ->
                            toast(getString(R.string.toast_reset_done, outcome.setAside.firstOrNull() ?: ConfigStore.CONFIG_FILE), Toast.LENGTH_LONG)
                        ResetOutcome.WasReadable -> toast(getString(R.string.toast_reset_readable_now), Toast.LENGTH_LONG)
                        ResetOutcome.NotUnreadable -> toast(getString(R.string.toast_reset_readable))
                        ResetOutcome.Failed -> toast(getString(R.string.toast_reset_failed), Toast.LENGTH_LONG)
                    }
                }
            }
        }
        if (!accepted) toast(getString(R.string.toast_busy))
    }

    /**
     * "~restore": read the newest backup back in. No picker — there is one current backup,
     * found by pattern whatever its timestamp, so there is nothing to choose.
     *
     * The read runs on [ioExecutor]; only the adoption returns to the main thread, where
     * the config lives.
     */
    private fun restoreConfig(t: CommandGate.Ticket) {
        commandIo(
            t,
            work = {
                // The newest backup of ours, whatever MediaStore named it — or, if none was ever
                // published, an interrupted first one (see BackupFiles.latestToRestore).
                val latest = backupStore.lookupLatest(BackupFiles.BACKUP_BASE)
                val text = (latest as? BackupStore.Latest.Found)?.let { backupStore.readText(it.uri) }
                // Parsed here, off the main thread: a hostile document is parse work the UI must
                // not wait on (and the parse is where a torn temp gets rejected).
                latest to text?.let { ConfigJson.parseForeign(it) }
            },
            refused = { toast(getString(R.string.toast_command_failed)) },
        ) { (latest, restored) ->
            // Nothing there at all is its own message: "~backup was never run" is a
            // different problem from "the backup is broken", and both differ from "the
            // folder could not be read" — saying which saves the user a wrong hunt.
            when {
                latest is BackupStore.Latest.Failed ->
                    toast(getString(R.string.toast_restore_list_failed), Toast.LENGTH_LONG)
                latest is BackupStore.Latest.None ->
                    toast(getString(R.string.toast_restore_none), Toast.LENGTH_LONG)
                restored == null -> toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                else -> adoptRestored(t, restored)
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
                    "primary:${Environment.DIRECTORY_DOWNLOADS}/${BackupFiles.DIR}",
                ),
            )
        try {
            startActivityForResult(intent, REQ_RESTORE)
        } catch (e: ActivityNotFoundException) {
            Log.w("Verso", "no document picker on this device", e)
            toast(getString(R.string.toast_restore_no_picker))
        }
    }

    /** The "~restore-saf" picker's answer: a command run of its own (the picker could not be
     *  part of one, see runCommand). Reading stays off the main thread; only the adoption comes
     *  back to it, exactly as in [restoreConfig]. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RESTORE) return
        val uri = data?.data
        // Backing out of the picker is not an error — say nothing.
        if (resultCode != RESULT_OK || uri == null) return
        val accepted = gate.start(Command.RESTORE_SAF) { t -> readPicked(t, uri) }
        if (!accepted) toast(getString(R.string.toast_busy))
    }

    /**
     * Read and parse the document a "~restore-saf" pick points at, then adopt it.
     *
     * Its own short-lived thread, NOT the app's IO thread: the picked document can live behind
     * any content provider — a cloud drive offline or slow — and a read there can block for as
     * long as the provider likes. On the IO thread that would stall every save and every app
     * refresh behind it. This read touches no file of the app's own, so it needs none of the IO
     * thread's ordering; the undo point and the adoption still go through it (adoptRestored).
     *
     * The one step with no bound on how long it takes, so the only one Back may abandon
     * ([CommandGate.Ticket.cancelable]): the busy layer must never hold the HOME screen hostage
     * to a stalled provider. An abandoned read that answers late finds its ticket ended and is
     * not adopted — nor is it over a newer pick's. Never throws: readText and parseForeign
     * return null; the hand-back is posted from a finally all the same, so the step always ends.
     */
    private fun readPicked(t: CommandGate.Ticket, uri: Uri) {
        if (!gate.hold(t)) return
        t.cancelable = true
        Thread(guarded("restore read") {
            var restored: VersoConfig? = null
            try {
                restored = backupStore.readText(uri)?.let { ConfigJson.parseForeign(it) }
            } finally {
                val read = restored
                onUi {
                    gate.finish(t) {
                        t.cancelable = false
                        updateBusyHint()  // "Back cancels" no longer holds
                        // Unlike [restoreConfig] a null here is not "no backup yet" — the user
                        // pointed at a file and it could not be read or is no config. Either way
                        // nothing was changed.
                        if (read == null) toast(getString(R.string.toast_restore_failed), Toast.LENGTH_LONG)
                        else adoptRestored(t, read)
                    }
                }
            }
        }, "verso-restore-read").start()
    }

    /**
     * Adopt a config that [restoreConfig] or the "~restore-saf" picker read and parsed — with
     * parseForeign, NOT parse: the lenient parser would turn any JSON object at all into an
     * empty config and wipe everything without so much as an error. A document that did not
     * parse never gets here, so a malformed backup leaves the live config untouched.
     *
     * Then the undo point — the config being replaced — is written out as a backup of
     * [BackupFiles.PRE_RESTORE_BASE], and ONLY once that write succeeded is [restored] adopted. A restore
     * whose undo point could not be written is aborted: replacing the config with nothing to
     * go back to is exactly the "destroyed without a trace" the undo point exists to prevent.
     * The outgoing config is taken from memory here on the main thread, or — if it was never
     * loaded in this process (the picker's result delivered to a fresh process after Android
     * killed the launcher, or the config is unreadable) — read from disk on the IO thread, where
     * an empty default would otherwise replace a real undo point with nothing. If the file cannot
     * be read there either, its files are set aside (renamed, not deleted) in place of an undo
     * point, and the restore goes ahead (RestoreSteps): it is the way out of an unreadable config.
     */
    private fun adoptRestored(t: CommandGate.Ticket, restored: VersoConfig) {
        // What a restore actually adopted, so a "my favorites are gone" report can be
        // settled from the log instead of guessed at.
        Log.i(
            "Verso",
            "restore: ${restored.favorites.size} favorites, ${restored.hidden.size} hidden, " +
                "${restored.names.size} names, ${restored.tags.size} tagged",
        )
        val outgoing = if (state.configLoaded) state.config.snapshot() else null
        commandIo(
            t,
            // The IO half in its load-bearing order (CLAUDE.md rule 7): stop if nothing would
            // change, write the undo point, persist the restored config — see RestoreSteps.
            work = {
                RestoreSteps.run(
                    current = outgoing ?: store.load(),
                    restored = restored,
                    writeUndo = { payload -> backupStore.write(BackupFiles.PRE_RESTORE_BASE, payload) },
                    writeConfig = { payload -> store.write(payload, rotateBackup = true) },
                    setAside = ::setAsideUnreadableConfig,
                )
            },
            refused = { toast(getString(R.string.toast_restore_undo_failed), Toast.LENGTH_LONG) },
        ) { outcome ->
            when (outcome) {
                RestoreSteps.Outcome.Unchanged -> toast(getString(R.string.toast_restore_same), Toast.LENGTH_LONG)
                RestoreSteps.Outcome.UndoFailed -> toast(getString(R.string.toast_restore_undo_failed), Toast.LENGTH_LONG)
                // The way out of an unreadable config: nothing readable was there to keep, so it
                // was set aside (renamed, not deleted) instead of an undo point.
                is RestoreSteps.Outcome.ReplacedUnreadable -> {
                    state.adopt(restored)
                    refilter()  // drawn now, not only once the refresh lands (see reload)
                    renameDialog?.dismiss()
                    commandDialog?.dismiss()
                    toast(getString(R.string.toast_restore_replaced, outcome.setAside.firstOrNull() ?: ConfigStore.CONFIG_FILE), Toast.LENGTH_LONG)
                }
                RestoreSteps.Outcome.SetAsideFailed -> toast(getString(R.string.toast_reset_failed), Toast.LENGTH_LONG)
                is RestoreSteps.Outcome.Done -> {
                    // Wholesale replace, save and re-render — with the epoch guard that keeps an
                    // enumeration already in flight from handing the OLD config back over this one.
                    state.adopt(restored)
                    refilter()  // drawn now, not only once the refresh lands (see reload)
                    // A dialog opened against the pre-restore config would write its stale name
                    // and tags over what the restore just brought in.
                    renameDialog?.dismiss()
                    commandDialog?.dismiss()
                    toast(getString(R.string.toast_restore_ok, BackupFiles.timeOf(outcome.undoName)), Toast.LENGTH_LONG)
                }
            }
        }
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

    /**
     * "##tag": put the chosen tag on [key] or take it off, persisted immediately like the
     * other edit modes' checkboxes. A tag lives in a per-app list inside a map, and an app
     * left with no tags loses its key (see [LauncherState.toggleTag]).
     */
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
        refilter()  // re-render in the (new) order
    }

    private fun promptRename(entry: AppEntry) {
        // The home page can show cached favorites before the config is loaded; the dialog
        // would open on the empty default (no tags) and its edit could not be saved.
        if (!editable()) return
        val key = entry.key
        // Dismiss any dialog already up (rapid long-presses) before opening a new one, and
        // keep the reference so onDestroy can tear it down. The view construction lives in
        // RenameDialog; the Activity keeps only the config decisions the commit implies.
        renameDialog?.dismiss()
        val dialog = RenameDialog.show(
            context = this,
            title = entry.label,
            initialName = state.config.names[key] ?: entry.systemLabel,
            initialTags = state.config.tags[key]?.joinToString(", ").orEmpty(),
            allTags = LauncherLogic.allTags(state.config.tags),
            // The dialog is dark on every wallpaper (Theme_Material_Dialog_Alert), so its text
            // stays light whatever the drawer's palette is.
            fgColor = DIALOG_TEXT,
            onAppInfo = { openAppInfo(entry) },
        ) { name, tags ->
            // Name override, tags, save, in-memory relabel + re-sort: LauncherState.rename. A load
            // in flight cannot undo it — it carries no names, they are applied at its hand-off.
            state.rename(key, entry.systemLabel, name, tags)
            refilter()
        }
        renameDialog = dialog
        // Clear the field on dismiss so we never hold a stale, already-gone dialog — but only if
        // it still holds THIS one: Dialog delivers the dismiss listener as a posted message, so
        // the old dialog's listener runs after the new one was assigned and must not null it.
        dialog.setOnDismissListener { if (renameDialog === dialog) renameDialog = null }
    }

    // ---- apps ---------------------------------------------------------------

    private val launcherApps: LauncherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val userManager: UserManager by lazy { getSystemService(UserManager::class.java) }

    /**
     * The profile an entry lives in: the personal one for [AppEntry.profile] == null, else
     * the profile with that serial — or null when that profile no longer exists (a work
     * profile removed since the list was loaded).
     */
    private fun userOf(entry: AppEntry): UserHandle? {
        val serial = entry.profile ?: return Process.myUserHandle()
        return userManager.getUserForSerialNumber(serial)
    }

    /**
     * LauncherState's enumerateApps, on the IO thread: every launchable app under its own
     * (system) label. No config: the custom names are applied by LauncherState on the main
     * thread, from the live config (LauncherLogic.named), so nothing the user does while this
     * runs can make its result stale.
     *
     * Through LauncherApps, the API Android provides for launchers: every profile the launcher
     * may show — the personal one plus a work profile or cloned apps — not just the current
     * user's PackageManager view. Apps of another profile are keyed with its serial
     * ([AppEntry.keyOf]) and labelled with the system's badge ("Gmail (Work)"), so the work
     * copy is neither the same row nor an identical-looking one.
     */
    private fun loadApps(): List<AppEntry>? = enumerate()?.apps

    /**
     * [loadApps]'s body, also telling whether it saw EVERYTHING: a profile or an app skipped
     * after an error makes it incomplete. "~cleanup" needs that — an app it did not see would
     * look uninstalled.
     */
    private fun enumerate(): Enumeration? {
        var complete = true
        val emptyProfiles = HashSet<Long>()
        val pm = packageManager
        val self = packageName
        val personal = Process.myUserHandle()
        // The badge is localized ("(Work)" / "(Arbeit)"): part of its cache key, since the
        // process — and the cache — outlives the activity recreated by a language change.
        val locale = resources.configuration.locales[0].toLanguageTag()
        // This runs on a background thread, and a HOME app that throws on resume loops
        // forever — so nothing here may let an exception escape. LauncherApps and the label
        // lookups surface plain RuntimeExceptions (a DeadSystemRuntimeException if
        // system_server restarts, a SecurityException for a profile that turns inaccessible
        // mid-scan). If the profile list or the PERSONAL profile cannot be read, return null
        // (NOT an empty list) so the caller keeps the last-known-good list rather than
        // blanking the launcher; another profile failing only drops that profile. A query
        // that SUCCEEDS returns its result even when empty.
        val profiles = try {
            launcherApps.profiles
        } catch (e: RuntimeException) {
            Log.w("Verso", "app enumeration failed: profiles", e)
            return null
        }
        val entries = mutableListOf<AppEntry>()
        for (user in profiles) {
            val isPersonal = user == personal
            var serial: Long? = null
            var paused = false
            val activities = try {
                // The serial and quiet-mode lookups are binder calls too: inside the try, so a
                // failure drops this profile like any other instead of escaping onto the IO thread.
                if (!isPersonal) {
                    serial = userManager.getSerialNumberForUser(user).takeIf { it >= 0 }
                        ?: run { complete = false; continue }
                    // A work profile that is turned off: its apps stay listed, dimmed (AppStatus).
                    paused = userManager.isQuietModeEnabled(user)
                }
                launcherApps.getActivityList(null, user)
            } catch (e: RuntimeException) {
                if (isPersonal) {
                    Log.w("Verso", "app enumeration failed", e)
                    return null  // failure sentinel: keep the current list, don't wipe it
                }
                Log.w("Verso", "skipping profile $serial", e)
                complete = false
                continue
            }
            // An empty list, no error: a profile with no apps — or one being set up or torn down,
            // or one the system will not show right now. "~cleanup" cannot tell which, so it
            // keeps that profile's entries (Enumeration.emptyProfiles). An empty PERSONAL list is
            // never real: nothing is seen.
            if (activities.isEmpty()) {
                if (isPersonal) complete = false else serial?.let { emptyProfiles += it }
            }
            for (info in activities) {
                try {
                    val component = info.componentName
                    // A component without a class name cannot be launched (and would key as
                    // "pkg/null"): skip it.
                    if (component.className.isNullOrEmpty()) continue
                    if (component.packageName == self) continue  // don't list ourselves
                    // Identity is the full launcher component, not the package — a package
                    // can expose several launcher activities, each its own row (AppEntry.key).
                    // The app's own label — the only one an enumeration knows; the custom
                    // names are applied over it later (LauncherLogic.named). In another
                    // profile it carries the system's badge, so the work copy reads apart.
                    val raw = info.label.toString()
                    // The badge is a binder call per app (DevicePolicyManager strings for a work
                    // profile): cached per (profile, label) across loads — IO thread only.
                    val systemLabel = if (serial == null) raw else {
                        app.badgedLabels.getOrPut(VersoApp.BadgeKey(serial, raw, locale)) { pm.getUserBadgedLabel(raw, user).toString() }
                    }
                    entries += AppEntry.of(
                        label = systemLabel,
                        packageName = component.packageName,
                        className = component.className,
                        systemLabel = systemLabel,
                        profile = serial,
                        // Archived (Android 15+): code removed, data kept; LauncherApps still
                        // lists it, and a tap lets the system restore it.
                        status = AppStatus.of(profilePaused = paused, archived = info.applicationInfo.isArchived),
                    )
                } catch (e: RuntimeException) {
                    Log.w("Verso", "skipping app", e)
                    complete = false
                }
            }
        }
        // Sorted by system label here, on the IO thread. The drawer order depends on the custom
        // names too, so LauncherLogic.named sorts again on the main thread after applying them —
        // but sortedWith is a stable merge sort that runs through input already in order in
        // about one comparison per app, so with a few renamed apps that re-sort is nearly free.
        return Enumeration(LauncherLogic.sortedForDrawer(entries), complete, emptyProfiles)
    }

    /**
     * Installs, uninstalls and updates — in any profile — go to [appsMaybeChanged]: a refresh
     * right away while the launcher is in front, a dirty mark for the next resume otherwise.
     * Registered for the activity's lifetime on the main looper, where [LauncherState]
     * lives; a burst of callbacks (an app update fires several) costs little, because each
     * refresh supersedes the one before it.
     */
    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = appsMaybeChanged()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = appsMaybeChanged()
        override fun onPackageChanged(packageName: String, user: UserHandle) = appsMaybeChanged()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            appsMaybeChanged()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            appsMaybeChanged()
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = appsMaybeChanged()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = appsMaybeChanged()
    }

    /** The profile a tap on a paused app turned back on, and that app's label — for the
     *  "ready" toast once the profile is available (see launch). */
    private var waitingForProfile: Pair<UserHandle, String>? = null
    // When that tap was. Only an answer within PROFILE_WAIT_MS gets the toast: a challenge
    // cancelled and work apps turned on hours later from Quick Settings must not announce an
    // app nobody just asked for. Not cleared in onStop — the work challenge covers us.
    private var waitingSince = 0L

    /** A profile added, removed, paused or resumed changes which apps there are to list (and
     *  how they are drawn: a paused profile's apps are dimmed). */
    private val profilesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsMaybeChanged()
            val waiting = waitingForProfile ?: return
            if (intent.action != Intent.ACTION_PROFILE_AVAILABLE) return
            val user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
            if (user != waiting.first) return
            waitingForProfile = null
            if (SystemClock.uptimeMillis() - waitingSince > PROFILE_WAIT_MS) return
            toast(getString(R.string.toast_app_ready, waiting.second), Toast.LENGTH_LONG)
        }
    }

    private fun watchApps() {
        launcherApps.registerCallback(appsChanged, Handler(Looper.getMainLooper()))
        registerReceiver(
            profilesChanged,
            IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            },
            RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unwatchApps() {
        launcherApps.unregisterCallback(appsChanged)
        runCatching { unregisterReceiver(profilesChanged) }
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
        // The personal-profile key, built where every key is built (AppEntry.keyOf).
        fun launcherKey(pkg: String): String? =
            pm.getLaunchIntentForPackage(pkg)?.component?.let { AppEntry.keyOf(it.packageName, it.className, null) }
        // [preferred] is a lambda, not a value, so it runs INSIDE the try: evaluated as an
        // argument it would run before it, and a throwing lookup (the SMS role on a device
        // without telephony, a vendor-modified one) would escape onto the IO thread — an
        // uncaught exception there takes the process down, on every cold start while fresh.
        fun handlers(intent: Intent, preferred: () -> String? = { null }): List<String> = try {
            // resolveActivity answers the ResolverActivity ("android") when no default is set —
            // that is no app, so it is dropped by the launcherKey lookup below like any other
            // package without a launcher entry.
            val default = preferred()
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
                preferred = { Telephony.Sms.getDefaultSmsPackage(this) },
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
        // The keyboard's Enter key says what Enter does here: "Done" in the edit modes (it
        // leaves them), "Go" where it launches or runs. Only switched when it changes —
        // restarting the input connection on every keystroke would make the IME flicker.
        val wantedAction = if (LauncherLogic.isEditMode(mode)) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_GO
        if (prompt.imeOptions != wantedAction) {
            prompt.imeOptions = wantedAction
            // Posted: this runs inside the keyboard's own edit (afterTextChanged), and keys it
            // sends to the old connection before it rebinds could otherwise be lost.
            prompt.post { getSystemService(InputMethodManager::class.java).restartInput(prompt) }
        }
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
        // A pick whose row is gone (the app uninstalled meanwhile) is no pick: the next tap would
        // move an invisible favorite instead of picking the one tapped.
        if (reorderPick != null && shown.none { (it as? AppRow)?.entry?.key == reorderPick }) reorderPick = null
        // Any replacement of shown invalidates a tap already in flight (see the click
        // listener). Bumped here, in the ONE place shown is assigned.
        shownGeneration++
        // An empty list explains itself (it is only shown while the list IS empty) — but not
        // before the apps have loaded, when "no apps" would be false. Set only when it changes:
        // the view is a live region, and TalkBack would repeat it on every keystroke.
        val hint = if (!state.appsLoaded) "" else getString(
            when (LauncherLogic.emptyHint(mode, q, tagEditTag)) {
                EmptyHint.NO_MATCH -> R.string.empty_no_match
                EmptyHint.NO_APPS -> R.string.empty_no_apps
                EmptyHint.NO_TAGS -> R.string.empty_no_tags
                EmptyHint.NO_TAG_MATCH -> R.string.empty_no_tag_match
                EmptyHint.NO_FAVORITES -> R.string.empty_no_favorites
                EmptyHint.NO_RECENTS -> R.string.empty_no_recents
                EmptyHint.NO_COMMAND -> R.string.empty_no_command
            }
        )
        if (emptyView.text.toString() != hint) emptyView.text = hint
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

    /** The favorites the cache file holds, as last written or read (see HomeCache). */
    private var cachedFavorites: List<Pair<String, String>>? = null

    /** Re-run [applyFilter] on what the prompt holds now — after the data under it changed. */
    private fun refilter() = applyFilter(prompt.text?.toString().orEmpty())

    /** Keep the home page in step with favorites, labels and the app set. Cheap when nothing
     *  it shows changed, so it is fine to call from every applyFilter. */
    private fun renderHome() {
        val favs = state.favorites()
        // Only with the config loaded: an unreadable one has no favorites in memory, and drawing
        // that would show the no-favorites hint — home keeps what it shows (the cached favorites)
        // until the config can be read.
        val ready = state.appsLoaded && state.configLoaded
        homePage.render(favs, ready)
        // Keep the cold-start cache in step with what home really shows — written only when
        // the favorites (keys, labels) actually changed, never on a plain re-render, and never
        // from a config that is not loaded (that would empty the cache).
        if (ready) {
            val pairs = favs.map { it.key to it.label }
            if (pairs != cachedFavorites) {
                cachedFavorites = pairs
                submitIo { HomeCache.write(filesDir, favs) }
            }
        }
    }

    private fun launch(entry: AppEntry) {
        // Through LauncherApps, so an app of another profile starts IN that profile; it starts
        // in its own task, not nested in this one.
        val user = userOf(entry)
        val component = ComponentName(entry.packageName, entry.className)
        try {
            when {
                // The entry's profile is gone (a work profile removed since the list loaded).
                user == null -> notFound(entry)
                // A paused work profile: the first tap only turns it back on (the system confirms
                // with the user where needed) — it does not start the app. Once the profile is
                // available the row turns normal and a toast says the app is ready, so the user
                // decides whether it was only about turning work apps on or also about starting
                // this one (see profilesChanged).
                userManager.isQuietModeEnabled(user) -> {
                    waitingForProfile = user to entry.label
                    waitingSince = SystemClock.uptimeMillis()
                    // Only the default home app may turn a profile on: anyone else gets a
                    // SecurityException, and a bare "denied" would not say what to change.
                    try {
                        userManager.requestQuietModeEnabled(false, user)
                    } catch (e: SecurityException) {
                        waitingForProfile = null
                        Log.w("Verso", "quiet mode denied: ${entry.key}", e)
                        toast(getString(R.string.toast_not_default_home), Toast.LENGTH_LONG)
                    }
                }
                // Component gone since the list loaded (uninstall race). Without this check
                // startMainActivity throws a SecurityException, which would misreport the
                // vanished app as "denied".
                !launcherApps.isActivityEnabled(component, user) -> {
                    Log.w("Verso", "launch unavailable: ${entry.key}")
                    notFound(entry)
                }
                else -> {
                    launcherApps.startMainActivity(component, user, null, null)
                    // Only a launch that actually started counts as "recent" — the other
                    // branches leave the list untouched so a dead component never lingers
                    // at the top of "?".
                    state.recordLaunch(entry.key)
                }
            }
        } catch (e: SecurityException) {
            Log.w("Verso", "launch denied: ${entry.key}", e)
            toast(getString(R.string.toast_denied, entry.label))
        } catch (e: RuntimeException) {
            // Dead system_server or similar — don't crash HOME.
            Log.w("Verso", "launch failed: ${entry.key}", e)
            notFound(entry)
        }
    }

    /**
     * Whether the system wallpaper wants dark text — Android's own judgement of it
     * (WallpaperColors.HINT_SUPPORTS_DARK_TEXT), the signal Unity Kolibri's smart contrast uses.
     * No permission needed. Unknown (a live wallpaper without colours, an error) counts as dark:
     * light text with a dark rim is what Verso always showed.
     */
    private fun wallpaperIsLight(): Boolean = try {
        val colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        colors.wantsDarkText()
    } catch (e: RuntimeException) {
        Log.w("Verso", "wallpaper colours unavailable", e)
        false
    }

    private fun WallpaperColors?.wantsDarkText(): Boolean =
        this != null && (colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0

    /** A new wallpaper (or new colours of a live one) may flip the palette. */
    private val wallpaperColors = WallpaperManager.OnColorsChangedListener { colors, which ->
        if (which and WallpaperManager.FLAG_SYSTEM == 0) return@OnColorsChangedListener
        val light = colors.wantsDarkText()
        ++paletteGeneration
        applyPalette(HomeStyle.palette(light))
    }

    // Bumped by every palette decision, so the IO thread's first answer cannot overwrite a
    // newer one from the listener.
    private var paletteGeneration = 0

    /**
     * Repaint everything that sits on the wallpaper in [p]: the home page, the drawer rows (via a
     * rebind), the prompt, its hint and the ×, and the system bars' icons (dark on a light
     * wallpaper, so the clock and battery up there stay visible).
     */
    private fun applyPalette(p: Palette) {
        palette = p
        app.palette = p
        homePage.applyTextColor(p.homeText)
        prompt.setTextColor(palette.listText)
        prompt.setHintTextColor(palette.listDim)
        prompt.legibleOnWallpaper()
        clearButton.setTextColor(palette.listDim)
        clearButton.legibleOnWallpaper()
        emptyView.setTextColor(palette.listDim)
        emptyView.setOutline(outlinePx, palette.listOutline)
        // The busy layer too, while it is up: the bars follow the focused window.
        for (w in listOfNotNull(window, busyLayer?.window)) applyBarAppearance(w)
        adapter.notifyDataSetChanged()
    }

    /** Dark bar icons on a light wallpaper, light ones on a dark one ([palette]), for [w]. */
    private fun applyBarAppearance(w: Window?) {
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w?.insetsController?.setSystemBarsAppearance(if (palette.darkBarIcons) lightBars else 0, lightBars)
    }

    /**
     * The prompt, its hint and the × sit on the wallpaper too, but an EditText cannot take the
     * stroked outline (the second pass would draw the cursor and selection twice) — a tight
     * dark shadow gives them the same footing on a bright wallpaper.
     */
    private fun TextView.legibleOnWallpaper() =
        setShadowLayer(3.dp().toFloat(), 0f, 0f, palette.shadow)

    private fun notFound(entry: AppEntry) =
        toast(getString(R.string.toast_not_found, entry.label))

    /**
     * Open the system "App info" screen for this component's package (uninstall, permissions,
     * force-stop live there). Reached from the long-press dialog's neutral button. Launched in
     * its own task like [launch], and guarded the same way so a missing Settings activity or a
     * component gone mid-session can never crash HOME.
     */
    private fun openAppInfo(entry: AppEntry) {
        // The app's details page in ITS profile — a work app's settings live there.
        val user = userOf(entry) ?: return notFound(entry)
        try {
            launcherApps.startAppDetailsActivity(ComponentName(entry.packageName, entry.className), user, null, null)
        } catch (e: SecurityException) {
            Log.w("Verso", "app info denied: ${entry.key}", e)
            toast(getString(R.string.toast_denied, entry.label))
        } catch (e: RuntimeException) {
            Log.w("Verso", "app info failed: ${entry.key}", e)
            notFound(entry)
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
        promptByCode = true
        try {
            prompt.setText(text)
        } finally {
            promptByCode = false
        }
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

    private fun Int.dp(): Int = dp(this)

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // Tag rows and app rows are the same monospace TextView, so a recycled view
            // is reused across both freely.
            val tv = (convertView as? TextView) ?: OutlinedTextView(this@MainActivity).apply {
                setTextColor(palette.listText)
                setOutline(outlinePx, palette.listOutline)
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                val v = 10.dp()
                setPadding(0, v, 0, v)
            }
            // Every bind, not only on creation: a recycled row may predate a palette switch.
            tv.setTextColor(palette.listText)
            (tv as? OutlinedTextView)?.setOutline(outlinePx, palette.listOutline)
            when (val row = shown[position]) {
                // Tag overview (bare "#"): plain tag name; a screen reader reads it fine,
                // so clear any stale edit-mode description from the recycled view.
                is TagRow -> {
                    tv.typeface = listTypeface
                    tv.text = row.name
                    tv.contentDescription = null
                    tv.alpha = 1f
                }
                // "~" overview: the command's own spelling is the label, and it reads
                // aloud as-is, so clear any stale edit-mode description off the recycled view.
                is CommandRow -> {
                    tv.typeface = Typeface.MONOSPACE  // commands are typed text: show them so
                    tv.text = row.name
                    tv.contentDescription = null
                    tv.alpha = 1f
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
            // A paused or archived app is dimmed: still there, but a tap brings it back first.
            tv.alpha = HomeStyle.rowAlpha(entry.status)
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
                // The read modes read the label as-is — unless the dimming says something a
                // screen reader would otherwise not hear.
                Mode.NORMAL, Mode.RECENTS, Mode.TAG_FILTER, Mode.COMMAND -> unavailableDescription(this@MainActivity, entry)
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REQ_RESTORE = 1
        // How long a "~" command runs before its busy layer shows the wheel (see showBusy).
        const val BUSY_WHEEL_DELAY_MS = 300L
        // How long before the busy layer offers its way out (Back) — far beyond any healthy
        // command, which ends within a second or two (see showBusy).
        const val BUSY_ESCAPE_MS = 10_000L
        /** What [stillThere] finds an app by: also disabled and uninstalled-with-data ones. */
        const val STILL_THERE_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        /** Kolibri's page turn: the drawer's full-height slide (anim_duration_drawer_slide)… */
        const val DRAWER_SLIDE_MS = 180L
        /** …and home's fade underneath it (fade_in / fade_out). */
        const val HOME_FADE_MS = 200L
        /** The rename dialog's field text — light, on the always-dark dialog. */
        const val DIALOG_TEXT = 0xFFD4D4D4.toInt()
        /** A return home re-enumerates anyway once the last load is this old (see onResume). */
        const val REFRESH_STALE_MS = 5 * 60 * 1000L
        /** How long a paused profile's "ready" toast stays expected after the tap (see launch). */
        const val PROFILE_WAIT_MS = 2 * 60 * 1000L
        /** A page drag that does not commit settles back this fast (Unity's settle-back). */
        const val DRAG_SETTLE_MS = 220L
        /** How far a back gesture's preview pulls the drawer down, as a fraction of the page. */
        const val BACK_PREVIEW_FRACTION = 0.25f
        /** How far home fades in by the end of a back preview. */
        const val BACK_PREVIEW_HOME_ALPHA = 0.5f
        /** How long a page turn runs, i.e. how long taps are swallowed (see swallowTaps). */
        const val PAGE_TURN_MS = HOME_FADE_MS
        /** What a swipe down on home opens the drawer on: the recently used apps. */
        const val RECENTS_PROMPT = "?"
    }
}
