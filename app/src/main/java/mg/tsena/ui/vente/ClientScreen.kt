package mg.tsena.ui.vente

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.container
import mg.tsena.credit.CreditClient
import mg.tsena.credit.CreditRepository
import mg.tsena.data.db.entity.ClientAvecCredit
import mg.tsena.data.db.entity.EncaissementClientEntity
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.StatutVente
import mg.tsena.data.db.entity.TypePaiement
import mg.tsena.data.db.entity.VenteAvecDetails
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Telephone
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.FinanceRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.credit.relancer
import mg.tsena.ui.finance.ChoixReglement
import mg.tsena.vente.VenteRepository

class ClientViewModel(
    private val clientId: String,
    private val ventes: VenteRepository,
    private val finance: FinanceRepository,
    credits: CreditRepository,
) : ViewModel() {
    /** Reste dû par vente à crédit (remboursements imputés de la plus ancienne à la plus récente) */
    val credit: StateFlow<CreditClient?> = credits.observerCreditClient(clientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val client: StateFlow<ClientAvecCredit?> = ventes.observerClients()
        .map { liste -> liste.firstOrNull { it.client.id == clientId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val achats: StateFlow<List<VenteAvecDetails>> = finance.observerVentesClient(clientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val encaissements: StateFlow<List<EncaissementClientEntity>> = finance.observerEncaissementsClient(clientId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val caisses: StateFlow<List<SessionAvecDetails>> = finance.observerCaissesOuvertes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Dialogue { MODIFIER, ENCAISSER }

    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun ouvrir(d: Dialogue) {
        erreur = null
        message = null
        dialogue = d
    }

    fun fermer() {
        dialogue = null
    }

    fun enregistrer(nom: String, telephone: String) {
        executer { ventes.enregistrerClient(clientId, nom, telephone) }
    }

    fun encaisser(montantTexte: String, mode: ModePaiement, pointVenteId: String?, reference: String) {
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        executer {
            finance.encaisserClient(clientId, montant, mode, pointVenteId, reference)
            message = "Remboursement de ${Montant.formater(montant)} enregistré."
        }
    }

    private fun executer(action: suspend () -> Unit) {
        erreur = null
        viewModelScope.launch {
            try {
                action()
                dialogue = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        fun factory(clientId: String) = viewModelFactory {
            initializer { ClientViewModel(clientId, container.venteRepository, container.financeRepository, container.creditRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    clientId: String,
    nomCommerce: String,
    peutEncaisser: Boolean,
    onRetour: () -> Unit,
    onVente: (venteId: String) -> Unit,
    vm: ClientViewModel = viewModel(key = "client_$clientId", factory = ClientViewModel.factory(clientId)),
) {
    val client by vm.client.collectAsStateWithLifecycle()
    val achats by vm.achats.collectAsStateWithLifecycle()
    val encaissements by vm.encaissements.collectAsStateWithLifecycle()
    val caisses by vm.caisses.collectAsStateWithLifecycle()
    val credit by vm.credit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = client

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.client?.nom ?: "Client") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { vm.ouvrir(ClientViewModel.Dialogue.MODIFIER) }) { Icon(Icons.Filled.Edit, contentDescription = "Modifier") }
                },
            )
        },
        bottomBar = {
            if (peutEncaisser && (c?.creditDu ?: 0) > 0) {
                Button(onClick = { vm.ouvrir(ClientViewModel.Dialogue.ENCAISSER) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Encaisser un remboursement")
                }
            }
        },
    ) { padding ->
        if (c == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Telephone.formater(c.client.telephone))
                        Text(
                            if (c.creditDu > 0) "Crédit dû : ${Montant.formater(c.creditDu)}" else "Aucun crédit en cours",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (c.creditDu > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        credit?.let { cc ->
                            cc.plusAncienJours?.let { jours ->
                                Text(
                                    "${cc.impayees.size} vente(s) impayée(s), la plus ancienne depuis $jours jour(s)",
                                    color = if (cc.enRetard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (cc.du > 0) {
                                OutlinedButton(onClick = { relancer(context, nomCommerce, cc) }) { Text("Relancer par WhatsApp") }
                            }
                        }
                        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            if (encaissements.isNotEmpty()) {
                item { Titre("Remboursements") }
                items(encaissements, key = { it.id }) { e ->
                    ListItem(
                        overlineContent = { Text("${e.numero} · ${formaterDateHeure(e.date)}") },
                        headlineContent = { Text(Montant.formater(e.montant)) },
                        supportingContent = { Text(listOfNotNull(e.modePaiement.libelle, e.reference).joinToString(" · ")) },
                    )
                }
            }
            item { Titre("Achats") }
            if (achats.isEmpty()) item { Text("Aucun achat.", Modifier.padding(horizontal = 16.dp)) }
            items(achats, key = { it.vente.id }) { v ->
                val aCredit = v.vente.typePaiement != TypePaiement.COMPTANT
                val etat = credit?.ventes?.firstOrNull { it.venteId == v.vente.id }
                ListItem(
                    overlineContent = { Text("${v.vente.numero} · ${formaterDateHeure(v.vente.date)}") },
                    headlineContent = { Text(Montant.formater(v.vente.totalNet)) },
                    trailingContent = {
                        if (etat != null && v.vente.statut == StatutVente.VALIDEE) {
                            Text(
                                if (etat.reste > 0) "Reste ${Montant.formater(etat.reste)}" else "Soldée",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (etat.reste > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                v.vente.typeVente.libelle,
                                if (aCredit) "à crédit" else "comptant",
                                if (v.vente.statut == StatutVente.ANNULEE) "annulée" else null,
                            ).joinToString(" · ")
                        )
                    },
                    modifier = Modifier.clickable { onVente(v.vente.id) },
                )
            }
        }
    }

    when (vm.dialogue) {
        ClientViewModel.Dialogue.MODIFIER -> c?.let { ClientDialog(it.client, vm.erreur, vm::enregistrer, vm::fermer) }
        ClientViewModel.Dialogue.ENCAISSER -> {
            var montant by rememberSaveable { mutableStateOf((c?.creditDu ?: 0L).toString()) }
            var mode by rememberSaveable { mutableStateOf(ModePaiement.ESPECES) }
            var pointVenteId by rememberSaveable { mutableStateOf<String?>(null) }
            var reference by rememberSaveable { mutableStateOf("") }
            val pvEffectif = pointVenteId ?: caisses.firstOrNull()?.pointVenteId
            AlertDialog(
                onDismissRequest = vm::fermer,
                title = { Text("Remboursement du client") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Crédit dû : ${Montant.formater(c?.creditDu ?: 0)}")
                        ChampTexte(montant, { montant = it }, "Montant reçu (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        ChoixReglement(mode, { mode = it }, caisses, pvEffectif, { pointVenteId = it })
                        ChampTexte(reference, { reference = it }, "Référence (facultatif)", Modifier.fillMaxWidth())
                        vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.encaisser(montant, mode, pvEffectif, reference) }) { Text("Encaisser") } },
                dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
            )
        }
        null -> Unit
    }
}
