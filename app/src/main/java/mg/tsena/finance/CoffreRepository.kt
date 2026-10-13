package mg.tsena.finance

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Comptable
import mg.tsena.compta.PieceComptable
import mg.tsena.compta.ReglesComptables
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationCoffreEntity
import mg.tsena.data.db.entity.SoldeCoffre
import mg.tsena.data.db.entity.TypeOperationCoffre
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException

/** Modes d'argent gardés au coffre. */
val MODES_COFFRE = listOf(ModePaiement.ESPECES, ModePaiement.MOBILE_MONEY)

/**
 * Coffre : là où va l'argent après la clôture de caisse. Son solde, par mode, est la somme de ses opérations ;
 * il ne peut jamais devenir négatif. Les méthodes [entrer] et [sortir] s'appellent dans la transaction du document
 * qui les provoque (clôture de caisse, dépense, paiement fournisseur).
 */
class CoffreRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val comptable: Comptable,
) {
    private val dao = db.coffreDao()

    fun observerSoldes(): Flow<List<SoldeCoffre>> = dao.observerSoldes()

    fun observerOperations(): Flow<List<OperationCoffreEntity>> = dao.observerOperations(LIMITE_HISTORIQUE)

    suspend fun solde(mode: ModePaiement): Long = dao.solde(mode)

    /** Enregistre une entrée au coffre. */
    suspend fun entrer(
        mode: ModePaiement,
        montant: Long,
        type: TypeOperationCoffre,
        libelle: String,
        auteurId: String?,
        sessionCaisseId: String? = null,
        documentType: String? = null,
        documentId: String? = null,
    ): OperationCoffreEntity {
        exigerMode(mode)
        require(type.entree) { "$type n'est pas une entrée" }
        return enregistrer(mode, montant, type, libelle, auteurId, sessionCaisseId, documentType, documentId)
    }

    /** Enregistre une sortie du coffre, refusée si le solde du mode est insuffisant. */
    suspend fun sortir(
        mode: ModePaiement,
        montant: Long,
        type: TypeOperationCoffre,
        libelle: String,
        auteurId: String?,
        documentType: String? = null,
        documentId: String? = null,
    ): OperationCoffreEntity {
        exigerMode(mode)
        require(!type.entree) { "$type n'est pas une sortie" }
        val solde = dao.solde(mode)
        if (montant > solde) {
            throw RegleMetierException("Il n'y a que ${Montant.formater(solde)} (${mode.libelle.lowercase()}) dans le coffre.")
        }
        return enregistrer(mode, montant, type, libelle, auteurId, null, documentType, documentId)
    }

    private suspend fun enregistrer(
        mode: ModePaiement,
        montant: Long,
        type: TypeOperationCoffre,
        libelle: String,
        auteurId: String?,
        sessionCaisseId: String?,
        documentType: String?,
        documentId: String?,
    ): OperationCoffreEntity {
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        val operation = OperationCoffreEntity(
            type = type, mode = mode, montant = montant, sessionCaisseId = sessionCaisseId,
            documentType = documentType, documentId = documentId, libelle = libelle, createdBy = auteurId,
        )
        dao.inserer(operation)
        return operation
    }

    /** Apport du propriétaire (ou du gérant) directement au coffre : 532 / 108. */
    suspend fun apporter(mode: ModePaiement, montant: Long, motif: String) {
        val auteur = session.exiger(Permission.GERER_COFFRE)
        val texte = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif.") }
        db.withTransaction {
            val operation = entrer(mode, montant, TypeOperationCoffre.APPORT, texte, auteur.id)
            comptable.passer(
                Journal.CA, "Apport au coffre : $texte", ReglesComptables.apportCoffre(montant, mode),
                PieceComptable(DOCUMENT_OPERATION_COFFRE, operation.id),
            )
            audit.enregistrer(ActionAudit.APPORT_COFFRE, "${mode.libelle} ${Montant.formater(montant)} : $texte")
        }
    }

    /** Retrait du propriétaire (ou du gérant) depuis le coffre : 108 / 532, au plus le solde. */
    suspend fun retirer(mode: ModePaiement, montant: Long, motif: String) {
        val auteur = session.exiger(Permission.GERER_COFFRE)
        val texte = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif.") }
        db.withTransaction {
            val operation = sortir(mode, montant, TypeOperationCoffre.RETRAIT, texte, auteur.id)
            comptable.passer(
                Journal.CA, "Retrait du coffre : $texte", ReglesComptables.retraitCoffre(montant, mode),
                PieceComptable(DOCUMENT_OPERATION_COFFRE, operation.id),
            )
            audit.enregistrer(ActionAudit.RETRAIT_COFFRE, "${mode.libelle} ${Montant.formater(montant)} : $texte")
        }
    }

    private fun exigerMode(mode: ModePaiement) {
        if (mode !in MODES_COFFRE) throw RegleMetierException("Le coffre ne garde que des espèces et du mobile money.")
    }

    companion object {
        const val DOCUMENT_OPERATION_COFFRE = "OPERATION_COFFRE"
        private const val LIMITE_HISTORIQUE = 200
    }
}
