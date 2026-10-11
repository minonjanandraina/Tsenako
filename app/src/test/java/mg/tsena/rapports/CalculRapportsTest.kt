package mg.tsena.rapports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculRapportsTest {
    private fun ligne(
        vente: String,
        produit: String,
        quantite: Long,
        retournee: Long,
        montant: Long,
        cout: Long,
        brut: Long,
        net: Long,
        pv: String = "Boutique",
    ) = LigneVenteRapport(vente, pv, produit, produit, "pce", quantite, retournee, montant, cout, brut, net)

    @Test
    fun `chiffre d affaires et marge`() {
        // 3 pièces à 1 000 Ar, coût 600 : CA 3 000, marge 1 200
        val r = CalculRapports.ventes(listOf(ligne("v1", "savon", 3_000, 0, 3_000, 600, 3_000, 3_000)))
        assertEquals(1, r.nbVentes)
        assertEquals(3_000L, r.chiffreAffaires)
        assertEquals(1_200L, r.marge)
        assertEquals(40, r.tauxMarge)
    }

    @Test
    fun `remise repartie et retours deduits`() {
        // Vente de 10 000 Ar avec 1 000 de remise ; 1 des 2 sacs de riz (8 000 Ar) rendu
        val lignes = listOf(
            ligne("v1", "riz", 2_000, 1_000, 8_000, 3_000, 10_000, 9_000),
            ligne("v1", "huile", 1_000, 0, 2_000, 1_500, 10_000, 9_000),
        )
        val r = CalculRapports.ventes(lignes)
        // riz : 4 000 × 0,9 = 3 600 ; huile : 2 000 × 0,9 = 1 800
        assertEquals(5_400L, r.chiffreAffaires)
        assertEquals(4_500L, r.coutAchat)
        assertEquals("riz", r.parProduit.first().nom)
        assertEquals(1_000L, r.parProduit.first().quantiteMilli)
    }

    @Test
    fun `rapport vide`() {
        val r = CalculRapports.ventes(emptyList())
        assertEquals(0L, r.chiffreAffaires)
        assertNull(r.tauxMarge)
    }
}
