package mg.tsena.ui.catalogue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.catalogue.Categories
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.ProduitAvecDetails
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.domain.Montant
import mg.tsena.ui.common.Selecteur
import java.io.File

class ProduitsViewModel(catalogue: CatalogueRepository, private val photos: PhotoManager) : ViewModel() {
    val produits: StateFlow<List<ProduitAvecDetails>> = catalogue.observerProduits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<TypeProduitEntity>> = catalogue.observerCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var recherche by mutableStateOf("")
    var categorieFiltre by mutableStateOf<String?>(null)

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    companion object {
        val Factory = viewModelFactory {
            initializer { ProduitsViewModel(container.catalogueRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProduitsScreen(
    onRetour: () -> Unit,
    onNouveau: () -> Unit,
    onModifier: (String) -> Unit,
    vm: ProduitsViewModel = viewModel(factory = ProduitsViewModel.Factory),
) {
    val produits by vm.produits.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoriesAffichees = remember(categories) { categories.avecChemins() }
    val parentDe = remember(categories) { categories.associate { it.id to it.parentId } }

    val filtres = remember(produits, vm.recherche, vm.categorieFiltre, parentDe) {
        val texte = vm.recherche.trim()
        produits.filter { p ->
            val correspondTexte = texte.isEmpty() ||
                p.produit.nom.contains(texte, ignoreCase = true) ||
                p.produit.code.contains(texte, ignoreCase = true) ||
                p.produit.codeBarre?.contains(texte) == true
            val filtre = vm.categorieFiltre
            val correspondCategorie = filtre == null || Categories.estDans(p.produit.typeProduitId, filtre) { parentDe[it] }
            correspondTexte && correspondCategorie
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Produits") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNouveau) { Icon(Icons.Filled.Add, contentDescription = "Nouveau produit") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.recherche,
                    onValueChange = { vm.recherche = it },
                    placeholder = { Text("Nom, code ou code-barre") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Selecteur(
                    libelle = "Catégorie",
                    valeurAffichee = categoriesAffichees.firstOrNull { it.categorie.id == vm.categorieFiltre }?.chemin ?: TOUTES,
                    options = listOf<CategorieAffichee?>(null) + categoriesAffichees,
                    texte = { it?.chemin ?: TOUTES },
                    onSelection = { vm.categorieFiltre = it?.categorie?.id },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (filtres.isEmpty()) {
                Text(
                    if (produits.isEmpty()) "Aucun produit. Appuyez sur + pour en ajouter." else "Aucun produit ne correspond.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(filtres, key = { it.produit.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.produit.nom) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    p.produit.code,
                                    p.categorieNom,
                                    p.produit.prixAchat?.let { "Achat : ${Montant.formater(it)}" },
                                    p.produit.prixVenteGros?.let { "Gros : ${Montant.formater(it)}" },
                                    if (p.produit.actif) null else "Inactif",
                                ).joinToString(" · ")
                            )
                        },
                        leadingContent = { PhotoProduit(vm.fichierPhoto(p.produit.photoChemin), Modifier.size(56.dp)) },
                        trailingContent = { Text("${Montant.formater(p.produit.prixVente)} / ${p.uniteSymbole}") },
                        modifier = Modifier
                            .clickable { onModifier(p.produit.id) }
                            .alpha(if (p.produit.actif) 1f else 0.5f),
                    )
                }
            }
        }
    }
}

private const val TOUTES = "Toutes"
