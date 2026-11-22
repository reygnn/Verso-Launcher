package com.github.reygnn.verso_launcher

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The command line at the foot of the drawer: the one-line field and the "×" that clears it. It
 * says when its text changed and when an Enter acts; what either means is [AppDrawer]'s.
 *
 * @param onChanged the field's text after every change, typed or set. It is called before the
 *   change returns, so a caller that sets a text finds it filtered.
 * @param onEnter the one call per Enter that acts ([LauncherLogic.enterEdge]).
 */
internal class DrawerPrompt(
    private val activity: Activity,
    palette: Palette,
    private val onChanged: (String) -> Unit,
    private val onEnter: () -> Unit,
) {
    // Recoloured in place: the Editor loads its cursor drawable once and ignores a new one.
    private val cursorDrawable = GradientDrawable()

    private val clearButton = OutlinedTextView(activity).apply {
        text = "×"
        typeface = Typeface.MONOSPACE
        textSize = HomeStyle.DRAWER_CLEAR_SP
        setTextColor(palette.text)
        gravity = Gravity.CENTER
        minWidth = activity.dp(48)
        contentDescription = activity.getString(R.string.clear_prompt)
        isFocusable = false  // keep focus (and the IME) on the prompt when tapped
        visibility = View.GONE
        setOnClickListener {
            set("")
            showKeyboard()
        }
    }

    private val edit = OutlinedEditText(activity).apply {
        // In the text colour, set apart by the slant alone.
        hint = SpannableString(activity.getString(R.string.hint_search)).apply {
            setSpan(StyleSpan(Typeface.ITALIC), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        setHintTextColor(palette.text)
        setTextColor(palette.text)
        textCursorDrawable = cursorDrawable.apply { setSize(activity.dp(2), 1) }
        typeface = Typeface.MONOSPACE
        textSize = HomeStyle.DRAWER_PROMPT_SP
        background = null
        // Room for the outline's outer half: the view clips at its bounds.
        val pad = activity.dp(2)
        setPadding(pad, pad, pad, pad)
        isSingleLine = true
        // A pasted line break would only look like a space here: it becomes one (LauncherLogic.promptRun).
        filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
            LauncherLogic.promptRun(source.subSequence(start, end))
        })
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_GO
        accessibilityDelegate = SpokenHint()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                clearButton.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                onChanged(text)
            }
        })
        setOnEditorActionListener { _, actionId, event ->
            // A key Enter calls this for down, repeats and up: only the first down acts.
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
            onEnter()
            true
        }
    }

    /** The row: the field, then the "×". */
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(activity.dp(8), 0, activity.dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = activity.dp(48)
        addView(edit, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(clearButton, LinearLayout.LayoutParams(WRAP, MATCH))
    }

    val text: String get() = edit.text?.toString().orEmpty()

    /** Caret at the end: setText alone parks it at 0, in front of a drilled "#tag". */
    fun set(text: String) {
        edit.setText(text)
        edit.setSelection(text.length)
    }

    /** A character typed elsewhere (the home page, the list) lands here, with the focus. */
    fun type(c: String) {
        edit.requestFocus()
        edit.append(c)
        edit.setSelection(edit.length())
    }

    /** The drawer is turning away: the field must not keep the focus (and with it the IME). */
    fun releaseFocus() = edit.clearFocus()

    fun showKeyboard() {
        // Not InputMethodManager.showSoftInput: unreliable straight from onResume.
        edit.requestFocus()
        edit.windowInsetsController?.show(WindowInsets.Type.ime())
    }

    fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    fun applyPalette(p: Palette) {
        val outlinePx = HomeStyle.OUTLINE_DP * activity.resources.displayMetrics.density
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.text)
        clearButton.setTextColor(p.text)
        edit.setOutline(outlinePx, p.outline)
        clearButton.setOutline(outlinePx, p.outline)
        // The theme's accent all but vanishes on a light wallpaper.
        cursorDrawable.setColor(p.text)
        edit.invalidate()
        edit.highlightColor = p.selection
        edit.textSelectHandle?.let { edit.setTextSelectHandle(it.mutate().apply { setTint(p.text) }) }
        edit.textSelectHandleLeft?.let { edit.setTextSelectHandleLeft(it.mutate().apply { setTint(p.text) }) }
        edit.textSelectHandleRight?.let { edit.setTextSelectHandleRight(it.mutate().apply { setTint(p.text) }) }
    }

    /**
     * A spoken hint that names the page.
     *
     * Why: TalkBack reads the visible hint's ">" as "greater than", and on a page turn it speaks the
     *   focused field, not the pane title (seen on the A17).
     */
    private inner class SpokenHint : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            val spoken = activity.getString(R.string.a11y_prompt_hint)
            info.hintText = spoken
            // Empty, the field reports its hint as its text too.
            if (info.isShowingHintText) info.text = spoken
        }

        // The focus event carries the visible hint as well, and that is what TalkBack speaks
        // as the drawer opens (the node alone changed nothing on the A17).
        override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) {
            super.onPopulateAccessibilityEvent(host, event)
            if ((host as TextView).text.isNullOrEmpty()) {
                event.text.clear()
                event.text.add(activity.getString(R.string.a11y_prompt_hint))
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
