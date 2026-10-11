package mg.tsena.stock

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Comptable
import mg.tsena.compta.PieceComptable
import mg.tsena.compta.ReglesComptables
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.AlerteStock
import mg.tsena.data.db.entity.InventaireAvecDepot
import mg.tsena.data.db.entity.InventaireEntity
import mg.tsena.data.db.entity.LigneInventaireAvecProduit
import mg.tsena.data.db.entity.LigneInventaireEntity
import mg.tsena.data.db.entity.MouvementAvecAuteur
import mg.tsena.data.db.entity.MouvementStockEntity
import mg.tsena.data.db.entity.StatutInventaire
import mg.tsena.data.db.entity.StockEntity
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.data.db.entity.TypeMouvement
import mg.tsena.domain.Permission
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant

/** Pièce à l'origine d'un mouvement de stock. */
data class DocumentStock(val type: String, val id: String)

/** Stocks par dépôt, mouvements (CMUP) et inventaires. */
class StockRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val comptable: Comptable,
) {
    private val dao = db.stockDao()
    private val inventaireDao = db.inventaireDao()

    fun observerStocks(depotId: String): Flow<List<StockProduit>> = dao.observerParDepot(depotId)

    fun observerStock(produitId: String, depotId: String): Flow<StockProduit?> = dao.observerProduit(produitId, depotId)

    fun observerAlertes(): Flow<List<AlerteStock>> = dao.observerAlertes()

    fun observerMouvements(produitId: String, depotId: String): Flow<List<MouvementAvecAuteur>> =
        dao.observerMouvements(produitId, depotId, LIMITE_HISTORIQUE)

    fun observerInventaires(): Flow<List<InventaireAvecDepot>> = inventaireDao.observerTous()

    fun observerInventaire(id: String): Flow<InventaireAvecDepot?> = inventaireDao.observer(id)

    fun observerLignesInventaire(inventaireId: String): Flow<List<LigneInventaireAvecProduit>> =
        inventaireDao.observerLignes(inventaireId)

    /**
     * Seul point d'entrée pour faire varier un stock (RG-S1). À appeler dans une transaction.
     * [quantiteMilli] signée : > 0 entrée, < 0 sortie. [coutUnitaire] : coût d'une entrée ;
     * null = CMUP actuel (sortie, ou entrée sans coût connu comme un gain d'inventaire).
     * Utilisé aussi par les achats, ventes et transferts (sprints suivants).
     */
    suspend fun enregistrerMouvement(
        produitId: String,
        depotId: String,
        type: TypeMouvement,
        quantiteMilli: Long,
        coutUnitaire: Long? = null,
        document: DocumentStock? = null,
        motif: String? = null,
        auteurId: String? = session.utilisateur.value?.id,
    ) {
        if (quantiteMilli == 0L) throw RegleMetierException("La quantité doit être différente de zéro.")
        val existant = dao.get(produitId, depotId)
        val stock = existant ?: StockEntity(produitId = produitId, depotId = depotId)

        val cout: Long
        val nouveauCmup: Long
        if (quantiteMilli > 0) {
            cout = coutUnitaire ?: stock.coutMoyenUnitaire
            if (cout < 0) throw RegleMetierException("Le coût unitaire ne peut pas être négatif.")
            nouveauCmup = Valorisation.nouveauCmup(stock.quantiteMilli, stock.coutMoyenUnitaire, quantiteMilli, cout)
        } else {
            val negatifAutorise = db.commerceDao().get()?.stockNegatifAutorise ?: false
            if (!Valorisation.sortiePossible(stock.quantiteMilli, -quantiteMilli, negatifAutorise)) {
                val nom = db.produitDao().get(produitId)?.nom.orEmpty()
                throw RegleMetierException(
                    "Stock insuffisant pour « $nom » : ${Quantite.formater(stock.quantiteMilli)} disponible(s)."
                )
            }
            cout = stock.coutMoyenUnitaire
            nouveauCmup = stock.coutMoyenUnitaire
        }

        val date = maintenant()
        val apres = stock.copy(quantiteMilli = stock.quantiteMilli + quantiteMilli, coutMoyenUnitaire = nouveauCmup, updatedAt = date)
        if (existant == null) dao.inserer(apres) else dao.modifier(apres)
        dao.insererMouvement(
            MouvementStockEntity(
                produitId = produitId,
                depotId = depotId,
                type = type,
                quantiteMilli = quantiteMilli,
                coutUnitaire = cout,
                quantiteApresMilli = apres.quantiteMilli,
                documentType = document?.type,
                documentId = document?.id,
                motif = motif,
                date = date,
                createdBy = auteurId,
            )
        )
    }

    /** Stock de départ d'un produit dans un dépôt, avec son coût d'achat unitaire. */
    suspend fun saisirStockInitial(produitId: String, depotId: String, quantiteMilli: Long, coutUnitaire: Long) {
        val auteur = session.exiger(Permission.GERER_STOCK)
        if (quantiteMilli <= 0) throw RegleMetierException("La quantité doit être supérieure à zéro.")
        if (coutUnitaire < 0) throw RegleMetierException("Le coût unitaire ne peut pas être négatif.")
        db.withTransaction {
            val produit = verifierProduitEtDepot(produitId, depotId)
            enregistrerMouvement(produitId, depotId, TypeMouvement.STOCK_INITIAL, quantiteMilli, coutUnitaire, auteurId = auteur.id)
            // Stock apporté au commerce : 37 / 101
            comptable.passer(
                Journal.OD, "Stock initial : $produit",
                ReglesComptables.stockInitial(Valorisation.valeur(quantiteMilli, coutUnitaire)),
            )
            audit.enregistrer(ActionAudit.STOCK_INITIAL, "$produit : ${Quantite.formater(quantiteMilli)}")
        }
    }

    /** Sortie pour casse, péremption, vol… valorisée au CMUP. */
    suspend fun declarerPerte(produitId: String, depotId: String, quantiteMilli: Long, motif: String) {
        val auteur = session.exiger(Permission.GERER_STOCK)
        val motifPropre = motif.trim()
        if (quantiteMilli <= 0) throw RegleMetierException("La quantité doit être supérieure à zéro.")
        if (motifPropre.isEmpty()) throw RegleMetierException("Indiquez le motif de la perte.")
        db.withTransaction {
            val produit = verifierProduitEtDepot(produitId, depotId)
            enregistrerMouvement(produitId, depotId, TypeMouvement.PERTE, -quantiteMilli, motif = motifPropre, auteurId = auteur.id)
            audit.enregistrer(ActionAudit.PERTE_STOCK, "$produit : ${Quantite.formater(quantiteMilli)} ($motifPropre)")
        }
    }

    /** RG-S3 : paramètre du commerce. */
    suspend fun autoriserStockNegatif(autorise: Boolean) {
        session.exiger(Permission.PARAMETRER_COMMERCE)
        db.commerceDao().modifierStockNegatif(autorise, maintenant())
        audit.enregistrer(ActionAudit.PARAMETRE_STOCK_NEGATIF, if (autorise) "autorisé" else "interdit")
    }

    // --- Inventaires (RG-S5) ---

    /** Ouvre un inventaire sur tous les produits actifs du dépôt. Renvoie son id. */
    suspend fun creerInventaire(depotId: String): String {
        val auteur = session.exiger(Permission.GERER_STOCK)
        return db.withTransaction {
            val depot = db.lieuDao().getDepot(depotId) ?: throw RegleMetierException("Dépôt introuvable.")
            if (!depot.actif) throw RegleMetierException("Le dépôt « ${depot.nom} » est désactivé.")
            if (inventaireDao.compterBrouillons(depotId) > 0) {
                throw RegleMetierException("Un inventaire est déjà en cours pour « ${depot.nom} ».")
            }
            val inventaire = InventaireEntity(depotId = depotId, createdBy = auteur.id)
            inventaireDao.inserer(inventaire)
            val produits = db.produitDao().actifs()
            if (produits.isEmpty()) throw RegleMetierException("Aucun produit actif à inventorier.")
            inventaireDao.insererLignes(
                produits.map { p ->
                    LigneInventaireEntity(
                        inventaireId = inventaire.id,
                        produitId = p.id,
                        quantiteTheoriqueMilli = dao.get(p.id, depotId)?.quantiteMilli ?: 0,
                    )
                }
            )
            audit.enregistrer(ActionAudit.CREATION_INVENTAIRE, depot.nom)
            inventaire.id
        }
    }

    /** Quantité comptée pour une ligne ; null efface le comptage. */
    suspend fun saisirComptage(ligneId: String, quantiteMilli: Long?) {
        session.exiger(Permission.GERER_STOCK)
        if (quantiteMilli != null && quantiteMilli < 0) throw RegleMetierException("La quantité ne peut pas être négative.")
        db.withTransaction {
            val ligne = inventaireDao.getLigne(ligneId) ?: throw RegleMetierException("Ligne introuvable.")
            exigerBrouillon(ligne.inventaireId)
            inventaireDao.modifierLigne(ligne.copy(quantiteCompteeMilli = quantiteMilli))
        }
    }

    /**
     * Valide l'inventaire : pour chaque produit compté, l'écart avec le stock actuel du dépôt
     * devient un mouvement AJUSTEMENT. Les produits non comptés ne sont pas modifiés.
     * Renvoie le nombre d'ajustements. La variation de stock est passée en comptabilité (RG-K7).
     */
    suspend fun validerInventaire(inventaireId: String): Int {
        val auteur = session.exiger(Permission.GERER_STOCK)
        return db.withTransaction {
            val inventaire = exigerBrouillon(inventaireId)
            val lignes = inventaireDao.lignes(inventaireId).filter { it.quantiteCompteeMilli != null }
            if (lignes.isEmpty()) throw RegleMetierException("Aucun produit compté.")
            val document = DocumentStock(DOCUMENT_INVENTAIRE, inventaireId)
            var ajustements = 0
            lignes.forEach { ligne ->
                val comptee = ligne.quantiteCompteeMilli!!
                val theorique = dao.get(ligne.produitId, inventaire.depotId)?.quantiteMilli ?: 0
                val ecart = comptee - theorique
                inventaireDao.modifierLigne(ligne.copy(quantiteTheoriqueMilli = theorique, ecartMilli = ecart))
                if (ecart != 0L) {
                    // Écart négatif : la quantité finale (comptée) est >= 0, la règle RG-S3 est respectée
                    enregistrerMouvement(
                        ligne.produitId, inventaire.depotId, TypeMouvement.AJUSTEMENT, ecart,
                        document = document, motif = "Inventaire", auteurId = auteur.id,
                    )
                    ajustements++
                }
            }
            val date = maintenant()
            inventaireDao.modifier(
                inventaire.copy(statut = StatutInventaire.VALIDE, dateValidation = date, validePar = auteur.id, updatedAt = date)
            )
            val depot = db.lieuDao().getDepot(inventaire.depotId)?.nom.orEmpty()
            // RG-K7 : le compte 37 suit la valeur du stock après l'inventaire (6037 en contrepartie)
            comptable.ajusterStock("Variation de stock (inventaire $depot)", PieceComptable(DOCUMENT_INVENTAIRE, inventaireId))
            audit.enregistrer(ActionAudit.VALIDATION_INVENTAIRE, "$depot : $ajustements ajustement(s)")
            ajustements
        }
    }

    /** Abandon d'un inventaire non validé (suppression logique, aucun mouvement). */
    suspend fun abandonnerInventaire(inventaireId: String) {
        session.exiger(Permission.GERER_STOCK)
        db.withTransaction {
            val inventaire = exigerBrouillon(inventaireId)
            val date = maintenant()
            inventaireDao.modifier(inventaire.copy(deletedAt = date, updatedAt = date))
            val depot = db.lieuDao().getDepot(inventaire.depotId)?.nom.orEmpty()
            audit.enregistrer(ActionAudit.ABANDON_INVENTAIRE, depot)
        }
    }

    private suspend fun exigerBrouillon(inventaireId: String): InventaireEntity {
        val inventaire = inventaireDao.get(inventaireId) ?: throw RegleMetierException("Inventaire introuvable.")
        if (inventaire.statut != StatutInventaire.BROUILLON) throw RegleMetierException("Cet inventaire est déjà validé.")
        return inventaire
    }

    /** Renvoie le nom du produit pour l'audit. */
    private suspend fun verifierProduitEtDepot(produitId: String, depotId: String): String {
        val produit = db.produitDao().get(produitId) ?: throw RegleMetierException("Produit introuvable.")
        val depot = db.lieuDao().getDepot(depotId) ?: throw RegleMetierException("Dépôt introuvable.")
        if (!depot.actif) throw RegleMetierException("Le dépôt « ${depot.nom} » est désactivé.")
        return "${produit.code} ${produit.nom} (${depot.nom})"
    }

    companion object {
        const val DOCUMENT_INVENTAIRE = "INVENTAIRE"
        private const val LIMITE_HISTORIQUE = 200
    }
}
