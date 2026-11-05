package io.github.panevx.csearchrevamped

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

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

    var onSelectionChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val path = Path()
    private var drawing = false
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var selection: RectF? = null

    private val dst = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0x66000000 }
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
        alpha = 150
        maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val colors = intArrayOf(
            Color.parseColor("#4FC3F7"), Color.parseColor("#7C4DFF"),
            Color.parseColor("#FF4081"), Color.parseColor("#FFC107"),
        )
        val shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.MIRROR)
        strokePaint.shader = shader
        glowPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, bitmapPaint)
        canvas.drawRect(dst, dimPaint)

        val s = selection
        if (s != null && !drawing) {
            val r = dp(20f)
            canvas.save()
            canvas.clipRect(s)
            canvas.drawBitmap(b, null, dst, bitmapPaint)
            canvas.restore()
            canvas.drawRoundRect(s, r, r, glowPaint)
            canvas.drawRoundRect(s, r, r, strokePaint)
        }
        if (drawing) {
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, strokePaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawing = true
                path.reset()
                path.moveTo(e.x, e.y)
                minX = e.x; maxX = e.x; minY = e.y; maxY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) extend(e.getHistoricalX(i), e.getHistoricalY(i))
                extend(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                drawing = false
                path.reset()
                selection = buildSelection()
                onSelectionChanged?.invoke()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                path.reset()
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun extend(x: Float, y: Float) {
        path.lineTo(x, y)
        minX = min(minX, x); maxX = max(maxX, x)
        minY = min(minY, y); maxY = max(maxY, y)
    }

    private fun buildSelection(): RectF {
        val tap = maxX - minX < dp(24f) && maxY - minY < dp(24f)
        val r = RectF(minX, minY, maxX, maxY)
        if (tap) {
            val half = dp(110f)
            r.set(r.centerX() - half, r.centerY() - half, r.centerX() + half, r.centerY() + half)
        } else {
            r.inset(-dp(8f), -dp(8f))
            val minSide = dp(56f)
            if (r.width() < minSide) r.inset((r.width() - minSide) / 2f, 0f)
            if (r.height() < minSide) r.inset(0f, (r.height() - minSide) / 2f)
        }
        r.intersect(0f, 0f, width.toFloat(), height.toFloat())
        return r
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
