package mg.tsena.vente

import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.TypeOperationCaisse
import mg.tsena.data.db.entity.TypePaiement
import mg.tsena.data.db.entity.TypeVente
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculsVenteTest {
    @Test
    fun `prix de gros absent interdit la vente en gros`() {
        assertEquals(1_000L, CalculsVente.prixApplique(TypeVente.DETAIL, 1_000, null))
        assertNull(CalculsVente.prixApplique(TypeVente.GROS, 1_000, null))
        assertEquals(900L, CalculsVente.prixApplique(TypeVente.GROS, 1_000, 900))
    }

    @Test
    fun `remise limitee au pourcentage du total`() {
        assertTrue(CalculsVente.remiseDansLimite(10_000, 1_000, 10))
        assertFalse(CalculsVente.remiseDansLimite(10_000, 1_001, 10))
        assertTrue(CalculsVente.remiseDansLimite(10_000, 0, 0))
    }

    @Test
    fun `type de paiement`() {
        assertEquals(TypePaiement.COMPTANT, CalculsVente.typePaiement(listOf(ModePaiement.ESPECES, ModePaiement.MOBILE_MONEY)))
        assertEquals(TypePaiement.CREDIT, CalculsVente.typePaiement(listOf(ModePaiement.CREDIT)))
        assertEquals(TypePaiement.MIXTE, CalculsVente.typePaiement(listOf(ModePaiement.ESPECES, ModePaiement.CREDIT)))
    }

    @Test
    fun `monnaie a rendre`() {
        assertEquals(2_500L, CalculsVente.rendu(10_000, 7_500))
        assertNull(CalculsVente.rendu(5_000, 7_500))
    }

    @Test
    fun `retour au prorata de la remise`() {
        // Vente de 10 000 Ar avec 1 000 Ar de remise : un article de 2 000 Ar est remboursé 1 800 Ar
        assertEquals(1_800L, CalculsVente.montantRetour(2_000, 10_000, 9_000))
        assertEquals(2_000L, CalculsVente.montantRetour(2_000, 10_000, 10_000))
    }

    @Test
    fun `solde theorique de la caisse`() {
        val operations = listOf(
            TypeOperationCaisse.ENCAISSEMENT to 12_000L,
            TypeOperationCaisse.RETRAIT to 5_000L,
            TypeOperationCaisse.APPORT to 1_000L,
            TypeOperationCaisse.DECAISSEMENT to 500L,
        )
        assertEquals(17_500L, CalculsVente.soldeTheorique(10_000, operations))
    }
}
