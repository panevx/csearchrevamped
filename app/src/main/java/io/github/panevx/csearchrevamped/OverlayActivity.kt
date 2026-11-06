package io.github.panevx.csearchrevamped

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen frozen screenshot where the user circles what to search. */
class OverlayActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, OverlayActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
    }

    private lateinit var shot: Bitmap
    private lateinit var selection: SelectionView
    private lateinit var progress: View
    private lateinit var hint: TextView
    private val autoSearch = Runnable { if (!isFinishing) openLens() }
    private val restoreHint = Runnable { hint.setText(R.string.hint_draw) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Take ownership so a later capture never gets cleared by this instance.
        val bmp = ScreenshotHolder.screen
        ScreenshotHolder.screen = null
        if (bmp == null) {
            finish()
            return
        }
        shot = bmp

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_overlay)
        // Keep the (transparent) system bars: the screenshot behind them already shows the same icons.
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        progress = findViewById(R.id.progress)
        val topBar = findViewById<View>(R.id.topBar)
        val actions = findViewById<View>(R.id.actions)
        val gap = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top + gap }
            actions.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + gap * 2 }
            insets
        }
        hint = findViewById(R.id.hint)
        selection = findViewById(R.id.selection)
        selection.bitmap = shot
        selection.onGestureStart = {
            selection.removeCallbacks(autoSearch)
            hint.removeCallbacks(restoreHint)
        }
        selection.onSelected = { deliberate ->
            hint.setText(R.string.hint_selected)
            // Let the selection animation finish before the results sheet slides up.
            if (deliberate && Prefs.autoSearch(this)) selection.postDelayed(autoSearch, 380)
        }
        selection.onRejected = {
            hint.setText(R.string.hint_rejected)
            hint.postDelayed(restoreHint, 1800)
        }
        Haptics.click(this)
        setUpTextSelection()

        findViewById<View>(R.id.close).setOnClickListener { dismiss() }
        onBackPressedDispatcher.addCallback(this) {
            if (selection.inTextMode) selection.endTextSelection() else dismiss()
        }
        findViewById<View>(R.id.music).setOnClickListener {
            Haptics.tick(this)
            startActivity(Intent(this, MusicActivity::class.java))
        }
        findViewById<View>(R.id.actionSearch).setOnClickListener { Haptics.tick(this); openLens() }
        findViewById<View>(R.id.actionShare).setOnClickListener { Haptics.tick(this); share() }
        findViewById<View>(R.id.actionTranslate).setOnClickListener {
            Haptics.tick(this)
            val service = CaptureService.instance
            if (service != null) {
                // Live translate works on the real app underneath, so the frozen screenshot goes away.
                finishAndRemoveTask()
                service.startLiveTranslate(delayMs = 300)
                return@setOnClickListener
            }
            withText { text ->
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.no_text_lens, Toast.LENGTH_SHORT).show()
                    openLens()
                } else {
                    translate(text)
                }
            }
        }
        findViewById<View>(R.id.actionText).setOnClickListener {
            Haptics.tick(this)
            withText { text ->
                if (text.isEmpty()) Toast.makeText(this, R.string.no_text, Toast.LENGTH_SHORT).show()
                else showText(text)
            }
        }
    }

    /**
     * Tap-to-select text: OCR reads the frozen screenshot (the original page, never the
     * translation overlay) as soon as the overlay opens, so tapping a word can select it.
     */
    private fun setUpTextSelection() {
        val actions = findViewById<View>(R.id.actions)
        val textActions = findViewById<View>(R.id.textActions)
        val hint = findViewById<TextView>(R.id.hint)
        lifecycleScope.launch {
            val words = withContext(Dispatchers.Default) { runCatching { readWords(shot) }.getOrDefault(emptyList()) }
            selection.words = words
        }
        selection.onTextSelection = { text, bounds ->
            if (text == null || bounds == null) {
                textActions.isVisible = false
                actions.isVisible = true
                hint.setText(R.string.hint_draw)
            } else {
                actions.isVisible = false
                hint.setText(R.string.hint_text_selected)
                textActions.isVisible = true
                textActions.post { placeTextActions(textActions, bounds) }
            }
        }
        findViewById<View>(R.id.textSearchGoogle).setOnClickListener {
            Haptics.tick(this)
            searchOnGoogle(selection.selectedText())
        }
        findViewById<View>(R.id.textCopy).setOnClickListener {
            Haptics.tick(this)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("text", selection.selectedText()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    /** Puts the toolbar just above the selection, or below it when there's no room. */
    private fun placeTextActions(bar: View, bounds: android.graphics.RectF) {
        val gap = 16 * resources.displayMetrics.density
        val topLimit = findViewById<View>(R.id.topBar).bottom.toFloat()
        val above = bounds.top - gap - bar.height
        bar.y = if (above > topLimit) above else bounds.bottom + gap * 2.5f
        bar.x = (bounds.centerX() - bar.width / 2f).coerceIn(gap, selection.width - bar.width - gap)
    }

    /** Words with their positions on the screenshot, in reading order. */
    private suspend fun readWords(bitmap: Bitmap): List<SelectionView.Word> {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            val out = ArrayList<SelectionView.Word>()
            var line = 0
            for (block in text.textBlocks) for (l in block.lines) {
                for (el in l.elements) el.boundingBox?.let { out.add(SelectionView.Word(el.text, it, line)) }
                line++
            }
            return out
        } finally {
            client.close()
        }
    }

    /** Opens Google Search with exactly this text: in the Google app if installed, otherwise the browser. */
    private fun searchOnGoogle(text: String) {
        if (text.isBlank()) return
        val app = Intent(Intent.ACTION_WEB_SEARCH)
            .setPackage("com.google.android.googlequicksearchbox")
            .putExtra(SearchManager.QUERY, text)
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse(Links.search(text)))
        val intent = if (app.resolveActivity(packageManager) != null) app else browser
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { startActivity(browser) } }
    }

    /** Fades the overlay out instead of cutting away. */
    private fun dismiss() {
        selection.removeCallbacks(autoSearch)
        window.decorView.animate().alpha(0f).setDuration(160).withEndAction { finish() }.start()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun currentImage(): Bitmap {
        val r = selection.selectionInBitmap() ?: return shot
        return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
    }

    private fun openLens() {
        ScreenshotHolder.crop = currentImage()
        startActivity(ResultActivity.lens(this))
    }

    private fun translate(text: String) {
        startActivity(
            ResultActivity.url(this, Links.translate(text, Prefs.targetLang(this)), getString(R.string.title_translate))
        )
    }

    private fun withText(block: (String) -> Unit) {
        progress.isVisible = true
        val image = currentImage()
        lifecycleScope.launch {
            val text = runCatching { Ocr.read(image) }.getOrDefault("")
            progress.isVisible = false
            block(text)
        }
    }

    private fun showText(text: String) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(text)
            minLines = 3
            maxLines = 12
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_found)
            .setView(container)
            .setPositiveButton(R.string.copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("text", input.text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.action_translate) { _, _ -> translate(input.text.toString()) }
            .setNegativeButton(R.string.action_search) { _, _ ->
                startActivity(
                    ResultActivity.url(this, Links.search(input.text.toString()), getString(R.string.title_web))
                )
            }
            .show()
    }

    private fun share() {
        val image = currentImage()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                File(dir, "csearch.jpg").also { f ->
                    f.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }
            }
            val uri = FileProvider.getUriForFile(this@OverlayActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, null))
        }
    }
}
