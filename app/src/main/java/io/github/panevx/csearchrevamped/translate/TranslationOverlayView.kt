package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Draws translated text boxes over the original text, positioned in screen coordinates. */
class TranslationOverlayView(context: Context) : View(context) {

    /** [original] is used to estimate the on-screen font size so the translation matches it. */
    data class Item(val bounds: Rect, val text: String, val original: String)

    private class Placed(val rect: RectF, val layout: StaticLayout)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0xFF202124.toInt() else 0xFFFFFFFF.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0xFFE8EAED.toInt() else 0xFF1F1F1F.toInt()
    }
    private val origin = IntArray(2)
    private val pad = dp(3f)
    private val minSize = dp(9f)
    private var placed: List<Placed> = emptyList()
    private val cache = HashMap<String, Placed>()

    fun setItems(items: List<Item>) {
        val screenWidth = resources.displayMetrics.widthPixels
        val used = HashMap<String, Placed>()
        placed = items.map { item ->
            val key = "${item.bounds.flattenToString()}|${item.text}"
            val p = cache[key]?.let { Placed(RectF(it.rect), it.layout) } ?: place(item, screenWidth)
            used[key] = Placed(RectF(p.rect), p.layout)
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

    private fun build(text: String, size: Float, width: Int): StaticLayout {
        val paint = TextPaint(textPaint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setIncludePad(false)
            .build()
    }

    /** Largest font size at which the original text fits its box, which approximates the app's font size. */
    private fun originalSize(original: String, w: Int, h: Int): Float {
        var lo = minSize
        var hi = max(minSize, min(h.toFloat(), dp(40f)))
        repeat(7) {
            val mid = (lo + hi) / 2
            if (build(original, mid, w).height <= h) lo = mid else hi = mid
        }
        return lo
    }

    private fun place(item: Item, screenWidth: Int): Placed {
        val b = item.bounds
        val w0 = max(1, b.width())
        val h0 = max(1, b.height())
        var size = originalSize(item.original, w0, h0)
        // Grow sideways (up to the screen edge) before wrapping, since translations are often longer.
        val maxWidth = max(w0, screenWidth - b.left - dp(8f).toInt())
        textPaint.textSize = size
        val width = min(maxWidth.toFloat(), max(w0.toFloat(), textPaint.measureText(item.text) + 1f)).toInt()
        var layout = build(item.text, size, width)
        while (layout.height > h0 * 1.5f && size > minSize) {
            size = max(minSize, size * 0.92f)
            layout = build(item.text, size, width)
        }
        val height = max(h0, layout.height)
        val rect = RectF(b.left - pad, b.top - pad, b.left + width + pad, b.top + height + pad)
        return Placed(rect, layout)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val radius = dp(4f)
        for (p in placed) {
            canvas.drawRoundRect(p.rect, radius, radius, boxPaint)
            canvas.save()
            canvas.clipRect(p.rect)
            val top = p.rect.top + (p.rect.height() - p.layout.height) / 2f
            canvas.translate(p.rect.left + pad, top)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }
}
