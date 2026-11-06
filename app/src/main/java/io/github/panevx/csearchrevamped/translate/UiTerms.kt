package io.github.panevx.csearchrevamped.translate

/**
 * Standalone interface labels that the on-device model translates in the wrong sense when it has
 * no context ("Изход" becomes "Output" instead of "Exit", "Запази" "Keep" instead of "Save").
 * Only a whole label is looked up; the same words inside sentences are translated normally.
 */
object UiTerms {

    private val BULGARIAN_ENGLISH = mapOf(
        "изход" to "Exit",
        "изход от профила" to "Sign out",
        "изход от акаунта" to "Sign out",
        "готово" to "Done",
        "харесвам" to "Like",
        "отговори" to "Reply",
        "запази" to "Save",
        "отхвърли" to "Reject",
        "времето" to "Weather",
        "последни" to "Latest",
        "поръчай" to "Order",
        "хранилище" to "Storage",
    )

    /**
     * Abbreviations the model doesn't know, written out before translating: interfaces shorten
     * "устройство" (device) to "у-во" to save space, and the model turns it into nonsense.
     */
    private val ABBREVIATIONS = listOf(
        Regex("(?<!\\p{L})у-вото(?!\\p{L})", RegexOption.IGNORE_CASE) to "устройството",
        Regex("(?<!\\p{L})у-вата(?!\\p{L})", RegexOption.IGNORE_CASE) to "устройствата",
        Regex("(?<!\\p{L})у-во(?!\\p{L})", RegexOption.IGNORE_CASE) to "устройство",
        Regex("(?<!\\p{L})у-ва(?!\\p{L})", RegexOption.IGNORE_CASE) to "устройства",
    )

    /** [text] with the abbreviations above written out, for Bulgarian. */
    fun expand(text: String, source: String): String {
        if (source != "bg" || !text.contains('-')) return text
        var out = text
        for ((pattern, full) in ABBREVIATIONS) out = pattern.replace(out, full)
        return out
    }

    /** The translation of [text] if it's one of these labels, keeping all-capitals labels in capitals. */
    fun lookup(text: String, source: String, target: String): String? {
        if (source != "bg" || target != "en") return null
        val label = text.trim()
        val hit = BULGARIAN_ENGLISH[label.trimEnd('.', '!', ':').lowercase()] ?: return null
        val allCaps = label.any { it.isLetter() } && label.all { !it.isLetter() || it.isUpperCase() }
        return if (allCaps && label.count { it.isLetter() } > 1) hit.uppercase() else hit
    }
}
