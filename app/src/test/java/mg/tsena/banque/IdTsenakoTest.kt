package mg.tsena.banque

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdTsenakoTest {
    @Test
    fun `saisie normalisee en majuscules sans espaces`() {
        assertEquals("LILO-0123456789", IdTsenako.normaliser(" lilo-01234 56789 "))
    }

    @Test
    fun `id invalide refuse`() {
        assertNull(IdTsenako.normaliser(""))
        assertNull(IdTsenako.normaliser("AB-1"))
        assertNull(IdTsenako.normaliser("LILO--0123"))
        assertNull(IdTsenako.normaliser("LILO_0123456"))
        assertNull(IdTsenako.normaliser("-LILO0123456"))
    }
}
