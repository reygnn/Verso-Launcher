package com.github.reygnn.verso_launcher

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView

/**
 * The long-press "custom name and tags" dialog. It hands a trimmed name and canonical tags to
 * [onCommit]; what an empty field means is the caller's decision.
 */
internal object RenameDialog {

    /** The caller owns the returned dialog (dismiss on teardown). */
    fun show(
        context: Context,
        title: String,
        initialName: String,
        initialTags: String,
        allTags: List<String>,
        fgColor: Int,
        onAppInfo: () -> Unit,
        onCommit: (name: String, tags: List<String>) -> Unit,
    ): AlertDialog {

        val noSuggest = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val nameInput = EditText(context).apply {
            filters = arrayOf(
                InputFilter { source, start, end, _, _, _ ->
                    val sub = source.subSequence(start, end).toString()
                    val kept = sub.filter { !it.isISOControl() && !LauncherLogic.isSpoofChar(it) }
                    if (kept == sub) null else kept
                },
                InputFilter.LengthFilter(LauncherLogic.MAX_NAME_LENGTH),
            )
            setText(initialName)
            hint = context.getString(R.string.hint_rename_name)
            isSingleLine = true
            inputType = noSuggest
            setSelection(text.length)
        }
        val tagsInput = MultiAutoCompleteTextView(context).apply {
            setText(initialTags)
            hint = context.getString(R.string.hint_rename_tags)
            isSingleLine = true
            inputType = noSuggest
            // Not every keyboard honours "no caps"; the fold makes the field show what is stored.
            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                val sub = source.subSequence(start, end).toString()
                val folded = LauncherLogic.foldLabel(sub)
                if (folded == sub) null else folded
            })
            setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            threshold = 1
            // getView is fully overridden, so the layout id is never inflated.
            setAdapter(object : ArrayAdapter<String>(
                context, android.R.layout.simple_list_item_1, allTags
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = (convertView as? TextView) ?: TextView(context).apply {
                        typeface = Typeface.MONOSPACE
                        setTextColor(fgColor)
                        textSize = 18f
                        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
                    }
                    tv.text = getItem(position)
                    return tv
                }
            })
            setDropDownBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(6), context.dp(12), 0)
            addView(nameInput, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(tagsInput, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val tags = LauncherLogic.parseTags(tagsInput.text?.toString().orEmpty())
                onCommit(name, tags)
            }
            .setNeutralButton(R.string.action_app_info) { _, _ -> onAppInfo() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
