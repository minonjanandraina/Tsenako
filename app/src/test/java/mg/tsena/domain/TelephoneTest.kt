package mg.tsena.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelephoneTest {
    @Test
    fun `formats acceptes`() {
        assertEquals("+261341234567", Telephone.normaliser("034 12 345 67"))
        assertEquals("+261341234567", Telephone.normaliser("0341234567"))
        assertEquals("+261341234567", Telephone.normaliser("+261 34 12 345 67"))
    }

    @Test
    fun `formats refuses`() {
        assertNull(Telephone.normaliser("34 12 345 67"))
        assertNull(Telephone.normaliser("03412345"))
        assertNull(Telephone.normaliser("+33 6 12 34 56 78"))
    }

    @Test
    fun `affichage national`() {
        assertEquals("034 12 345 67", Telephone.formater("+261341234567"))
    }
}
