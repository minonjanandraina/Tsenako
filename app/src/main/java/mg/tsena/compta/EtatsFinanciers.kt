package mg.tsena.compta

/** Ligne d'écriture à plat, pour le calcul des états. */
data class LigneComptable(
    val ecritureId: String,
    val date: Long,
    val documentType: String?,
    val compte: String,
    val debit: Long,
    val credit: Long,
)

data class LigneBalance(val compte: String, val libelle: String, val debit: Long, val credit: Long) {
    val soldeDebiteur: Long get() = maxOf(0, debit - credit)
    val soldeCrediteur: Long get() = maxOf(0, credit - debit)
}

/** Poste d'un état : libellé, montant, et le détail par compte. */
data class Poste(val libelle: String, val montant: Long, val comptes: List<Pair<String, Long>> = emptyList())

data class CompteResultat(
    val chiffreAffaires: Poste,
    val consommations: Poste,
    val valeurAjoutee: Long,
    val chargesPersonnel: Poste,
    val impotsTaxes: Poste,
    val excedentBrut: Long,
    val autresProduits: Poste,
    val autresCharges: Poste,
    val resultatOperationnel: Long,
    val produitsFinanciers: Poste,
    val chargesFinancieres: Poste,
    val resultatFinancier: Long,
    val resultatNet: Long,
)

data class Bilan(
    val immobilisations: Poste,
    val stocks: Poste,
    val creances: Poste,
    val tresorerieActif: Poste,
    val totalActif: Long,
    val capital: Poste,
    val resultatsAnterieurs: Poste,
    val resultatExercice: Long,
    val emprunts: Poste,
    val dettesFournisseurs: Poste,
    val autresDettes: Poste,
    val tresoreriePassif: Poste,
    val totalPassif: Long,
)

data class FluxTresorerie(
    val tresorerieDebut: Long,
    val encaissementsClients: Long,
    val paiementsFournisseurs: Long,
    val paiementsPersonnel: Long,
    val impotsPayes: Long,
    val autresOperationnels: Long,
    val fluxOperationnels: Long,
    val fluxInvestissement: Long,
    val fluxFinancement: Long,
    val variation: Long,
    val tresorerieFin: Long,
)

/**
 * États financiers PCG 2005 (RG-K6), calculés depuis les écritures, sans Android (testés sur JVM).
 * Convention : solde = débit − crédit.
 */
object EtatsFinanciers {
    /** Balance : totaux débit et crédit par compte. */
    fun balance(lignes: List<LigneComptable>, libelles: Map<String, String>): List<LigneBalance> =
        lignes.groupBy { it.compte }
            .map { (compte, ls) -> LigneBalance(compte, libelles[compte].orEmpty(), ls.sumOf { it.debit }, ls.sumOf { it.credit }) }
            .sortedBy { it.compte }

    /**
     * Compte de résultat par nature, à partir des mouvements de l'exercice (sans l'écriture de clôture).
     * Charges et produits sont présentés en positif.
     */
    fun compteResultat(lignesExercice: List<LigneComptable>): CompteResultat {
        val soldes = soldes(lignesExercice)
        fun charges(libelle: String, vararg prefixes: String) = poste(libelle, soldes, prefixes.toList(), signe = 1)
        fun produits(libelle: String, vararg prefixes: String) = poste(libelle, soldes, prefixes.toList(), signe = -1)

        val ca = produits("Chiffre d'affaires", "70", "71", "72")
        val consommations = charges("Consommations de l'exercice", "60", "61", "62")
        val valeurAjoutee = ca.montant - consommations.montant
        val personnel = charges("Charges de personnel", "64")
        val impots = charges("Impôts, taxes et versements assimilés", "63")
        val ebe = valeurAjoutee - personnel.montant - impots.montant
        val autresProduits = produits("Autres produits opérationnels", "75", "78")
        val autresCharges = charges("Autres charges opérationnelles", "65", "68")
        val resultatOperationnel = ebe + autresProduits.montant - autresCharges.montant
        val produitsFinanciers = produits("Produits financiers", "76")
        val chargesFinancieres = charges("Charges financières", "66")
        val resultatFinancier = produitsFinanciers.montant - chargesFinancieres.montant
        // Comptes de classe 6 / 7 non classés ci-dessus (67, 69, 77, 79…) : pris dans le résultat net
        val classes = listOf("60", "61", "62", "63", "64", "65", "66", "68", "70", "71", "72", "75", "76", "78")
        val autres = soldes.filter { (c, _) -> (c.startsWith("6") || c.startsWith("7")) && classes.none { c.startsWith(it) } }
            .values.sum()
        return CompteResultat(
            chiffreAffaires = ca,
            consommations = consommations,
            valeurAjoutee = valeurAjoutee,
            chargesPersonnel = personnel,
            impotsTaxes = impots,
            excedentBrut = ebe,
            autresProduits = autresProduits,
            autresCharges = autresCharges,
            resultatOperationnel = resultatOperationnel,
            produitsFinanciers = produitsFinanciers,
            chargesFinancieres = chargesFinancieres,
            resultatFinancier = resultatFinancier,
            resultatNet = resultatOperationnel + resultatFinancier - autres,
        )
    }

