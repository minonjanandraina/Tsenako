package mg.tsena.credit

import org.junit.Assert.assertEquals
import org.junit.Test

class CreditsTest {
    private val jour = 24 * 60 * 60 * 1000L
    private fun vente(id: String, jourVente: Long, montant: Long, retours: Long = 0) =
        CreditVente(id, id, "c1", jourVente * jour, montant, retours)

    @Test
    fun `remboursements imputes de la plus ancienne vente a la plus recente`() {
        val etats = Credits.suivre(listOf(vente("v2", 10, 50_000), vente("v1", 1, 30_000)), 40_000, 20 * jour)
        assertEquals("v1", etats[0].venteId)
        assertEquals(0L, etats[0].reste)
        assertEquals(40_000L, etats[1].reste)
        assertEquals(19, etats[0].ageJours)
    }

    @Test
    fun `retour sur credit deduit de la vente concernee`() {
        val etats = Credits.suivre(listOf(vente("v1", 1, 30_000, retours = 10_000)), 0, 2 * jour)
        assertEquals(20_000L, etats[0].reste)
    }

    @Test
    fun `anciennete des impayes`() {
        val etats = Credits.suivre(listOf(vente("v1", 0, 10_000), vente("v2", 50, 5_000)), 0, 100 * jour)
        val tranches = Credits.parAnciennete(etats)
        assertEquals(10_000L, tranches[Anciennete.PLUS_90])
        assertEquals(5_000L, tranches[Anciennete.DE_31_A_60])
        assertEquals(0L, tranches[Anciennete.MOINS_30])
    }
}
