package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws each translation exactly over the original text's bounds, at the original font size
 * (shrunk only as much as needed to fit), in the original background and text colors when known.
 */
class TranslationOverlayView(context: Context) : View(context) {

    /**
     * [original] is used to estimate the on-screen font size so the translation matches it.
     * [background] and [foreground] are the colors sampled from the screen, or null if unknown.
     */
    data class Item(
        val bounds: Rect,
        val text: String,
        val original: String,
        val background: Int?,
        val foreground: Int?,
        /** How far right the box may grow without covering other text, icons or controls. */
        val maxRight: Int,
    )

    private class Placed(val rect: RectF, val layout: StaticLayout, val background: Int)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val defaultBackground = if (night) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
    private val defaultForeground = if (night) 0xFFE8EAED.toInt() else 0xFF1F1F1F.toInt()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val origin = IntArray(2)
    private val minSize = dp(7f)
    private val pad = dp(2f)
    private var placed: List<Placed> = emptyList()
    private val cache = HashMap<String, Placed>()

    fun setItems(items: List<Item>) {
        val used = HashMap<String, Placed>()
        placed = items.map { item ->
            val key = "${item.bounds.flattenToString()}|${item.text}|${item.background}|${item.foreground}|${item.maxRight}"
            val p = cache[key]?.let { Placed(RectF(it.rect), it.layout, it.background) } ?: place(item)
            used[key] = Placed(RectF(p.rect), p.layout, p.background)
            p
        }
        cache.clear()
        cache.putAll(used)
        invalidate()
    }

    /** Moves boxes inside the scrolled view along with the content until the next refresh. */
    fun scrollBy(dx: Int, dy: Int, area: Rect?) {
        if (dx == 0 && dy == 0) {
            // Unknown scroll amount: hide what might now be in the wrong place.
            placed = placed.filterNot { area == null || area.contains(it.rect.centerX().toInt(), it.rect.centerY().toInt()) }
        } else {
            for (p in placed) {
                if (area == null || area.contains(p.rect.centerX().toInt(), p.rect.centerY().toInt())) {
                    p.rect.offset(-dx.toFloat(), -dy.toFloat())
                }
            }
        }
        invalidate()
    }

    private fun build(text: String, size: Float, width: Int, color: Int, maxLines: Int = Int.MAX_VALUE): StaticLayout {
        val paint = TextPaint(textPaint).apply {
            textSize = size
            this.color = color
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .build()
    }

    /** Largest font size at which the original text fits its box, which approximates the app's font size. */
    private fun originalSize(original: String, w: Int, h: Int): Float {
        var lo = minSize
        var hi = max(minSize, min(h.toFloat(), dp(40f)))
        repeat(7) {
            val mid = (lo + hi) / 2
            if (build(original, mid, w, 0).height <= h) lo = mid else hi = mid
        }
        return lo
    }

    private fun place(item: Item): Placed {
        val w0 = max(1, item.bounds.width())
        val h = max(1, item.bounds.height())
        val color = item.foreground ?: defaultForeground

        // Same spot and height as the original. A longer translation may first use free space to
        // the right on the same line, then shrinks to fit.
        var size = originalSize(item.original, w0, h)
        var w = w0
        if (build(item.text, size, w0, color).height > h) {
            textPaint.textSize = size
            val oneLine = textPaint.measureText(item.text).toInt() + 2
            w = min(oneLine, item.maxRight - item.bounds.left).coerceAtLeast(w0)
        }
        val rect = RectF(item.bounds).apply { right = left + w; inset(-pad * 2, -pad) }
        var layout = build(item.text, size, w, color)
        while (layout.height > h && size > minSize) {
            size = max(minSize, size * 0.92f)
            layout = build(item.text, size, w, color)
        }
        if (layout.height > h) {
            val lines = max(1, h / max(1, layout.height / max(1, layout.lineCount)))
            layout = build(item.text, size, w, color, lines)
        }
        return Placed(rect, layout, item.background ?: defaultBackground)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val radius = dp(2f)
        for (p in placed) {
            boxPaint.color = p.background
            canvas.drawRoundRect(p.rect, radius, radius, boxPaint)
            canvas.save()
            canvas.clipRect(p.rect)
            canvas.translate(p.rect.left + pad * 2, p.rect.top + (p.rect.height() - p.layout.height) / 2f)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }
}
