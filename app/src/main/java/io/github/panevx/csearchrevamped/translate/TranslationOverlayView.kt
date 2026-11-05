package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws each translation over the original text's bounds, matching the original font size,
 * line spacing, alignment and colors as measured from the screen when available.
 */
class TranslationOverlayView(context: Context) : View(context) {

    /** What was measured from the pixels of the original text. */
    class Ink(
        /** Font size in px estimated from the height of the letters. */
        val textSize: Float,
        /** Distance between lines in px, or 0 for a single line. */
        val linePitch: Float,
        val centered: Boolean,
        /** Where the letters are, relative to the bounds; null if the bounds changed since measuring. */
        val area: Rect?,
    )

    /**
     * [original] is used to estimate the font size when it wasn't measured.
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
        /** 1 when the original continues below the visible area, -1 when it starts above it. */
        val cut: Int = 0,
        /** Things on top of the original (pop-ups, banners, buttons): never paint over them. */
        val covers: List<Rect> = emptyList(),
        val ink: Ink? = null,
    )

    private class Placed(
        val rect: RectF,
        val layout: StaticLayout,
        val textLeft: Float,
        val textTop: Float,
        val background: Int,
        var covers: List<Rect> = emptyList(),
    )

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
    private val sizeCache = HashMap<String, Float>()

    fun setItems(items: List<Item>) {
        // Text cut off at the screen edge can't reveal its font size from its bounds, so without
        // a measurement it uses the page's usual size.
        val sizes = items.filter { it.cut == 0 }
            .map { it.ink?.textSize ?: originalSize(it.original, max(1, it.bounds.width()), max(1, it.bounds.height())) }
            .sorted()
        val typical = if (sizes.isEmpty()) dp(16f) else sizes[sizes.size / 2]
        val used = HashMap<String, Placed>()
        placed = items.map { item ->
            val ink = item.ink
            val key = "${item.bounds.flattenToString()}|${item.text}|${item.background}|${item.foreground}|" +
                "${item.maxRight}|${item.cut}|$typical|${ink?.textSize}|${ink?.area}"
            val p = cache[key] ?: place(item, typical)
            used[key] = p
            Placed(p.rect, p.layout, p.textLeft, p.textTop, p.background, item.covers)
        }
        cache.clear()
        cache.putAll(used)
        invalidate()
    }

    private fun build(
        text: String,
        size: Float,
        width: Int,
        color: Int,
        spacing: Float = 1f,
        centered: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
    ): StaticLayout {
        val paint = TextPaint(textPaint).apply {
            textSize = size
            this.color = color
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setIncludePad(false)
            .setLineSpacing(0f, spacing)
            .setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .build()
    }

    /** Largest font size at which the original text fits its box: an upper bound for the app's font size. */
    private fun originalSize(original: String, w: Int, h: Int): Float {
        val key = "$w:$h:$original"
        sizeCache[key]?.let { return it }
        if (sizeCache.size > 2000) sizeCache.clear()
        var lo = minSize
        var hi = max(minSize, min(h.toFloat(), dp(40f)))
        repeat(7) {
            val mid = (lo + hi) / 2
            if (build(original, mid, w, 0).height <= h) lo = mid else hi = mid
        }
        sizeCache[key] = lo
        return lo
    }

    private fun place(item: Item, typical: Float): Placed {
        val b = item.bounds
        val w0 = max(1, b.width())
        val h = max(1, b.height())
        val color = item.foreground ?: defaultForeground
        val ink = item.ink
        val rect = RectF(b).apply { inset(-pad * 2, -pad) }
        val background = item.background ?: defaultBackground
        val centered = ink?.centered == true

        // Font size: measured from the letters when possible (never larger than what fits the box).
        val fit = if (item.cut == 0) originalSize(item.original, w0, h) else Float.MAX_VALUE
        // Body text on a page shares one size: measurements close to the page's usual size snap to it,
        // so neighbouring paragraphs match. Headings and small print keep their own size.
        val measured = ink?.textSize?.takeIf { it > 0 }?.let { if (abs(it - typical) < typical * 0.2f) typical else it }
        var size = when {
            // Cut-off text can't be checked against its box, so don't trust a measurement far from the page's usual size.
            measured != null && item.cut != 0 -> min(measured, typical * 1.4f)
            measured != null -> min(measured, fit)
            item.cut != 0 -> typical
            else -> fit
        }.coerceAtLeast(minSize)
        // Keep the original's line spacing, so the translation has the same rhythm.
        val spacing = if (ink != null && ink.linePitch > 0) (ink.linePitch / (size * 1.17f)).coerceIn(1f, 2.2f) else 1f

        // Start where the original letters start (left-aligned text), or use the full width (centered).
        val inset = if (ink?.area != null && !centered) (ink.area.left - dp(1f)).coerceIn(0f, w0 / 3f) else 0f
        var w = (w0 - inset).toInt()

        if (item.cut != 0) {
            // Only part of the original is visible: anchor the translation to the visible edge;
            // the rest is clipped like the original.
            val layout = build(item.text, size, w, color, spacing, centered)
            val top = if (item.cut > 0) {
                b.top + (ink?.area?.top?.toFloat() ?: 0f) - size * 0.2f
            } else {
                b.bottom - layout.height.toFloat()
            }
            return Placed(rect, layout, b.left + inset, top, background)
        }

        var layout = build(item.text, size, w, color, spacing, centered)
        if (layout.height > h && !centered) {
            // Longer than the original: use free space to the right on the same line first.
            textPaint.textSize = size
            val oneLine = textPaint.measureText(item.text).toInt() + 2
            w = min(oneLine, (item.maxRight - b.left - inset).toInt()).coerceAtLeast(w)
            layout = build(item.text, size, w, color, spacing, centered)
            rect.right = max(rect.right, b.left + inset + w + pad * 2)
        }
        // Then shrink until it fits the original's height.
        while (layout.height > h && size > minSize) {
            size = max(minSize, size * 0.93f)
            layout = build(item.text, size, w, color, 1f, centered)
        }
        if (layout.height > h) {
            val lines = max(1, h / max(1, layout.height / max(1, layout.lineCount)))
            layout = build(item.text, size, w, color, 1f, centered, lines)
        }

        // Vertically centre on where the original letters were (or on the box).
        val inkTop = ink?.area?.top ?: 0
        val inkBottom = ink?.area?.bottom ?: h
        val center = b.top + (inkTop + inkBottom) / 2f
        val top = center - layout.height / 2f
        val clamped = top.coerceIn(b.top.toFloat(), max(b.top.toFloat(), b.bottom - layout.height.toFloat()))
        return Placed(rect, layout, b.left + inset, clamped, background)
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        val radius = dp(2f)
        for (p in placed) {
            canvas.save()
            for (c in p.covers) canvas.clipOutRect(c)
            boxPaint.color = p.background
            canvas.drawRoundRect(p.rect, radius, radius, boxPaint)
            canvas.clipRect(p.rect)
            canvas.translate(p.textLeft, p.textTop)
            p.layout.draw(canvas)
            canvas.restore()
        }
    }
}
