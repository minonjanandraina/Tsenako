package mg.tsena.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun `format du PIN`() {
        assertTrue(PinHasher.formatValide("1234"))
        assertTrue(PinHasher.formatValide("123456"))
        assertFalse(PinHasher.formatValide("123"))
        assertFalse(PinHasher.formatValide("1234567"))
        assertFalse(PinHasher.formatValide("12a4"))
    }

    @Test
    fun `verification du PIN`() {
        val sel = PinHasher.nouveauSel()
        val hash = PinHasher.hacher("2468", sel)
        assertTrue(PinHasher.verifier("2468", sel, hash))
        assertFalse(PinHasher.verifier("2469", sel, hash))
    }

    @Test
    fun `le sel rend chaque hash unique`() {
        val sel1 = PinHasher.nouveauSel()
        val sel2 = PinHasher.nouveauSel()
        assertNotEquals(sel1, sel2)
        assertNotEquals(PinHasher.hacher("1234", sel1), PinHasher.hacher("1234", sel2))
        assertEquals(PinHasher.hacher("1234", sel1), PinHasher.hacher("1234", sel1))
    }
}
