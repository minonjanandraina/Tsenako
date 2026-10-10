package mg.tsena.appro

import mg.tsena.data.db.entity.StatutCommande
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovisionnementTest {
    @Test
    fun `2 cartons de 24 pieces donnent 48 pieces`() {
        assertEquals(48_000L, Approvisionnement.versUniteProduit(2_000, 24_000, uniteProduitDecimale = false))
    }

    @Test
    fun `un demi-sac de 50 kg donne 25 kg`() {
        assertEquals(25_000L, Approvisionnement.versUniteProduit(500, 50_000, uniteProduitDecimale = true))
    }

    @Test
    fun `une fraction de piece est refusee`() {
        // 0,5 carton de 25 pièces = 12,5 pièces
        assertNull(Approvisionnement.versUniteProduit(500, 25_000, uniteProduitDecimale = false))
    }

    @Test
    fun `une fraction de millieme est refusee`() {
        // 0,001 × 0,5 kg = 0,0005 kg
        assertNull(Approvisionnement.versUniteProduit(1, 500, uniteProduitDecimale = true))
    }

    @Test
    fun `prix unitaire arrondi a l Ariary`() {
        // 10 000 Ar pour 24 pièces = 416,67 Ar
        assertEquals(417L, Approvisionnement.prixUnitaire(10_000, 24_000))
        // 3 000 Ar pour 1,5 kg = 2 000 Ar/kg
        assertEquals(2_000L, Approvisionnement.prixUnitaire(3_000, 1_500))
    }

    @Test
    fun `montant au prorata exact pour la ligne entiere`() {
        assertEquals(10_001L, Approvisionnement.montantAuProrata(10_001, 3_000, 3_000))
        assertEquals(3_334L, Approvisionnement.montantAuProrata(10_001, 3_000, 1_000))
    }

    @Test
    fun `reliquat jamais negatif`() {
        assertEquals(4_000L, Approvisionnement.reliquat(10_000, 6_000))
        assertEquals(0L, Approvisionnement.reliquat(10_000, 12_000))
    }

    @Test
    fun `commande recue quand toutes les lignes sont completes`() {
        assertEquals(StatutCommande.RECUE, Approvisionnement.statutApresReception(listOf(10_000L to 10_000L, 5_000L to 6_000L)))
        assertEquals(StatutCommande.PARTIELLE, Approvisionnement.statutApresReception(listOf(10_000L to 10_000L, 5_000L to 0L)))
    }

    @Test
    fun `numero sur six chiffres`() {
        assertEquals("CF-000012", Approvisionnement.numero("CF", 12))
    }
}
