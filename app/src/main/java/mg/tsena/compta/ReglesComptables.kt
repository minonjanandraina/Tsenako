package mg.tsena.compta

import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.SourceFonds
import java.time.LocalDate

/** Ligne d'écriture à passer : un compte, un débit ou un crédit, et le tiers éventuel. */
data class Imputation(
    val compte: String,
    val debit: Long = 0,
    val credit: Long = 0,
    val tiersType: String? = null,
    val tiersId: String? = null,
)

/**
 * Schémas d'écritures PCG 2005 (Claude.md §5.9), sans Android (testés sur JVM).
 * Les lignes à zéro sont retirées ; une liste vide signifie « aucune écriture ».
 */
object ReglesComptables {
    const val CAPITAL = "101"
    const val EXPLOITANT = "108"
    const val RESULTAT_BENEFICE = "120"
    const val RESULTAT_PERTE = "129"
    const val EMPRUNTS_ETABLISSEMENTS = "164"
    const val EMPRUNTS_AUTRES = "168"
    const val STOCK = "37"
    const val FOURNISSEURS = "401"
    const val CLIENTS = "411"
    const val BANQUE = "512"
    const val MOBILE_MONEY = "517"
    /** Espèces des caisses des points de vente (sous-compte de 53) */
    const val CAISSE = "531"
    /** Espèces du coffre, où va l'argent à la clôture de caisse (sous-compte de 53) */
    const val COFFRE = "532"
    const val VARIATION_STOCK = "6037"
    const val ACHATS = "607"
    const val CHARGES_DIVERSES = "658"
    const val INTERETS = "661"
    const val VENTES = "707"
    const val PRODUITS_DIVERS = "758"

    const val TIERS_CLIENT = "CLIENT"
    const val TIERS_FOURNISSEUR = "FOURNISSEUR"

    /** Compte de trésorerie (ou client pour le crédit) d'un mode de paiement ; [source] ne joue que pour les espèces. */
    fun compteReglement(mode: ModePaiement, source: SourceFonds = SourceFonds.CAISSE): String = when (mode) {
        ModePaiement.ESPECES -> if (source == SourceFonds.COFFRE) COFFRE else CAISSE
        ModePaiement.MOBILE_MONEY -> MOBILE_MONEY
        ModePaiement.CARTE, ModePaiement.BANQUE -> BANQUE
        ModePaiement.CREDIT -> CLIENTS
    }

    /** Journal d'un règlement : caisse pour espèces et mobile money, banque sinon. */
    fun journalReglement(mode: ModePaiement): Journal = when (mode) {
        ModePaiement.ESPECES, ModePaiement.MOBILE_MONEY -> Journal.CA
        ModePaiement.CARTE, ModePaiement.BANQUE -> Journal.BQ
        ModePaiement.CREDIT -> Journal.OD
    }

    /** RG-K1 : au moins une ligne, montants positifs, une ligne = débit ou crédit, Σ débit = Σ crédit. */
    fun equilibree(lignes: List<Imputation>): Boolean =
        lignes.isNotEmpty() &&
            lignes.all { it.debit >= 0 && it.credit >= 0 && (it.debit == 0L || it.credit == 0L) && it.debit + it.credit > 0 } &&
            lignes.sumOf { it.debit } == lignes.sumOf { it.credit }

    /** Vente : débit des règlements (53, 517, 512, 411 pour le crédit) ; crédit 707 du total net. */
    fun vente(totalNet: Long, paiements: List<Pair<ModePaiement, Long>>, clientId: String?): List<Imputation> =
        nettoyer(
            paiements.map { (mode, montant) -> reglement(mode, debit = montant, clientId = clientId) } +
                Imputation(VENTES, credit = totalNet)
        )

    /** Retour client : débit 707 ; crédit de la caisse, du mobile money ou du compte client. */
    fun retourVente(montant: Long, mode: ModePaiement, clientId: String?): List<Imputation> =
        nettoyer(listOf(Imputation(VENTES, debit = montant), reglement(mode, credit = montant, clientId = clientId)))

