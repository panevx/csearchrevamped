package io.github.panevx.csearchrevamped.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiTermsTest {
    @Test
    fun wholeLabelsOnly() {
        assertEquals("Exit", UiTerms.lookup("Изход", "bg", "en"))
        assertEquals("Exit", UiTerms.lookup(" изход: ", "bg", "en"))
        assertEquals("EXIT", UiTerms.lookup("ИЗХОД", "bg", "en"))
        assertEquals("Sign out", UiTerms.lookup("Изход от профила", "bg", "en"))
        assertNull(UiTerms.lookup("Изходът е отляво", "bg", "en"))
    }

    @Test
    fun onlyBulgarianToEnglish() {
        assertNull(UiTerms.lookup("Изход", "ru", "en"))
        assertNull(UiTerms.lookup("Изход", "bg", "de"))
    }

    @Test
    fun writesOutDeviceAbbreviation() {
        assertEquals(
            "Сигурност на приложенията, заключване на устройството, разрешения",
            UiTerms.expand("Сигурност на приложенията, заключване на у-вото, разрешения", "bg"),
        )
        assertEquals("Ку-во", UiTerms.expand("Ку-во", "bg"))
        assertEquals("у-вото", UiTerms.expand("у-вото", "ru"))
    }
}
