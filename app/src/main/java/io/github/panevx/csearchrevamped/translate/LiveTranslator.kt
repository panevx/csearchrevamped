package io.github.panevx.csearchrevamped.translate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val grandparent: Int = 0,
        var covers: List<Rect> = emptyList(),
        /** A separate tap target (icon, button, tab): never joined with neighbours. */
        val clickable: Boolean = false,
    )

    /** Something drawn on screen, in drawing order: used to keep translations from painting over it. */
    private class Shape(val order: Int, val bounds: Rect)

    private companion object {
        const val MAX_BLOCKS = 250
        const val REFRESH_DELAY_MS = 120L
        const val MAX_REFRESH_LATENCY_MS = 400L
        const val SCREENSHOT_INTERVAL_MS = 1050L
        /** Share of a line's area covered by ink above which text counts as bold. */
        const val BOLD_FILL = 0.355f
        const val BOLD_FILL_CYRILLIC = 0.3f
        /** Stroke thickness relative to the line height above which text is bold. */
        const val BOLD_STROKE = 0.165f
        /** Reported text up to this long is a label (an icon's name, a button), not a paragraph. */
        const val LABEL_CHARS = 40
        /** Share of a translation's area that may overlap one already drawn. */
        const val MAX_OVERLAP = 0.3f
        /** OCR text is only translated when language identification is at least this sure. */
        const val OCR_LANGUAGE_CONFIDENCE = 0.5f
        const val UND = LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG
        /** While the screen keeps changing, OCR looks again this often. */
        const val OCR_RECHECK_MS = 4000L
        /** OCR looks again at most this often for text that moved or appeared. */
        const val OCR_MIN_INTERVAL_MS = 2000L
        /** How often to check again for a language that can't be downloaded while offline. */
        const val OFFLINE_RETRY_MS = 3000L
        val CYRILLIC_LANGUAGES = setOf("bg", "ru", "uk", "mk", "sr", "be")
        /**
         * Letters each Cyrillic language has and Bulgarian doesn't. Cyrillic text with none of them
         * is Bulgarian: short phrases are otherwise easily taken for Russian or Macedonian.
         */
        val CYRILLIC_MARKERS = linkedMapOf(
            "be" to "ў",
            "uk" to "іїєґ",
            "sr" to "ђћ",
            "mk" to "ѓќѕ",
            "ru" to "ыэё",
        )
        /** Letters Serbian and Macedonian share (and Bulgarian lacks). */
        const val SERBIAN_OR_MACEDONIAN = "јљњџ"
        /** How long scrolling must have stopped before translating the new position. */
        const val SCROLL_SETTLE_MS = 220L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Optional debug log of every block's path (see [TranslateTrace]). */
    private val trace = TranslateTrace(service)

    private fun traceKey(b: TextBlock) = "${b.text}|${b.bounds.flattenToString()}"
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
    /** Measurements (colors, font size, layout) per text and visible size; see [measureKey]. */
    private val colors = object : LruCache<String, Measure>(48 * 1024) {
        override fun sizeOf(key: String, value: Measure) = 1 + (value.patch?.byteCount ?: 0) / 1024
    }

    /**
     * What was measured for a block. [v] holds: background, text color, font size px, lines,
     * ink left/top/right/bottom (relative to the bounds), centered, line pitch, bounds width,
     * bounds height, visible top, visible bottom (relative), grow limit (absolute x), bold.
     * [patch] is the original background with the letters painted out (for non-flat backgrounds),
     * covering [patchRect] relative to the bounds.
     */
    private class Measure(val v: IntArray, val patch: Bitmap?, val patchRect: Rect?)

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
    private val recheckRunnable = Runnable { if (!stopped && !scrolling) scheduleRefresh() }

    /**
     * Text read from the screenshot (OCR), like Google's Circle to Search: covers text the app
     * doesn't report to Android (many web pages) and text inside images. Read again after each scroll.
     */
    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var ocrBlocks: List<TextBlock> = emptyList()
    /** Texts that came from OCR (not from the app): their language must be clear to translate them. */
    private val ocrTexts = HashSet<String>()
    /**
     * The app's text blocks ([traceKey]) that OCR saw on screen, or null before OCR has looked
     * (since the last scroll). Only these are drawn: an app also reports text that's hidden or
     * covered by something it doesn't report (a dialog in a frame, a video ad).
     */
    private var verified: Set<String>? = null
    /** Why other blocks aren't drawn (debug log). */
    private var unverified: Map<String, String> = emptyMap()
    /** Parts of verified blocks covered by something the app doesn't report: drawn around. */
    private var coveredParts: Map<String, Rect> = emptyMap()
    /** Texts measured while covered: measured again once they're visible. */
    private val measuredCovered = HashSet<String>()
    /** When OCR last looked, and whether the screen changed since (re-checked every few seconds). */
    private var lastOcrAt = 0L
    private var changedSinceOcr = false
    /**
     * Where OCR saw text on screen (any confidence), or null before it has looked. Text that apps
     * report but don't draw (e.g. hidden labels under home-screen icons) has none and is skipped.
     */
    private var ocrEvidence: List<Rect>? = null
    /** Reported texts present when OCR last looked: new ones trigger another look. */
    private var checkedTexts: Set<String> = emptySet()
    /** Text fields (typing, address bars): OCR results there are never used. */
    private val editableAreas = ArrayList<Rect>()
    /** Images and their descriptions: OCR text matching the description is a logo, kept as it is. */
    private val imageLabels = ArrayList<Pair<Rect, String>>()
    private var ocrDone = false

    /** While the user scrolls, translations are hidden; they come back once scrolling stops. */
    private var scrolling = false
    private val settleRunnable = Runnable {
        scrolling = false
        pendingSince = 0L
        // What was read from the screen belongs to the old scroll position.
        ocrBlocks = emptyList()
        ocrEvidence = null
        verified = null
        unverified = emptyMap()
        coveredParts = emptyMap()
        ocrDone = false
        refresh()
        overlay?.animate()?.alpha(1f)?.setDuration(140)?.start()
    }

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
        trace.start(service.rootInActiveWindow?.packageName?.toString(), target, wm.currentWindowMetrics.bounds)
        showTranslatingStatus()
        handler.postDelayed(refreshRunnable, 350)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacks(refreshRunnable)
        handler.removeCallbacks(settleRunnable)
        handler.removeCallbacks(renderRunnable)
        handler.removeCallbacks(recheckRunnable)
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
        runCatching { ocr.close() }
        closeScreenOcr()
        trace.close()
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
            val screen = wm.currentWindowMetrics.bounds
            // Small auto-scrolling things (carousels, tickers) shouldn't hide everything.
            val big = area == null || area.width().toLong() * area.height() > screen.width().toLong() * screen.height() * 3 / 10
            if (big) {
                if (!scrolling) {
                    scrolling = true
                    handler.removeCallbacks(refreshRunnable)
                    overlay?.animate()?.alpha(0f)?.setDuration(70)?.start()
                }
                handler.removeCallbacks(settleRunnable)
                handler.postDelayed(settleRunnable, SCROLL_SETTLE_MS)
                return
            }
        }
        if (scrolling) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) changedSinceOcr = true
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
        if (stopped || scrolling) return
        val root = service.rootInActiveWindow ?: return
        val screen = wm.currentWindowMetrics.bounds
        val found = ArrayList<TextBlock>()
        val obstacles = ArrayList<Rect>()
        val shapes = ArrayList<Shape>()
        order = 0
        editableAreas.clear()
        imageLabels.clear()
        collect(root, screen, screen, screen, found, obstacles, shapes, 0, 0, 0)
        val fromTree = fitToLetters(mergeFragments(found))
        if (trace.enabled) for (b in fromTree) trace.record(traceKey(b))?.let { rec ->
            if (rec.source.isEmpty()) {
                rec.source = "app text (accessibility)"
                rec.region = Rect(b.bounds)
                rec.text = b.text
            }
        }
        limitGrowth(fromTree, obstacles, screen)
        findCovers(fromTree, shapes)
        // Add what OCR read on screen that the app didn't report (or that's inside images).
        // OCR only fills in where the app reports no text: what the app reports is exact.
        val merged = fromTree + ocrBlocks.filter { o -> fromTree.none { Rect.intersects(it.bounds, o.bounds) } }
        if (ocrDone && fromTree.any { it.text !in checkedTexts }) ocrDone = false
        // Blocks that moved or appeared since OCR looked need another look (not too often:
        // animations move things constantly), and something may have appeared that the app
        // doesn't report (a dialog in a frame, a carousel's next slide): while the screen keeps
        // changing, OCR looks again every few seconds. When it's too soon, a look is scheduled,
        // as the app may send nothing more once an animation ends.
        val checked = verified
        val sinceOcr = SystemClock.uptimeMillis() - lastOcrAt
        val moved = checked != null && fromTree.any { traceKey(it).let { k -> k !in checked && k !in unverified } }
        if (ocrDone && (moved || changedSinceOcr)) {
            val wait = if (moved) OCR_MIN_INTERVAL_MS else OCR_RECHECK_MS
            if (sinceOcr > wait) {
                ocrDone = false
            } else {
                handler.removeCallbacks(recheckRunnable)
                handler.postDelayed(recheckRunnable, wait - sinceOcr + 50)
            }
        }
        blocks = merged
        render()
        sampleColors(root, merged)

        val missing = merged.map { it.text }.distinct().filter { cache.get(it) == null }
        if (missing.isNotEmpty() && translating?.isActive != true) {
            translating = scope.launch {
                translateAll(missing)
                if (blocks.any { cache.get(it.text) == null }) scheduleRefresh()
            }
        }
    }

    /**
     * Some apps report a label with the bounds of the whole control (a home-screen icon and its
     * name): once OCR has seen where the letters are, such text is shrunk to them, so the
     * translation keeps the label's size and place and doesn't cover the icon.
     */
    private fun fitToLetters(found: List<TextBlock>): List<TextBlock> {
        val evidence = ocrEvidence ?: return found
        return found.map { b ->
            // Only labels: a paragraph keeps its own box, or part of it would stay untranslated.
            if (b.text.length > LABEL_CHARS) return@map b
            val parts = evidence.mapNotNull { e -> Rect(e).takeIf { it.intersect(b.bounds) } }
            if (parts.isEmpty()) return@map b
            val letters = Rect()
            parts.forEach { letters.union(it) }
            // And only when the letters are one line.
            val middle = letters.centerY()
            if (parts.any { it.bottom < middle || it.top > middle }) return@map b
            val tall = letters.height() < b.bounds.height() * 0.6f
            // A short label in a wide control (a button): its letters, not the whole button, so the
            // button's shape stays as it is around the translation.
            val wide = letters.width() < b.bounds.width() * 0.7f && letters.height() < b.bounds.height() * 0.8f
            if (!tall && !wide) return@map b
            val pad = maxOf(3, letters.height() / 6)
            letters.inset(-pad * 2, -pad)
            if (!letters.intersect(b.bounds)) return@map b
            val fitted = if (wide) letters else Rect(b.bounds.left, letters.top, b.bounds.right, letters.bottom)
            b.copy(bounds = fitted, maxRight = maxOf(fitted.right, minOf(b.maxRight, b.bounds.right)))
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
        grandparentKey: Int,
        depth: Int,
    ): Boolean {
        if (depth > 80 || out.size >= MAX_BLOCKS || !node.isVisibleToUser) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        val myOrder = ++order
        val myKey = node.hashCode()
        val text = node.text?.toString()?.trim()
        // An image's text is its description (alt text), not something drawn on screen.
        val isImage = node.className?.contains("Image") == true
        if (isImage && !text.isNullOrBlank() && !r.isEmpty) imageLabels.add(Rect(r) to text.toString())
        if (node.isEditable && !r.isEmpty) editableAreas.add(Rect(r))
        val hasText = !isImage && !text.isNullOrEmpty() && text.any { it.isLetter() }

        // Edges of scrolling lists, where text gets cut off while scrolling. Short scrolling strips
        // (carousels, rows that scroll sideways) don't cut text vertically, so they don't count.
        val tallScroller = node.isScrollable && r.height() > screen.height() / 4
        val childViewport = if (tallScroller) Rect(r).apply { if (!intersect(viewport)) setEmpty() } else viewport
        // Everything inside a node is clipped to it. Regular views already report clipped bounds,
        // but web content (e.g. Firefox-based browsers) reports text hidden under the toolbar too.
        // Some browsers report containers at stale positions; one that doesn't even overlap its
        // content can't be trusted for clipping, so it's skipped.
        val childClip = if (Rect.intersects(r, clip)) Rect(r).apply { intersect(clip) } else Rect(clip)
        var childText = false
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let {
                if (collect(it, screen, childViewport, childClip, out, obstacles, shapes, myKey, parentKey, depth + 1)) childText = true
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
            var shown = visible.intersect(clip) && visible.intersect(viewport) && visible.intersect(screen)
            if (!shown) {
                // The containers' reported position doesn't match the text's: trust the text.
                visible.set(r)
                shown = visible.intersect(viewport) && visible.intersect(screen)
            }
            // Only the screen and real scrolling areas cut text off; containers just trim extra margins.
            val edge = 2
            val onScreen = Rect(r).apply { intersect(viewport); intersect(screen) }
            val cut = when {
                onScreen.bottom < r.bottom - edge || onScreen.bottom >= viewport.bottom - edge -> 1
                onScreen.top > r.top + edge || onScreen.top <= viewport.top + edge -> -1
                else -> 0
            }
            if (!container && shown && visible.width() > 8 && visible.height() > 8) {
                out.add(
                    TextBlock(
                        text!!, visible, cut = cut, order = myOrder, parent = parentKey,
                        grandparent = grandparentKey, clickable = node.isClickable,
                    )
                )
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
        // Page-sized ones count when they hold content and can be tapped (a consent dialog) or are a
        // picture; a bare page-sized element is an invisible helper (the home screen lays one over
        // all its icons).
        val solid = !pageSized || node.isClickable && node.childCount > 0 || node.childCount == 0 && isImage
        if (solid && !r.isEmpty) shapes.add(Shape(myOrder, r))
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
            // Pieces of one paragraph share a parent, or a link inside it sits one level deeper.
            val related = last != null && (last.parent == b.parent || last.parent == b.grandparent ||
                last.grandparent == b.parent || last.grandparent == b.grandparent)
            val separateTargets = last != null && last.clickable && b.clickable
            if (last != null && !separateTargets &&
                (related && overlapsMostly(last.bounds, b.bounds) || continuesLine(last.bounds, b.bounds))
            ) {
                val joiner = if (last.text.last().isWhitespace() || b.text.first().let { !it.isLetterOrDigit() }) "" else " "
                out[out.size - 1] = TextBlock(
                    last.text + joiner + b.text, Rect(last.bounds).apply { union(b.bounds) },
                    cut = if (last.cut != 0) last.cut else b.cut,
                    order = last.order, lastOrder = b.lastOrder, parent = last.parent, grandparent = last.grandparent,
                    clickable = last.clickable,
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

    /** [b] carries on right where [a] ends on the same line, like a link inside a sentence. */
    private fun continuesLine(a: Rect, b: Rect): Boolean {
        val lineHeight = minOf(a.height(), b.height())
        val sameLine = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > lineHeight / 2
        val gap = b.left - a.right
        return sameLine && gap in -lineHeight / 3..lineHeight / 3
    }

    /** How much of the smaller of [a] and [b] the two share. */
    private fun overlapShare(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (smaller <= 0) 0f else i.width().toLong() * i.height() / smaller.toFloat()
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
        if (scrolling) return
        val waitForColors = canSampleColors && shotFailures < 3
        val drawn = ArrayList<Rect>()
        // Blocks drawn on top come first, so where two translations would overlap, the one on top
        // (a dialog over the page) is the one shown; then they're drawn in the app's order.
        val items = blocks.withIndex().sortedByDescending { it.value.order }.mapNotNull { (index, b) ->
            val translated = cache.get(b.text)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val m = colors.get(measureKey(b))
            // Briefly hold back until the real colors are known so boxes don't flash white.
            if (m == null && waitForColors) return@mapNotNull null
            val key = traceKey(b)
            if (b.parent != -1 && waitForColors) {
                // The app's text is drawn once OCR has seen it on screen: apps also report text
                // that's hidden (launchers' hidden labels) or covered by something they don't
                // report (a dialog in a frame, a video ad), and drawing it would cover that.
                val seen = verified ?: return@mapNotNull null
                if (key !in seen) {
                    unverified[key]?.let { why ->
                        trace.existing(key)?.let {
                            it.overlay = "not drawn: $why"
                            trace.finish(it)
                        }
                    }
                    return@mapNotNull null
                }
            }
            val v = m?.v
            // Rows hidden behind a pinned header or footer are left alone.
            var bounds = b.bounds
            var cut = b.cut
            if (v != null && (v[12] > 0 || v[13] < b.bounds.height())) {
                if (v[13] - v[12] < 8) return@mapNotNull null
                bounds = Rect(b.bounds.left, b.bounds.top + v[12], b.bounds.right, b.bounds.top + v[13])
                if (v[12] > 0) cut = -1 else if (v[13] < b.bounds.height()) cut = 1
            }
            val ink = v?.takeIf { it[2] > 0 }?.let {
                val sameBounds = bounds == b.bounds
                TranslationOverlayView.Ink(
                    textSize = it[2].toFloat(),
                    linePitch = it[9].toFloat(),
                    centered = it[8] == 1,
                    area = if (sameBounds) Rect(it[4], it[5], it[6], it[7]) else null,
                    bold = it[15] == 1,
                )
            }
            // Never two translations on top of each other: the one on top wins. (Where this block
            // is drawn around something on top of it, they don't overlap.)
            val covers = coveredParts[key]?.let { b.covers + it } ?: b.covers
            if (drawn.any { d -> overlapShare(d, bounds) > MAX_OVERLAP && covers.none { overlapShare(it, d) > 0.5f } }) {
                trace.existing(traceKey(b))?.let {
                    it.overlay = "not drawn: would overlap another translation"
                    trace.finish(it)
                }
                return@mapNotNull null
            }
            drawn.add(bounds)
            val patchRect = m?.patchRect?.let { Rect(it).apply { offset(b.bounds.left, b.bounds.top) } }
            TranslationOverlayView.Item(
                bounds, translated, b.text, v?.get(0), v?.get(1),
                if (v != null) minOf(b.maxRight, v[14]) else b.maxRight, cut, covers, ink,
                m?.patch, patchRect, if (trace.enabled) traceKey(b) else null,
            ).let { index to it }
        }
        overlay?.setItems(items.sortedBy { it.first }.map { it.second })
    }

    /**
     * Reads the text on the screenshot that the app doesn't report (web pages that don't, text in
     * images) and adds it as blocks; measures them too. See [ScreenOcr].
     */
    private fun readScreen(bmp: Bitmap, px: IntArray, windowOrigin: Rect) {
        scope.launch {
            val screen = wm.currentWindowMetrics.bounds
            val w = bmp.width
            val h = bmp.height
            try {
                val started = SystemClock.uptimeMillis()
                val result = runCatching {
                    // Starts ML Kit native code: not at the same time as a translator (see nativeInit).
                    nativeInit.withLock { ocr.process(InputImage.fromBitmap(bmp, 0)).await() }
                }.getOrNull()
                if (stopped || scrolling || result == null) return@launch
                val mlKitMs = SystemClock.uptimeMillis() - started
                ocrEvidence = result.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                    l.boundingBox?.let { Rect(it).apply { offset(windowOrigin.left, windowOrigin.top) } }
                }
                // What the app reports is exact: OCR only reads the rest of the screen, and lines
                // that show something else on top of the app's text. Never our own bar.
                val reported = checkReported(result, windowOrigin) + ownLines(result, windowOrigin)
                if (stopped || scrolling) return@launch
                val tryCyrillic = cyrillicLikely(result)
                val readStarted = SystemClock.uptimeMillis()
                val paragraphs = tessLock.withLock {
                    withContext(Dispatchers.Default) {
                        runCatching { screenOcr.read(bmp, result, tryCyrillic, reported) }.getOrDefault(emptyList())
                    }
                }
                trace.note(
                    "OCR pass (window at ${windowOrigin.toShortString()}): ML Kit found " +
                        "${result.textBlocks.sumOf { it.lines.size }} lines in $mlKitMs ms; " +
                        "${paragraphs.size} paragraphs outside the app's own text read in " +
                        "${SystemClock.uptimeMillis() - readStarted} ms (Bulgarian reading ${if (tryCyrillic) "on" else "off"}), " +
                        "${paragraphs.count { it.problem == null }} passed the word check"
                )
                if (stopped || scrolling) return@launch
                publishOcr(paragraphs, px, w, h, windowOrigin, screen)
            } finally {
                bmp.recycle()
                if (stopped) closeScreenOcr()
            }
        }
    }

    /**
     * Compares each line OCR found with the app's text where it lies (see [VisibleText]). A block
     * is verified when a line shows its text (or only lines too short to tell are there); text
     * that belongs to something drawn on top of it, or that the app doesn't report at all, covers
     * it. Returns the lines showing the app's text, which OCR needn't read.
     */
    private suspend fun checkReported(result: com.google.mlkit.vision.text.Text, windowOrigin: Rect): Set<Rect> {
        val tree = blocks.filter { it.parent != -1 }
        val areas = tree.map { Rect(it.bounds).apply { offset(-windowOrigin.left, -windowOrigin.top) } }
        // Comparing every line with long texts takes a moment: not on the main thread.
        val check = withContext(Dispatchers.Default) { compareLines(result, tree, areas, windowOrigin) }
        val (skip, seen, unclear) = check
        val foreign = check.foreign
        val unreported = check.unreported
        val ok = HashSet<String>()
        val why = HashMap<String, String>()
        val covered = HashMap<String, Rect>()
        for ((i, b) in tree.withIndex()) {
            val key = traceKey(b)
            val visible = (i in seen || i in unclear && i !in foreign) && i !in unreported
            when {
                visible -> ok.add(key)
                i in unreported -> why[key] = "covered by something the app doesn't report (OCR read other text there)"
                i in foreign -> why[key] = "covered by the app's own text drawn on top"
                else -> why[key] = "OCR saw no such text there (hidden, or covered by something without text)"
            }
            if (visible) foreign[i]?.let { r -> covered[key] = Rect(r).apply { val m = r.height() / 2; inset(-m, -m) } }
            // A measurement taken while something covered the text is wrong (its colours, its
            // size): measure again once the text is visible.
            if (!visible || i in foreign || i in unreported) {
                if (measuredCovered.add(b.text)) colors.remove(measureKey(b))
            } else if (measuredCovered.remove(b.text)) {
                colors.remove(measureKey(b))
            }
        }
        verified = ok
        unverified = why
        coveredParts = covered
        if (why.isNotEmpty() || covered.isNotEmpty()) {
            trace.note("OCR check of the app's text: ${ok.size} blocks seen, ${why.size} not visible, ${covered.size} partly covered")
        }
        return skip
    }

    /** What [checkReported] finds, by index into the app's blocks. */
    private data class LineCheck(
        val skip: Set<Rect>,
        val seen: Set<Int>,
        val unclear: Set<Int>,
        /** Covered by the app's own text drawn on top (a sticky bar, a dialog): drawn around it. */
        val foreign: Map<Int, Rect>,
        /**
         * Covered by text the app doesn't report (a frame, an error page, an ad): not drawn, as
         * there's no telling what else of it is covered (see-through frames show the text under them).
         */
        val unreported: Set<Int>,
    )

    private fun compareLines(
        result: com.google.mlkit.vision.text.Text,
        tree: List<TextBlock>,
        areas: List<Rect>,
        windowOrigin: Rect,
    ): LineCheck {
        val shapes = tree.map { VisibleText.shapes(it.text) }
        val skip = HashSet<Rect>()
        val seen = HashSet<Int>()
        val unclear = HashSet<Int>()
        val foreign = HashMap<Int, Rect>()
        val unreported = HashSet<Int>()
        /** Judges OCR text at [box] lying in the blocks [inside]; true when it's the app's text (or unclear). */
        fun judge(text: String, box: Rect, inside: List<Int>): Boolean {
            val lineShapes = VisibleText.shapes(text)
            val scores = inside.map { VisibleText.matchShapes(lineShapes, shapes[it]) }
            val onScreen = Rect(box).apply { offset(windowOrigin.left, windowOrigin.top) }
            // Whose text this is: the block drawn on top that it matches, if any.
            val owner = inside.indices.filter { (scores[it] ?: 0f) >= VisibleText.SAME }.maxByOrNull { tree[inside[it]].order }
            if (owner != null) {
                seen.add(inside[owner])
                // Blocks drawn before (under) the owner, whose text this isn't, are covered here.
                for ((k, i) in inside.withIndex()) {
                    val other = scores[k]
                    if (i != inside[owner] && other != null && other < VisibleText.OTHER && tree[i].lastOrder < tree[inside[owner]].order) {
                        foreign.getOrPut(i) { Rect(onScreen) }.union(onScreen)
                    }
                }
                return true
            }
            if (scores.all { it != null && it < VisibleText.OTHER }) {
                // Text the app doesn't report, on top of the app's text: OCR reads it.
                unreported.addAll(inside)
                return false
            }
            // Too short or too unclear to tell: the app's text is trusted.
            unclear.addAll(inside)
            return true
        }
        for (line in result.textBlocks.flatMap { it.lines }) {
            val box = line.boundingBox ?: continue
            val inside = tree.indices.filter { mostlyInside(box, areas[it]) }
            if (inside.isNotEmpty()) {
                if (judge(line.text, box, inside)) skip.add(box)
                continue
            }
            // A line across several of the app's blocks (a row of buttons): judge its words by block.
            val byBlock = LinkedHashMap<Int, MutableList<com.google.mlkit.vision.text.Text.Element>>()
            var outside = false
            for (e in line.elements) {
                val eb = e.boundingBox ?: continue
                val owners = tree.indices.filter { mostlyInside(eb, areas[it]) }
                if (owners.isEmpty()) outside = true
                owners.forEach { byBlock.getOrPut(it) { ArrayList() }.add(e) }
            }
            var allApp = byBlock.isNotEmpty()
            for ((i, words) in byBlock) {
                val area = Rect().also { r -> words.forEach { w -> w.boundingBox?.let { r.union(it) } } }
                if (!judge(words.joinToString(" ") { it.text }, area, listOf(i))) allApp = false
            }
            if (allApp && !outside) skip.add(box)
        }
        return LineCheck(skip, seen, unclear, foreign, unreported)
    }

    /** OCR lines on our own translate bar (it can show up in screenshots of the whole display). */
    private fun ownLines(result: com.google.mlkit.vision.text.Text, windowOrigin: Rect): Set<Rect> {
        val view = bar ?: return emptySet()
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        val area = Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        area.offset(-windowOrigin.left, -windowOrigin.top)
        return result.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.takeIf { Rect.intersects(it, area) } }.toSet()
    }

    private fun mostlyInside(a: Rect, area: Rect): Boolean {
        val i = Rect()
        if (!i.setIntersect(a, area)) return false
        return i.width().toLong() * i.height() * 2 >= a.width().toLong() * a.height()
    }

    /**
     * Whether the screen may show Cyrillic, so OCR also reads it as Bulgarian: the phone's language
     * or the app's text is Cyrillic, or ML Kit's Latin reading is unsure (as it is on Cyrillic).
     */
    private fun cyrillicLikely(result: com.google.mlkit.vision.text.Text): Boolean {
        if (java.util.Locale.getDefault().language in CYRILLIC_LANGUAGES) return true
        if (blocks.any { it.parent != -1 && CyrillicOcr.hasCyrillic(it.text) }) return true
        val lines = result.textBlocks.flatMap { it.lines }.filter { l -> l.text.count { it.isLetter() } >= 3 }
        return lines.isNotEmpty() && lines.count { it.confidence < 0.8f } * 4 >= lines.size
    }

    /** Debug log: OCR text that was read but not used. */
    private fun traceOcrDropped(raw: List<String>, box: Rect?, windowOrigin: Rect, why: String, px: IntArray, w: Int, h: Int) {
        if (!trace.enabled || box == null) return
        val region = Rect(box).apply { offset(windowOrigin.left, windowOrigin.top) }
        val rec = trace.record("dropped|${raw.joinToString()}|${region.flattenToString()}") ?: return
        if (rec.done) return
        rec.source = "OCR (not used)"
        rec.region = region
        rec.ocr.addAll(raw)
        rec.ocrVerdict = why
        trace.saveCrop(rec, px, w, h, box)
        trace.finish(rec)
    }

    /** Turns OCR paragraphs into blocks (skipping misread text, text fields and logos), measures them and redraws. */
    private fun publishOcr(found: List<ScreenOcr.Paragraph>, px: IntArray, w: Int, h: Int, windowOrigin: Rect, screen: Rect) {
        val lineHeights = found.map { it.box.height() / it.lines }.sorted()
        val usual = if (lineHeights.isEmpty()) 0 else lineHeights[lineHeights.size / 2]
        val out = ArrayList<TextBlock>()
        for (f in found) {
            fun dropped(why: String) = traceOcrDropped(f.raw, f.box, windowOrigin, why, px, w, h)
            // Misread or unclear: the original stays as it is rather than becoming nonsense.
            val problem = f.problem
            if (problem != null) { dropped("not used: $problem"); continue }
            val box = Rect(f.box)
            if (box.height() < 10) { dropped("not used: too small"); continue }
            // OCR boxes hug the letters; give them a little room so the background can be sampled.
            val lineHeight = box.height() / f.lines
            box.inset(-maxOf(3, lineHeight / 5), -maxOf(3, lineHeight / 6))
            box.offset(windowOrigin.left, windowOrigin.top)
            if (!box.intersect(screen)) continue
            val text = f.text
            // Never text the user is typing or an address bar.
            if (editableAreas.any { Rect.intersects(it, box) }) { dropped("not used: inside a text field"); continue }
            // A logo: the image's description is the same words (e.g. "Sammy Fans").
            val letters = text.filter { it.isLetterOrDigit() }.lowercase()
            val logo = imageLabels.any { (area, label) ->
                val l = label.filter { it.isLetterOrDigit() }.lowercase()
                Rect.intersects(area, box) && letters.length >= 2 && (l.contains(letters) || letters.contains(l))
            }
            // Logos and brand marks: a short phrase in capitals, much bigger than the page's text.
            val words = text.split(' ').filter { it.isNotBlank() }
            val caps = text.filter { it.isLetter() }
            val bigCaps = words.size <= 3 && caps.isNotEmpty() && caps.all { it.isUpperCase() } && lineHeight > usual * 1.6f
            if (logo || bigCaps) { dropped(if (logo) "not used: matches an image's description (a logo)" else "not used: big capitals (a logo)"); continue }
            val cut = when {
                box.top <= screen.top + 2 -> -1
                box.bottom >= screen.bottom - 2 -> 1
                else -> 0
            }
            val block = TextBlock(text, box, maxRight = box.right, cut = cut, order = Int.MAX_VALUE - 1, parent = -1)
            out.add(block)
            ocrTexts.add(text)
            trace.record(traceKey(block))?.let { rec ->
                if (rec.source.isEmpty()) {
                    rec.source = "OCR" + if (f.latinOnly) " (ML Kit, Latin)" else " (Tesseract Bulgarian + ML Kit Latin, word by word)"
                    rec.region = Rect(box)
                    rec.text = text
                    rec.ocr.addAll(f.raw)
                    rec.ocrVerdict = "passed: every word is a known word or was read clearly"
                    trace.saveCrop(rec, px, w, h, Rect(box).apply { offset(-windowOrigin.left, -windowOrigin.top) })
                }
            }
        }
        ocrBlocks = out
        for (b in out) {
            if (colors.get(measureKey(b)) != null) continue
            val r = Rect(b.bounds).apply { offset(-windowOrigin.left, -windowOrigin.top) }
            sample(px, w, h, r, b.text, b.cut != 0)?.let { colors.put(measureKey(b), it) }
        }
        scheduleRefresh()
    }

    private val screenOcr by lazy { ScreenOcr(service) }
    private val tessLock = Mutex()

    private fun closeScreenOcr() {
        if (!tessLock.isLocked) runCatching { screenOcr.close() }
    }

    /** Text partly off screen looks different from the same text fully in view, so measure each separately. */
    private fun measureKey(b: TextBlock) = "${b.bounds.width()}x${b.bounds.height()}|${b.cut}|${b.text}"

    private val renderRunnable = Runnable { render() }

    /** Shows translations as they arrive, without redrawing for every single one. */
    private fun renderSoon() {
        handler.removeCallbacks(renderRunnable)
        handler.postDelayed(renderRunnable, 50)
    }

    /** Samples colors and measures the letters for blocks that don't have that yet. */
    private fun sampleColors(root: AccessibilityNodeInfo, found: List<TextBlock>) {
        if (!canSampleColors || shotFailures >= 3 || shotPending) return
        val missing = found.filter { colors.get(measureKey(it)) == null }
        if (missing.isEmpty() && ocrDone) return
        val now = SystemClock.uptimeMillis()
        if (now - lastShotAt < SCREENSHOT_INTERVAL_MS) {
            handler.postDelayed({ if (!stopped && !scrolling) scheduleRefresh() }, SCREENSHOT_INTERVAL_MS - (now - lastShotAt))
            return
        }
        shotPending = true
        lastShotAt = now
        val pkg = root.packageName?.toString()
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            // Window screenshots are relative to the window; display screenshots to the screen.
            val usedDisplay = pkg in displayShotApps
            val windowOrigin = if (usedDisplay) Rect() else Rect().also { root.getBoundsInScreen(it) }

            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                shotPending = false
                if (usedDisplay) showOverlay(true)
                val buffer = result.hardwareBuffer
                val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                buffer.close()
                if (bmp == null || stopped) return
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                if (!usedDisplay && pkg != null && mostlyTransparent(px)) {
                    // A see-through window (e.g. the home screen over its wallpaper): its own pixels
                    // don't show what's on screen, so this app gets full-display screenshots instead.
                    bmp.recycle()
                    displayShotApps.add(pkg)
                    lastShotAt = 0L
                    handler.postDelayed({ if (!stopped && !scrolling) scheduleRefresh() }, SCREENSHOT_INTERVAL_MS)
                    return
                }
                if (!ocrDone) {
                    ocrDone = true
                    lastOcrAt = SystemClock.uptimeMillis()
                    changedSinceOcr = false
                    checkedTexts = blocks.filter { it.parent != -1 }.map { it.text }.toSet()
                    readScreen(bmp, px, windowOrigin)
                } else {
                    bmp.recycle()
                }
                for (b in missing) {
                    val r = Rect(b.bounds).apply { offset(-windowOrigin.left, -windowOrigin.top) }
                    trace.existing(traceKey(b))?.let { trace.saveCrop(it, px, w, h, r) }
                    val covers = b.covers.map { Rect(it).apply { offset(-windowOrigin.left, -windowOrigin.top) } }
                    val growTo = b.maxRight - windowOrigin.left
                    sample(px, w, h, r, b.text, b.cut != 0, covers, growTo)?.let { colors.put(measureKey(b), it) }
                }
                shotFailures = 0
                render()
            }

            override fun onFailure(errorCode: Int) {
                shotPending = false
                if (usedDisplay) showOverlay(true)
                if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    // Just too soon after the previous one: try again shortly.
                    handler.postDelayed({ if (!stopped && !scrolling) scheduleRefresh() }, SCREENSHOT_INTERVAL_MS)
                } else {
                    shotFailures++
                }
                render()
            }
        }
        if (callback.usedDisplay) {
            // The display screenshot would include our own translations and bar: hide them for it.
            showOverlay(false)
            handler.postDelayed({
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
            }, 48)
        } else {
            service.takeScreenshotOfWindow(root.windowId, service.mainExecutor, callback)
        }
    }

    /** Apps whose windows are see-through (home screens): screenshots of the whole display are used. */
    private val displayShotApps = HashSet<String>()

    private fun mostlyTransparent(px: IntArray): Boolean {
        var clear = 0
        var total = 0
        var i = 0
        while (i < px.size) {
            if (Color.alpha(px[i]) < 128) clear++
            total++
            i += 997
        }
        return clear > total / 5
    }

    private fun showOverlay(visible: Boolean) {
        val a = if (visible) 1f else 0f
        overlay?.animate()?.cancel()
        if (!scrolling) overlay?.alpha = a
        bar?.alpha = a
    }

    /**
     * Measures the original text from the screenshot. Returns
     * [background, text color, font size px, lines, ink left, ink top, ink right, ink bottom
     * (relative to the bounds), centered, line pitch px, bounds width, bounds height].
     */
    /**
     * Typical thickness of the letters' strokes, relative to the line height: the median length of
     * runs of ink across the middle rows of each line. Unlike the amount of ink, it doesn't depend
     * on the alphabet, so it tells bold from regular for any script.
     */
    private fun strokeWidth(px: IntArray, w: Int, r: Rect, bands: List<IntArray>, bg: Int, covers: List<Rect>): Float {
        val runs = ArrayList<Int>()
        var heights = 0f
        for (band in bands) {
            val bh = band[1] - band[0]
            if (bh < 6) continue
            heights += bh
            // The middle of a line crosses the vertical strokes of nearly every letter.
            for (row in (band[0] + bh * 2 / 5)..(band[0] + bh * 3 / 5)) {
                val y = r.top + row
                var run = 0
                for (x in r.left until r.right) {
                    val c = px[y * w + x]
                    val ink = abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) +
                        abs(Color.blue(c) - Color.blue(bg)) > 200 && covers.none { it.contains(x, y) }
                    if (ink) {
                        run++
                    } else if (run > 0) {
                        runs.add(run); run = 0
                    }
                }
                if (run > 0) runs.add(run)
            }
        }
        if (runs.size < 4 || heights == 0f) return 0f
        runs.sort()
        val usable = bands.count { it[1] - it[0] >= 6 }
        return runs[runs.size / 2] / (heights / usable)
    }

    /**
     * The most common colour along the edge of [r] (each bucket's exact average, so a fill blends
     * in seamlessly) and the share of the edge it covers.
     */
    private fun edgeColor(px: IntArray, w: Int, r: Rect): Pair<Int, Float> {
        val counts = HashMap<Int, IntArray>()
        fun edge(x: Int, y: Int) {
            val c = px[y * w + x]
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
        return Color.rgb(acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0]) to acc[0].toFloat() / counts.values.sumOf { it[0] }
    }

    private fun sample(
        px: IntArray,
        w: Int,
        h: Int,
        bounds: Rect,
        text: String,
        cut: Boolean,
        covers: List<Rect> = emptyList(),
        growTo: Int = bounds.right,
    ): Measure? {
        val r = Rect(bounds)
        if (!r.intersect(0, 0, w, h) || r.width() < 3 || r.height() < 3) return null
        fun at(x: Int, y: Int) = px[y * w + x]
        fun dist(a: Int, b: Int) =
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

        // Background: the most common colour along the edge. A box with a border (a button, a
        // card, a field) has the border there: then the colour just inside it is the background,
        // and the border is left out of everything below.
        var (bg, share) = edgeColor(px, w, r)
        val k = maxOf(3, minOf(r.width(), r.height()) / 12)
        if (r.width() > 2 * k + 8 && r.height() > 2 * k + 8) {
            val inner = Rect(r).apply { inset(k, k) }
            val (innerBg, innerShare) = edgeColor(px, w, inner)
            if (dist(bg, innerBg) > 60 && innerShare >= 0.7f && innerShare > share) {
                bg = innerBg
                share = innerShare
                r.set(inner)
            }
        }
        // Flat background (one colour all around): a plain fill is pixel-identical. Otherwise
        // (photos, gradients) the real background is rebuilt behind the letters.
        val flat = share >= 0.85f

        // Letters: rows and columns that contain pixels clearly different from the background. Their
        // colour is the most common one among those pixels (one stray pixel of a border or icon
        // mustn't decide it); [best] is the strongest difference seen.
        var fg = 0
        var best = 0
        val inkColors = HashMap<Int, IntArray>()
        var inkLeft = Int.MAX_VALUE
        var inkRight = -1
        val rowCount = IntArray(r.height())
        val rowLeft = IntArray(r.height()) { Int.MAX_VALUE }
        val rowRight = IntArray(r.height()) { -1 }
        for (y in r.top until r.bottom) {
            var x = r.left
            while (x < r.right) {
                if (covers.isNotEmpty() && covers.any { it.contains(x, y) }) { x += 2; continue }
                val c = at(x, y)
                val d = dist(c, bg)
                if (d > 120) {
                    val row = y - r.top
                    rowCount[row]++
                    if (d > 200) {
                        val bucket = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
                        val acc = inkColors.getOrPut(bucket) { IntArray(4) }
                        acc[0]++; acc[1] += Color.red(c); acc[2] += Color.green(c); acc[3] += Color.blue(c)
                    }
                    if (x < rowLeft[row]) rowLeft[row] = x
                    if (x > rowRight[row]) rowRight[row] = x
                    if (x < inkLeft) inkLeft = x
                    if (x > inkRight) inkRight = x
                }
                if (d > best) { best = d; fg = c }
                x += 2
            }
        }
        inkColors.values.maxByOrNull { it[0] }?.takeIf { it[0] >= 6 }?.let { acc ->
            fg = Color.rgb(acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0])
        }
        if (best < 90) {
            // Too few text pixels hit: pick black or white for contrast.
            val luminance = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
            fg = if (luminance > 140) 0xFF1F1F1F.toInt() else 0xFFF1F1F1.toInt()
        }

        // Lines of text are bands of rows with a fair amount of ink; the thin rows where one line's
        // descenders touch the next line's ascenders separate them. Small bands (accents) join a neighbour.
        val peak = rowCount.maxOrNull() ?: 0
        val rowInk = BooleanArray(rowCount.size) { rowCount[it] > maxOf(1, peak / 20) }
        val bands = ArrayList<IntArray>()
        var y = 0
        while (y < rowInk.size) {
            if (rowInk[y]) {
                val s = y
                while (y < rowInk.size && rowInk[y]) y++
                bands.add(intArrayOf(s, y))
            } else {
                y++
            }
        }
        val tallest = bands.maxOfOrNull { it[1] - it[0] } ?: 0
        var i = 0
        while (i < bands.size && bands.size > 1) {
            val bh = bands[i][1] - bands[i][0]
            if (bh < tallest * 0.45f) {
                val j = if (i + 1 < bands.size && (i == 0 || bands[i + 1][0] - bands[i][1] < bands[i][0] - bands[i - 1][1])) i + 1 else i - 1
                bands[j] = intArrayOf(minOf(bands[i][0], bands[j][0]), maxOf(bands[i][1], bands[j][1]))
                bands.removeAt(i)
                i = 0
            } else {
                i++
            }
        }

        var size = 0
        var pitch = 0
        var centered = 0
        var area = intArrayOf(0, 0, 0, 0)
        if (bands.isNotEmpty() && inkRight >= inkLeft) {
            // A line of letters, from the top of tall letters to the bottom of descenders, spans
            // about 0.95 em; lines without descenders are a little shorter, which errs on small.
            val heights = bands.map { it[1] - it[0] }.sorted()
            // Text cut off at the screen edge doesn't show all its lines: leave its size to the page's usual size.
            if (!cut) size = fontSize(text, bands.size, inkRight - inkLeft + 1, heights[heights.size / 2])
            if (bands.size >= 2) {
                val gaps = (1 until bands.size).map { bands[it][0] - bands[it - 1][0] }.sorted()
                pitch = gaps[gaps.size / 2]
            }
            // Centered text: every line's middle sits at the box's middle, and some line is short.
            val boxCenter = (bounds.left + bounds.right) / 2f
            val lineEdges = bands.map { band ->
                var l = Int.MAX_VALUE
                var rr = -1
                for (row in band[0] until band[1]) {
                    l = minOf(l, rowLeft[row]); rr = maxOf(rr, rowRight[row])
                }
                intArrayOf(l, rr)
            }.filter { it[1] >= it[0] }
            val allMiddle = lineEdges.all { abs((it[0] + it[1]) / 2f - boxCenter) < bounds.width() * 0.05f }
            val someShort = lineEdges.any { it[1] - it[0] < bounds.width() * 0.85f }
            if (lineEdges.isNotEmpty() && allMiddle && someShort) centered = 1
            area = intArrayOf(
                inkLeft - bounds.left, r.top - bounds.top + bands.first()[0],
                inkRight - bounds.left + 1, r.top - bounds.top + bands.last()[1],
            )
        }
        val textColor = fg or 0xFF000000.toInt()

        // Bold: thick strokes fill much more of each line than regular ones.
        var bold = 0
        if (bands.isNotEmpty() && inkRight > inkLeft) {
            val inked = rowCount.sum().toFloat()
            val lineArea = bands.sumOf { it[1] - it[0] } * (inkRight - inkLeft + 1) / 2f
            val fill = if (lineArea > 0) inked / lineArea else 0f
            bold = if (CyrillicOcr.hasCyrillic(text)) {
                // Cyrillic fills its lines more than Latin (few ascenders), so the amount of ink alone
                // misleads: the strokes must also be thick.
                if (fill > BOLD_FILL_CYRILLIC && strokeWidth(px, w, r, bands, bg, covers) > BOLD_STROKE) 1 else 0
            } else {
                if (fill > BOLD_FILL) 1 else 0
            }
        }

        // Rows hidden behind something pinned on top (a sticky header or footer bar) show that
        // thing at both side edges instead of the background: find them from the top and bottom.
        var visibleTop = 0
        var visibleBottom = bounds.height()
        // A pinned bar spans the whole width, so it also covers the background just outside the
        // text on both sides; ordinary text has plain background there.
        val outLeft = r.left - 4
        val outRight = r.right + 3
        if (flat && outLeft >= 0 && outRight < w) {
            fun hidden(yy: Int): Boolean {
                val a1 = at(outLeft, yy)
                val a2 = at(outRight, yy)
                return dist(a1, bg) > 60 && dist(a2, bg) > 60 && dist(a1, a2) < 60
            }
            var t = r.top
            while (t < r.bottom && hidden(t)) t++
            if (t - r.top >= 6) visibleTop = t - bounds.top
            var bt = r.bottom - 1
            while (bt >= r.top && hidden(bt)) bt--
            if (r.bottom - 1 - bt >= 6) visibleBottom = bt + 1 - bounds.top
        }

        // How far a longer translation may extend to the right: only over the same flat background.
        var growLimit = bounds.right
        if (flat) {
            val rows = intArrayOf(r.top + r.height() / 4, r.top + r.height() / 2, r.top + r.height() * 3 / 4)
            var x = r.right
            val end = minOf(growTo, w)
            while (x < end && rows.all { dist(at(x, it), bg) <= 50 }) x += 2
            growLimit = x
        }

        // Non-flat background: keep the real pixels and paint the letters out.
        var patch: Bitmap? = null
        var patchRect: Rect? = null
        if (!flat) {
            val pr = Rect(r).apply { inset(-3, -3); intersect(0, 0, w, h) }
            patch = inpaint(px, w, pr, textColor, bg)
            patchRect = Rect(pr).apply { offset(-bounds.left, -bounds.top) }
        }

        return Measure(
            intArrayOf(
                bg, textColor, size, bands.size,
                area[0], area[1], area[2], area[3], centered, pitch, bounds.width(), bounds.height(),
                visibleTop, visibleBottom, growLimit, bold,
            ),
            patch, patchRect,
        )
    }

    /**
     * Rebuilds the background behind text: pixels close to the text colour (grown by 2 px to catch
     * anti-aliasing) are replaced by blending the nearest background pixels to the left/right and
     * above/below, so photos and gradients continue smoothly where the letters were.
     */
    private fun inpaint(px: IntArray, w: Int, rect: Rect, fg: Int, bg: Int): Bitmap? {
        val pw = rect.width()
        val ph = rect.height()
        if (pw <= 0 || ph <= 0 || pw.toLong() * ph > 2_500_000) return null
        fun dist(a: Int, b: Int) =
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
        val src = IntArray(pw * ph)
        for (yy in 0 until ph) System.arraycopy(px, (rect.top + yy) * w + rect.left, src, yy * pw, pw)

        val near = maxOf(45, dist(fg, bg) / 2)
        var mask = BooleanArray(pw * ph) { dist(src[it], fg) < near }
        // Grow the mask by 2 px horizontally, then vertically.
        val grown = BooleanArray(pw * ph)
        for (yy in 0 until ph) for (xx in 0 until pw) {
            if (!mask[yy * pw + xx]) continue
            for (d in -2..2) { val x2 = xx + d; if (x2 in 0 until pw) grown[yy * pw + x2] = true }
        }
        mask = BooleanArray(pw * ph)
        for (yy in 0 until ph) for (xx in 0 until pw) {
            if (!grown[yy * pw + xx]) continue
            for (d in -2..2) { val y2 = yy + d; if (y2 in 0 until ph) mask[y2 * pw + xx] = true }
        }

        fun lerp(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
        val horizontal = arrayOfNulls<Int>(pw * ph)
        for (yy in 0 until ph) {
            var xx = 0
            while (xx < pw) {
                if (!mask[yy * pw + xx]) { xx++; continue }
                val s0 = xx
                while (xx < pw && mask[yy * pw + xx]) xx++
                val left = if (s0 > 0) src[yy * pw + s0 - 1] else null
                val right = if (xx < pw) src[yy * pw + xx] else null
                for (k in s0 until xx) {
                    horizontal[yy * pw + k] = when {
                        left != null && right != null -> lerp(left, right, (k - s0 + 1f) / (xx - s0 + 1f))
                        else -> left ?: right
                    }
                }
            }
        }
        val out = src.copyOf()
        for (xx in 0 until pw) {
            var yy = 0
            while (yy < ph) {
                if (!mask[yy * pw + xx]) { yy++; continue }
                val s0 = yy
                while (yy < ph && mask[yy * pw + xx]) yy++
                val top = if (s0 > 0) src[(s0 - 1) * pw + xx] else null
                val bottom = if (yy < ph) src[yy * pw + xx] else null
                for (k in s0 until yy) {
                    val i = k * pw + xx
                    val v = when {
                        top != null && bottom != null -> lerp(top, bottom, (k - s0 + 1f) / (yy - s0 + 1f))
                        else -> top ?: bottom
                    }
                    val hz = horizontal[i]
                    out[i] = when {
                        v != null && hz != null -> lerp(v, hz, 0.5f)
                        else -> v ?: hz ?: bg
                    } or 0xFF000000.toInt()
                }
            }
        }
        return Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888)
    }

    private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The original's font size, found from the text itself so it works for any script: one line
     * must exactly fill its measured width; several lines must wrap into the same number of lines
     * at the measured width. The letters' height keeps the answer sane.
     */
    private fun fontSize(text: String, lines: Int, inkWidth: Int, lineHeight: Int): Int {
        val lo = lineHeight / 1.05f
        val hi = lineHeight / 0.6f
        val size = if (lines <= 1) {
            measurePaint.textSize = 100f
            val at100 = measurePaint.measureText(text)
            if (at100 <= 0f) return (lineHeight / 0.95f).toInt()
            inkWidth * 100f / at100
        } else {
            measurePaint.textSize = lo
            val atSmallest = StaticLayout.Builder.obtain(text, 0, text.length, measurePaint, maxOf(1, inkWidth))
                .setIncludePad(false).build().lineCount
            if (atSmallest > lines) {
                // Doesn't fit even at the smallest plausible size: the page shows only part of the
                // text ("…"), so only the height of its lines tells the size. Cyrillic lines are
                // shorter (few tall letters) than Latin ones.
                return (lineHeight / if (CyrillicOcr.hasCyrillic(text)) 0.75f else 0.95f).toInt()
            }
            var a = lo
            var b = hi
            repeat(8) {
                val m = (a + b) / 2
                measurePaint.textSize = m
                val wrapped = StaticLayout.Builder.obtain(text, 0, text.length, measurePaint, maxOf(1, inkWidth))
                    .setIncludePad(false).build().lineCount
                if (wrapped <= lines) a = m else b = m
            }
            a
        }
        return size.coerceIn(lo, hi).toInt()
    }

    private val nativeInit = Mutex()

    private suspend fun translateAll(texts: List<String>) {
        // The first language check also starts ML Kit's native code: do it alone (see nativeInit).
        // The language of the app's own text on screen is the fallback for its short, unclear texts.
        val pageSample = texts.filter { it !in ocrTexts }.joinToString("\n").take(2000)
        val pageLang = nativeInit.withLock {
            if (pageSample.isBlank()) UND else detectLanguage(pageSample, UND, fromOcr = false).first
        }
        // A few at a time, top of the screen first; each one appears as soon as it's ready.
        for (chunk in texts.chunked(6)) {
            chunk.map { text ->
                scope.async {
                    translate(text, pageLang)?.let { result ->
                        cache.put(text, result)
                        if (result.isEmpty()) traceKept(text)
                        renderSoon()
                    }
                }
            }.awaitAll()
        }
        showTranslatingStatus()
    }

    /** Debug log: the blocks showing [text] keep their original. */
    private fun traceKept(text: String) {
        if (!trace.enabled) return
        blocks.filter { it.text == text }.forEach { b ->
            trace.existing(traceKey(b))?.let { rec ->
                rec.overlay = "not drawn: original kept"
                trace.finish(rec)
            }
        }
    }

    /**
     * The language of [text], and why (for the debug log). Cyrillic is told apart by its letters:
     * Russian, Ukrainian, Serbian, Macedonian and Belarusian each have letters Bulgarian doesn't, so
     * Cyrillic without them is Bulgarian (short Bulgarian phrases are otherwise often taken for
     * Russian or Macedonian). Other scripts use ML Kit's language identification. OCR text whose
     * language isn't clear isn't translated at all; the app's own short texts fall back to
     * [pageLang] when it's the same script.
     */
    private suspend fun detectLanguage(text: String, pageLang: String, fromOcr: Boolean): Pair<String, String> {
        val letters = text.filter { it.isLetter() }
        val cyrillic = letters.count { it in 'Ѐ'..'ӿ' }
        if (letters.isNotEmpty() && cyrillic * 2 >= letters.length) {
            val lower = text.lowercase()
            for ((lang, chars) in CYRILLIC_MARKERS) {
                val seen = chars.filter { it in lower }
                if (seen.isNotEmpty()) return lang to "Cyrillic with $seen, which Bulgarian doesn't have"
            }
            val shared = SERBIAN_OR_MACEDONIAN.filter { it in lower }
            if (shared.isNotEmpty()) {
                val id = identify(text)
                return (if (id == "mk") "mk" else "sr") to "Cyrillic with $shared (Serbian or Macedonian; ML Kit said $id)"
            }
            return TranslateLanguage.BULGARIAN to "Cyrillic using only Bulgarian letters"
        }
        val candidates = runCatching { langId.identifyPossibleLanguages(text).await() }.getOrDefault(emptyList())
        val listed = candidates.joinToString { "${it.languageTag} ${"%.2f".format(it.confidence)}" }
        val top = candidates.firstOrNull { it.languageTag != UND }
        if (top != null && (!fromOcr || top.confidence >= OCR_LANGUAGE_CONFIDENCE)) {
            return top.languageTag to "ML Kit language ID: $listed"
        }
        if (fromOcr) return UND to "unclear, so not translated (ML Kit language ID: $listed)"
        val sameScript = pageLang != UND && (pageLang in CYRILLIC_LANGUAGES) == (cyrillic > 0)
        return if (sameScript) {
            pageLang to "unclear on its own, so the screen's language (ML Kit language ID: $listed)"
        } else {
            UND to "unclear (ML Kit language ID: $listed)"
        }
    }

    private suspend fun identify(text: String): String =
        runCatching { langId.identifyLanguage(text).await() }.getOrDefault(UND)

    /** Returns the translation, "" to leave the text as it is, or null on a temporary failure. */
    private suspend fun translate(text: String, pageLang: String): String? {
        val fromOcr = text in ocrTexts
        val (lang, why) = detectLanguage(text, pageLang, fromOcr)
        val info = trace.text(text)
        info?.language = "$lang: $why"
        val source = TranslateLanguage.fromLanguageTag(lang)
        if (source == null || source == target) {
            info?.translation = if (source == null) "not translated: language unclear or not supported" else "not translated: already ${Languages.name(target)}"
            return ""
        }
        UiTerms.lookup(text, source, target)?.let { term ->
            info?.translation = "\"$term\" (interface label; the model alone gets its sense wrong)"
            return term
        }
        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
            )
        }
        val ready = models.getOrPut(source) {
            scope.async {
                val have = runCatching {
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
                        .map { it.language }
                }.getOrDefault(emptyList())
                val missing = listOf(source, target).filter { it !in have }
                if (missing.isNotEmpty()) {
                    if (!online()) {
                        // Offline and never downloaded: say so instead of waiting for a download.
                        setStatus(
                            service.getString(R.string.live_offline_missing, missing.joinToString { Languages.name(it) })
                        )
                        delay(OFFLINE_RETRY_MS)
                        return@async false
                    }
                    setStatus(
                        service.getString(R.string.live_downloading, Languages.name(source), Languages.name(target))
                    )
                }
                // ML Kit's native code must not be started from two threads at once (it aborts the
                // app), so each translator is set up and warmed up one at a time.
                nativeInit.withLock {
                    runCatching {
                        translator.downloadModelIfNeeded().await()
                        translator.translate(".").await()
                    }.isSuccess
                }
            }
        }
        if (!ready.await()) {
            models.remove(source)
            return null
        }
        val input = UiTerms.expand(text, source)
        val first = runCatching { translator.translate(input).await() }
            .onFailure { e -> info?.translation = "translation failed: $e" }
            .getOrNull() ?: return null
        var output = first
        var problem = checkTranslation(text, first, source, fromOcr)
        val sentences = sentences(input)
        if (problem != null && sentences.size > 1) {
            // Long paragraphs sometimes come back partly untranslated: try sentence by sentence.
            val parts = sentences.map { part -> runCatching { translator.translate(part).await() }.getOrNull() ?: return null }
            val joined = parts.joinToString(" ")
            if (checkTranslation(text, joined, source, fromOcr) == null) {
                output = joined
                problem = null
            }
        }
        if (problem != null) {
            info?.translation = "left as original: $problem (the model said \"$first\")"
            return ""
        }
        info?.translation = "\"$output\"" + if (output != first) " (translated sentence by sentence)" else ""
        return output
    }

    /** Why [translation] of [text] can't be shown, or null when it's fine. */
    private fun checkTranslation(text: String, translation: String, source: String, fromOcr: Boolean): String? {
        // An icon read as a letter, say: a translation without letters isn't trusted.
        if (text.any { it.isLetter() } && translation.none { it.isLetter() }) return "no letters in the translation"
        // Names, addresses: nothing to change.
        if (translation.filter { it.isLetterOrDigit() }.equals(text.filter { it.isLetterOrDigit() }, ignoreCase = true)) {
            return "the translation is the same as the original"
        }
        // An abbreviation the model didn't understand comes back spelled in Latin letters ("Пт" -> "PT").
        // Longer words spelled the same are names or shared words ("Иван" -> "Ivan", "Видео" -> "Video").
        if (text.count { it.isLetter() } <= 3 && CyrillicOcr.sameWord(text, translation)) {
            return "the translation only spells the original in Latin letters"
        }
        // Words the model couldn't translate come back as they were. Names (capitalised) may stay.
        if (source in CYRILLIC_LANGUAGES && target !in CYRILLIC_LANGUAGES) {
            val left = translation.split(' ')
                .map { w -> w.trim { !it.isLetter() } }
                .filter { w -> w.isNotEmpty() && CyrillicOcr.hasCyrillic(w, 1) && w.first().isLowerCase() }
            if (left.isNotEmpty()) return "words left untranslated: ${left.take(5).joinToString()}"
        }
        if (fromOcr) {
            // Signs of the model making things up instead of translating.
            val ratio = translation.length.toFloat() / text.length
            if (text.length >= 12 && (ratio > 2.5f || ratio < 0.35f)) {
                return "the translation's length doesn't match the original (${"%.1f".format(ratio)}x)"
            }
            if (repeats(translation)) return "the translation repeats itself"
        }
        return null
    }

    /** Whether some word or short phrase repeats three times in a row ("the the the"). */
    private fun repeats(text: String): Boolean {
        val w = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (n in 1..3) for (i in 0..w.size - 3 * n) {
            val a = w.subList(i, i + n)
            if (a == w.subList(i + n, i + 2 * n) && a == w.subList(i + 2 * n, i + 3 * n)) return true
        }
        return false
    }

    /** Splits a paragraph into sentences (at . ! ? … followed by a capital letter or quote). */
    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+(?=[\\p{Lu}\"„«(])")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun online(): Boolean {
        val cm = service.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
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
        view.onPlaced = { key, where ->
            trace.existing(key)?.let {
                it.overlay = where
                trace.finish(it)
            }
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
