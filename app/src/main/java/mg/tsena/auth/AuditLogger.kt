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
