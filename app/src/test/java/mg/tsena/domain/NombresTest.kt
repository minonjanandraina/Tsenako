package mg.tsena.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NombresTest {
    @Test
    fun `quantites decimales`() {
        assertEquals(1500L, Quantite.parser("1,5", decimale = true))
        assertEquals(1500L, Quantite.parser("1.5", decimale = true))
        assertEquals(250L, Quantite.parser("0,25", decimale = true))
        assertEquals(0L, Quantite.parser("", decimale = true))
        assertNull(Quantite.parser("0,0001", decimale = true))
        assertNull(Quantite.parser("-1", decimale = true))
        assertNull(Quantite.parser("abc", decimale = true))
    }

    @Test
    fun `quantites entieres`() {
        assertEquals(12000L, Quantite.parser("12", decimale = false))
        assertEquals(12000L, Quantite.parser("12,0", decimale = false))
        assertNull(Quantite.parser("1,5", decimale = false))
    }

    @Test
    fun `formatage des quantites`() {
        assertEquals("1,5", Quantite.formater(1500))
        assertEquals("12", Quantite.formater(12000))
        assertEquals("10", Quantite.formater(10000))
        assertEquals("0", Quantite.formater(0))
        assertEquals("0,25", Quantite.formater(250))
    }

    @Test
    fun montants() {
        assertEquals(12500L, Montant.parser("12 500"))
        assertNull(Montant.parser("12,5"))
        assertNull(Montant.parser(""))
        assertEquals("12 500 Ar", Montant.formater(12500))
        assertEquals("1 000 000 Ar", Montant.formater(1_000_000))
        assertEquals("500 Ar", Montant.formater(500))
        assertEquals("-2 000 Ar", Montant.formater(-2000))
    }
}