    /** Remboursement d'un crédit client : débit trésorerie ; crédit 411. */
    fun reglementClient(montant: Long, mode: ModePaiement, clientId: String): List<Imputation> =
        nettoyer(listOf(reglement(mode, debit = montant), Imputation(CLIENTS, credit = montant, tiersType = TIERS_CLIENT, tiersId = clientId)))

    /** Réception (RG-K7) : débit 607 ; crédit 401. */
    fun achat(montant: Long, fournisseurId: String): List<Imputation> =
        nettoyer(listOf(Imputation(ACHATS, debit = montant), fournisseur(credit = montant, fournisseurId = fournisseurId)))

    /** Retour au fournisseur : débit 401 ; crédit 607. */
    fun retourAchat(montant: Long, fournisseurId: String): List<Imputation> =
        nettoyer(listOf(fournisseur(debit = montant, fournisseurId = fournisseurId), Imputation(ACHATS, credit = montant)))

    /** Paiement fournisseur : débit 401 ; crédit trésorerie. */
    fun paiementFournisseur(
        montant: Long,
        mode: ModePaiement,
        fournisseurId: String,
        source: SourceFonds = SourceFonds.CAISSE,
    ): List<Imputation> =
        nettoyer(listOf(fournisseur(debit = montant, fournisseurId = fournisseurId), reglement(mode, credit = montant, source = source)))

    /** Dépense : débit du compte de charge de la catégorie ; crédit trésorerie. */
    fun depense(montant: Long, compteCharge: String, mode: ModePaiement, source: SourceFonds = SourceFonds.CAISSE): List<Imputation> =
        nettoyer(listOf(Imputation(compteCharge, debit = montant), reglement(mode, credit = montant, source = source)))

    /** Écart de caisse (compté − théorique) : négatif 658 / 53 ; positif 53 / 758. */
    fun ecartCaisse(ecart: Long): List<Imputation> = when {
        ecart < 0 -> listOf(Imputation(CHARGES_DIVERSES, debit = -ecart), Imputation(CAISSE, credit = -ecart))
        ecart > 0 -> listOf(Imputation(CAISSE, debit = ecart), Imputation(PRODUITS_DIVERS, credit = ecart))
        else -> emptyList()
    }

    /** Apport du propriétaire : 53 / 108. */
    fun apport(montant: Long): List<Imputation> =
        nettoyer(listOf(Imputation(CAISSE, debit = montant), Imputation(EXPLOITANT, credit = montant)))

    /** Retrait du propriétaire : 108 / 53. */
    fun retrait(montant: Long): List<Imputation> =
        nettoyer(listOf(Imputation(EXPLOITANT, debit = montant), Imputation(CAISSE, credit = montant)))

    /** Clôture de caisse : les espèces comptées passent de la caisse au coffre (532 / 531). */
    fun versementCoffre(montant: Long): List<Imputation> =
        nettoyer(listOf(Imputation(COFFRE, debit = montant), Imputation(CAISSE, credit = montant)))

    /** Ouverture de caisse : le fond de caisse sort du coffre (531 / 532). */
    fun fondCaisse(montant: Long): List<Imputation> =
        nettoyer(listOf(Imputation(CAISSE, debit = montant), Imputation(COFFRE, credit = montant)))

    /** Apport du propriétaire directement au coffre : 532 (ou 517 en mobile money) / 108. */
    fun apportCoffre(montant: Long, mode: ModePaiement = ModePaiement.ESPECES): List<Imputation> =
        nettoyer(listOf(reglement(mode, debit = montant, source = SourceFonds.COFFRE), Imputation(EXPLOITANT, credit = montant)))

    /** Retrait du propriétaire depuis le coffre : 108 / 532 (ou 517 en mobile money). */
    fun retraitCoffre(montant: Long, mode: ModePaiement = ModePaiement.ESPECES): List<Imputation> =
        nettoyer(listOf(Imputation(EXPLOITANT, debit = montant), reglement(mode, credit = montant, source = SourceFonds.COFFRE)))

    /**
     * Injection de capital ou emprunt reçu : débit trésorerie ; crédit [compteFinancement]
     * (101 capital, 164 emprunt auprès d'un établissement de crédit, 168 autre emprunt).
     */
    fun financement(
        montant: Long,
        compteFinancement: String,
        mode: ModePaiement,
        source: SourceFonds = SourceFonds.CAISSE,
    ): List<Imputation> =
        nettoyer(listOf(reglement(mode, debit = montant, source = source), Imputation(compteFinancement, credit = montant)))

