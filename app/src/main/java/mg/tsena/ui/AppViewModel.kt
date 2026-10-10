package mg.tsena.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.SessionManager
import mg.tsena.auth.UtilisateurConnecte
import mg.tsena.container
import mg.tsena.data.db.entity.CommerceEntity

sealed interface EtatApp {
    data object Chargement : EtatApp
    data object NonInitialise : EtatApp
    data class Deconnecte(val commerce: CommerceEntity) : EtatApp
    data class Connecte(val commerce: CommerceEntity, val utilisateur: UtilisateurConnecte) : EtatApp
}

class AppViewModel(auth: AuthRepository, session: SessionManager) : ViewModel() {
    val etat: StateFlow<EtatApp> =
        combine(auth.observerCommerce(), session.utilisateur) { commerce, utilisateur ->
            when {
                commerce == null -> EtatApp.NonInitialise
                utilisateur == null -> EtatApp.Deconnecte(commerce)
                else -> EtatApp.Connecte(commerce, utilisateur)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, EtatApp.Chargement)

    companion object {
        val Factory = viewModelFactory {
            initializer { AppViewModel(container.authRepository, container.session) }
        }
    }
}
