package mg.tsena.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.tsena.BuildConfig
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.PinHasher
import mg.tsena.container
import mg.tsena.demo.DonneesDemo
import mg.tsena.domain.RegleMetierException
import mg.tsena.ui.common.ChampPin
import mg.tsena.ui.common.ChampTexte

data class EtatSetup(
    val nomCommerce: String = "",
    val nomProprietaire: String = "",
    val telephone: String = "",
    val pin: String = "",
    val confirmationPin: String = "",
    val erreur: String? = null,
    val enCours: Boolean = false,
)

class SetupViewModel(private val auth: AuthRepository, private val demo: DonneesDemo) : ViewModel() {
    var etat by mutableStateOf(EtatSetup())
        private set

    fun modifier(changement: EtatSetup.() -> EtatSetup) {
        etat = etat.changement().copy(erreur = null)
    }

    fun creer() {
        val e = etat
        val erreur = when {
            e.nomCommerce.isBlank() -> "Indiquez le nom du commerce."
            e.nomProprietaire.isBlank() -> "Indiquez le nom du propriétaire."
            !PinHasher.formatValide(e.pin) -> "Le code PIN doit contenir 4 à 6 chiffres."
            e.pin != e.confirmationPin -> "Les deux codes PIN ne correspondent pas."
            else -> null
        }
        if (erreur != null) {
            etat = e.copy(erreur = erreur)
            return
        }
        etat = e.copy(enCours = true)
        viewModelScope.launch {
            try {
                auth.initialiser(e.nomCommerce, e.nomProprietaire, e.telephone, e.pin)
            } catch (ex: RegleMetierException) {
                etat = etat.copy(enCours = false, erreur = ex.message)
            }
        }
    }

    /**
     * Boutique de démonstration « Tsenasabotsy » (versions de test). Le chargement continue même quand l'accueil
     * remplace cet écran, dès la création du commerce.
     */
    fun chargerDemo() {
        etat = etat.copy(enCours = true, erreur = null)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { demo.charger() }
            } catch (ex: RegleMetierException) {
                etat = etat.copy(enCours = false, erreur = ex.message)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SetupViewModel(container.authRepository, DonneesDemo(container)) }
        }
    }
}

/** Premier lancement : création du commerce et du compte propriétaire, ou restauration. */
@Composable
fun SetupScreen(onRestaurer: () -> Unit, vm: SetupViewModel = viewModel(factory = SetupViewModel.Factory)) {
    val e = vm.etat
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bienvenue sur Tsena", style = MaterialTheme.typography.headlineMedium)
        Text("Configurez votre commerce pour commencer.", style = MaterialTheme.typography.bodyMedium)

        val largeur = Modifier.fillMaxWidth()
        ChampTexte(e.nomCommerce, { v -> vm.modifier { copy(nomCommerce = v) } }, "Nom du commerce", largeur)
        ChampTexte(e.nomProprietaire, { v -> vm.modifier { copy(nomProprietaire = v) } }, "Votre nom", largeur)
        ChampTexte(
            e.telephone, { v -> vm.modifier { copy(telephone = v) } }, "Téléphone (facultatif)", largeur,
            keyboardType = KeyboardType.Phone,
        )
        ChampPin(e.pin, { v -> vm.modifier { copy(pin = v) } }, "Code PIN (4 à 6 chiffres)", largeur, ImeAction.Next)
        ChampPin(e.confirmationPin, { v -> vm.modifier { copy(confirmationPin = v) } }, "Confirmez le code PIN", largeur)

        e.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = vm::creer, enabled = !e.enCours, modifier = largeur) {
            Text(if (e.enCours) "Création…" else "Créer mon commerce")
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Vous changez de téléphone ?", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = onRestaurer, enabled = !e.enCours, modifier = largeur) {
            Text("Restaurer une sauvegarde")
        }

        if (BuildConfig.DEBUG) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Version de test", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = vm::chargerDemo, enabled = !e.enCours, modifier = largeur) {
                Text("Boutique de démonstration « Tsenasabotsy »")
            }
            Text(
                "Boutique de vêtements avec un seul dépôt « tsenasabotsy » : produits, stock, fournisseurs, clients, " +
                    "caisse ouverte et ventes. Codes PIN : ${DonneesDemo.COMPTES}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
