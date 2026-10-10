package mg.tsena.ui.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.container
import mg.tsena.data.db.entity.UniteEntity
import mg.tsena.domain.RegleMetierException
import mg.tsena.ui.common.ChampTexte

class UnitesViewModel(private val catalogue: CatalogueRepository) : ViewModel() {
    val unites: StateFlow<List<UniteEntity>> = catalogue.observerUnites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var dialogueOuvert by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun ouvrir() {
        erreur = null
        dialogueOuvert = true
    }

    fun fermer() {
        dialogueOuvert = false
    }

    fun creer(nom: String, symbole: String, decimale: Boolean) {
        viewModelScope.launch {
            try {
                catalogue.creerUnite(nom, symbole, decimale)
                dialogueOuvert = false
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { UnitesViewModel(container.catalogueRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitesScreen(onRetour: () -> Unit, vm: UnitesViewModel = viewModel(factory = UnitesViewModel.Factory)) {
    val unites by vm.unites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unités") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::ouvrir) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle unité") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(unites, key = { it.id }) { u ->
                ListItem(
                    headlineContent = { Text("${u.nom} (${u.symbole})") },
                    supportingContent = { Text(if (u.decimale) "Quantités décimales" else "Quantités entières") },
                )
            }
        }
    }

    if (vm.dialogueOuvert) {
        var nom by rememberSaveable { mutableStateOf("") }
        var symbole by rememberSaveable { mutableStateOf("") }
        var decimale by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Nouvelle unité") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChampTexte(nom, { nom = it }, "Nom (ex. Kapoaka)", Modifier.fillMaxWidth())
                    ChampTexte(symbole, { symbole = it }, "Symbole (ex. kpk)", Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quantités décimales (ex. 1,5 kg)", Modifier.weight(1f))
                        Switch(checked = decimale, onCheckedChange = { decimale = it })
                    }
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.creer(nom, symbole, decimale) }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}
