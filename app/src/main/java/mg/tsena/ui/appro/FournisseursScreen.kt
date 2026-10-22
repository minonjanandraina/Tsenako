package mg.tsena.ui.appro

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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
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
import mg.tsena.appro.ApproRepository
import mg.tsena.container
import mg.tsena.data.db.entity.FournisseurAvecSolde
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Telephone
import mg.tsena.ui.common.ChampTexte

class FournisseursViewModel(private val appro: ApproRepository) : ViewModel() {
    val fournisseurs: StateFlow<List<FournisseurAvecSolde>> = appro.observerFournisseurs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Fournisseur en cours d'édition ; Nouveau = création */
    var edition by mutableStateOf<Edition?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    sealed interface Edition {
        data object Nouveau : Edition
        data class Existant(val fournisseur: FournisseurEntity) : Edition
    }

    fun ouvrir(e: Edition) {
        erreur = null
        edition = e
    }

    fun fermer() {
        edition = null
    }

    fun enregistrer(nom: String, telephone: String, adresse: String, nif: String, delai: String, idTsenako: String, actif: Boolean) {
        val jours = delai.trim().ifEmpty { "0" }.toIntOrNull() ?: run {
            erreur = "Délai de paiement invalide (nombre de jours)."
            return
        }
        val id = (edition as? Edition.Existant)?.fournisseur?.id
        viewModelScope.launch {
            try {
                appro.enregistrerFournisseur(id, nom, telephone, adresse, nif, jours, actif, idTsenako)
                edition = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { FournisseursViewModel(container.approRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FournisseursScreen(
    onRetour: () -> Unit,
    onOuvrir: (fournisseurId: String) -> Unit,
    vm: FournisseursViewModel = viewModel(factory = FournisseursViewModel.Factory),
) {
    val fournisseurs by vm.fournisseurs.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fournisseurs") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.ouvrir(FournisseursViewModel.Edition.Nouveau) }) {
                Icon(Icons.Filled.Add, contentDescription = "Nouveau fournisseur")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (fournisseurs.isEmpty()) {
                item { Text("Aucun fournisseur. Appuyez sur + pour en ajouter un.", Modifier.padding(16.dp)) }
            }
            items(fournisseurs, key = { it.fournisseur.id }) { f ->
                ListItem(
                    headlineContent = { Text(f.fournisseur.nom) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                f.fournisseur.telephone?.let(Telephone::formater),
                                "${f.nbReceptions} réception(s)",
                                if (f.fournisseur.actif) null else "Désactivé",
                            ).joinToString(" · ")
                        )
                    },
                    trailingContent = {
                        if (f.solde != 0L) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    Montant.formater(f.solde),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (f.solde > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                Text(if (f.solde > 0) "à payer" else "avoir", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    modifier = Modifier
                        .clickable { onOuvrir(f.fournisseur.id) }
                        .alpha(if (f.fournisseur.actif) 1f else 0.5f),
                )
            }
        }
    }

    vm.edition?.let { e ->
        FournisseurDialog((e as? FournisseursViewModel.Edition.Existant)?.fournisseur, vm.erreur, vm::enregistrer, vm::fermer)
    }
}

@Composable
fun FournisseurDialog(
    fournisseur: FournisseurEntity?,
    erreur: String?,
    onValider: (nom: String, telephone: String, adresse: String, nif: String, delai: String, idTsenako: String, actif: Boolean) -> Unit,
    onAnnuler: () -> Unit,
) {
    var nom by rememberSaveable { mutableStateOf(fournisseur?.nom.orEmpty()) }
    var telephone by rememberSaveable { mutableStateOf(fournisseur?.telephone?.let(Telephone::formater).orEmpty()) }
    var adresse by rememberSaveable { mutableStateOf(fournisseur?.adresse.orEmpty()) }
    var nif by rememberSaveable { mutableStateOf(fournisseur?.nif.orEmpty()) }
    var delai by rememberSaveable { mutableStateOf(fournisseur?.delaiPaiementJours?.takeIf { it > 0 }?.toString().orEmpty()) }
    var idTsenako by rememberSaveable { mutableStateOf(fournisseur?.idTsenako.orEmpty()) }
    var actif by rememberSaveable { mutableStateOf(fournisseur?.actif ?: true) }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (fournisseur == null) "Nouveau fournisseur" else "Modifier le fournisseur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                ChampTexte(telephone, { telephone = it }, "Téléphone (facultatif)", Modifier.fillMaxWidth(), KeyboardType.Phone)
                ChampTexte(adresse, { adresse = it }, "Adresse (facultatif)", Modifier.fillMaxWidth())
                ChampTexte(nif, { nif = it }, "NIF (facultatif)", Modifier.fillMaxWidth())
                ChampTexte(delai, { delai = it }, "Délai de paiement en jours (vide = comptant)", Modifier.fillMaxWidth(), KeyboardType.Number)
                ChampTexte(idTsenako, { idTsenako = it }, "Id Tsenako (n° de compte, facultatif)", Modifier.fillMaxWidth())
                Text("Pour le payer par virement depuis votre compte Tsenako.", style = MaterialTheme.typography.bodySmall)
                if (fournisseur != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Actif", Modifier.weight(1f))
                        Switch(checked = actif, onCheckedChange = { actif = it })
                    }
                }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(nom, telephone, adresse, nif, delai, idTsenako, actif) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
