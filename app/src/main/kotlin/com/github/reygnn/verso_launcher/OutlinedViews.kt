package com.github.reygnn.verso_launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.LayerDrawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView

/**
 * A crisp outline around a TextView's glyphs (Kolibri's): stroked in the outline colour, then
 * filled on top, so only the stroke's outer half shows.
 *
 * The colour swap must go through setTextColor, which invalidates, so the host ignores
 * invalidate() mid-draw or it redraws forever. That flag is a primitive on the host: View's
 * constructor calls invalidate() before this helper's fields are initialised.
 */
internal class TextOutline {
    private var widthPx = 0f
    private var color = Color.TRANSPARENT
    private var strokeColors: ColorStateList? = null
    private var savedFill: ColorStateList? = null

    val isActive: Boolean get() = widthPx > 0f && strokeColors != null

    /**
     * [Color.TRANSPARENT] switches the outline off. False when nothing changed: drawer rows set
     * it on every bind (a recycled row may predate a palette switch), and only a change redraws.
     */
    fun set(widthPx: Float, color: Int): Boolean {
        if (widthPx == this.widthPx && color == this.color) return false
        this.widthPx = widthPx
        this.color = color
        strokeColors = if (color == Color.TRANSPARENT) null else ColorStateList.valueOf(color)
        return true
    }

    fun beginStroke(view: TextView) {
        savedFill = view.textColors
        view.paint.style = Paint.Style.STROKE
        view.paint.strokeJoin = Paint.Join.ROUND
        view.paint.strokeWidth = widthPx
        strokeColors?.let { view.setTextColor(it) }
    }

    fun endStroke(view: TextView) {
        view.paint.style = Paint.Style.FILL
        savedFill?.let { view.setTextColor(it) }
    }
}

/** A TextView with a [TextOutline]: the battery line, the favorites, the hint, the drawer rows. */
internal class OutlinedTextView(context: Context) : TextView(context) {
    private val outline = TextOutline()
    private var drawingOutline = false

    fun setOutline(widthPx: Float, color: Int) {
        if (outline.set(widthPx, color)) invalidate()
    }

    override fun invalidate() {
        if (drawingOutline) return
        super.invalidate()
    }

    /** At reduced alpha (a dimmed row) stroke and fill must fade as one, or the outline shows through. */
    override fun hasOverlappingRendering(): Boolean = outline.isActive

    override fun onDraw(canvas: Canvas) {
        if (!outline.isActive) {
            super.onDraw(canvas)
            return
        }
        drawingOutline = true
        outline.beginStroke(this)
        super.onDraw(canvas)
        outline.endStroke(this)
        drawingOutline = false
        super.onDraw(canvas)
    }
}

/**
 * The command line with the outline. The stroke pass also strokes the hint and clears the
 * selection highlight, or a translucent selection would be drawn twice; the cursor is opaque,
 * drawn twice in the same place.
 */
internal class OutlinedEditText(context: Context) : EditText(context) {
    private val outline = TextOutline()
    private var drawingOutline = false
    private var outlineColors: ColorStateList? = null

    fun setOutline(widthPx: Float, color: Int) {
        if (!outline.set(widthPx, color)) return
        outlineColors = ColorStateList.valueOf(color)
        invalidate()
    }

    override fun invalidate() {
        if (drawingOutline) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!outline.isActive) {
            super.onDraw(canvas)
            return
        }
        drawingOutline = true
        val hint = hintTextColors
        val highlight = highlightColor
        outline.beginStroke(this)
        outlineColors?.let { setHintTextColor(it) }
        highlightColor = Color.TRANSPARENT
        super.onDraw(canvas)
        outline.endStroke(this)
        setHintTextColor(hint)
        highlightColor = highlight
        drawingOutline = false
        super.onDraw(canvas)
    }
}

/** The same for TextClock (clock and date tick by themselves). */
internal class OutlinedTextClock(context: Context) : TextClock(context) {
    private val outline = TextOutline()
    private var drawingOutline = false

    fun setOutline(widthPx: Float, color: Int) {
        if (outline.set(widthPx, color)) invalidate()
    }

    override fun invalidate() {
        if (drawingOutline) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!outline.isActive) {
            super.onDraw(canvas)
            return
        }
        drawingOutline = true
        outline.beginStroke(this)
        super.onDraw(canvas)
        outline.endStroke(this)
        drawingOutline = false
        super.onDraw(canvas)
    }
}

/**
 * The icon counterpart: a two-layer LayerDrawable (ic_*_outlined.xml), a fatter copy beneath
 * tinted with the outline colour. SRC_IN, not setColorFilter(int)'s SRC_ATOP, so a translucent
 * outline does not let the drawable's own white bleed through.
 */
internal class OutlinedImageView(context: Context) : ImageView(context) {
    private var iconColor = Color.WHITE
    private var outlineColor = Color.TRANSPARENT

    fun setColors(icon: Int, outline: Int) {
        iconColor = icon
        outlineColor = outline
        applyLayerColors()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        applyLayerColors()
    }

    private fun applyLayerColors() {
        val current = drawable ?: return
        if (current is LayerDrawable && current.numberOfLayers >= 2) {
            val fill = current.numberOfLayers - 1
            current.getDrawable(fill).colorFilter = BlendModeColorFilter(iconColor, BlendMode.SRC_IN)
            for (i in 0 until fill) {
                val layer = current.getDrawable(i)
                if (outlineColor != Color.TRANSPARENT) {
                    layer.alpha = 255
                    layer.colorFilter = BlendModeColorFilter(outlineColor, BlendMode.SRC_IN)
                } else {
                    layer.alpha = 0
                }
            }
        } else {
            current.colorFilter = BlendModeColorFilter(iconColor, BlendMode.SRC_IN)
        }
        invalidate()
    }
}
