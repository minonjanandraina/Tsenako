package mg.tsena.compta

import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.SourceFonds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReglesComptablesTest {
    @Test
    fun `vente mixte especes et credit equilibree`() {
        val lignes = ReglesComptables.vente(10_000, listOf(ModePaiement.ESPECES to 6_000L, ModePaiement.CREDIT to 4_000L), "c1")
        assertTrue(ReglesComptables.equilibree(lignes))
        assertEquals(6_000L, lignes.first { it.compte == "531" }.debit)
        val client = lignes.first { it.compte == "411" }
        assertEquals(4_000L, client.debit)
        assertEquals("c1", client.tiersId)
        assertEquals(10_000L, lignes.first { it.compte == "707" }.credit)
    }

    @Test
    fun `vente gratuite sans ecriture`() {
        assertTrue(ReglesComptables.vente(0, emptyList(), null).isEmpty())
    }

    @Test
    fun `ecart de caisse`() {
        val manque = ReglesComptables.ecartCaisse(-500)
        assertEquals(500L, manque.first { it.compte == "658" }.debit)
        assertTrue(ReglesComptables.equilibree(manque))
        val excedent = ReglesComptables.ecartCaisse(300)
        assertEquals(300L, excedent.first { it.compte == "758" }.credit)
        assertTrue(ReglesComptables.ecartCaisse(0).isEmpty())
    }

    @Test
    fun `variation de stock vers la valeur calculee`() {
        val hausse = ReglesComptables.variationStock(valeurStock = 150_000, solde37 = 100_000)
        assertEquals(50_000L, hausse.first { it.compte == "37" }.debit)
        val baisse = ReglesComptables.variationStock(valeurStock = 80_000, solde37 = 100_000)
        assertEquals(20_000L, baisse.first { it.compte == "6037" }.debit)
        assertTrue(ReglesComptables.variationStock(100_000, 100_000).isEmpty())
    }

    @Test
    fun `contre-passation inverse debits et credits`() {
        val lignes = ReglesComptables.achat(7_000, "f1")
        val inverse = ReglesComptables.inverse(lignes)
        assertEquals(7_000L, inverse.first { it.compte == "607" }.credit)
        assertEquals(7_000L, inverse.first { it.compte == "401" }.debit)
    }

    @Test
    fun `cloture porte le benefice en 120`() {
        // Ventes 707 : 100 000 au crédit ; achats 607 : 60 000 au débit → bénéfice 40 000
        val lignes = ReglesComptables.clotureResultat(mapOf("707" to -100_000L, "607" to 60_000L))
        assertTrue(ReglesComptables.equilibree(lignes))
        assertEquals(40_000L, lignes.first { it.compte == "120" }.credit)
    }

    @Test
    fun `cloture porte la perte en 129`() {
        val lignes = ReglesComptables.clotureResultat(mapOf("707" to -10_000L, "613" to 25_000L))
        assertTrue(ReglesComptables.equilibree(lignes))
        assertEquals(15_000L, lignes.first { it.compte == "129" }.debit)
    }

    @Test
    fun `exercice decale`() {
        // Exercice commençant en juillet : le 15 mars 2026 appartient à 2025-07-01 → 2026-06-30
        val (debut, fin) = ReglesComptables.exercice(LocalDate.of(2026, 3, 15), 7)
        assertEquals(LocalDate.of(2025, 7, 1), debut)
        assertEquals(LocalDate.of(2026, 6, 30), fin)
    }

    @Test
    fun `versement a la cloture de la caisse vers le coffre`() {
        val lignes = ReglesComptables.versementCoffre(80_000)
        assertTrue(ReglesComptables.equilibree(lignes))
        assertEquals(80_000L, lignes.first { it.compte == "532" }.debit)
        assertEquals(80_000L, lignes.first { it.compte == "531" }.credit)
        assertTrue(ReglesComptables.versementCoffre(0).isEmpty())
    }

    @Test
    fun `fond de caisse pris dans le coffre`() {
        val lignes = ReglesComptables.fondCaisse(20_000)
        assertEquals(20_000L, lignes.first { it.compte == "531" }.debit)
        assertEquals(20_000L, lignes.first { it.compte == "532" }.credit)
    }

    @Test
    fun `depense payee depuis le coffre credite le compte coffre`() {
        val coffre = ReglesComptables.depense(15_000, "613", ModePaiement.ESPECES, SourceFonds.COFFRE)
        assertEquals(15_000L, coffre.first { it.compte == "532" }.credit)
        val caisse = ReglesComptables.depense(15_000, "613", ModePaiement.ESPECES, SourceFonds.CAISSE)
        assertEquals(15_000L, caisse.first { it.compte == "531" }.credit)
        // Le mobile money reste au compte 517 quelle que soit la source
        val mobile = ReglesComptables.depense(15_000, "613", ModePaiement.MOBILE_MONEY, SourceFonds.COFFRE)
        assertEquals(15_000L, mobile.first { it.compte == "517" }.credit)
    }

    @Test
    fun `paiement fournisseur depuis le coffre`() {
        val lignes = ReglesComptables.paiementFournisseur(40_000, ModePaiement.ESPECES, "f1", SourceFonds.COFFRE)
        assertTrue(ReglesComptables.equilibree(lignes))
        assertEquals(40_000L, lignes.first { it.compte == "532" }.credit)
    }

    @Test
    fun `injection de capital et emprunt`() {
        val capital = ReglesComptables.financement(500_000, ReglesComptables.CAPITAL, ModePaiement.ESPECES)
        assertTrue(ReglesComptables.equilibree(capital))
        assertEquals(500_000L, capital.first { it.compte == "531" }.debit)
        assertEquals(500_000L, capital.first { it.compte == "101" }.credit)
        val emprunt = ReglesComptables.financement(1_000_000, ReglesComptables.EMPRUNTS_ETABLISSEMENTS, ModePaiement.BANQUE)
        assertEquals(1_000_000L, emprunt.first { it.compte == "512" }.debit)
        assertEquals(1_000_000L, emprunt.first { it.compte == "164" }.credit)
    }

    @Test
    fun `remboursement d'emprunt capital et interets`() {
        val capital = ReglesComptables.remboursementEmprunt(100_000, ReglesComptables.EMPRUNTS_AUTRES, ModePaiement.MOBILE_MONEY)
        assertTrue(ReglesComptables.equilibree(capital))
        assertEquals(100_000L, capital.first { it.compte == "168" }.debit)
        assertEquals(100_000L, capital.first { it.compte == "517" }.credit)
        val interets = ReglesComptables.interetsEmprunt(5_000, ModePaiement.ESPECES)
        assertEquals(5_000L, interets.first { it.compte == "661" }.debit)
        assertEquals(5_000L, interets.first { it.compte == "531" }.credit)
        assertTrue(ReglesComptables.interetsEmprunt(0, ModePaiement.ESPECES).isEmpty())
    }

    @Test
    fun `capital et emprunt par le coffre`() {
        val capital = ReglesComptables.financement(300_000, ReglesComptables.CAPITAL, ModePaiement.ESPECES, SourceFonds.COFFRE)
        assertEquals(300_000L, capital.first { it.compte == "532" }.debit)
        val rembourse = ReglesComptables.remboursementEmprunt(50_000, ReglesComptables.EMPRUNTS_AUTRES, ModePaiement.ESPECES, SourceFonds.COFFRE)
        assertEquals(50_000L, rembourse.first { it.compte == "532" }.credit)
        val interets = ReglesComptables.interetsEmprunt(2_000, ModePaiement.ESPECES, SourceFonds.COFFRE)
        assertEquals(2_000L, interets.first { it.compte == "532" }.credit)
    }
}
