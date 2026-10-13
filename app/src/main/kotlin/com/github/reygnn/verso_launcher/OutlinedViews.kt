package com.github.reygnn.verso_launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView

/**
 * A thin, crisp outline around a TextView's glyphs — Kolibri's wallpaper-independent
 * replacement for a drop shadow or a scrim, ported to plain platform widgets (Kolibri's
 * versions extend the AppCompat classes, which Verso does not ship).
 *
 * The glyphs are painted twice: first STROKED in the outline colour, then filled normally on
 * top. The fill covers the inner half of the centred stroke, so only its outer half shows as a
 * contour — it wins contrast locally against both dark and light wallpaper pixels at once.
 *
 * TextView.onDraw takes the paint colour from the view's text colour, so the colour swap has
 * to go through setTextColor — which invalidates. The host view therefore ignores invalidate()
 * while it is mid-draw, or the two swaps would schedule redraws forever. That flag lives on the
 * host as a primitive (false by default): View's own constructor calls invalidate() before any
 * field of this helper would be initialised.
 */
internal class TextOutline {
    private var widthPx = 0f
    private var strokeColors: ColorStateList? = null
    private var savedFill: ColorStateList? = null

    val isActive: Boolean get() = widthPx > 0f && strokeColors != null

    /** [Color.TRANSPARENT] switches the outline off. */
    fun set(widthPx: Float, color: Int) {
        this.widthPx = widthPx
        strokeColors = if (color == Color.TRANSPARENT) null else ColorStateList.valueOf(color)
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

/** A TextView with a [TextOutline]: the battery line, the favorites, the hint. */
internal class OutlinedTextView(context: Context) : TextView(context) {
    private val outline = TextOutline()
    private var drawingOutline = false

    fun setOutline(widthPx: Float, color: Int) {
        outline.set(widthPx, color)
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
        outline.beginStroke(this)
        super.onDraw(canvas)
        outline.endStroke(this)
        drawingOutline = false
        super.onDraw(canvas)
    }
}

/** The same for TextClock (clock and date tick by themselves). */
internal class OutlinedTextClock(context: Context) : TextClock(context) {
    private val outline = TextOutline()
    private var drawingOutline = false

    fun setOutline(widthPx: Float, color: Int) {
        outline.set(widthPx, color)
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
        outline.beginStroke(this)
        super.onDraw(canvas)
        outline.endStroke(this)
        drawingOutline = false
        super.onDraw(canvas)
    }
}

/**
 * The icon counterpart (charging bolt, protection shield). Its drawable is a two-layer
 * LayerDrawable (ic_*_outlined.xml, from Kolibri): a fatter-stroke copy underneath and the
 * normal icon on top. The top layer is tinted with the text colour, the layer beneath with the
 * outline colour — so the icon gets the same crisp rim as the text beside it. SRC_IN (not the
 * SRC_ATOP default of setColorFilter(int)) so a translucent outline colour stays translucent
 * instead of letting the drawable's own white bleed through.
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
