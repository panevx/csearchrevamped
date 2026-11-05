package io.github.panevx.csearchrevamped.translate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.panevx.csearchrevamped.Haptics
import io.github.panevx.csearchrevamped.Prefs
import io.github.panevx.csearchrevamped.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * Translates the text of the app on screen in place and keeps up while the user scrolls.
 * Text and positions come from the accessibility tree; translation runs on-device with ML Kit.
 */
class LiveTranslator(
    private val service: AccessibilityService,
    private val onClosed: () -> Unit,
) {
    /**
     * [cut] is 1 when the text continues below the visible area, -1 when it starts above it.
     * [order] is the position in drawing order (later is on top); [parent] identifies the parent node.
     */
    private data class TextBlock(
        val text: String,
        val bounds: Rect,
        var maxRight: Int = 0,
        val cut: Int = 0,
        val order: Int = 0,
        val lastOrder: Int = order,
        val parent: Int = 0,
        var covers: List<Rect> = emptyList(),
    )

    /** Something drawn on screen, in drawing order: used to keep translations from painting over it. */
    private class Shape(val order: Int, val bounds: Rect)

    private companion object {
        const val MAX_BLOCKS = 250
        const val REFRESH_DELAY_MS = 120L
        const val MAX_REFRESH_LATENCY_MS = 400L
        const val SCREENSHOT_INTERVAL_MS = 1100L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val themed = ContextThemeWrapper(service, R.style.Theme_CSearch_Overlay)
    private var target: String =
        TranslateLanguage.fromLanguageTag(Prefs.targetLang(service)) ?: TranslateLanguage.ENGLISH

    private val langId: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build()
    )
    private val translators = HashMap<String, Translator>()
    private val models = HashMap<String, Deferred<Boolean>>()
    /** Original text to translation; "" means leave it as it is. */
    private val cache = LruCache<String, String>(1500)
    /** Original text to (background, text color) sampled from the screen. */
    private val colors = LruCache<String, IntArray>(1500)

    private var overlay: TranslationOverlayView? = null
    private var bar: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var collapsed = false
    private var savedInfo: IntArray? = null
    private var blocks: List<TextBlock> = emptyList()
    private var translating: Job? = null
    private var pendingSince = 0L
    private var stopped = false
    private val refreshRunnable = Runnable { refresh() }

    // Screenshots of the app window (not our overlay) to match its colors; Android 14+.
    private val canSampleColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private var shotPending = false
    private var lastShotAt = 0L
    private var shotFailures = 0

    fun start() {
        service.serviceInfo?.let { info ->
            savedInfo = intArrayOf(info.eventTypes, info.flags, info.notificationTimeout.toInt())
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 50
            service.serviceInfo = info
        }
        addWindows()
        showTranslatingStatus()
        handler.postDelayed(refreshRunnable, 350)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacks(refreshRunnable)
        scope.cancel()
        savedInfo?.let { saved ->
            service.serviceInfo?.let { info ->
                info.eventTypes = saved[0]
                info.flags = saved[1]
                info.notificationTimeout = saved[2].toLong()
                service.serviceInfo = info
            }
        }
        overlay?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        translators.values.forEach { it.close() }
        langId.close()
        onClosed()
    }

    /** Switches the target language while running. */
    private fun setTarget(code: String) {
        if (code == target) return
        Prefs.setTargetLang(service, code)
        target = code
        translating?.cancel()
        translators.values.forEach { it.close() }
        translators.clear()
        models.clear()
        cache.evictAll()
        render()
        showTranslatingStatus()
        scheduleRefresh()
    }

    fun onEvent(event: AccessibilityEvent) {
        if (stopped || event.packageName == service.packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val area = event.source?.let { node -> Rect().also { node.getBoundsInScreen(it) } }
            overlay?.scrollBy(event.scrollDeltaX, event.scrollDeltaY, area)
        }
        scheduleRefresh()
    }

    /** Debounces bursts of events but never waits longer than [MAX_REFRESH_LATENCY_MS]. */
    private fun scheduleRefresh() {
        val now = SystemClock.uptimeMillis()
        if (pendingSince == 0L) pendingSince = now
        handler.removeCallbacks(refreshRunnable)
        val delay = if (now - pendingSince >= MAX_REFRESH_LATENCY_MS) 0L else REFRESH_DELAY_MS
        handler.postDelayed(refreshRunnable, delay)
    }

    private fun refresh() {
        pendingSince = 0L
        if (stopped) return
        val root = service.rootInActiveWindow ?: return
        val screen = wm.currentWindowMetrics.bounds
        val found = ArrayList<TextBlock>()
        val obstacles = ArrayList<Rect>()
        val shapes = ArrayList<Shape>()
        order = 0
        collect(root, screen, screen, screen, found, obstacles, shapes, 0, 0)
        val merged = mergeFragments(found)
        limitGrowth(merged, obstacles, screen)
        findCovers(merged, shapes)
        blocks = merged
        render()
        sampleColors(root, found)

        val missing = found.map { it.text }.distinct().filter { cache.get(it) == null }
        if (missing.isNotEmpty() && translating?.isActive != true) {
            translating = scope.launch {
                translateAll(missing)
                if (blocks.any { cache.get(it.text) == null }) scheduleRefresh()
            }
        }
    }

    private var order = 0

    /**
     * Collects the most specific text on screen. Children are visited first: when a node's
     * descendants carry text (a web page whose root holds the page title, a paragraph split into
     * links and spans), only the descendants are translated, so text is never drawn twice.
     * Returns whether this subtree contains any text.
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        screen: Rect,
        viewport: Rect,
        clip: Rect,
        out: MutableList<TextBlock>,
        obstacles: MutableList<Rect>,
        shapes: MutableList<Shape>,
        parentKey: Int,
        depth: Int,
    ): Boolean {
        if (depth > 80 || out.size >= MAX_BLOCKS || !node.isVisibleToUser) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        val myOrder = ++order
        val myKey = node.hashCode()
        val text = node.text?.toString()?.trim()
        val hasText = !text.isNullOrEmpty() && text.any { it.isLetter() }

        // Edges of scrolling lists, where text gets cut off while scrolling.
        val childViewport = if (node.isScrollable) Rect(r).apply { if (!intersect(viewport)) setEmpty() } else viewport
        // Everything inside a node is clipped to it. Regular views already report clipped bounds,
        // but web content (e.g. Firefox-based browsers) reports text hidden under the toolbar too.
        val childClip = Rect(r).apply { if (!intersect(clip)) setEmpty() }
        var childText = false
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                if (collect(it, screen, childViewport, childClip, out, obstacles, shapes, myKey, depth + 1)) childText = true
            }
        }
        if (childText) return true

        if (hasText && !node.isEditable) {
            // A page-sized node with text is a container (e.g. a browser reporting the page title),
            // not text drawn on screen.
            val container = r.width().toLong() * r.height() > screen.width().toLong() * screen.height() * 2 / 5
            // Trim to what's actually visible and remember which side is cut, so the translation
            // can be anchored to the visible edge of the original. Text touching the edge of its
            // container counts as cut too (Android trims those bounds before reporting them).
            val visible = Rect(r)
            val shown = visible.intersect(clip) && visible.intersect(viewport) && visible.intersect(screen)
            val edge = 2
            val cut = when {
                visible.bottom < r.bottom - edge || visible.bottom >= viewport.bottom - edge -> 1
                visible.top > r.top + edge || visible.top <= viewport.top + edge -> -1
                else -> 0
            }
            if (!container && shown && visible.width() > 8 && visible.height() > 8) {
                out.add(TextBlock(text!!, visible, cut = cut, order = myOrder, parent = parentKey))
                shapes.add(Shape(myOrder, visible))
            }
            return true
        }
        if (node.childCount == 0 && !r.isEmpty) {
            // Icons, switches, images and other text: translations must not grow over them.
            obstacles.add(r)
        }
        // Leaves, tappable things and anything smaller than a page (cards, banners, pop-ups,
        // toolbars) are solid on screen. Page-sized layout containers are usually transparent.
        val pageSized = r.width().toLong() * r.height() > screen.width().toLong() * screen.height() * 2 / 5
        if ((node.childCount == 0 || node.isClickable || !pageSized) && !r.isEmpty) shapes.add(Shape(myOrder, r))
        return hasText
    }

    /**
     * Web pages often split one paragraph into pieces (text, a link, more text) whose boxes each
     * span the paragraph. Pieces that follow each other and overlap are joined back into one
     * block, so the paragraph is translated as a whole and never drawn on top of itself.
     */
    private fun mergeFragments(found: List<TextBlock>): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        for (b in found) {
            val last = out.lastOrNull()
            if (last != null && last.parent == b.parent && overlapsMostly(last.bounds, b.bounds)) {
                val joiner = if (last.text.last().isWhitespace() || b.text.first().let { !it.isLetterOrDigit() }) "" else " "
                out[out.size - 1] = TextBlock(
                    last.text + joiner + b.text, Rect(last.bounds).apply { union(b.bounds) },
                    cut = if (last.cut != 0) last.cut else b.cut,
                    order = last.order, lastOrder = b.lastOrder, parent = last.parent,
                )
            } else {
                out.add(b)
            }
        }
        return out
    }

    /** Things drawn later that overlap a block are on top of it; its translation is cut out around them. */
    private fun findCovers(found: List<TextBlock>, shapes: List<Shape>) {
        // Browsers often don't report the card or banner itself, only what's inside it (an image,
        // a title, a close button), so pieces close together are treated as one solid area.
        val gap = (48 * service.resources.displayMetrics.density).toInt()
        for (b in found) {
            val covers = shapes.filter { it.order > b.lastOrder && Rect.intersects(it.bounds, b.bounds) }
                .map { Rect(it.bounds) }.toMutableList()
            var merged = true
            while (merged) {
                merged = false
                loop@ for (i in covers.indices) for (j in i + 1 until covers.size) {
                    val near = Rect(covers[i]).apply { inset(-gap, -gap) }
                    if (Rect.intersects(near, covers[j])) {
                        covers[i].union(covers[j])
                        covers.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
            b.covers = covers
        }
    }

    private fun overlapsMostly(a: Rect, b: Rect): Boolean {
        val i = Rect()
        if (!i.setIntersect(a, b)) return false
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return i.width().toLong() * i.height() * 4 >= smaller
    }

    /** Lets each block grow right only up to the next thing on the same line. */
    private fun limitGrowth(found: List<TextBlock>, obstacles: List<Rect>, screen: Rect) {
        val margin = (8 * service.resources.displayMetrics.density).toInt()
        val others = obstacles + found.map { it.bounds }
        for (b in found) {
            var limit = screen.right - margin
            for (o in others) {
                if (o === b.bounds) continue
                val sameLine = o.top < b.bounds.bottom && o.bottom > b.bounds.top
                if (sameLine && o.left >= b.bounds.right - 2) limit = minOf(limit, o.left - margin / 2)
            }
            b.maxRight = maxOf(limit, b.bounds.right)
        }
    }

    private fun render() {
        val waitForColors = canSampleColors && shotFailures < 3
        overlay?.setItems(blocks.mapNotNull { b ->
            val translated = cache.get(b.text)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val c = colors.get(b.text)
            // Briefly hold back until the real colors are known so boxes don't flash white.
            if (c == null && waitForColors) return@mapNotNull null
            TranslationOverlayView.Item(b.bounds, translated, b.text, c?.get(0), c?.get(1), b.maxRight, b.cut, b.covers)
        })
    }

    /** Samples background and text colors for blocks that don't have them yet. */
    private fun sampleColors(root: AccessibilityNodeInfo, found: List<TextBlock>) {
        if (!canSampleColors || shotFailures >= 3 || shotPending) return
        val missing = found.filter { colors.get(it.text) == null }
        if (missing.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotAt < SCREENSHOT_INTERVAL_MS) {
            handler.postDelayed({ if (!stopped) scheduleRefresh() }, SCREENSHOT_INTERVAL_MS - (now - lastShotAt))
            return
        }
        shotPending = true
        lastShotAt = now
        val windowOrigin = Rect().also { root.getBoundsInScreen(it) }
        service.takeScreenshotOfWindow(
            root.windowId, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    shotPending = false
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (bmp == null || stopped) return
                    for (b in missing) {
                        val r = Rect(b.bounds).apply { offset(-windowOrigin.left, -windowOrigin.top) }
                        sample(bmp, r)?.let { colors.put(b.text, it) }
                    }
                    bmp.recycle()
                    shotFailures = 0
                    render()
                }

                override fun onFailure(errorCode: Int) {
                    shotPending = false
                    shotFailures++
                    render()
                }
            },
        )
    }

    /** Background = most common color along the box edge; text = the pixel inside that differs most from it. */
    private fun sample(bmp: Bitmap, r: Rect): IntArray? {
        if (!r.intersect(0, 0, bmp.width, bmp.height) || r.width() < 3 || r.height() < 3) return null
        // Bucket similar colors, but keep each bucket's exact average so the box blends in seamlessly.
        val counts = HashMap<Int, IntArray>()
        fun edge(x: Int, y: Int) {
            val c = bmp.getPixel(x, y)
            val key = (Color.red(c) shr 3 shl 10) or (Color.green(c) shr 3 shl 5) or (Color.blue(c) shr 3)
            val acc = counts.getOrPut(key) { IntArray(4) }
            acc[0]++; acc[1] += Color.red(c); acc[2] += Color.green(c); acc[3] += Color.blue(c)
        }
        val steps = 24
        for (i in 0..steps) {
            val x = r.left + (r.width() - 1) * i / steps
            val y = r.top + (r.height() - 1) * i / steps
            edge(x, r.top); edge(x, r.bottom - 1); edge(r.left, y); edge(r.right - 1, y)
        }
        val acc = counts.values.maxByOrNull { it[0] }!!
        val bg = Color.rgb(acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0])

        var fg = 0
        var best = 0
        val grid = 16
        for (gy in 1 until grid) for (gx in 1 until grid) {
            val c = bmp.getPixel(r.left + r.width() * gx / grid, r.top + r.height() * gy / grid)
            val d = abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) + abs(Color.blue(c) - Color.blue(bg))
            if (d > best) { best = d; fg = c }
        }
        if (best < 90) {
            // Too few text pixels hit: pick black or white for contrast.
            val luminance = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
            fg = if (luminance > 140) 0xFF1F1F1F.toInt() else 0xFFF1F1F1.toInt()
        }
        return intArrayOf(bg, fg or 0xFF000000.toInt())
    }

    private suspend fun translateAll(texts: List<String>) {
        val pageLang = identify(texts.joinToString("\n").take(2000))
        for (chunk in texts.chunked(12)) {
            chunk.map { text -> scope.async { text to translate(text, pageLang) } }
                .awaitAll()
                .forEach { (text, result) -> if (result != null) cache.put(text, result) }
            render()
        }
        showTranslatingStatus()
    }

    private suspend fun identify(text: String): String =
        runCatching { langId.identifyLanguage(text).await() }.getOrDefault(LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG)

    /** Returns the translation, "" to leave the text alone, or null on a temporary failure. */
    private suspend fun translate(text: String, pageLang: String): String? {
        var lang = identify(text)
        if (lang == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) lang = pageLang
        val source = TranslateLanguage.fromLanguageTag(lang) ?: return ""
        if (source == target) return ""

        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
            )
        }
        val ready = models.getOrPut(source) {
            scope.async {
                setStatus(
                    service.getString(R.string.live_downloading, Languages.name(source), Languages.name(target))
                )
                runCatching { translator.downloadModelIfNeeded().await() }.isSuccess
            }
        }
        if (!ready.await()) {
            models.remove(source)
            return null
        }
        return runCatching { translator.translate(text).await() }.getOrNull()
    }

    private fun showTranslatingStatus() = setStatus(service.getString(R.string.live_translating, Languages.name(target)))

    private fun setStatus(text: String) {
        status?.text = text
    }

    private fun addWindows() {
        val view = TranslationOverlayView(themed)
        val full = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, full)
        overlay = view

        val root = LayoutInflater.from(themed).inflate(R.layout.live_translate_pill, null)
        status = root.findViewById(R.id.liveStatus)
        root.findViewById<View>(R.id.liveLanguage).setOnClickListener {
            Haptics.tick(service)
            Languages.pickOverlay(themed, wm, target) { setTarget(it) }
        }
        root.findViewById<View>(R.id.liveCollapse).setOnClickListener { setCollapsed(true) }
        root.findViewById<View>(R.id.liveExpand).setOnClickListener { setCollapsed(false) }
        root.findViewById<View>(R.id.liveStop).setOnClickListener { stop() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * service.resources.displayMetrics.density).toInt()
        }
        wm.addView(root, params)
        bar = root
        barParams = params
    }

    /** Tucks the bar into a small tab on the right edge, or brings it back. */
    private fun setCollapsed(collapse: Boolean) {
        val root = bar ?: return
        val params = barParams ?: return
        if (collapse == collapsed) return
        collapsed = collapse
        Haptics.tick(service)
        val expanded = root.findViewById<View>(R.id.liveExpanded)
        val tab = root.findViewById<View>(R.id.liveExpand)
        val slide = 60 * service.resources.displayMetrics.density
        root.animate().translationX(slide).alpha(0f).setDuration(120).withEndAction {
            expanded.isVisible = !collapse
            tab.isVisible = collapse
            params.gravity = Gravity.BOTTOM or if (collapse) Gravity.END else Gravity.CENTER_HORIZONTAL
            runCatching { wm.updateViewLayout(root, params) }
            root.translationX = slide
            root.animate().translationX(0f).alpha(1f).setDuration(160).start()
        }.start()
    }
}
