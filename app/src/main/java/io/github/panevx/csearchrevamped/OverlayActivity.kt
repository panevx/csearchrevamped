package io.github.panevx.csearchrevamped

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
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
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        progress = findViewById(R.id.progress)
        val hint = findViewById<TextView>(R.id.hint)
        selection = findViewById(R.id.selection)
        selection.bitmap = shot
        selection.onSelectionChanged = { hint.setText(R.string.hint_selected) }

        findViewById<View>(R.id.close).setOnClickListener { finish() }
        findViewById<View>(R.id.music).setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }
        findViewById<View>(R.id.actionSearch).setOnClickListener { openLens() }
        findViewById<View>(R.id.actionShare).setOnClickListener { share() }
        findViewById<View>(R.id.actionTranslate).setOnClickListener {
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
            withText { text ->
                if (text.isEmpty()) Toast.makeText(this, R.string.no_text, Toast.LENGTH_SHORT).show()
                else showText(text)
            }
        }
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
