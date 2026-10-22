package mg.tsena.banque

import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Comptable
import mg.tsena.compta.PieceComptable
import mg.tsena.compta.ReglesComptables
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.CompteBancaireEntity
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationBancaireEntity
import mg.tsena.data.db.entity.TypeOperationBancaire
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.finance.FinanceRepository

/**
 * Compte Tsenako (RG-B1 à RG-B5) : compte bancaire du commerce chez une banque partenaire, joignable par la
 * [passerelle]. Le solde est la somme des opérations enregistrées ici ; en comptabilité, c'est le compte 512.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BanqueRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val comptable: Comptable,
    private val finance: FinanceRepository,
    val passerelle: PasserelleBancaire,
) {
    private val dao = db.banqueDao()

    fun observerCompte(): Flow<CompteBancaireEntity?> = dao.observerCompte()

    fun observerSolde(): Flow<Long> = dao.observerCompte().flatMapLatest { c -> c?.let { dao.observerSolde(it.id) } ?: flowOf(0L) }

    fun observerOperations(): Flow<List<OperationBancaireEntity>> =
        dao.observerCompte().flatMapLatest { c -> c?.let { dao.observerOperations(it.id, LIMITE_HISTORIQUE) } ?: flowOf(emptyList()) }

    /** RG-B1 : ouvre le compte Tsenako du commerce, une seule fois. */
    suspend fun ouvrirCompte() {
        val auteur = session.exiger(Permission.GERER_BANQUE)
        db.withTransaction {
            if (dao.compte() != null) throw RegleMetierException("Le compte Tsenako est déjà ouvert.")
            val commerce = db.commerceDao().get() ?: throw RegleMetierException("Commerce introuvable.")
            val compte = CompteBancaireEntity(
                banque = passerelle.banque,
                numero = passerelle.ouvrirCompte(commerce.nom),
                intitule = "Tsenako ${commerce.nom}",
                titulaire = commerce.nom,
                createdBy = auteur.id,
            )
            dao.insererCompte(compte)
            audit.enregistrer(ActionAudit.OUVERTURE_COMPTE_BANCAIRE, "${compte.banque} ${compte.numero}")
        }
    }

    /**
     * RG-B3 : argent arrivé sur le compte. En simulation, c'est l'utilisateur qui le déclare (WTB fictif depuis son
     * mobile money) ; avec une banque réelle, il viendra de la notification de la banque. Écriture 512 / 108.
     */
    suspend fun approvisionner(montant: Long) {
        val auteur = session.exiger(Permission.GERER_BANQUE)
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        db.withTransaction {
            val compte = exigerCompte()
            val reference = passerelle.executer(OrdreBancaire(compte.numero, montant, entree = true, libelle = "Approvisionnement WTB"))
            val operation = OperationBancaireEntity(
                numero = numero(dao.compterOperations() + 1),
                compteId = compte.id,
                type = TypeOperationBancaire.APPROVISIONNEMENT,
                montant = montant,
                reference = reference,
                libelle = "Approvisionnement par mobile money (WTB)",
                createdBy = auteur.id,
            )
            dao.insererOperation(operation)
            comptable.passer(
                Journal.BQ, "Approvisionnement compte Tsenako (${operation.numero})",
                ReglesComptables.approvisionnementBanque(montant), PieceComptable(DOCUMENT_OPERATION_BANCAIRE, operation.id),
            )
            audit.enregistrer(ActionAudit.APPROVISIONNEMENT_BANQUE, "${operation.numero} : ${Montant.formater(montant)} ($reference)")
        }
    }

    /**
     * RG-B4 : virement à un fournisseur depuis le compte Tsenako, au plus son solde. Le paiement suit les règles
     * RG-A9 (réception ou acompte, écriture 401 / 512) ; l'ordre ne part à la banque qu'une fois tout vérifié.
     */
    suspend fun payerFournisseur(fournisseurId: String, receptionId: String?, montant: Long, reference: String) {
        val auteur = session.exiger(Permission.GERER_BANQUE)
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        db.withTransaction {
            val compte = exigerCompte()
            val solde = dao.solde(compte.id)
            if (montant > solde) {
                throw RegleMetierException("Il n'y a que ${Montant.formater(solde)} sur le compte Tsenako. Approvisionnez-le d'abord.")
            }
            val paiement = finance.payerFournisseur(
                fournisseurId, receptionId, montant, ModePaiement.BANQUE, null, reference.trim().ifEmpty { "Compte Tsenako" },
            )
            val fournisseur = db.fournisseurDao().get(fournisseurId)?.nom.orEmpty()
            val ref = passerelle.executer(
                OrdreBancaire(compte.numero, montant, entree = false, libelle = "Paiement ${paiement.numero}", beneficiaire = fournisseur),
            )
            val operation = OperationBancaireEntity(
                numero = numero(dao.compterOperations() + 1),
                compteId = compte.id,
                type = TypeOperationBancaire.PAIEMENT_FOURNISSEUR,
                montant = montant,
                reference = ref,
                libelle = "Paiement $fournisseur (${paiement.numero})",
                documentType = FinanceRepository.DOCUMENT_PAIEMENT_FOURNISSEUR,
                documentId = paiement.id,
                createdBy = auteur.id,
            )
            dao.insererOperation(operation)
            audit.enregistrer(ActionAudit.VIREMENT_BANCAIRE, "${operation.numero} : $fournisseur, ${Montant.formater(montant)} ($ref)")
        }
    }

    private suspend fun exigerCompte(): CompteBancaireEntity =
        dao.compte() ?: throw RegleMetierException("Ouvrez d'abord le compte Tsenako.")

    private fun numero(rang: Int) = "$PREFIXE_OPERATION-" + rang.toString().padStart(6, '0')

    companion object {
        const val DOCUMENT_OPERATION_BANCAIRE = "OPERATION_BANCAIRE"
        private const val PREFIXE_OPERATION = "OB"
        private const val LIMITE_HISTORIQUE = 200
    }
}
