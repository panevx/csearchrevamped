package io.github.panevx.csearchrevamped.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Reads text on a screenshot reliably enough to translate it. ML Kit finds the lines and reads
 * Latin script; Tesseract reads each line again with the Bulgarian model. Every word is then
 * checked: a Cyrillic word must be a known Bulgarian word (or read with very high confidence), and
 * a Latin word must be read confidently by ML Kit. A paragraph is only used when every word in it
 * passes, so a misread paragraph is left as the original instead of becoming nonsense.
 */
class ScreenOcr(private val context: Context) {

    /** A paragraph of text in screenshot pixels, checked word by word. */
    class Paragraph(
        val text: String,
        val box: Rect,
        val lines: Int,
        /** Null when every word passed; otherwise why the paragraph isn't used. */
        val problem: String?,
        /** What each engine read, line by line (for the debug log). */
        val raw: List<String>,
        /** No Cyrillic in it: everything came from ML Kit's Latin reading. */
        val latinOnly: Boolean,
    )

    private class Element(val text: String, val box: Rect, val confidence: Float)

    /** One line as read and checked. */
    private class LineRead(
        val box: Rect,
        val text: String,
        val problem: String?,
        val raw: String,
        val latinOnly: Boolean,
        /** How well it was read (known words count most); used to pick between attempts. */
        val score: Int,
        val cyrillicWords: Int = 0,
        val knownWords: Int = 0,
    )

    private val cyrillic = CyrillicOcr(context)
    /** Lines already read, by ML Kit's reading and place: the screen is checked again every few seconds. */
    private val known = android.util.LruCache<String, LineRead>(400)
    private val bulgarian by lazy { WordList.bulgarian(context) }
    private val english by lazy { WordList.english(context) }

    fun close() = cyrillic.close()

