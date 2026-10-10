package mg.tsena.stock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorisationTest {
    @Test
    fun `cmup pondere par les quantites`() {
        // 10 pièces à 1 000 Ar + 30 pièces à 2 000 Ar = 70 000 Ar / 40 = 1 750 Ar
        assertEquals(1750L, Valorisation.nouveauCmup(10_000, 1000, 30_000, 2000))
    }

    @Test
    fun `cmup arrondi a l'ariary`() {
        // (1 × 1 000 + 2 × 1 001) / 3 = 1 000,67 → 1 001
        assertEquals(1001L, Valorisation.nouveauCmup(1000, 1000, 2000, 1001))
    }

    @Test
    fun `cmup avec quantites decimales`() {
        // 2,5 kg à 4 000 Ar + 0,5 kg à 5 200 Ar = 12 600 Ar / 3 kg = 4 200 Ar
        assertEquals(4200L, Valorisation.nouveauCmup(2500, 4000, 500, 5200))
    }

    @Test
    fun `stock vide ou negatif prend le cout de l'entree`() {
        assertEquals(3000L, Valorisation.nouveauCmup(0, 1000, 5000, 3000))
        assertEquals(3000L, Valorisation.nouveauCmup(-2000, 1000, 5000, 3000))
    }

    @Test
    fun `gros stock sans debordement`() {
        assertEquals(50_000_000L, Valorisation.nouveauCmup(1_000_000_000, 50_000_000, 1_000_000_000, 50_000_000))
    }

    @Test
    fun `valeur du stock`() {
        assertEquals(17_500L, Valorisation.valeur(10_000, 1750))
        assertEquals(1_250L, Valorisation.valeur(250, 5000))
        assertEquals(0L, Valorisation.valeur(0, 1000))
    }

    @Test
    fun `stock negatif interdit par defaut`() {
        assertTrue(Valorisation.sortiePossible(5000, 5000, negatifAutorise = false))
        assertFalse(Valorisation.sortiePossible(5000, 6000, negatifAutorise = false))
        assertTrue(Valorisation.sortiePossible(5000, 6000, negatifAutorise = true))
    }

    @Test
    fun `alerte au seuil`() {
        assertTrue(Valorisation.enAlerte(3000, 3000))
        assertTrue(Valorisation.enAlerte(0, 3000))
        assertFalse(Valorisation.enAlerte(3001, 3000))
        assertFalse(Valorisation.enAlerte(0, 0))
    }
}
