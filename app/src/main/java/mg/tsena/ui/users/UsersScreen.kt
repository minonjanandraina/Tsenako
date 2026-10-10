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
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.UtilisateurAvecRoles
import mg.tsena.data.db.entity.rolesActifs
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Role
import mg.tsena.ui.common.ChampPin
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import mg.tsena.lieux.LieuRepository
import kotlinx.coroutines.flow.map

class UsersViewModel(private val auth: AuthRepository, lieux: LieuRepository) : ViewModel() {
    val utilisateurs: StateFlow<List<UtilisateurAvecRoles>> = auth.observerTousUtilisateurs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pointsVente: StateFlow<List<PointVenteAvecDepot>> = lieux.observerPointsVente()
        .map { liste -> liste.filter { it.pointVente.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Vendeur dont on change le point de vente (RG-X2) */
    var rattachement by mutableStateOf<UtilisateurAvecRoles?>(null)
        private set

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

    fun ouvrirRattachement(u: UtilisateurAvecRoles) {
        erreurFormulaire = null
        rattachement = u
    }

    fun fermerRattachement() {
        rattachement = null
    }

    fun rattacher(pointVenteId: String?) {
        val u = rattachement ?: return
        viewModelScope.launch {
            try {
                auth.modifierPointVente(u.utilisateur.id, pointVenteId)
                rattachement = null
                message = "Point de vente modifié : pris en compte à la prochaine connexion de ${u.utilisateur.nom}."
            } catch (e: RegleMetierException) {
                erreurFormulaire = e.message
            }
        }
    }

    fun creer(nom: String, telephone: String, role: Role, pin: String, confirmation: String, pointVenteId: String?) {
        erreurFormulaire = when {
            nom.isBlank() -> "Le nom est obligatoire."
            !PinHasher.formatValide(pin) -> "Le code PIN doit contenir 4 à 6 chiffres."
            pin != confirmation -> "Les deux codes PIN ne correspondent pas."
            role == Role.VENDEUR && pointVenteId == null -> "Choisissez le point de vente du vendeur."
            else -> null
        }
        if (erreurFormulaire != null) return
        viewModelScope.launch {
            try {
                auth.creerUtilisateur(nom, telephone, role, pin, pointVenteId)
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
            initializer { UsersViewModel(container.authRepository, container.lieuRepository) }
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
    val pointsVente by vm.pointsVente.collectAsStateWithLifecycle()
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
                val vendeur = Role.VENDEUR in u.rolesActifs
                val pointVente = u.roles.firstOrNull { it.deletedAt == null && it.pointVenteId != null }?.pointVenteId
                    ?.let { id -> pointsVente.firstOrNull { it.pointVente.id == id }?.pointVente?.nom }
                ListItem(
                    headlineContent = { Text(u.utilisateur.nom) },
                    supportingContent = {
                        Text(
                            u.rolesActifs.joinToString { it.libelle } +
                                (u.utilisateur.telephone?.let { " · $it" } ?: "") +
                                (if (vendeur) " · ${pointVente ?: "Aucun point de vente"}" else "") +
                                (if (actif) "" else " · Désactivé")
                        )
                    },
                    trailingContent = {
                        if (actif && u.utilisateur.id != utilisateurConnecte.id) {
                            TextButton(onClick = { aDesactiver = u.utilisateur.id }) { Text("Désactiver") }
                        }
                    },
                    modifier = if (actif && vendeur) Modifier.clickable { vm.ouvrirRattachement(u) } else Modifier,
                )
            }
        }
    }

    if (vm.formulaireOuvert) {
        FormulaireUtilisateur(vm, pointsVente)
    }

    vm.rattachement?.let { u ->
        val actuel = u.roles.firstOrNull { it.deletedAt == null && it.pointVenteId != null }?.pointVenteId
        var choix by rememberSaveable(u.utilisateur.id) { mutableStateOf(actuel) }
        AlertDialog(
            onDismissRequest = vm::fermerRattachement,
            title = { Text("Point de vente de ${u.utilisateur.nom}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selecteur(
                        libelle = "Point de vente",
                        valeurAffichee = pointsVente.firstOrNull { it.pointVente.id == choix }?.pointVente?.nom.orEmpty(),
                        options = pointsVente,
                        texte = { it.pointVente.nom },
                        onSelection = { choix = it.pointVente.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Le vendeur ne voit que les ventes et la caisse de ce point de vente.", style = MaterialTheme.typography.bodySmall)
                    vm.erreurFormulaire?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.rattacher(choix) }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::fermerRattachement) { Text("Annuler") } },
        )
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
private fun FormulaireUtilisateur(vm: UsersViewModel, pointsVente: List<PointVenteAvecDepot>) {
    var nom by rememberSaveable { mutableStateOf("") }
    var telephone by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.VENDEUR) }
    var pointVenteId by rememberSaveable { mutableStateOf(pointsVente.singleOrNull()?.pointVente?.id) }
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
                if (role == Role.VENDEUR) {
                    Selecteur(
                        libelle = "Point de vente du vendeur",
                        valeurAffichee = pointsVente.firstOrNull { it.pointVente.id == pointVenteId }?.pointVente?.nom.orEmpty(),
                        options = pointsVente,
                        texte = { it.pointVente.nom },
                        onSelection = { pointVenteId = it.pointVente.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ChampPin(pin, { pin = it }, "Code PIN (4 à 6 chiffres)", Modifier.fillMaxWidth())
                ChampPin(confirmation, { confirmation = it }, "Confirmez le code PIN", Modifier.fillMaxWidth())
                vm.erreurFormulaire?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.creer(nom, telephone, role, pin, confirmation, pointVenteId) }) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = vm::fermerFormulaire) { Text("Annuler") } },
    )
}
