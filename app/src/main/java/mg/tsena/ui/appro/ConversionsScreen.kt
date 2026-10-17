package mg.tsena.ui.appro

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
import mg.tsena.appro.ApproRepository
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.container
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.ProduitAvecDetails
import mg.tsena.data.db.entity.UniteEntity
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur

class ConversionsViewModel(private val appro: ApproRepository, catalogue: CatalogueRepository) : ViewModel() {
    val conversions: StateFlow<List<ConversionAvecUnite>> = appro.observerConversions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val produits: StateFlow<List<ProduitAvecDetails>> = catalogue.observerProduits()
        .map { liste -> liste.filter { it.produit.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unites: StateFlow<List<UniteEntity>> = catalogue.observerUnites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var creationOuverte by mutableStateOf(false)
        private set
    var aSupprimer by mutableStateOf<ConversionAvecUnite?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun ouvrirCreation() {
        erreur = null
        creationOuverte = true
    }

    fun demanderSuppression(c: ConversionAvecUnite) {
        erreur = null
        aSupprimer = c
    }

    fun fermer() {
        creationOuverte = false
        aSupprimer = null
    }

    fun creer(produit: ProduitAvecDetails?, uniteId: String?, nombre: String, uniteProduitDecimale: Boolean) {
        if (produit == null || uniteId == null) {
            erreur = "Choisissez le produit et l'unité d'achat."
            return
        }
        val facteur = Quantite.parser(nombre, uniteProduitDecimale)?.takeIf { it > 0 } ?: run {
            erreur = if (uniteProduitDecimale) "Nombre invalide." else "Nombre invalide (entier de ${produit.uniteSymbole})."
            return
        }
        executer {
            appro.ajouterConversion(produit.produit.id, uniteId, facteur)
            creationOuverte = false
        }
    }

    fun supprimer() {
        val c = aSupprimer ?: return
        executer {
            appro.supprimerConversion(c.conversion.id)
            aSupprimer = null
        }
    }

    private fun executer(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ConversionsViewModel(container.approRepository, container.catalogueRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionsScreen(onRetour: () -> Unit, vm: ConversionsViewModel = viewModel(factory = ConversionsViewModel.Factory)) {
    val conversions by vm.conversions.collectAsStateWithLifecycle()
    val produits by vm.produits.collectAsStateWithLifecycle()
    val unites by vm.unites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unités d'achat") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::ouvrirCreation) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle unité d'achat") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Achetez en carton, en sac… et stockez à la pièce ou au kilo : la quantité est convertie à la saisie " +
                        "des commandes, réceptions et transferts.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (conversions.isEmpty()) item { Text("Aucune unité d'achat.", Modifier.padding(horizontal = 16.dp)) }
            items(conversions, key = { it.conversion.id }) { c ->
                ListItem(
                    headlineContent = { Text(libelleProduit(c.produitCode, c.produitNom)) },
                    supportingContent = { Text("1 ${c.uniteSymbole} = ${Quantite.formater(c.conversion.facteurMilli)} ${c.uniteProduitSymbole}") },
                    trailingContent = {
                        IconButton(onClick = { vm.demanderSuppression(c) }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
                    },
                )
            }
        }
    }

    if (vm.creationOuverte) {
        var produitId by rememberSaveable { mutableStateOf<String?>(null) }
        var uniteId by rememberSaveable { mutableStateOf<String?>(null) }
        var nombre by rememberSaveable { mutableStateOf("") }
        val produit = produits.firstOrNull { it.produit.id == produitId }
        val uniteProduit = unites.firstOrNull { it.id == produit?.produit?.uniteId }
        val unite = unites.firstOrNull { it.id == uniteId }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Nouvelle unité d'achat") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selecteur(
                        libelle = "Produit",
                        valeurAffichee = produit?.let { libelleProduit(it.produit.code, it.produit.nom) }.orEmpty(),
                        options = produits,
                        texte = { "${libelleProduit(it.produit.code, it.produit.nom)} (${it.uniteSymbole})" },
                        onSelection = { produitId = it.produit.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Selecteur(
                        libelle = "Unité d'achat",
                        valeurAffichee = unite?.let { "${it.nom} (${it.symbole})" }.orEmpty(),
                        options = unites.filter { it.id != produit?.produit?.uniteId },
                        texte = { "${it.nom} (${it.symbole})" },
                        onSelection = { uniteId = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val symboleProduit = produit?.uniteSymbole ?: "unités"
                    ChampTexte(
                        nombre, { nombre = it }, "Nombre de $symboleProduit dans 1 ${unite?.symbole ?: "unité d'achat"}",
                        Modifier.fillMaxWidth(),
                        if (uniteProduit?.decimale == true) KeyboardType.Decimal else KeyboardType.Number,
                    )
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.creer(produit, uniteId, nombre, uniteProduit?.decimale ?: false) }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }

    vm.aSupprimer?.let { c ->
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Supprimer cette unité d'achat ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${libelleProduit(c.produitCode, c.produitNom)} : 1 ${c.uniteSymbole} = ${Quantite.formater(c.conversion.facteurMilli)} ${c.uniteProduitSymbole}")
                    Text("Les documents déjà saisis ne sont pas modifiés.", style = MaterialTheme.typography.bodySmall)
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::supprimer) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}
