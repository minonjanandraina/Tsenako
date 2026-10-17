package mg.tsena.compta

import org.junit.Assert.assertEquals
import org.junit.Test

class EtatsFinanciersTest {
    private var n = 0

    /** Écriture à deux lignes : débit [debit] / crédit [credit]. */
    private fun ecriture(debit: String, credit: String, montant: Long): List<LigneComptable> {
        val id = "e${n++}"
        return listOf(LigneComptable(id, n.toLong(), null, debit, montant, 0), LigneComptable(id, n.toLong(), null, credit, 0, montant))
    }

    private val lignes = ecriture("37", "101", 100_000) + // stock de départ
        ecriture("53", "707", 30_000) + // vente au comptant
        ecriture("607", "401", 20_000) + // réception
        ecriture("613", "53", 5_000) + // loyer
        ecriture("6037", "37", 10_000) + // baisse du stock à l'inventaire
        ecriture("401", "53", 15_000) // paiement du fournisseur

    @Test
    fun `compte de resultat par nature`() {
        val cr = EtatsFinanciers.compteResultat(lignes)
        assertEquals(30_000L, cr.chiffreAffaires.montant)
        assertEquals(35_000L, cr.consommations.montant)
        assertEquals(-5_000L, cr.valeurAjoutee)
        assertEquals(-5_000L, cr.resultatNet)
    }

    @Test
    fun `bilan equilibre`() {
        val resultat = EtatsFinanciers.compteResultat(lignes).resultatNet
        val bilan = EtatsFinanciers.bilan(lignes, resultat)
        assertEquals(90_000L, bilan.stocks.montant)
        assertEquals(10_000L, bilan.tresorerieActif.montant)
        assertEquals(5_000L, bilan.dettesFournisseurs.montant)
        assertEquals(100_000L, bilan.capital.montant)
        assertEquals(0L, bilan.resultatsAnterieurs.montant)
        assertEquals(bilan.totalActif, bilan.totalPassif)
        assertEquals(100_000L, bilan.totalActif)
    }

    @Test
    fun `flux de tresorerie par nature`() {
        val flux = EtatsFinanciers.fluxTresorerie(lignes, tresorerieDebut = 0)
        assertEquals(30_000L, flux.encaissementsClients)
        assertEquals(-15_000L, flux.paiementsFournisseurs)
        assertEquals(-5_000L, flux.autresOperationnels)
        assertEquals(10_000L, flux.tresorerieFin)
    }

    @Test
    fun `balance equilibree`() {
        val balance = EtatsFinanciers.balance(lignes, emptyMap())
        assertEquals(balance.sumOf { it.debit }, balance.sumOf { it.credit })
        assertEquals(balance.sumOf { it.soldeDebiteur }, balance.sumOf { it.soldeCrediteur })
    }

    @Test
    fun `capital et emprunts au bilan et en flux de financement`() {
        val financement = ecriture("53", "101", 200_000) + // injection de capital
            ecriture("512", "164", 500_000) + // emprunt bancaire
            ecriture("164", "512", 100_000) + // remboursement du capital
            ecriture("661", "512", 10_000) // intérêts
        val tout = lignes + financement
        val resultat = EtatsFinanciers.compteResultat(tout).resultatNet
        assertEquals(-15_000L, resultat)
        assertEquals(10_000L, EtatsFinanciers.compteResultat(tout).chargesFinancieres.montant)
        val bilan = EtatsFinanciers.bilan(tout, resultat)
        assertEquals(300_000L, bilan.capital.montant)
        assertEquals(400_000L, bilan.emprunts.montant)
        assertEquals(bilan.totalActif, bilan.totalPassif)
        val flux = EtatsFinanciers.fluxTresorerie(tout, tresorerieDebut = 0)
        assertEquals(600_000L, flux.fluxFinancement)
        assertEquals(-15_000L, flux.autresOperationnels)
    }
}
