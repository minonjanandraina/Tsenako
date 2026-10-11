package mg.tsena.ui.vente

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import mg.tsena.container
import mg.tsena.data.db.entity.ClientAvecCredit
import mg.tsena.data.db.entity.ClientEntity
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Telephone
import mg.tsena.ui.common.ChampTexte
import mg.tsena.vente.VenteRepository

class ClientsViewModel(private val ventes: VenteRepository) : ViewModel() {
    val clients: StateFlow<List<ClientAvecCredit>> = ventes.observerClients()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var recherche by mutableStateOf("")
    /** Client modifié ; [nouveau] = création */
    var edition by mutableStateOf<ClientEntity?>(null)
        private set
    var nouveau by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun creer() {
        erreur = null
        edition = null
        nouveau = true
    }

    fun modifier(c: ClientEntity) {
        erreur = null
        nouveau = false
        edition = c
    }

    fun fermer() {
        edition = null
        nouveau = false
    }

    fun enregistrer(nom: String, telephone: String) {
        val id = edition?.id
        viewModelScope.launch {
            try {
                ventes.enregistrerClient(id, nom, telephone)
                fermer()
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ClientsViewModel(container.venteRepository) }
        }
    }
}

/** RG-V7 : recherche par nom ou par téléphone (avec ou sans indicatif). */
fun List<ClientAvecCredit>.filtrer(texte: String): List<ClientAvecCredit> {
    val t = texte.trim()
    if (t.isEmpty()) return this
    val chiffres = t.filter { it.isDigit() }.trimStart('0')
    return filter { c ->
        c.client.nom.contains(t, ignoreCase = true) || (chiffres.length >= 3 && c.client.telephone.contains(chiffres))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    onRetour: () -> Unit,
    onOuvrir: (clientId: String) -> Unit,
    vm: ClientsViewModel = viewModel(factory = ClientsViewModel.Factory),
) {
    val clients by vm.clients.collectAsStateWithLifecycle()
    val filtres = remember(clients, vm.recherche) { clients.filtrer(vm.recherche) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clients") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::creer) { Icon(Icons.Filled.Add, contentDescription = "Nouveau client") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = vm.recherche,
                onValueChange = { vm.recherche = it },
                placeholder = { Text("Nom ou téléphone") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (clients.isEmpty()) {
                    item {
                        Text(
                            "Aucun client. Enregistrez ici les clients en gros ou à crédit (nom et téléphone).",
                            Modifier.padding(16.dp),
                        )
                    }
                }
                items(filtres, key = { it.client.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.client.nom) },
                        supportingContent = { Text(Telephone.formater(c.client.telephone)) },
                        trailingContent = {
                            if (c.creditDu != 0L) {
                                Column {
                                    Text(Montant.formater(c.creditDu), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                                    Text("à crédit", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOuvrir(c.client.id) },
                    )
                }
            }
        }
    }

    if (vm.nouveau || vm.edition != null) {
        ClientDialog(vm.edition, vm.erreur, vm::enregistrer, vm::fermer)
    }
}

@Composable
fun ClientDialog(
    client: ClientEntity?,
    erreur: String?,
    onValider: (nom: String, telephone: String) -> Unit,
    onAnnuler: () -> Unit,
) {
    var nom by rememberSaveable { mutableStateOf(client?.nom.orEmpty()) }
    var telephone by rememberSaveable { mutableStateOf(client?.telephone?.let(Telephone::formater).orEmpty()) }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (client == null) "Nouveau client" else "Modifier le client") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                ChampTexte(telephone, { telephone = it }, "Téléphone (ex. 034 12 345 67)", Modifier.fillMaxWidth(), KeyboardType.Phone)
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(nom, telephone) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

/** RG-V7 : choix d'un client existant à la vente (aucune création depuis l'écran de vente). */
@Composable
fun ChoixClientDialog(
    clients: List<ClientAvecCredit>,
    onChoisir: (ClientAvecCredit) -> Unit,
    onRetirer: (() -> Unit)?,
    onAnnuler: () -> Unit,
) {
    var recherche by rememberSaveable { mutableStateOf("") }
    val filtres = remember(clients, recherche) { clients.filtrer(recherche) }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Client") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = recherche,
                    onValueChange = { recherche = it },
                    placeholder = { Text("Nom ou téléphone") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (clients.isEmpty()) {
                    Text("Aucun client enregistré. Les clients se créent depuis l'écran « Clients ».", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtres, key = { it.client.id }) { c ->
                        ListItem(
                            headlineContent = { Text(c.client.nom) },
                            supportingContent = { Text(Telephone.formater(c.client.telephone)) },
                            modifier = Modifier.clickable { onChoisir(c) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (onRetirer != null) TextButton(onClick = onRetirer) { Text("Sans client") }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
