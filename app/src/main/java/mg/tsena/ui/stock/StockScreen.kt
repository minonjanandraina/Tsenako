package mg.tsena.ui.stock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.AlerteStock
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository
import mg.tsena.stock.Valorisation
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.Selecteur
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StockViewModel(
    private val stock: StockRepository,
    lieux: LieuRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val depots: StateFlow<List<DepotEntity>> = lieux.observerDepots()
        .map { liste -> liste.filter { it.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val choix = MutableStateFlow<String?>(null)

    /** Dépôt affiché : celui choisi, sinon le premier dépôt actif. */
    val depotId: StateFlow<String?> = combine(choix, depots) { id, liste ->
        liste.firstOrNull { it.id == id }?.id ?: liste.firstOrNull()?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stocks: StateFlow<List<StockProduit>> = depotId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else stock.observerStocks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val alertes: StateFlow<List<AlerteStock>> = stock.observerAlertes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var recherche by mutableStateOf("")
    var enAlerteSeulement by mutableStateOf(false)
    var parametresOuverts by mutableStateOf(false)
    var erreur by mutableStateOf<String?>(null)
        private set

    fun choisirDepot(id: String) {
        choix.value = id
    }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun autoriserStockNegatif(autorise: Boolean) {
        viewModelScope.launch {
            try {
                erreur = null
                stock.autoriserStockNegatif(autorise)
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { StockViewModel(container.stockRepository, container.lieuRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(
    peutParametrer: Boolean,
    stockNegatifAutorise: Boolean,
    onRetour: () -> Unit,
    onFiche: (produitId: String, depotId: String) -> Unit,
    onInventaires: () -> Unit,
    vm: StockViewModel = viewModel(factory = StockViewModel.Factory),
) {
    val depots by vm.depots.collectAsStateWithLifecycle()
    val depotId by vm.depotId.collectAsStateWithLifecycle()
    val stocks by vm.stocks.collectAsStateWithLifecycle()
    val alertes by vm.alertes.collectAsStateWithLifecycle()

    val filtres = remember(stocks, vm.recherche, vm.enAlerteSeulement) {
        val texte = vm.recherche.trim()
        stocks.filter { s ->
            (texte.isEmpty() || s.nom.contains(texte, ignoreCase = true) || s.code.contains(texte, ignoreCase = true)) &&
                (!vm.enAlerteSeulement || Valorisation.enAlerte(s.quantiteMilli, s.seuilAlerteMilli))
        }
    }
    val valeurTotale = remember(stocks) {
        stocks.sumOf { if (it.quantiteMilli > 0) Valorisation.valeur(it.quantiteMilli, it.coutMoyenUnitaire) else 0L }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stocks") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    TextButton(onClick = onInventaires) { Text("Inventaires") }
                    if (peutParametrer) {
                        IconButton(onClick = { vm.parametresOuverts = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Paramètres du stock")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (depots.isEmpty()) {
                Text(
                    "Aucun dépôt actif. Créez d'abord un dépôt dans « Dépôts et points de vente ».",
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                ListeStocks(vm, depots, depotId, stocks, filtres, alertes.size, valeurTotale, onFiche)
            }
        }
    }

    if (vm.parametresOuverts) {
        AlertDialog(
            onDismissRequest = { vm.parametresOuverts = false },
            title = { Text("Paramètres du stock") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Autoriser le stock négatif", Modifier.weight(1f))
                        Switch(checked = stockNegatifAutorise, onCheckedChange = vm::autoriserStockNegatif)
                    }
                    Text(
                        "Si désactivé, une sortie (vente, perte, transfert) est refusée quand le stock est insuffisant.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.parametresOuverts = false }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun ListeStocks(
    vm: StockViewModel,
    depots: List<DepotEntity>,
    depotId: String?,
    stocks: List<StockProduit>,
    filtres: List<StockProduit>,
    nbAlertes: Int,
    valeurTotale: Long,
    onFiche: (produitId: String, depotId: String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (nbAlertes > 0) {
            Text(
                "$nbAlertes produit(s) sous le seuil d'alerte, tous dépôts confondus.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Selecteur(
            libelle = "Dépôt",
            valeurAffichee = depots.firstOrNull { it.id == depotId }?.nom.orEmpty(),
            options = depots,
            texte = { it.nom },
            onSelection = { vm.choisirDepot(it.id) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.recherche,
            onValueChange = { vm.recherche = it },
            placeholder = { Text("Nom ou code") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = vm.enAlerteSeulement,
                onClick = { vm.enAlerteSeulement = !vm.enAlerteSeulement },
                label = { Text("En alerte") },
            )
            Text(
                "Valeur : ${Montant.formater(valeurTotale)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                textAlign = TextAlign.End,
            )
        }
    }

    if (filtres.isEmpty()) {
        Text(
            if (stocks.isEmpty()) "Aucun produit actif." else "Aucun produit ne correspond.",
            modifier = Modifier.padding(16.dp),
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(filtres, key = { it.produitId }) { s ->
            val alerte = Valorisation.enAlerte(s.quantiteMilli, s.seuilAlerteMilli)
            ListItem(
                headlineContent = { Text(s.nom) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            s.code,
                            s.coutMoyenUnitaire.takeIf { it > 0 }?.let { "CMUP ${Montant.formater(it)}" },
                            if (alerte) "Seuil ${Quantite.formater(s.seuilAlerteMilli)}" else null,
                        ).joinToString(" · ")
                    )
                },
                leadingContent = { PhotoProduit(vm.fichierPhoto(s.photoChemin), Modifier.size(48.dp)) },
                trailingContent = {
                    Text(
                        "${Quantite.formater(s.quantiteMilli)} ${s.uniteSymbole}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (alerte || s.quantiteMilli < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
                modifier = Modifier.clickable { depotId?.let { onFiche(s.produitId, it) } },
            )
        }
    }
}

/** Alertes de stock de tous les dépôts (RG-S4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertesStockScreen(
    onRetour: () -> Unit,
    onFiche: (produitId: String, depotId: String) -> Unit,
    vm: StockViewModel = viewModel(factory = StockViewModel.Factory),
) {
    val alertes by vm.alertes.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alertes de stock") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (alertes.isEmpty()) item { Text("Aucun produit sous son seuil d'alerte.", Modifier.padding(16.dp)) }
            items(alertes, key = { it.depotId + it.produitId }) { a ->
                ListItem(
                    headlineContent = { Text(a.produitNom) },
                    supportingContent = { Text("${a.depotNom} · Seuil ${Quantite.formater(a.seuilAlerteMilli)} ${a.uniteSymbole}") },
                    trailingContent = {
                        Text(
                            "${Quantite.formater(a.quantiteMilli)} ${a.uniteSymbole}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    modifier = Modifier.clickable { onFiche(a.produitId, a.depotId) },
                )
            }
        }
    }
}
