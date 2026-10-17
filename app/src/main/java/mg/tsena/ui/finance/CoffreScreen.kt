package mg.tsena.ui.finance

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationCoffreEntity
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.CoffreRepository
import mg.tsena.finance.MODES_COFFRE
import mg.tsena.ui.common.ChampTexte

class CoffreViewModel(private val coffre: CoffreRepository) : ViewModel() {
    val soldes: StateFlow<Map<ModePaiement, Long>> = coffre.observerSoldes()
        .map { liste -> liste.associate { it.mode to it.solde } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val operations: StateFlow<List<OperationCoffreEntity>> = coffre.observerOperations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var erreur by mutableStateOf<String?>(null)
        private set

    fun effacerErreur() {
        erreur = null
    }

    /** [apport] : true pour un apport, false pour un retrait. [onFait] est appelé une fois enregistré. */
    fun enregistrer(apport: Boolean, mode: ModePaiement, montantTexte: String, motif: String, onFait: () -> Unit) {
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        erreur = null
        viewModelScope.launch {
            try {
                if (apport) coffre.apporter(mode, montant, motif) else coffre.retirer(mode, montant, motif)
                onFait()
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { CoffreViewModel(container.coffreRepository) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoffreScreen(
    peutFinancer: Boolean,
    onFinancements: () -> Unit,
    onRetour: () -> Unit,
    vm: CoffreViewModel = viewModel(factory = CoffreViewModel.Factory),
) {
    val soldes by vm.soldes.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    var dialogue by rememberSaveable { mutableStateOf<Boolean?>(null) } // true = apport, false = retrait

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coffre") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dans le coffre", style = MaterialTheme.typography.titleSmall)
                    MODES_COFFRE.forEach { mode ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(mode.libelle)
                            Text(Montant.formater(soldes[mode] ?: 0), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text(
                        "L'argent y arrive à la clôture de caisse. Le fond de caisse de l'ouverture en sort.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.effacerErreur(); dialogue = true }) { Text("Apport") }
                OutlinedButton(onClick = { vm.effacerErreur(); dialogue = false }) { Text("Retrait") }
            }
            if (peutFinancer) {
                OutlinedButton(onClick = onFinancements, modifier = Modifier.fillMaxWidth()) { Text("Injection de capital / emprunt") }
            }
            Text("Mouvements récents", style = MaterialTheme.typography.titleSmall)
            if (operations.isEmpty()) {
                Text("Aucun mouvement pour l'instant.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(operations, key = { it.id }) { o ->
                    ListItem(
                        headlineContent = { Text("${o.type.libelle} · ${o.mode.libelle}") },
                        supportingContent = { Text("${formaterDateHeure(o.date)} · ${o.libelle}") },
                        trailingContent = {
                            Text(
                                (if (o.type.entree) "+ " else "− ") + Montant.formater(o.montant),
                                color = if (o.type.entree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    dialogue?.let { apport ->
        MouvementCoffreDialog(
            apport = apport,
            soldes = soldes,
            erreur = vm.erreur,
            onValider = { mode, montant, motif -> vm.enregistrer(apport, mode, montant, motif) { dialogue = null } },
            onAnnuler = { dialogue = null },
        )
    }
}

@Composable
private fun MouvementCoffreDialog(
    apport: Boolean,
    soldes: Map<ModePaiement, Long>,
    erreur: String?,
    onValider: (ModePaiement, String, String) -> Unit,
    onAnnuler: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(ModePaiement.ESPECES) }
    var montant by rememberSaveable { mutableStateOf("") }
    var motif by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (apport) "Apport au coffre" else "Retrait du coffre") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODES_COFFRE.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.libelle) }) }
                }
                if (!apport) Text("Disponible : ${Montant.formater(soldes[mode] ?: 0)}", style = MaterialTheme.typography.bodySmall)
                ChampTexte(montant, { montant = it }, "Montant (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                ChampTexte(
                    motif, { motif = it }, if (apport) "Motif (ex. capital de départ)" else "Motif (ex. retrait du propriétaire)",
                    Modifier.fillMaxWidth(),
                )
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(mode, montant, motif) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
