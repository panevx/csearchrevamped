package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlin.math.abs

/**
 * Offline OCR for Bulgarian (Cyrillic), which ML Kit can't read, using Tesseract with the
 * tessdata_fast Bulgarian model shipped in assets/tessdata. It reads one line at a time, where
 * ML Kit found it: whole-screen layout analysis merges unrelated things (columns, buttons,
 * banners) into one paragraph. Only the Bulgarian model is used, so a word is never a mix of
 * Cyrillic and Latin letters. Not thread-safe: call from one coroutine at a time and [close] when done.
 */
class CyrillicOcr(private val context: Context) {

    /** A word and its box in the source image's pixels; [confidence] is 0-100. */
    class Word(val text: String, val box: Rect, val confidence: Float)

    private var api: TessBaseAPI? = null

    private fun engine(): TessBaseAPI? {
        api?.let { return it }
        val base = File(context.filesDir, "tesseract")
        val dir = File(base, "tessdata").apply { mkdirs() }
        val file = File(dir, "$LANGUAGE.traineddata")
        if (file.length() == 0L) {
            context.assets.open("tessdata/$LANGUAGE.traineddata").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        val created = TessBaseAPI()
        if (!created.init(base.absolutePath, LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY)) {
            created.recycle()
            return null
        }
        created.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        // Screenshots have no resolution info; this just avoids Tesseract guessing one.
        created.setVariable("user_defined_dpi", "300")
        api = created
        return created
    }

    /**
     * Reads the line of text inside [box] of [src]. The line is cut out with a margin (completing
     * letters the box cuts off), turned into
     * dark letters on a light background whatever its colors (white on a coloured button, text on
     * a gradient), stretched to full contrast and enlarged when small. [attempt] 1 enlarges more,
     * for a second try when the first reading wasn't reliable.
     */
    fun readLine(src: Bitmap, box: Rect, attempt: Int = 0): List<Word>? {
        val tess = engine() ?: return null
        val h = box.height()
        if (h < 6 || box.width() < 4) return null
        // Look at a wider area first: ML Kit's box sometimes cuts off part of the first or last
        // letter (half an "о" reads as "у", half a "В" as "З").
        val area = Rect(box.left - h, box.top - maxOf(4, h / 4), box.right + h, box.bottom + maxOf(4, h / 4))
        if (!area.intersect(0, 0, src.width, src.height)) return null
        val aw = area.width()
        val ah = area.height()
        val px = IntArray(aw * ah)
        src.getPixels(px, 0, aw, area.left, area.top, aw, ah)
        val all = IntArray(px.size) { luma(px[it]) }

        // Background: the median along the edge of the area.
        val border = ArrayList<Int>(2 * (aw + ah))
        for (x in 0 until aw) { border.add(all[x]); border.add(all[(ah - 1) * aw + x]) }
        for (y in 0 until ah) { border.add(all[y * aw]); border.add(all[y * aw + aw - 1]) }
        border.sort()
        val bg = border[border.size / 2]

        // Grow the line sideways while the columns just outside it still have ink (the rest of a
        // cut letter), then add a little background, stopping before anything else (an icon).
        val rowTop = (box.top - area.top).coerceIn(0, ah - 1)
        val rowBottom = (box.bottom - area.top).coerceIn(rowTop + 1, ah)
        fun ink(x: Int): Boolean {
            for (y in rowTop until rowBottom) if (abs(all[y * aw + x] - bg) > 60) return true
            return false
        }
        var left = (box.left - area.left).coerceIn(0, aw - 1)
        while (left > 0 && ink(left - 1)) left--
        var right = (box.right - area.left).coerceIn(left + 1, aw)
        while (right < aw && ink(right)) right++
        val margin = maxOf(4, h / 3)
        repeat(margin) { if (left > 0 && !ink(left - 1)) left-- }
        repeat(margin) { if (right < aw && !ink(right)) right++ }
        val crop = Rect(area.left + left, area.top, area.left + right, area.top + ah)
        val cw = crop.width()
        val ch = crop.height()
        val gray = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(all, y * aw + left, gray, y * cw, cw)

        // Letters lighter than the background (white on a coloured button): invert everything.
        var lighter = 0L
        var darker = 0L
        for (v in gray) {
            if (v > bg + 40) lighter += v - bg
            if (v < bg - 40) darker += bg - v
        }
        if (lighter > darker) for (i in gray.indices) gray[i] = 255 - gray[i]

        // Full contrast between the darkest letters and the background.
        val sorted = gray.copyOf().also { it.sort() }
        val lo = sorted[sorted.size / 100]
        val hi = sorted[sorted.size - 1 - sorted.size / 100]
        val range = maxOf(1, hi - lo)
        for (i in gray.indices) gray[i] = ((gray[i] - lo) * 255 / range).coerceIn(0, 255)

        // Tesseract reads best with letters about 40-60 px tall.
        val target = if (attempt == 0) 48f else 72f
        val scale = (target / h).coerceIn(1f, 4f)
        val sw = (cw * scale).toInt().coerceAtLeast(1)
        val sh = (ch * scale).toInt().coerceAtLeast(1)
        val pad = 16
        val outW = sw + 2 * pad
        val outH = sh + 2 * pad
        val out = ByteArray(outW * outH) { 255.toByte() }
        for (y in 0 until sh) {
            val fy = (y + 0.5f) / scale - 0.5f
            val y0 = fy.toInt().coerceIn(0, ch - 1)
            val y1 = (y0 + 1).coerceAtMost(ch - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until sw) {
                val fx = (x + 0.5f) / scale - 0.5f
                val x0 = fx.toInt().coerceIn(0, cw - 1)
                val x1 = (x0 + 1).coerceAtMost(cw - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val top = gray[y0 * cw + x0] * (1 - tx) + gray[y0 * cw + x1] * tx
                val bottom = gray[y1 * cw + x0] * (1 - tx) + gray[y1 * cw + x1] * tx
                out[(y + pad) * outW + x + pad] = (top * (1 - ty) + bottom * ty).toInt().toByte()
            }
        }

        tess.setImage(out, outW, outH, 1, outW)
        tess.utF8Text // runs recognition
        val words = ArrayList<Word>()
        val it = tess.resultIterator ?: run { tess.clear(); return emptyList() }
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                if (text.isEmpty()) continue
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                val mapped = Rect(
                    crop.left + ((r.left - pad) / scale).toInt(),
                    crop.top + ((r.top - pad) / scale).toInt(),
                    crop.left + ((r.right - pad) / scale).toInt(),
                    crop.top + ((r.bottom - pad) / scale).toInt(),
                )
                words.add(Word(text, mapped, it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)))
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
            tess.clear()
        }
        return words
    }

    fun close() {
        api?.recycle()
        api = null
    }

    companion object {
        private const val LANGUAGE = "bul"

        private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

        fun hasCyrillic(text: String, min: Int = 2) = text.count { it in 'Ѐ'..'ӿ' } >= min

        private val LATIN = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
            'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
            'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sht", 'ъ' to "a", 'ь' to "y", 'ю' to "yu", 'я' to "ya", 'ё' to "yo", 'ы' to "y",
            'э' to "e", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g",
        )

        private fun latin(text: String) = buildString {
            for (c in text.lowercase()) if (c.isLetterOrDigit()) append(LATIN[c] ?: c.toString())
        }

        /** Whether [translation] is just [original] written in Latin letters, i.e. not translated. */
        fun sameWord(original: String, translation: String): Boolean {
            if (!hasCyrillic(original, 1)) return false
            val a = latin(original)
            val b = latin(translation)
            return a.isNotEmpty() && a == b
        }
    }
}