    /**
     * Bilan à la fin de l'exercice. [lignesCumul] : toutes les écritures jusqu'à la fin de l'exercice, sans son
     * écriture de clôture. Le résultat de l'exercice vient de [resultatExercice] ; les soldes de classes 6 / 7 des
     * exercices antérieurs non clôturés vont en résultats antérieurs.
     */
    fun bilan(lignesCumul: List<LigneComptable>, resultatExercice: Long): Bilan {
        val soldes = soldes(lignesCumul)
        /** Comptes dont le numéro commence par un des [prefixes] et dont le solde passe [filtre] ; [signe] −1 au passif. */
        fun poste(libelle: String, prefixes: List<String>, signe: Int, filtre: (String, Long) -> Boolean = { _, _ -> true }): Poste {
            val comptes = soldes.filter { (c, s) -> s != 0L && prefixes.any { c.startsWith(it) } && filtre(c, s) }
                .mapValues { it.value * signe }.toSortedMap().toList()
            return Poste(libelle, comptes.sumOf { it.second }, comptes)
        }

        val immobilisations = poste("Immobilisations", listOf("2"), 1)
        val stocks = poste("Stocks", listOf("3"), 1)
        val creances = poste("Clients et autres débiteurs", listOf("4"), 1) { _, s -> s > 0 }
        val tresorerieActif = poste("Trésorerie", listOf("5"), 1) { _, s -> s > 0 }
        val capital = poste("Capital et compte de l'exploitant", listOf("1"), -1) { c, _ -> !c.startsWith("12") && !c.startsWith("16") }
        val emprunts = poste("Emprunts et dettes financières", listOf("16"), -1)
        // Résultats affectés (12) + charges et produits cumulés, moins ceux de l'exercice
        val anterieurs12 = -soldes.filterKeys { it.startsWith("12") }.values.sum()
        val gestionCumulee = -soldes.filterKeys { it.startsWith("6") || it.startsWith("7") }.values.sum()
        val resultatsAnterieurs = Poste("Résultats antérieurs", anterieurs12 + gestionCumulee - resultatExercice)
        val fournisseurs = poste("Fournisseurs", listOf("40"), -1) { _, s -> s < 0 }
        val autresDettes = poste("Autres dettes", listOf("4"), -1) { c, s -> !c.startsWith("40") && s < 0 }
        val tresoreriePassif = poste("Trésorerie passif (découverts)", listOf("5"), -1) { _, s -> s < 0 }

        val totalActif = immobilisations.montant + stocks.montant + creances.montant + tresorerieActif.montant
        val totalPassif = capital.montant + resultatsAnterieurs.montant + resultatExercice + emprunts.montant +
            fournisseurs.montant + autresDettes.montant + tresoreriePassif.montant
        return Bilan(
            immobilisations = immobilisations,
            stocks = stocks,
            creances = creances,
            tresorerieActif = tresorerieActif,
            totalActif = totalActif,
            capital = capital,
            resultatsAnterieurs = resultatsAnterieurs,
            resultatExercice = resultatExercice,
            emprunts = emprunts,
            dettesFournisseurs = fournisseurs,
            autresDettes = autresDettes,
            tresoreriePassif = tresoreriePassif,
            totalPassif = totalPassif,
        )
    }

    /**
     * Tableau des flux de trésorerie (méthode directe) : chaque écriture qui touche la trésorerie (classe 5) est
     * classée selon sa contrepartie. Les virements entre comptes de trésorerie ne sont pas des flux.
     */
    fun fluxTresorerie(lignesExercice: List<LigneComptable>, tresorerieDebut: Long): FluxTresorerie {
        var clients = 0L
        var fournisseurs = 0L
        var personnel = 0L
        var impots = 0L
        var autres = 0L
        var investissement = 0L
        var financement = 0L
        lignesExercice.groupBy { it.ecritureId }.values.forEach { ecriture ->
            val flux = ecriture.filter { it.compte.startsWith("5") }.sumOf { it.debit - it.credit }
            if (flux == 0L) return@forEach
            val contreparties = ecriture.filterNot { it.compte.startsWith("5") }
            // Contrepartie principale : le compte non financier au plus gros montant
            val contrepartie = contreparties.maxByOrNull { it.debit + it.credit }?.compte ?: return@forEach
            when {
                contrepartie.startsWith("70") || contrepartie.startsWith("41") -> clients += flux
                contrepartie.startsWith("60") || contrepartie.startsWith("40") -> fournisseurs += flux
                contrepartie.startsWith("64") || contrepartie.startsWith("42") -> personnel += flux
                contrepartie.startsWith("63") || contrepartie.startsWith("44") -> impots += flux
                contrepartie.startsWith("2") -> investissement += flux
                contrepartie.startsWith("1") -> financement += flux
                else -> autres += flux
            }
        }
        val operationnels = clients + fournisseurs + personnel + impots + autres
        val variation = operationnels + investissement + financement
        return FluxTresorerie(
            tresorerieDebut = tresorerieDebut,
            encaissementsClients = clients,
            paiementsFournisseurs = fournisseurs,
            paiementsPersonnel = personnel,
            impotsPayes = impots,
            autresOperationnels = autres,
            fluxOperationnels = operationnels,
            fluxInvestissement = investissement,
            fluxFinancement = financement,
            variation = variation,
            tresorerieFin = tresorerieDebut + variation,
        )
    }

    fun soldes(lignes: List<LigneComptable>): Map<String, Long> =
        lignes.groupBy { it.compte }.mapValues { (_, ls) -> ls.sumOf { it.debit - it.credit } }

    /** [signe] 1 pour une charge (solde débiteur positif), −1 pour un produit. */
    private fun poste(libelle: String, soldes: Map<String, Long>, prefixes: List<String>, signe: Int): Poste {
        val comptes = soldes.filter { (c, s) -> s != 0L && prefixes.any { c.startsWith(it) } }
            .mapValues { it.value * signe }.toSortedMap().toList()
        return Poste(libelle, comptes.sumOf { it.second }, comptes)
    }
}
