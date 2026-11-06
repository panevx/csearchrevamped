package io.github.panevx.csearchrevamped.translate

import java.text.Normalizer

/**
 * Tells whether a line OCR read on screen shows the text an app reports there, or something else
 * on top of it that the app doesn't report (a consent dialog in a frame, a video ad). ML Kit's
 * Latin reading turns Cyrillic into look-alike Latin letters ("Мрежа" reads "Mpeka"), so lines are
 * compared by letter shapes, and the line must match one stretch of the reported text in order.
 */
object VisibleText {

    /** A line at least this similar to some stretch of the reported text shows that text. */
    const val SAME = 0.62f
    /** Below this it's something else. In between, it's unclear. */
    const val OTHER = 0.56f
    /** Lines shorter than this (in letters, digits and spaces) are too short to tell. */
    private const val MIN_LENGTH = 10

    private val SHAPE = HashMap<Char, Char>().apply {
        val classes = mapOf(
            'a' to "аaя", 'b' to "бвъьb6", 'r' to "гr", 'd' to "дdgq", 'e' to "еeёє", 'k' to "жкхkx",
            '3' to "з3z", 'u' to "ийцuv", 'n' to "лпn", 'm' to "мm", 'h' to "нh", 'o' to "оo0ю",
            'p' to "рp", 'c' to "сc", 't' to "тt", 'y' to "уy", 'f' to "фf", '4' to "ч4", 'w' to "шщw",
            'l' to "il1|!ıj", 's' to "s5",
        )
        for ((shape, chars) in classes) for (c in chars) put(c, shape)
    }

    /** The text as letter shapes: lowercase, no accents or punctuation, single spaces. */
    fun shapes(text: String): String {
        val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val out = StringBuilder(plain.length)
        for (c in plain) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> out.append(SHAPE[c] ?: c)
                out.isNotEmpty() && out.last() != ' ' -> out.append(' ')
            }
        }
        return out.toString().trim()
    }

    /**
     * How closely [seen] (OCR) matches the best stretch of [reported], 0 to 1, by letter shapes:
     * 1 minus the edit distance to that stretch, per letter. Null when [seen] is too short to tell.
     */
    fun match(seen: String, reported: String): Float? = matchShapes(shapes(seen), shapes(reported))

    /** [match] for texts already turned into [shapes] (when one is compared many times). */
    fun matchShapes(a: String, b: String): Float? {
        if (a.length < MIN_LENGTH) return null
        // Edit distance from [a] to any substring of [b] (a free start and end in [b]).
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            val ai = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ai == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            val t = prev; prev = cur; cur = t
        }
        val best = prev.minOrNull() ?: a.length
        return 1f - best.toFloat() / a.length
    }
}
