package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import io.github.panevx.csearchrevamped.Prefs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Optional debug log of live translate. For every text block it records where the text came from
 * (the app or OCR), an image of the original region, what each OCR engine read and how sure it
 * was, the detected language, the translation, and where the translation was drawn, so a bad
 * result can be traced to OCR, language detection, translation or drawing. Turned on in the app's
 * settings; stays on the phone until shared.
 */
class TranslateTrace(context: Context) {

    val enabled = Prefs.debugLog(context)

    /** One text block's path through live translate; fields are filled in stage by stage. */
    class Record(val id: Int) {
        var source = ""
        var region: Rect? = null
        var crop: String? = null
        val ocr = ArrayList<String>()
        var ocrVerdict = ""
        var text = ""
        var language = ""
        var translation = ""
        var overlay = ""
        var done = false
    }

    /** What happened to a text in language detection and translation (shared by equal texts). */
    class TextInfo {
        var language = ""
        var translation = ""
    }

    private val dir = File(context.getExternalFilesDir(null), DIR)

    /** Appends to one of the log files (re-creating the folder if it was cleared meanwhile). */
    private fun append(name: String, text: String) {
        runCatching {
            dir.mkdirs()
            File(dir, name).appendText(text)
        }
    }
    private val records = HashMap<String, Record>()
    private val texts = HashMap<String, TextInfo>()
    private var nextId = 1
    private var crops = 0
    private val io = Executors.newSingleThreadExecutor()
    private val session = SimpleDateFormat("HHmmss", Locale.US).format(Date())

    fun start(app: String?, target: String, screen: Rect) {
        if (!enabled) return
        val header = "=== ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} · app $app · " +
            "translate to $target · screen ${screen.width()}x${screen.height()} · ${Build.MODEL} Android ${Build.VERSION.RELEASE} ==="
        Log.i(TAG, header)
        io.execute {
            if (dirSize(dir) > MAX_BYTES) dir.deleteRecursively()
            File(dir, "crops").mkdirs()
            append("trace.txt", "\n$header\n")
        }
    }

    /** The record for a block, created on first use; [key] identifies the block (text and place). */
    fun record(key: String): Record? {
        if (!enabled) return null
        return records.getOrPut(key) { Record(nextId++) }
    }

    fun existing(key: String): Record? = if (enabled) records[key] else null

    fun text(text: String): TextInfo? {
        if (!enabled) return null
        return texts.getOrPut(text) { TextInfo() }
    }

    /** A note about the whole pass (OCR timing, counts), not tied to one block. */
    fun note(message: String) {
        if (!enabled) return
        Log.i(TAG, message)
        io.execute { append("trace.txt", "  · $message\n") }
    }

    /** Saves the original pixels of [rect] (screenshot coordinates) as the record's image. */
    fun saveCrop(rec: Record, px: IntArray, w: Int, h: Int, rect: Rect) {
        if (!enabled || rec.crop != null || crops >= MAX_CROPS) return
        val r = Rect(rect)
        if (!r.intersect(0, 0, w, h) || r.width() < 2 || r.height() < 2) return
        if (r.width().toLong() * r.height() > 1_500_000) return
        val copy = IntArray(r.width() * r.height())
        for (y in 0 until r.height()) System.arraycopy(px, (r.top + y) * w + r.left, copy, y * r.width(), r.width())
        val name = "crops/$session-${rec.id}.png"
        rec.crop = name
        crops++
        val cw = r.width()
        val ch = r.height()
        io.execute {
            runCatching {
                val bmp = Bitmap.createBitmap(copy, cw, ch, Bitmap.Config.ARGB_8888)
                File(dir, "crops").mkdirs()
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
    }

    /** Writes the record (once): to logcat and to the log files. */
    fun finish(rec: Record) {
        if (!enabled || rec.done) return
        rec.done = true
        texts[rec.text]?.let { info ->
            if (rec.language.isEmpty()) rec.language = info.language
            if (rec.translation.isEmpty()) rec.translation = info.translation
        }
        val region = rec.region?.toShortString() ?: "?"
        val readable = buildString {
            append("#${rec.id} · ${rec.source} · region $region")
            rec.crop?.let { append(" · image $it") }
            append('\n')
            if (rec.ocr.isNotEmpty()) {
                append("  OCR raw:     ").append(rec.ocr.joinToString("\n               ")).append('\n')
            }
            if (rec.ocrVerdict.isNotEmpty()) append("  OCR check:   ${rec.ocrVerdict}\n")
            append("  Text:        \"${rec.text}\"\n")
            if (rec.language.isNotEmpty()) append("  Language:    ${rec.language}\n")
            if (rec.translation.isNotEmpty()) append("  Translation: ${rec.translation}\n")
            append("  Overlay:     ${rec.overlay.ifEmpty { "not drawn" }}\n")
        }
        Log.i(TAG, readable.trimEnd().replace("\n", " | "))
        val json = JSONObject().apply {
            put("id", rec.id)
            put("source", rec.source)
            put("region", region)
            put("image", rec.crop ?: "")
            put("ocr", rec.ocr.joinToString("\n"))
            put("ocrCheck", rec.ocrVerdict)
            put("text", rec.text)
            put("language", rec.language)
            put("translation", rec.translation)
            put("overlay", rec.overlay)
        }.toString()
        io.execute {
            append("trace.txt", readable)
            append("trace.jsonl", json + "\n")
        }
    }

    fun close() {
        io.shutdown()
    }

    companion object {
        const val TAG = "CSTrace"
        const val DIR = "translate-debug"
        private const val MAX_CROPS = 600
        private const val MAX_BYTES = 40L * 1024 * 1024

        private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        fun hasLog(context: Context): Boolean =
            File(File(context.getExternalFilesDir(null), DIR), "trace.txt").exists()

        /** Packs the log and its images into one zip in the shared cache folder. */
        fun zip(context: Context): File? {
            val dir = File(context.getExternalFilesDir(null), DIR)
            if (!dir.exists()) return null
            val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "translate-debug.zip")
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(dir).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return out
        }

        fun clear(context: Context) {
            File(context.getExternalFilesDir(null), DIR).deleteRecursively()
        }
    }
}
