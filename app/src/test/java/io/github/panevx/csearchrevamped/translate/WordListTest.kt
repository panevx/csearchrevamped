package io.github.panevx.csearchrevamped.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordListTest {
    private val bulgarian = WordList.read(File("src/main/assets/words/bg.bin").inputStream())
    private val english = WordList.read(File("src/main/assets/words/en.bin").inputStream())

    @Test
    fun hashMatchesTheGenerator() {
        // Value printed by the script that made the files.
        assertEquals(4638238466255362026L, WordList.hash("мрежа"))
    }

    @Test
    fun knowsBulgarianWordsInAnyCaseWithPunctuation() {
        assertTrue(bulgarian.contains("Мрежа"))
        assertTrue(bulgarian.contains("образованието"))
        assertTrue(bulgarian.contains("година."))
        assertTrue(bulgarian.contains("„Настройки“"))
    }

    @Test
    fun rejectsMisreadings() {
        // Real misreadings from live translate's debug log.
        assertFalse(bulgarian.contains("рочетете"))
        assertFalse(bulgarian.contains("Зремето"))
        assertFalse(bulgarian.contains("язшке"))
        assertFalse(bulgarian.contains("Затзипд"))
    }

    @Test
    fun englishListHasBrandsButNotCyrillicMisreadAsLatin() {
        assertTrue(english.contains("Samsung"))
        assertTrue(english.contains("website."))
        assertFalse(english.contains("npegnara"))
        assertFalse(english.contains("OTCTbIKa"))
    }
}
