package io.github.panevx.csearchrevamped.translate

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream

/**
 * A language's known words, used to tell real OCR readings from misreadings. Stored compactly as
 * sorted 64-bit hashes of the lowercased words (made from Tesseract's word lists; see README).
 */
class WordList private constructor(private val hashes: LongArray) {

    /** Whether [word] (any case, surrounding punctuation ignored) is a known word. */
    fun contains(word: String): Boolean {
        val w = normalize(word)
        return w.isNotEmpty() && hashes.binarySearch(hash(w)) >= 0
    }

    companion object {
        @Volatile private var bulgarian: WordList? = null
        @Volatile private var english: WordList? = null

        fun bulgarian(context: Context): WordList =
            bulgarian ?: synchronized(this) { bulgarian ?: load(context, "words/bg.bin").also { bulgarian = it } }

        fun english(context: Context): WordList =
            english ?: synchronized(this) { english ?: load(context, "words/en.bin").also { english = it } }

        private fun load(context: Context, asset: String): WordList = read(context.assets.open(asset))

        /** Reads a word list file: the number of words, then their sorted hashes. */
        fun read(stream: InputStream): WordList =
            DataInputStream(stream.buffered()).use { input ->
                val n = input.readInt()
                WordList(LongArray(n) { input.readLong() })
            }

        /** Lowercase, without the punctuation around the word (inner hyphens and apostrophes stay). */
        fun normalize(word: String): String =
            word.trim { !it.isLetterOrDigit() }.lowercase()

        /** FNV-1a over the UTF-16 code units; must match the generator script. */
        internal fun hash(w: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in w) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
