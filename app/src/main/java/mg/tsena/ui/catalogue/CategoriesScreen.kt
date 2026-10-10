package mg.tsena.ui.catalogue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import mg.tsena.catalogue.Categories
import mg.tsena.container
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.domain.RegleMetierException

class CategoriesViewModel(private val catalogue: CatalogueRepository) : ViewModel() {
    val categories: StateFlow<List<TypeProduitEntity>> = catalogue.observerCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null : fermé ; "" : création ; sinon id de la catégorie modifiée */
    var edition by mutableStateOf<String?>(null)
        private set
    var erreurDialogue by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun ouvrir(id: String?) {
        erreurDialogue = null
        edition = id ?: ""
    }

    fun fermer() {
        edition = null
    }

    fun enregistrer(nom: String, parentId: String?) {
        val id = edition?.ifEmpty { null }
        viewModelScope.launch {
            try {
                catalogue.enregistrerCategorie(id, nom, parentId)
                edition = null
            } catch (e: RegleMetierException) {
                erreurDialogue = e.message
            }
        }
    }

    fun supprimer(id: String) {
        viewModelScope.launch {
            message = try {
                catalogue.supprimerCategorie(id)
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
            initializer { CategoriesViewModel(container.catalogueRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onRetour: () -> Unit, vm: CategoriesViewModel = viewModel(factory = CategoriesViewModel.Factory)) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val affichees = remember(categories) { categories.avecChemins() }
    var aSupprimer by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catégories") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.ouvrir(null) }) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle catégorie") }
        },
    ) { padding ->
        if (affichees.isEmpty()) {
            Text(
                "Aucune catégorie. Appuyez sur + pour en créer une (ex. Boissons, Épicerie, Hygiène).",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(affichees, key = { it.categorie.id }) { c ->
                val profondeur = c.chemin.split(Categories.SEPARATEUR).size - 1
                val sousTitre: (@Composable () -> Unit)? = if (profondeur > 0) {
                    { Text(c.chemin, style = MaterialTheme.typography.bodySmall) }
                } else {
                    null
                }
                ListItem(
                    headlineContent = { Text(c.categorie.nom) },
                    supportingContent = sousTitre,
                    trailingContent = {
                        IconButton(onClick = { aSupprimer = c.categorie.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                        }
                    },
                    modifier = Modifier.clickable { vm.ouvrir(c.categorie.id) }.padding(start = (profondeur * 16).dp),
                )
            }
        }
    }

    vm.edition?.let { id ->
        val existante = categories.firstOrNull { it.id == id }
        val parentDe = categories.associate { it.id to it.parentId }
        // Parents possibles : toutes sauf la catégorie elle-même et ses descendantes
        val parentsPossibles = if (existante == null) {
            affichees
        } else {
            affichees.filterNot { Categories.estDans(it.categorie.id, existante.id) { parentDe[it] } }
        }
        CategorieDialog(
            titre = if (existante == null) "Nouvelle catégorie" else "Modifier la catégorie",
            nomInitial = existante?.nom.orEmpty(),
            parentInitial = existante?.parentId,
            categories = parentsPossibles,
            erreur = vm.erreurDialogue,
            onValider = vm::enregistrer,
            onAnnuler = vm::fermer,
        )
    }

    aSupprimer?.let { id ->
        val nom = categories.firstOrNull { it.id == id }?.nom.orEmpty()
        AlertDialog(
            onDismissRequest = { aSupprimer = null },
            title = { Text("Supprimer « $nom » ?") },
            text = { Text("Possible uniquement si elle ne contient ni produit ni sous-catégorie.") },
            confirmButton = { TextButton(onClick = { vm.supprimer(id); aSupprimer = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { aSupprimer = null }) { Text("Annuler") } },
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
