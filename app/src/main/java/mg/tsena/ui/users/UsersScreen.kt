package mg.tsena.ui.users

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import mg.tsena.auth.PinHasher
import mg.tsena.auth.UtilisateurConnecte
import mg.tsena.container
import mg.tsena.data.db.entity.UtilisateurAvecRoles
import mg.tsena.data.db.entity.rolesActifs
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Role
import mg.tsena.ui.common.ChampPin
import mg.tsena.ui.common.ChampTexte

class UsersViewModel(private val auth: AuthRepository) : ViewModel() {
    val utilisateurs: StateFlow<List<UtilisateurAvecRoles>> = auth.observerTousUtilisateurs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var formulaireOuvert by mutableStateOf(false)
        private set
    var erreurFormulaire by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun ouvrirFormulaire() {
        erreurFormulaire = null
        formulaireOuvert = true
    }

    fun fermerFormulaire() {
        formulaireOuvert = false
    }

    fun creer(nom: String, telephone: String, role: Role, pin: String, confirmation: String) {
        erreurFormulaire = when {
            nom.isBlank() -> "Le nom est obligatoire."
            !PinHasher.formatValide(pin) -> "Le code PIN doit contenir 4 à 6 chiffres."
            pin != confirmation -> "Les deux codes PIN ne correspondent pas."
            else -> null
        }
        if (erreurFormulaire != null) return
        viewModelScope.launch {
            try {
                auth.creerUtilisateur(nom, telephone, role, pin)
                formulaireOuvert = false
            } catch (e: RegleMetierException) {
                erreurFormulaire = e.message
            }
        }
    }

    fun desactiver(id: String) {
        viewModelScope.launch {
            message = try {
                auth.desactiverUtilisateur(id)
                null
            } catch (e: RegleMetierException) {
                e.message
            }
        }
    }

    fun effacerMessage() {
        message = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { UsersViewModel(container.authRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    utilisateurConnecte: UtilisateurConnecte,
    onRetour: () -> Unit,
    vm: UsersViewModel = viewModel(factory = UsersViewModel.Factory),
) {
    val utilisateurs by vm.utilisateurs.collectAsStateWithLifecycle()
    var aDesactiver by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Utilisateurs") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::ouvrirFormulaire) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter un utilisateur")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(utilisateurs, key = { it.utilisateur.id }) { u ->
                val actif = u.utilisateur.actif
                ListItem(
                    headlineContent = { Text(u.utilisateur.nom) },
                    supportingContent = {
                        Text(
                            u.rolesActifs.joinToString { it.libelle } +
                                (u.utilisateur.telephone?.let { " · $it" } ?: "") +
                                (if (actif) "" else " · Désactivé")
                        )
                    },
                    trailingContent = {
                        if (actif && u.utilisateur.id != utilisateurConnecte.id) {
                            TextButton(onClick = { aDesactiver = u.utilisateur.id }) { Text("Désactiver") }
                        }
                    },
                )
            }
        }
    }

    if (vm.formulaireOuvert) {
        FormulaireUtilisateur(vm)
    }

    aDesactiver?.let { id ->
        val nom = utilisateurs.firstOrNull { it.utilisateur.id == id }?.utilisateur?.nom.orEmpty()
        AlertDialog(
            onDismissRequest = { aDesactiver = null },
            title = { Text("Désactiver $nom ?") },
            text = { Text("Cet utilisateur ne pourra plus se connecter. Son historique est conservé.") },
            confirmButton = {
                TextButton(onClick = { vm.desactiver(id); aDesactiver = null }) { Text("Désactiver") }
            },
            dismissButton = { TextButton(onClick = { aDesactiver = null }) { Text("Annuler") } },
        )
    }

    vm.message?.let {
        AlertDialog(
            onDismissRequest = vm::effacerMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::effacerMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun FormulaireUtilisateur(vm: UsersViewModel) {
    var nom by rememberSaveable { mutableStateOf("") }
    var telephone by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.VENDEUR) }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = vm::fermerFormulaire,
        title = { Text("Nouvel utilisateur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                ChampTexte(telephone, { telephone = it }, "Téléphone (facultatif)", Modifier.fillMaxWidth(), KeyboardType.Phone)
                Text("Rôle", style = MaterialTheme.typography.titleSmall)
                Role.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { role = r },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = role == r, onClick = { role = r })
                        Text(r.libelle)
                    }
                }
                ChampPin(pin, { pin = it }, "Code PIN (4 à 6 chiffres)", Modifier.fillMaxWidth())
                ChampPin(confirmation, { confirmation = it }, "Confirmez le code PIN", Modifier.fillMaxWidth())
                vm.erreurFormulaire?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.creer(nom, telephone, role, pin, confirmation) }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = vm::fermerFormulaire) { Text("Annuler") } },
    )
}
