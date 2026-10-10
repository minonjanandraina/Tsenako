package mg.tsena.auth

import mg.tsena.data.db.dao.JournalAuditDao
import mg.tsena.data.db.entity.JournalAuditEntity

enum class ActionAudit {
    CREATION_COMMERCE,
    CONNEXION,
    ECHEC_CONNEXION,
    DECONNEXION,
    CREATION_UTILISATEUR,
    DESACTIVATION_UTILISATEUR,
    SAUVEGARDE,
    CREATION_CATEGORIE,
    MODIFICATION_CATEGORIE,
    SUPPRESSION_CATEGORIE,
    CREATION_UNITE,
    CREATION_PRODUIT,
    MODIFICATION_PRODUIT,
    CREATION_DEPOT,
    MODIFICATION_DEPOT,
    CREATION_POINT_VENTE,
    MODIFICATION_POINT_VENTE,
    STOCK_INITIAL,
    PERTE_STOCK,
    CREATION_INVENTAIRE,
    VALIDATION_INVENTAIRE,
    ABANDON_INVENTAIRE,
    PARAMETRE_STOCK_NEGATIF,
    CREATION_FOURNISSEUR,
    MODIFICATION_FOURNISSEUR,
    CREATION_CONVERSION,
    SUPPRESSION_CONVERSION,
    CREATION_COMMANDE,
    MODIFICATION_COMMANDE,
    ENVOI_COMMANDE,
    ANNULATION_COMMANDE,
    SOLDE_COMMANDE,
    RECEPTION,
    ENVOI_TRANSFERT,
    RECEPTION_TRANSFERT,
    ANNULATION_TRANSFERT,
}

class AuditLogger(private val dao: JournalAuditDao, private val session: SessionManager) {
    suspend fun enregistrer(
        action: ActionAudit,
        details: String? = null,
        utilisateurId: String? = session.utilisateur.value?.id,
    ) {
        dao.inserer(JournalAuditEntity(utilisateurId = utilisateurId, action = action.name, details = details))
    }
}
