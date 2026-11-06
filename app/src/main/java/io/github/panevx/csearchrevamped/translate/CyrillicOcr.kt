package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Offline OCR for text ML Kit can't read, mainly Bulgarian (Cyrillic), using Tesseract with the
 * tessdata_fast Bulgarian + English models shipped in assets/tessdata. Not thread-safe: call
 * [read] from one coroutine at a time and [close] when done.
 */
class CyrillicOcr(private val context: Context) {

    class Paragraph(val text: String, val box: Rect, val confidence: Float)

    private var api: TessBaseAPI? = null

    private fun engine(): TessBaseAPI? {
        api?.let { return it }
        val base = File(context.filesDir, "tesseract")
        val dir = File(base, "tessdata").apply { mkdirs() }
        for (lang in LANGUAGES) {
            val file = File(dir, "$lang.traineddata")
            if (file.length() > 0) continue
            context.assets.open("tessdata/$lang.traineddata").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        val created = TessBaseAPI()
        if (!created.init(base.absolutePath, LANGUAGES.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) {
            created.recycle()
            return null
        }
        created.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api = created
        return created
    }

    /** Paragraphs of text with their boxes in [bitmap] pixels. */
    fun read(bitmap: Bitmap): List<Paragraph> {
        val tess = engine() ?: return emptyList()
        tess.setImage(bitmap)
        tess.utF8Text // runs recognition
        val out = ArrayList<Paragraph>()
        val it = tess.resultIterator ?: return emptyList()
        try {
            it.begin()
            do {
                val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_PARA)?.trim().orEmpty()
                if (text.isEmpty()) continue
                val box = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_PARA)
                val confidence = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_PARA)
                // Lines of a paragraph are joined with spaces, like the other OCR results.
                out.add(Paragraph(text.replace(Regex("\\s*\\n\\s*"), " "), box, confidence))
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_PARA))
        } finally {
            it.delete()
            tess.clear()
        }
        return out
    }

    fun close() {
        api?.recycle()
        api = null
    }

    companion object {
        private val LANGUAGES = listOf("bul", "eng")

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

        /**
         * Whether Latin OCR [text] is real Latin text rather than Cyrillic misread as Latin. Cyrillic
         * read with a Latin model only yields look-alike letters (M, p, e, H, T, a, c, y, K, x, B...);
         * real Latin text has letters no Cyrillic letter resembles.
         */
        fun clearlyLatin(text: String): Boolean {
            val letters = text.filter { it.isLetter() }
            if (letters.length < 3 || hasCyrillic(text, 1)) return false
            val unlike = letters.count { it in "dfhijlqstvwzDFGIJLQRSUVWZ" }
            return unlike >= maxOf(2, letters.length / 6)
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
