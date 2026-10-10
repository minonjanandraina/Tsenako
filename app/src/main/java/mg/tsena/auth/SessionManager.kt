package mg.tsena.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Role

data class UtilisateurConnecte(val id: String, val nom: String, val roles: Set<Role>) {
    fun peut(permission: Permission): Boolean = roles.any { permission in it.permissions }
}

/** Utilisateur actuellement connecté sur le téléphone (en mémoire uniquement). */
class SessionManager {
    private val _utilisateur = MutableStateFlow<UtilisateurConnecte?>(null)
    val utilisateur: StateFlow<UtilisateurConnecte?> = _utilisateur.asStateFlow()

    fun ouvrir(utilisateur: UtilisateurConnecte) {
        _utilisateur.value = utilisateur
    }

    fun fermer() {
        _utilisateur.value = null
    }

    fun exiger(permission: Permission): UtilisateurConnecte {
        val utilisateur = _utilisateur.value ?: throw RegleMetierException("Aucun utilisateur connecté.")
        if (!utilisateur.peut(permission)) throw RegleMetierException("Action non autorisée pour votre rôle.")
        return utilisateur
    }
}
