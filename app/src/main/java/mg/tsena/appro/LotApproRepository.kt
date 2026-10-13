package mg.tsena.appro

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.AchatLotAppro
import mg.tsena.data.db.entity.EtiquetageApproEntity
import mg.tsena.data.db.entity.LigneLotAppro
import mg.tsena.data.db.entity.LotApproAvecTotaux
import mg.tsena.data.db.entity.LotApproEntity
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.data.db.entity.StatutLotAppro
import mg.tsena.data.db.entity.TypeMouvement
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId
import mg.tsena.finance.FinanceRepository
import mg.tsena.stock.DocumentStock
import mg.tsena.stock.StockRepository
import java.time.Instant
import java.time.ZoneId

/**
 * Appro (lot d'achats) : plusieurs achats chez plusieurs fournisseurs, payés directement en espèces depuis le coffre
 * dans la limite d'un budget, puis étiquetés (codes produit posés à la main) et comptés ; les écarts entre achat et
 * comptage sont justifiés avant l'envoi au dépôt de vente par un transfert.
 */
class LotApproRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val appro: ApproRepository,
    private val finance: FinanceRepository,
    private val stock: StockRepository,
) {
    private val dao = db.lotApproDao()

    fun observerLots(): Flow<List<LotApproAvecTotaux>> = dao.observerTous()

    fun observerLot(id: String): Flow<LotApproAvecTotaux?> = dao.observer(id)

    fun observerAchats(lotId: String): Flow<List<AchatLotAppro>> = dao.observerAchats(lotId)

    fun observerLignes(lotId: String): Flow<List<LigneLotAppro>> = dao.observerLignes(lotId)

    /** Crée un appro « Appro AAAA-MM-JJ » avec son budget en espèces. Renvoie son id. */
    suspend fun creer(budget: Long, depotSourceId: String, depotDestinationId: String, note: String): String {
        val auteur = session.exiger(Permission.GERER_APPRO)
        if (budget <= 0) throw RegleMetierException("Le budget de l'appro doit être supérieur à zéro.")
        if (depotSourceId == depotDestinationId) throw RegleMetierException("Le dépôt de destination doit être différent du dépôt d'achat.")
        return db.withTransaction {
            val source = db.lieuDao().getDepot(depotSourceId)?.takeIf { it.actif } ?: throw RegleMetierException("Dépôt d'achat introuvable ou désactivé.")
            val destination = db.lieuDao().getDepot(depotDestinationId)?.takeIf { it.actif }
                ?: throw RegleMetierException("Dépôt de destination introuvable ou désactivé.")
            val date = maintenant()
            val jour = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate()
            val lot = LotApproEntity(
                numero = Approvisionnement.numero(PREFIXE_LOT, dao.compter() + 1),
                nom = "Appro $jour",
                date = date,
                budget = budget,
                depotSourceId = source.id,
                depotDestinationId = destination.id,
                note = note.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            dao.inserer(lot)
            audit.enregistrer(ActionAudit.CREATION_LOT_APPRO, "${lot.numero} ${lot.nom} : budget ${Montant.formater(budget)}, ${source.nom} → ${destination.nom}")
            lot.id
        }
    }

    /** Change le budget (au moins ce qui est déjà payé). Réservé à qui gère le coffre. */
    suspend fun modifierBudget(lotId: String, budget: Long) {
        session.exiger(Permission.GERER_COFFRE)
        db.withTransaction {
            val lot = exigerEnCours(lotId)
            val totaux = dao.avecTotaux(lotId) ?: throw RegleMetierException("Appro introuvable.")
            if (budget < totaux.totalPaye) {
                throw RegleMetierException("Le budget ne peut pas être inférieur à ce qui est déjà payé (${Montant.formater(totaux.totalPaye)}).")
            }
            dao.modifier(lot.copy(budget = budget, updatedAt = maintenant()))
            audit.enregistrer(ActionAudit.BUDGET_LOT_APPRO, "${lot.numero} : ${Montant.formater(lot.budget)} → ${Montant.formater(budget)}")
        }
    }

    /**
     * Ajoute un achat à l'appro : une réception au dépôt d'achat chez [fournisseurId]. Avec [payerDirectement], le
     * fournisseur est payé aussitôt en espèces depuis le coffre ; le total payé de l'appro ne dépasse jamais son budget.
     * Renvoie l'id de la réception.
     */
    suspend fun ajouterAchat(
        lotId: String,
        fournisseurId: String,
        reference: String,
        lignes: List<LigneAchat>,
        payerDirectement: Boolean,
    ): String {
        session.exiger(Permission.GERER_APPRO)
        return db.withTransaction {
            val lot = exigerEnCours(lotId)
            val montant = lignes.sumOf { it.montant }
            if (payerDirectement) {
                val totaux = dao.avecTotaux(lotId) ?: throw RegleMetierException("Appro introuvable.")
                if (totaux.totalPaye + montant > lot.budget) {
                    throw RegleMetierException(
                        "Budget dépassé : il reste ${Montant.formater(maxOf(0, lot.budget - totaux.totalPaye))} sur ${Montant.formater(lot.budget)}."
                    )
                }
            }
            val receptionId = appro.validerReception(null, fournisseurId, lot.depotSourceId, reference, lignes, lotId)
            if (payerDirectement && montant > 0) {
                finance.payerFournisseur(
                    fournisseurId, receptionId, montant, ModePaiement.ESPECES, null, "${lot.numero} (${lot.nom})", SourceFonds.COFFRE,
                )
            }
            audit.enregistrer(
                ActionAudit.ACHAT_LOT_APPRO,
                "${lot.numero} : ${Montant.formater(montant)}" + if (payerDirectement) ", payé du coffre" else ", à payer",
            )
            receptionId
        }
    }

    /**
     * Étiquetage d'un produit : [quantiteMilli] = nombre d'articles réellement étiquetés et comptés. Un écart avec la
     * quantité achetée (manque ou surplus) demande un [motif].
     */
    suspend fun etiqueter(lotId: String, produitId: String, quantiteMilli: Long, motif: String) {
        val auteur = session.exiger(Permission.GERER_APPRO)
        db.withTransaction {
            val lot = exigerEnCours(lotId)
            val ligne = dao.lignes(lotId).firstOrNull { it.produitId == produitId } ?: throw RegleMetierException("Ce produit n'est pas dans l'appro.")
            if (quantiteMilli < 0) throw RegleMetierException("La quantité ne peut pas être négative.")
            if (!ligne.uniteDecimale && quantiteMilli % 1000 != 0L) throw RegleMetierException("La quantité de « ${ligne.nom} » doit être un nombre entier.")
            val ecart = quantiteMilli - ligne.quantiteAcheteeMilli
            val motifPropre = motif.trim().ifEmpty { null }
            if (ecart != 0L && motifPropre == null) {
                throw RegleMetierException("Indiquez le motif de l'écart pour « ${ligne.nom} » (${signe(ecart)}).")
            }
            dao.enregistrerEtiquetage(
                EtiquetageApproEntity(
                    id = dao.etiquetage(lotId, produitId)?.id ?: nouvelId(),
                    lotId = lotId,
                    produitId = produitId,
                    quantiteCompteeMilli = quantiteMilli,
                    motifEcart = if (ecart != 0L) motifPropre else null,
                    createdBy = auteur.id,
                )
            )
            audit.enregistrer(
                ActionAudit.ETIQUETAGE_LOT_APPRO,
                "${lot.numero} : ${ligne.nom} ${Quantite.formater(quantiteMilli)} / ${Quantite.formater(ligne.quantiteAcheteeMilli)}",
            )
        }
    }

    /**
     * Envoi à Antsirabe : tout doit être étiqueté. Les écarts sont régularisés au dépôt d'achat (manque = perte avec
     * son motif, surplus = ajustement), puis un transfert envoie les quantités comptées. Il est ensuite réceptionné
     * au dépôt de destination comme tout transfert. Renvoie l'id du transfert.
     */
    suspend fun envoyer(lotId: String): String {
        val auteur = session.exiger(Permission.GERER_APPRO)
        return db.withTransaction {
            val lot = exigerEnCours(lotId)
            val lignes = dao.lignes(lotId)
            if (lignes.isEmpty()) throw RegleMetierException("Aucun achat dans cet appro.")
            val restants = lignes.filterNot { it.etiquete }
            if (restants.isNotEmpty()) {
                val noms = restants.take(3).joinToString { it.nom } + if (restants.size > 3) "…" else ""
                throw RegleMetierException("Étiquetage restant : ${restants.size} produit(s) ($noms).")
            }
            val document = DocumentStock(DOCUMENT_LOT_APPRO, lot.id)
            lignes.forEach { l ->
                val ecart = l.ecartMilli ?: 0L
                val motif = "Écart ${lot.numero} : ${l.motifEcart.orEmpty()}"
                when {
                    ecart < 0 -> stock.enregistrerMouvement(
                        l.produitId, lot.depotSourceId, TypeMouvement.PERTE, ecart, document = document, motif = motif, auteurId = auteur.id,
                    )
                    ecart > 0 -> stock.enregistrerMouvement(
                        l.produitId, lot.depotSourceId, TypeMouvement.AJUSTEMENT, ecart,
                        coutUnitaire = Approvisionnement.prixUnitaire(l.montantAchete, l.quantiteAcheteeMilli),
                        document = document, motif = motif, auteurId = auteur.id,
                    )
                }
            }
            val aEnvoyer = lignes.filter { (it.quantiteCompteeMilli ?: 0L) > 0 }.map { LigneEnvoi(it.produitId, it.quantiteCompteeMilli ?: 0L) }
            if (aEnvoyer.isEmpty()) throw RegleMetierException("Rien à envoyer : toutes les quantités comptées sont à zéro.")
            val transfertId = appro.envoyerTransfert(lot.depotSourceId, lot.depotDestinationId, "${lot.numero} (${lot.nom})", aEnvoyer)
            val date = maintenant()
            dao.modifier(lot.copy(statut = StatutLotAppro.ENVOYE, transfertId = transfertId, dateEnvoi = date, updatedAt = date))
            audit.enregistrer(ActionAudit.ENVOI_LOT_APPRO, "${lot.numero} : ${aEnvoyer.size} produit(s) envoyés")
            transfertId
        }
    }

    /** Un appro sans aucun achat peut être annulé. */
    suspend fun annuler(lotId: String) {
        session.exiger(Permission.GERER_APPRO)
        db.withTransaction {
            val lot = exigerEnCours(lotId)
            val totaux = dao.avecTotaux(lotId)
            if ((totaux?.nbAchats ?: 0) > 0) throw RegleMetierException("Cet appro a déjà des achats : il ne peut plus être annulé.")
            dao.modifier(lot.copy(statut = StatutLotAppro.ANNULE, updatedAt = maintenant()))
            audit.enregistrer(ActionAudit.ANNULATION_LOT_APPRO, lot.numero)
        }
    }

    private suspend fun exigerEnCours(lotId: String): LotApproEntity {
        val lot = dao.get(lotId) ?: throw RegleMetierException("Appro introuvable.")
        if (lot.statut != StatutLotAppro.EN_COURS) throw RegleMetierException("L'appro ${lot.numero} est ${lot.statut.libelle.lowercase()}.")
        return lot
    }

    private fun signe(ecartMilli: Long) = (if (ecartMilli > 0) "+" else "−") + Quantite.formater(kotlin.math.abs(ecartMilli))

    companion object {
        const val DOCUMENT_LOT_APPRO = "LOT_APPRO"
        private const val PREFIXE_LOT = "AP"
    }
}