    /** Remboursement du capital d'un emprunt : débit 164 / 168 ; crédit trésorerie (flux de financement). */
    fun remboursementEmprunt(
        capital: Long,
        compteEmprunt: String,
        mode: ModePaiement,
        source: SourceFonds = SourceFonds.CAISSE,
    ): List<Imputation> =
        nettoyer(listOf(Imputation(compteEmprunt, debit = capital), reglement(mode, credit = capital, source = source)))

    /** Intérêts d'un emprunt : débit 661 ; crédit trésorerie (écriture séparée : flux opérationnel). */
    fun interetsEmprunt(interets: Long, mode: ModePaiement, source: SourceFonds = SourceFonds.CAISSE): List<Imputation> =
        nettoyer(listOf(Imputation(INTERETS, debit = interets), reglement(mode, credit = interets, source = source)))

    /** Stock de départ apporté au commerce : 37 / 101. */
    fun stockInitial(valeur: Long): List<Imputation> =
        nettoyer(listOf(Imputation(STOCK, debit = valeur), Imputation(CAPITAL, credit = valeur)))

    /**
     * RG-K7 : ajuste le compte 37 à la valeur du stock calculée par l'app (CMUP).
     * Hausse : 37 / 6037 ; baisse : 6037 / 37.
     */
    fun variationStock(valeurStock: Long, solde37: Long): List<Imputation> {
        val ecart = valeurStock - solde37
        return when {
            ecart > 0 -> listOf(Imputation(STOCK, debit = ecart), Imputation(VARIATION_STOCK, credit = ecart))
            ecart < 0 -> listOf(Imputation(VARIATION_STOCK, debit = -ecart), Imputation(STOCK, credit = -ecart))
            else -> emptyList()
        }
    }

    /** Contre-passation (RG-K4) : débits et crédits inversés. */
    fun inverse(lignes: List<Imputation>): List<Imputation> = lignes.map { it.copy(debit = it.credit, credit = it.debit) }

    /**
     * Clôture : solde chaque compte de charges et de produits ([soldes] = débit − crédit) et porte le résultat
     * en 120 (bénéfice) ou 129 (perte).
     */
    fun clotureResultat(soldes: Map<String, Long>): List<Imputation> {
        val soldage = soldes.filterValues { it != 0L }.map { (compte, solde) ->
            if (solde > 0) Imputation(compte, credit = solde) else Imputation(compte, debit = -solde)
        }
        val resultat = -soldes.values.sum() // produits (soldes créditeurs) − charges (soldes débiteurs)
        val ligneResultat = when {
            resultat > 0 -> Imputation(RESULTAT_BENEFICE, credit = resultat)
            resultat < 0 -> Imputation(RESULTAT_PERTE, debit = -resultat)
            else -> null
        }
        return soldage + listOfNotNull(ligneResultat)
    }

    /** Exercice contenant [date], qui commence le 1er du mois [moisDebut] (1 = janvier). */
    fun exercice(date: LocalDate, moisDebut: Int): Pair<LocalDate, LocalDate> {
        val annee = if (date.monthValue >= moisDebut) date.year else date.year - 1
        val debut = LocalDate.of(annee, moisDebut, 1)
        return debut to debut.plusYears(1).minusDays(1)
    }

    private fun reglement(
        mode: ModePaiement,
        debit: Long = 0,
        credit: Long = 0,
        clientId: String? = null,
        source: SourceFonds = SourceFonds.CAISSE,
    ): Imputation {
        val compte = compteReglement(mode, source)
        return if (compte == CLIENTS) {
            Imputation(compte, debit, credit, TIERS_CLIENT, clientId)
        } else {
            Imputation(compte, debit, credit)
        }
    }

    private fun fournisseur(debit: Long = 0, credit: Long = 0, fournisseurId: String) =
        Imputation(FOURNISSEURS, debit, credit, TIERS_FOURNISSEUR, fournisseurId)

    private fun nettoyer(lignes: List<Imputation>): List<Imputation> = lignes.filter { it.debit != 0L || it.credit != 0L }
}