    /**
     * Reads every line of ML Kit's [result] for [bmp] except those in [skip] (showing text the app
     * already reports exactly). With [tryCyrillic], lines are also read as Bulgarian. Runs
     * Tesseract: call off the main thread, one call at a time.
     */
    fun read(bmp: Bitmap, result: Text, tryCyrillic: Boolean, skip: Set<Rect>): List<Paragraph> {
        val groups = ArrayList<MutableList<LineRead>>()
        for (block in result.textBlocks) {
            val lines = block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                if (box in skip) return@mapNotNull null
                val key = "${line.text}|${box.flattenToString()}|$tryCyrillic"
                known.get(key) ?: readLine(bmp, line, box, tryCyrillic).also { known.put(key, it) }
            }
            for (group in paragraphs(lines)) {
                // ML Kit sometimes puts a paragraph's last line in a block of its own.
                val previous = if (group.size == 1) {
                    groups.firstOrNull { g -> continues(g, group.first().box) && !between(groups, g, group) }
                } else {
                    null
                }
                if (previous != null) previous.addAll(group) else groups.add(group.toMutableList())
            }
        }
        return groups.map { paragraph(it) }
    }

    private fun readLine(bmp: Bitmap, line: Text.Line, box: Rect, tryCyrillic: Boolean): LineRead {
        val elements = line.elements.mapNotNull { e -> e.boundingBox?.let { Element(e.text, it, e.confidence) } }
        val mlKit = "line ${box.toShortString()}: ML Kit \"${line.text}\" ${pct(line.confidence)} [" +
            elements.joinToString { "${it.text} ${pct(it.confidence)}" } + "]"
        if (abs(line.angle) > MAX_ANGLE) {
            return LineRead(box, line.text, "rotated text (${line.angle.toInt()}°)", mlKit, true, 0)
        }
        if (!tryCyrillic) {
            val bad = elements.firstOrNull { !latinWordOk(it, cyrillicAround = false) }
            val problem = bad?.let { "\"${it.text}\" wasn't read clearly (${pct(it.confidence)})" }
            return LineRead(box, line.text, problem, mlKit, true, if (problem == null) 1 else 0)
        }
        // Tesseract again, enlarged more, when the first reading has a word that doesn't pass or
        // isn't a known word.
        var best: LineRead? = null
        for (attempt in 0..1) {
            val words = cyrillic.readLine(bmp, box, attempt) ?: break
            val fused = fuse(box, elements, words, mlKit, attempt)
            if (best == null || fused.score > best.score) best = fused
            if (fused.problem == null && fused.knownWords == fused.cyrillicWords) break
        }
        return best ?: LineRead(box, line.text, "Tesseract couldn't read it", mlKit, true, 0)
    }

    /**
     * The same stretch of a line as read by both engines: Tesseract's words and ML Kit's words that
     * overlap each other (one engine may split what the other reads as one word, like "18°C").
     */
    private class Unit(val tess: MutableList<CyrillicOcr.Word> = ArrayList(), val latin: MutableList<Element> = ArrayList()) {
        val left get() = minOf(tess.minOfOrNull { it.box.left } ?: Int.MAX_VALUE, latin.minOfOrNull { it.box.left } ?: Int.MAX_VALUE)
        var cyrillicOk = false
        var latinOk = false
        var known = false
        /** 1 = Cyrillic reading used, 2 = Latin reading used, 0 = undecided. */
        var choice = 0
    }

    /**
     * Combines both readings: for each stretch of the line, Tesseract's reading where it's
     * Bulgarian, ML Kit's where it's Latin (a brand, a name, an English word, a code), and notes the
     * first stretch neither engine read reliably.
     */
    private fun fuse(box: Rect, elements: List<Element>, words: List<CyrillicOcr.Word>, mlKit: String, attempt: Int): LineRead {
        val units = align(words, elements)
        var cyrillicWords = 0
        var knownWords = 0
        for (u in units) {
            val tessWords = u.tess.filter { w -> w.text.any { it.isLetterOrDigit() } }
            u.known = tessWords.isNotEmpty() && tessWords.all { bulgarian.contains(it.text) }
            u.cyrillicOk = tessWords.isNotEmpty() && tessWords.all { w ->
                val letters = w.text.count { it.isLetter() }
                val cyrillicWord = letters > 0 && w.text.all { !it.isLetter() || it in CYRILLIC }
                when {
                    letters == 0 -> w.confidence >= SYMBOL_CONFIDENCE
                    !cyrillicWord -> false
                    // Short dictionary words also need some confidence: icons get read as "ни", "Пит".
                    bulgarian.contains(w.text) -> letters > 3 || w.confidence >= SHORT_WORD_CONFIDENCE
                    else -> w.confidence >= RARE_WORD_CONFIDENCE
                }
            }
            val cyrillicAround = units.any { it !== u && it.tess.any { w -> bulgarian.contains(w.text) } }
            u.latinOk = u.latin.isNotEmpty() && u.latin.all { latinWordOk(it, cyrillicAround) }
        }
        // Clear cases first.
        for (u in units) {
            u.choice = when {
                u.cyrillicOk && !u.latinOk -> 1
                u.latinOk && !u.cyrillicOk -> 2
                else -> 0
            }
        }
        // Both readings plausible.
        for ((i, u) in units.withIndex()) {
            if (u.choice != 0 || !u.cyrillicOk || !u.latinOk) continue
            val latinText = u.latin.joinToString("") { it.text }
            val tessLetters = u.tess.sumOf { w -> w.text.count { it.isLetter() } }
            u.choice = when {
                // Numbers and codes: ML Kit reads digits and Latin letters better ("S26", not "526").
                tessLetters == 0 || isCode(latinText) -> 2
                // A long known Bulgarian word read very clearly.
                u.known && tessLetters > 3 && u.tess.all { it.confidence >= CLEAR_WORD_CONFIDENCE } -> 1
                // Latin letters that no Cyrillic letter looks like ("Ultra", "is"): really Latin.
                latinText.any { it.isLetter() && it !in LOOKALIKES } -> 2
                // Shapes both scripts share ("on" / "оп", "ce" / "се"): like the words around it.
                else -> if (neighbours(units, i).let { n -> n.isNotEmpty() && n.all { it == 2 } }) 2 else 1
            }
        }
        val tokens = ArrayList<Pair<Int, String>>()
        var latinWords = 0
        var problem: String? = null
        for (u in units) {
            when (u.choice) {
                1 -> {
                    u.tess.forEach { tokens.add(it.box.left to it.text) }
                    u.tess.filter { w -> w.text.any { it.isLetter() } }.forEach { w ->
                        cyrillicWords++
                        if (bulgarian.contains(w.text)) knownWords++
                    }
                }
                2 -> {
                    u.latin.forEach { tokens.add(it.box.left to it.text) }
                    latinWords++
                }
                else -> if (problem == null) {
                    val t = u.tess.joinToString(" ") { "${it.text} ${it.confidence.toInt()}%" }
                    val l = u.latin.joinToString(" ") { "${it.text} ${pct(it.confidence)}" }
                    problem = when {
                        u.tess.isEmpty() -> "ML Kit saw \"$l\" there, but Tesseract didn't read it"
                        u.latin.isEmpty() -> "\"$t\" (Tesseract) is neither a known word nor read clearly"
                        else -> "neither reading is reliable: Tesseract \"$t\", ML Kit \"$l\""
                    }
                }
            }
        }
        if (problem == null && tokens.isEmpty()) problem = "nothing was read"
        val text = tokens.sortedBy { it.first }.joinToString(" ") { it.second }
        val raw = "$mlKit | Tesseract${if (attempt > 0) " (2nd try, larger)" else ""} [" +
            words.joinToString { "${it.text} ${it.confidence.toInt()}%" } + "] → \"$text\""
        val score = (if (problem == null) 10_000 else 0) + knownWords * 10 + latinWords * 5 + cyrillicWords
        return LineRead(box, text, problem, raw, cyrillicWords == 0, score, cyrillicWords, knownWords)
    }

    /** Groups the two engines' words into [Unit]s by overlap, left to right. */
    private fun align(words: List<CyrillicOcr.Word>, elements: List<Element>): List<Unit> {
        val units = ArrayList<Unit>()
        val unitOfElement = arrayOfNulls<Unit>(elements.size)
        for (w in words) {
            val over = elements.indices.filter { overlapX(elements[it].box, w.box) >= 0.5f }
            val existing = over.mapNotNull { unitOfElement[it] }.distinct()
            val unit = existing.firstOrNull() ?: Unit().also { units.add(it) }
            // A Tesseract word spanning two ML Kit units joins them.
            for (other in existing.drop(1)) {
                unit.tess.addAll(other.tess)
                unit.latin.addAll(other.latin)
                other.latin.forEach { e -> unitOfElement[elements.indexOf(e)] = unit }
                units.remove(other)
            }
            unit.tess.add(w)
            for (i in over) if (unitOfElement[i] == null) {
                unitOfElement[i] = unit
                unit.latin.add(elements[i])
            }
        }
        // Words only ML Kit saw.
        for (i in elements.indices) if (unitOfElement[i] == null && elements[i].text.any { it.isLetterOrDigit() }) {
            units.add(Unit(latin = mutableListOf(elements[i])))
        }
        units.forEach { u -> u.tess.sortBy { it.box.left }; u.latin.sortBy { it.box.left } }
        units.sortBy { it.left }
        return units
    }

    /** The choices (1 Cyrillic, 2 Latin) of the nearest decided units on each side of [i]. */
    private fun neighbours(units: List<Unit>, i: Int): List<Int> {
        val left = (i - 1 downTo 0).map { units[it].choice }.firstOrNull { it != 0 }
        val right = (i + 1 until units.size).map { units[it].choice }.firstOrNull { it != 0 }
        return listOfNotNull(left, right)
    }

    /**
     * Numbers and codes like "S26", "18°C", "5G", "2026": mostly digits. Digits that Cyrillic letters
     * are misread as (3 for з, 6 for б, 4 for ч, 0 for о) don't count when letters are around them
     * ("3a" is "за").
     */
    private fun isCode(text: String): Boolean {
        val alnum = text.count { it.isLetterOrDigit() }
        val digits = text.count { it.isDigit() }
        if (digits == 0 || digits * 5 < alnum * 2) return false
        return digits == alnum || text.any { it.isDigit() && it !in "3640" }
    }

    /**
     * Whether ML Kit's Latin reading of a word can be trusted: a known English word read fairly
     * confidently, a code or number ("S26", "300"), or anything read with very high confidence.
     * Inside Cyrillic text, short words made only of letters that look like Cyrillic ones ("Ha",
     * "ce", "oT") are Cyrillic misread, not Latin.
     */
    private fun latinWordOk(e: Element, cyrillicAround: Boolean): Boolean {
        val letters = e.text.count { it.isLetter() }
        if (letters == 0 || isCode(e.text)) return e.confidence >= LATIN_CODE_CONFIDENCE
        if (cyrillicAround && letters <= 3 && e.text.filter { it.isLetter() }.all { it in LOOKALIKES }) return false
        return when {
            english.contains(e.text) -> e.confidence >= LATIN_KNOWN_CONFIDENCE
            else -> e.confidence >= LATIN_UNKNOWN_CONFIDENCE
        }
    }

    /**
     * Splits a block's lines into paragraphs: lines belong together when they're about the same
     * size, close together and aligned (left, centre or within the previous line).
     */
    private fun paragraphs(lines: List<LineRead>): List<List<LineRead>> {
        val groups = ArrayList<MutableList<LineRead>>()
        for (line in lines) {
            val group = groups.lastOrNull()
            if (group != null && continues(group, line.box)) group.add(line) else groups.add(mutableListOf(line))
        }
        return groups
    }

    /** Whether some other paragraph sits between [upper]'s last line and [lower]'s first. */
    private fun between(groups: List<List<LineRead>>, upper: List<LineRead>, lower: List<LineRead>): Boolean {
        val gap = Rect(upper.last().box.left, upper.last().box.bottom, upper.last().box.right, lower.first().box.top)
        if (gap.height() <= 0) return false
        return groups.any { g -> g !== upper && g.any { Rect.intersects(it.box, gap) } }
    }

    /**
     * Whether the line at [next] continues the paragraph [group]: about the same size, aligned with
     * its last line, and following the paragraph's rhythm. A line's box spans its letters, and
     * lowercase Cyrillic is mostly x-height: a line without tall letters (б, д, р, у, capitals) is
     * only about 70% as tall as its neighbours and starts lower, so lines are compared by their
     * bottoms (the baseline, roughly), which keep a steady distance.
     */
    private fun continues(group: List<LineRead>, next: Rect): Boolean {
        val last = group.last().box
        val h = minOf(last.height(), next.height())
        val tall = maxOf(last.height(), next.height())
        if (h <= 0 || tall > h * 1.6f) return false
        if (next.top < last.bottom - h / 2) return false
        val leftAligned = abs(last.left - next.left) <= h * 1.5f
        val centered = abs(last.centerX() - next.centerX()) <= h * 1.5f
        val inside = next.left >= last.left - h && next.right <= last.right + h
        if (!leftAligned && !centered && !inside) return false
        if (group.size >= 2) {
            val pitch = last.bottom - group[group.size - 2].box.bottom
            return abs(next.bottom - last.bottom - pitch) <= pitch * 0.3f
        }
        return next.top - last.bottom <= tall * 0.8f
    }

    private fun paragraph(lines: List<LineRead>): Paragraph {
        val box = Rect()
        lines.forEach { box.union(it.box) }
        var text = ""
        for (line in lines) text = if (text.isEmpty()) line.text else join(text, line.text)
        val bad = lines.indexOfFirst { it.problem != null }
        val cyrillicWords = lines.sumOf { it.cyrillicWords }
        val knownWords = lines.sumOf { it.knownWords }
        val problem = when {
            bad >= 0 -> (if (lines.size > 1) "line ${bad + 1} of ${lines.size}: " else "") + lines[bad].problem
            // Icons, counters and badges get read as short words ("6" as "Се"): too little to check.
            text.count { it.isLetter() } < MIN_LETTERS -> "too short to read reliably"
            // Real Bulgarian text is nearly all dictionary words; misread or other-language text isn't.
            cyrillicWords >= 2 && knownWords * 4 < cyrillicWords * 3 ->
                "only $knownWords of $cyrillicWords Cyrillic words are Bulgarian words"
            else -> null
        }
        return Paragraph(text, box, lines.size, problem, lines.map { it.raw }, lines.all { it.latinOnly })
    }

    /**
     * Joins two lines of a paragraph. A word broken with a hyphen at the end of a line is joined
     * back when that makes a known word; otherwise the hyphen belongs to it ("по-високи").
     */
    private fun join(a: String, b: String): String {
        if (a.endsWith("-") && a.length >= 2 && a[a.length - 2].isLetter() && b.firstOrNull()?.isLowerCase() == true) {
            val joined = a.substringAfterLast(' ').dropLast(1) + b.substringBefore(' ')
            return if (bulgarian.contains(joined) || english.contains(joined)) a.dropLast(1) + b else a + b
        }
        return "$a $b"
    }

    private fun overlapX(a: Rect, b: Rect): Float {
        val o = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val smaller = minOf(a.width(), b.width())
        return if (o <= 0 || smaller <= 0) 0f else o.toFloat() / smaller
    }

    private fun pct(confidence: Float) = "${(confidence * 100).toInt()}%"

    private companion object {
        const val MAX_ANGLE = 8f
        /** OCR text with fewer letters isn't used. */
        const val MIN_LETTERS = 4
        /** Tesseract word confidence (0-100) above which an unknown Cyrillic word is accepted. */
        const val RARE_WORD_CONFIDENCE = 85f
        /** A known Bulgarian word of up to 3 letters needs this Tesseract confidence. */
        const val SHORT_WORD_CONFIDENCE = 45f
        /** Above this, a known Bulgarian word of 4+ letters beats a Latin reading of the same spot. */
        const val CLEAR_WORD_CONFIDENCE = 90f
        const val SYMBOL_CONFIDENCE = 75f
        const val LATIN_KNOWN_CONFIDENCE = 0.7f
        const val LATIN_CODE_CONFIDENCE = 0.65f
        const val LATIN_UNKNOWN_CONFIDENCE = 0.9f
        /** Latin letters Cyrillic letters are misread as (а с е о р х у к м н т в ь ъ и п г д б з). */
        const val LOOKALIKES = "aAcCeEoOpPxXyYkKmMHTBbunNrg36"
        val CYRILLIC = 'Ѐ'..'ӿ'
    }
}
