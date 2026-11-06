package io.github.panevx.csearchrevamped.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTextTest {
    private val paragraph = "Министерството на образованието ще получи допълнителни 200 милиона лева през " +
        "следващата година. Средствата ще бъдат използвани за ремонт на училища и за по-високи заплати на учителите."

    private fun same(seen: String, reported: String) = (VisibleText.match(seen, reported) ?: 0f) >= VisibleText.SAME
    private fun other(seen: String, reported: String) = (VisibleText.match(seen, reported) ?: 1f) < VisibleText.OTHER

    @Test
    fun cyrillicReadAsLatinLookAlikesMatches() {
        // ML Kit's Latin readings of lines of that paragraph.
        assertTrue(same("MMHMCTepcTBOTO Ha oópa3OBaHMeTO Ļe nony4m", paragraph))
        assertTrue(same("cheABaujata ronMHa. CpeAcTBaTa ue őbAat", paragraph))
        assertTrue(same("A6oHMpaŭte ce 3a HauiMa őroneTMH", "Абонирайте се за нашия бюлетин"))
    }

    @Test
    fun otherTextDoesNotMatch() {
        assertTrue(other("IpaBuTencTBOTO ono6pn HOB", paragraph))
        assertTrue(other("Read more about this topic on our website.", paragraph))
    }

    @Test
    fun identicalLatinTextMatchesFully() {
        assertEquals(1f, VisibleText.match("Read more about this topic.", "Read more about this topic.")!!, 0.001f)
    }

    @Test
    fun shortLinesCantBeTold() {
        assertNull(VisibleText.match("lpocun", "Профил"))
    }
}
