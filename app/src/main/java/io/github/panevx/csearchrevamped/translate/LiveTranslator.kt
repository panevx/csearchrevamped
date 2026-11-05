package io.github.panevx.csearchrevamped.translate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
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
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
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
import java.util.Locale

/**
 * Translates the text of the app on screen in place and keeps up while the user scrolls.
 * Text and positions come from the accessibility tree; translation runs on-device with ML Kit.
 */
class LiveTranslator(
    private val service: AccessibilityService,
    private val onClosed: () -> Unit,
) {
    private data class TextBlock(val text: String, val bounds: Rect)

    private companion object {
        const val MAX_BLOCKS = 250
        const val REFRESH_DELAY_MS = 120L
        const val MAX_REFRESH_LATENCY_MS = 400L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val themed = ContextThemeWrapper(service, R.style.Theme_CSearch_Overlay)
    private val target: String =
        TranslateLanguage.fromLanguageTag(Prefs.targetLang(service)) ?: TranslateLanguage.ENGLISH
    private val targetName = Locale.forLanguageTag(target).displayLanguage

    private val langId: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build()
    )
    private val translators = HashMap<String, Translator>()
    private val models = HashMap<String, Deferred<Boolean>>()
    /** Original text to translation; "" means leave it as it is. */
    private val cache = LruCache<String, String>(1500)

    private var overlay: TranslationOverlayView? = null
    private var pill: View? = null
    private var status: TextView? = null
    private var savedInfo: IntArray? = null
    private var blocks: List<TextBlock> = emptyList()
    private var translating: Job? = null
    private var pendingSince = 0L
    private var stopped = false
    private val refreshRunnable = Runnable { refresh() }

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
        setStatus(service.getString(R.string.live_translating, targetName))
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
        pill?.let { runCatching { wm.removeView(it) } }
        translators.values.forEach { it.close() }
        langId.close()
        onClosed()
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
        collect(root, screen, found, 0)
        blocks = found
        render()

        val missing = found.map { it.text }.distinct().filter { cache.get(it) == null }
        if (missing.isNotEmpty() && translating?.isActive != true) {
            translating = scope.launch {
                translateAll(missing)
                if (blocks.any { cache.get(it.text) == null }) scheduleRefresh()
            }
        }
    }

    private fun collect(node: AccessibilityNodeInfo, screen: Rect, out: MutableList<TextBlock>, depth: Int) {
        if (depth > 80 || out.size >= MAX_BLOCKS || !node.isVisibleToUser) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && !node.isEditable && text.any { it.isLetter() }) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 8 && r.height() > 8 && r.intersect(screen)) out.add(TextBlock(text, r))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, screen, out, depth + 1) }
        }
    }

    private fun render() {
        overlay?.setItems(blocks.mapNotNull { b ->
            cache.get(b.text)?.takeIf { it.isNotEmpty() }?.let { TranslationOverlayView.Item(b.bounds, it, b.text) }
        })
    }

    private suspend fun translateAll(texts: List<String>) {
        val pageLang = identify(texts.joinToString("\n").take(2000))
        for (chunk in texts.chunked(12)) {
            chunk.map { text -> scope.async { text to translate(text, pageLang) } }
                .awaitAll()
                .forEach { (text, result) -> if (result != null) cache.put(text, result) }
            render()
        }
        setStatus(service.getString(R.string.live_translating, targetName))
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
                    service.getString(
                        R.string.live_downloading, Locale.forLanguageTag(source).displayLanguage, targetName
                    )
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

        val bar = LayoutInflater.from(themed).inflate(R.layout.live_translate_pill, null)
        status = bar.findViewById(R.id.liveStatus)
        bar.findViewById<View>(R.id.liveStop).setOnClickListener { stop() }
        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * service.resources.displayMetrics.density).toInt()
        }
        wm.addView(bar, barParams)
        pill = bar
    }
}
