package mg.tsena.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.ResultatConnexion
import mg.tsena.container
import mg.tsena.data.db.entity.UtilisateurAvecRoles
import mg.tsena.data.db.entity.rolesActifs
import mg.tsena.ui.common.ChampPin

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    val utilisateurs: StateFlow<List<UtilisateurAvecRoles>> = auth.observerUtilisateursActifs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var selection by mutableStateOf<String?>(null)
        private set
    var pin by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
        private set
    var enCours by mutableStateOf(false)
        private set

    fun selectionner(id: String) {
        selection = id
        pin = ""
        message = null
    }

    fun connecter() {
        val id = selection ?: return
        enCours = true
        viewModelScope.launch {
            val resultat = auth.connecter(id, pin)
            enCours = false
            pin = ""
            message = when (resultat) {
                ResultatConnexion.Succes -> null
                is ResultatConnexion.PinIncorrect -> "Code PIN incorrect. ${resultat.essaisRestants} essai(s) restant(s)."
                is ResultatConnexion.Bloque -> "Trop d'essais. Réessayez dans ${resultat.secondes} secondes."
                ResultatConnexion.Introuvable -> "Utilisateur introuvable ou désactivé."
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LoginViewModel(container.authRepository) }
        }
    }
}

@Composable
fun LoginScreen(nomCommerce: String, vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory)) {
    val utilisateurs by vm.utilisateurs.collectAsStateWithLifecycle()
    LaunchedEffect(utilisateurs) {
        if (vm.selection == null && utilisateurs.size == 1) vm.selectionner(utilisateurs.first().utilisateur.id)
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(nomCommerce, style = MaterialTheme.typography.headlineMedium)
        Text("Qui êtes-vous ?", style = MaterialTheme.typography.titleMedium)

        LazyColumn(Modifier.weight(1f)) {
            items(utilisateurs, key = { it.utilisateur.id }) { u ->
                val choisi = u.utilisateur.id == vm.selection
                ListItem(
                    headlineContent = { Text(u.utilisateur.nom) },
                    supportingContent = { Text(u.rolesActifs.joinToString { it.libelle }) },
                    leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    colors = if (choisi) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectionner(u.utilisateur.id) },
                )
            }
        }

        if (vm.selection != null) {
            ChampPin(vm.pin, { vm.pin = it }, "Code PIN", Modifier.fillMaxWidth())
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = vm::connecter,
                enabled = !vm.enCours && vm.pin.length >= 4,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Se connecter") }
        }
    }
}
