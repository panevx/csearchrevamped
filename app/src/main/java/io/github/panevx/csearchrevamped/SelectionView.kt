package io.github.panevx.csearchrevamped

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Shows the frozen screenshot and lets the user circle, scribble or tap a region. */
class SelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** A finger touched down; any pending auto-search should be cancelled. */
    var onGestureStart: (() -> Unit)? = null

    /** A region was selected. [deliberate] is true for circles and scribbles, false for taps. */
    var onSelected: ((deliberate: Boolean) -> Unit)? = null

    /** The gesture looked accidental and was ignored; the previous selection is kept. */
    var onRejected: (() -> Unit)? = null

    // ---- Direct text selection (tap on text). Circles keep working exactly as before. ----

    /** A word read by OCR from the screenshot, in reading order. [box] is in screenshot pixels. */
    class Word(val text: String, val box: Rect, val line: Int)

    /** Words on the screenshot (original, untranslated text); empty until OCR has finished. */
    var words: List<Word> = emptyList()

    /** Selected text and its bounds on screen, or null when text selection ends. */
    var onTextSelection: ((text: String?, bounds: RectF?) -> Unit)? = null

    val inTextMode: Boolean get() = selStart >= 0

    private var selStart = -1
    private var selEnd = -1
    /** 0 = none, 1 = dragging the start handle, 2 = dragging the end handle. */
    private var dragging = 0
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x667C4DFF }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7C4DFF.toInt() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Current stroke
    private val path = Path()
    private var drawing = false
    private var pathAlpha = 0f
    private var downTime = 0L
    private var pathLength = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f

    // Selection: [selection] is the final rect, [shown] is what is drawn while animating.
    private var selection: RectF? = null
    private val shown = RectF()
    private var frameAlpha = 0f
    private var appear = 0f
    private var animator: ValueAnimator? = null

    private val dst = Rect()
    private val hole = Path()
    private val shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(14f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.NORMAL)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener { appear = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val colors = intArrayOf(
            Color.parseColor("#4FC3F7"), Color.parseColor("#7C4DFF"),
            Color.parseColor("#FF4081"), Color.parseColor("#FFC107"),
        )
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.MIRROR).also {
            strokePaint.shader = it
            glowPaint.shader = it
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, bitmapPaint)

        // Let the gradient flow slowly so the strokes shimmer.
        val phase = (SystemClock.uptimeMillis() % 4000L) / 4000f * 2f
        shaderMatrix.setTranslate(phase * width, phase * height)
        shader?.setLocalMatrix(shaderMatrix)

        val radius = dp(20f)
        val hasSelection = frameAlpha > 0f && !shown.isEmpty && !inTextMode
        val dim = when {
            inTextMode -> 0x30
            hasSelection -> 0x80
            else -> 0x60
        }
        dimPaint.alpha = (dim * min(1f, appear * 2f)).toInt()
        if (hasSelection) {
            canvas.save()
            hole.reset()
            hole.addRoundRect(shown, radius, radius, Path.Direction.CW)
            canvas.clipOutPath(hole)
            canvas.drawRect(dst, dimPaint)
            canvas.restore()
        } else {
            canvas.drawRect(dst, dimPaint)
        }

        // Opening sweep: a glow runs around the screen edge and fades away.
        if (appear < 1f) {
            val a = sin(appear * PI).toFloat()
            val inset = dp(2f)
            val edge = RectF(inset, inset, width - inset, height - inset)
            drawStroke(canvas, a) { p -> canvas.drawRoundRect(edge, dp(28f), dp(28f), p) }
        }

        if (hasSelection) {
            val a = frameAlpha * if (drawing) 0.4f else 1f
            drawStroke(canvas, a) { p -> canvas.drawRoundRect(shown, radius, radius, p) }
        }
        if (pathAlpha > 0f) drawStroke(canvas, pathAlpha) { p -> canvas.drawPath(path, p) }
        if (inTextMode) drawTextSelection(canvas)

        postInvalidateOnAnimation()
    }

    private inline fun drawStroke(canvas: Canvas, alpha: Float, draw: (Paint) -> Unit) {
        glowPaint.alpha = (150 * alpha).toInt()
        strokePaint.alpha = (255 * alpha).toInt()
        draw(glowPaint)
        draw(strokePaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (handleTextTouch(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                drawing = true
                pathAlpha = 1f
                downTime = e.eventTime
                pathLength = 0f
                path.reset()
                path.moveTo(e.x, e.y)
                lastX = e.x; lastY = e.y
                minX = e.x; maxX = e.x; minY = e.y; maxY = e.y
                Haptics.tick(context)
                onGestureStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) extend(e.getHistoricalX(i), e.getHistoricalY(i))
                extend(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                drawing = false
                extend(e.x, e.y)
                finishGesture(e)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                fadeOutPath()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun extend(x: Float, y: Float) {
        path.lineTo(x, y)
        pathLength += hypot(x - lastX, y - lastY)
        lastX = x; lastY = y
        minX = min(minX, x); maxX = max(maxX, x)
        minY = min(minY, y); maxY = max(maxY, y)
    }

    private fun finishGesture(e: MotionEvent) {
        val duration = e.eventTime - downTime
        val tap = pathLength < dp(12f)
        val size = max(maxX - minX, maxY - minY)
        val edge = dp(4f)
        val offScreen = lastX <= edge || lastY <= edge || lastX >= width - edge || lastY >= height - edge

        val accidental = !tap && (size < dp(40f) || duration < 100 || offScreen)
        if (accidental) {
            fadeOutPath()
            Haptics.tick(context)
            onRejected?.invoke()
            return
        }

        if (tap) {
            val word = wordAt(lastX, lastY)
            if (word >= 0) {
                // Tap on text: select the word instead of a screenshot region.
                fadeOutPath()
                startTextSelection(word)
                return
            }
        }
        // Any other gesture leaves text selection and works exactly as before.
        if (inTextMode) endTextSelection()

        val target = RectF(minX, minY, maxX, maxY)
        val from = RectF(target)
        if (tap) {
            val half = dp(110f)
            target.set(target.centerX() - half, target.centerY() - half, target.centerX() + half, target.centerY() + half)
            from.inset(-dp(10f), -dp(10f))
        } else {
            target.inset(-dp(8f), -dp(8f))
            val minSide = dp(56f)
            if (target.width() < minSide) target.inset((target.width() - minSide) / 2f, 0f)
            if (target.height() < minSide) target.inset(0f, (target.height() - minSide) / 2f)
        }
        target.intersect(0f, 0f, width.toFloat(), height.toFloat())
        selection = target
        Haptics.click(context)
        animateTo(from, target)
        onSelected?.invoke(!tap)
    }

    /** Morphs the drawn stroke into the rounded selection box. */
    private fun animateTo(from: RectF, to: RectF) {
        val startPathAlpha = pathAlpha
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                shown.set(
                    lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t),
                )
                frameAlpha = t
                pathAlpha = startPathAlpha * (1f - t)
            }
            start()
        }
    }

    private fun fadeOutPath() {
        val start = pathAlpha
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 220
            addUpdateListener { pathAlpha = start * (it.animatedValue as Float) }
            start()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---- Text selection helpers ----

    private fun toView(r: Rect): RectF {
        val b = bitmap ?: return RectF()
        val sx = width / b.width.toFloat()
        val sy = height / b.height.toFloat()
        return RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
    }

    /** The word under (x, y) in view coordinates, with a little slack for fingers, or -1. */
    private fun wordAt(x: Float, y: Float): Int {
        val slack = dp(6f)
        var best = -1
        var bestDist = Float.MAX_VALUE
        for ((i, w) in words.withIndex()) {
            val r = toView(w.box).apply { inset(-slack, -slack) }
            if (!r.contains(x, y)) continue
            val d = hypot(r.centerX() - x, r.centerY() - y)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    /** The word nearest to (x, y): first the closest line, then the closest word on it. */
    private fun nearestWord(x: Float, y: Float): Int {
        if (words.isEmpty()) return -1
        val lines = words.indices.groupBy { words[it].line }
        val line = lines.values.minByOrNull { idx ->
            val top = idx.minOf { toView(words[it].box).top }
            val bottom = idx.maxOf { toView(words[it].box).bottom }
            if (y in top..bottom) 0f else min(kotlin.math.abs(y - top), kotlin.math.abs(y - bottom))
        } ?: return -1
        return line.minByOrNull { i ->
            val r = toView(words[i].box)
            if (x in r.left..r.right) 0f else min(kotlin.math.abs(x - r.left), kotlin.math.abs(x - r.right))
        } ?: -1
    }

    private fun startTextSelection(word: Int) {
        // The region selection is replaced by the text selection.
        selection = null
        shown.setEmpty()
        frameAlpha = 0f
        selStart = word
        selEnd = word
        Haptics.click(context)
        notifyTextSelection()
    }

    /** Leaves text selection (e.g. back button, or a new circle). */
    fun endTextSelection() {
        if (!inTextMode) return
        selStart = -1
        selEnd = -1
        dragging = 0
        onTextSelection?.invoke(null, null)
        invalidate()
    }

    /** The exact selected text: words in reading order, separated by single spaces. */
    fun selectedText(): String =
        if (!inTextMode) "" else (selStart..selEnd).joinToString(" ") { words[it].text }

    private fun selectionBounds(): RectF {
        val r = RectF(toView(words[selStart].box))
        for (i in selStart..selEnd) r.union(toView(words[i].box))
        return r
    }

    private fun notifyTextSelection() {
        onTextSelection?.invoke(selectedText(), selectionBounds())
        invalidate()
    }

    /** Where a handle's teardrop sits: below the start of the first word / end of the last word. */
    private fun handleCenter(start: Boolean): PointF {
        val r = toView(words[if (start) selStart else selEnd].box)
        val radius = dp(11f)
        return if (start) PointF(r.left - radius, r.bottom + radius) else PointF(r.right + radius, r.bottom + radius)
    }

    /** Dragging the selection handles; returns true when the touch belongs to text selection. */
    private fun handleTextTouch(e: MotionEvent): Boolean {
        if (!inTextMode) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val grab = dp(28f)
                val s = handleCenter(true)
                val en = handleCenter(false)
                val ds = hypot(e.x - s.x, e.y - s.y)
                val de = hypot(e.x - en.x, e.y - en.y)
                dragging = when {
                    de <= grab && de <= ds -> 2
                    ds <= grab -> 1
                    else -> 0
                }
                if (dragging != 0) { Haptics.tick(context); return true }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == 0) return false
                // Aim slightly above the finger, at the text line rather than the handle.
                val target = nearestWord(e.x, e.y - dp(24f))
                if (target < 0) return true
                var start = selStart
                var end = selEnd
                if (dragging == 1) start = target else end = target
                if (start > end) {
                    val t = start; start = end; end = t
                    dragging = if (dragging == 1) 2 else 1
                }
                if (start != selStart || end != selEnd) {
                    selStart = start
                    selEnd = end
                    Haptics.tick(context)
                    notifyTextSelection()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging == 0) return false
                dragging = 0
                notifyTextSelection()
                return true
            }
        }
        return dragging != 0
    }

    private fun drawTextSelection(canvas: Canvas) {
        // One highlight per line, from the first to the last selected word on it (like Android).
        val pad = dp(2f)
        (selStart..selEnd).groupBy { words[it].line }.values.forEach { idx ->
            val r = RectF(toView(words[idx.first()].box))
            idx.forEach { r.union(toView(words[it].box)) }
            r.inset(-pad, -pad)
            canvas.drawRoundRect(r, pad, pad, highlightPaint)
        }
        // Teardrop handles: a circle with one square corner pointing at the text.
        val radius = dp(11f)
        for (start in listOf(true, false)) {
            val c = handleCenter(start)
            canvas.drawCircle(c.x, c.y, radius, handlePaint)
            val corner = if (start) RectF(c.x, c.y - radius, c.x + radius, c.y) else RectF(c.x - radius, c.y - radius, c.x, c.y)
            canvas.drawRect(corner, handlePaint)
        }
    }

    /** Selection mapped into screenshot pixels, or null if nothing is selected. */
    fun selectionInBitmap(): Rect? {
        val s = selection ?: return null
        val b = bitmap ?: return null
        if (width == 0 || height == 0) return null
        val sx = b.width / width.toFloat()
        val sy = b.height / height.toFloat()
        val r = Rect((s.left * sx).toInt(), (s.top * sy).toInt(), (s.right * sx).toInt(), (s.bottom * sy).toInt())
        if (!r.intersect(0, 0, b.width, b.height)) return null
        return if (r.width() > 4 && r.height() > 4) r else null
    }
}
