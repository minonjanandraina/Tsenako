package mg.tsena.ui.stock

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.MouvementAvecAuteur
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository
import mg.tsena.stock.Valorisation
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import java.io.File

enum class OperationStock { STOCK_INITIAL, PERTE }

class FicheStockViewModel(
    private val produitId: String,
    private val depotId: String,
    private val stock: StockRepository,
    lieux: LieuRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    var depotNom by mutableStateOf("")
        private set

    init {
        viewModelScope.launch { depotNom = lieux.getDepot(depotId)?.nom.orEmpty() }
    }

    val produit: StateFlow<StockProduit?> = stock.observerStock(produitId, depotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val mouvements: StateFlow<List<MouvementAvecAuteur>> = stock.observerMouvements(produitId, depotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var operation by mutableStateOf<OperationStock?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun ouvrir(op: OperationStock) {
        erreur = null
        operation = op
    }

    fun fermer() {
        operation = null
    }

    fun valider(quantite: String, cout: String, motif: String) {
        val p = produit.value ?: return
        val quantiteMilli = Quantite.parser(quantite, p.uniteDecimale)
        if (quantiteMilli == null || quantiteMilli == 0L) {
            erreur = if (p.uniteDecimale) "Quantité invalide." else "Quantité invalide (nombre entier de ${p.uniteSymbole})."
            return
        }
        viewModelScope.launch {
            try {
                when (operation) {
                    OperationStock.STOCK_INITIAL -> {
                        val coutUnitaire = Montant.parser(cout) ?: throw RegleMetierException("Coût d'achat unitaire invalide.")
                        stock.saisirStockInitial(produitId, depotId, quantiteMilli, coutUnitaire)
                    }
                    OperationStock.PERTE -> stock.declarerPerte(produitId, depotId, quantiteMilli, motif)
                    null -> Unit
                }
                operation = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        fun factory(produitId: String, depotId: String) = viewModelFactory {
            initializer { FicheStockViewModel(produitId, depotId, container.stockRepository, container.lieuRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FicheStockScreen(
    produitId: String,
    depotId: String,
    onRetour: () -> Unit,
    vm: FicheStockViewModel = viewModel(key = "$produitId|$depotId", factory = FicheStockViewModel.factory(produitId, depotId)),
) {
    val produit by vm.produit.collectAsStateWithLifecycle()
    val mouvements by vm.mouvements.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(produit?.nom.orEmpty())
                        Text(vm.depotNom, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        val p = produit
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (p != null) {
                item {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PhotoProduit(vm.fichierPhoto(p.photoChemin), Modifier.size(80.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${Quantite.formater(p.quantiteMilli)} ${p.uniteSymbole}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (Valorisation.enAlerte(p.quantiteMilli, p.seuilAlerteMilli) || p.quantiteMilli < 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text("Coût moyen (CMUP) : ${Montant.formater(p.coutMoyenUnitaire)} / ${p.uniteSymbole}")
                            if (p.quantiteMilli > 0) {
                                Text("Valeur : ${Montant.formater(Valorisation.valeur(p.quantiteMilli, p.coutMoyenUnitaire))}")
                            }
                            if (p.seuilAlerteMilli > 0) {
                                Text("Seuil d'alerte : ${Quantite.formater(p.seuilAlerteMilli)} ${p.uniteSymbole}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.ouvrir(OperationStock.STOCK_INITIAL) }, modifier = Modifier.weight(1f)) {
                            Text("Stock initial")
                        }
                        OutlinedButton(onClick = { vm.ouvrir(OperationStock.PERTE) }, modifier = Modifier.weight(1f)) {
                            Text("Déclarer une perte")
                        }
                    }
                }
            }
            item {
                Text("Mouvements", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            if (mouvements.isEmpty()) item { Text("Aucun mouvement.", Modifier.padding(horizontal = 16.dp)) }
            items(mouvements, key = { it.mouvement.id }) { m ->
                LigneMouvement(m, p?.uniteSymbole.orEmpty())
                HorizontalDivider()
            }
        }
    }

    val op = vm.operation
    val p = produit
    if (op != null && p != null) OperationDialog(op, p, vm.erreur, vm::valider, vm::fermer)
}

@Composable
private fun LigneMouvement(m: MouvementAvecAuteur, unite: String) {
    val mv = m.mouvement
    val signe = if (mv.quantiteMilli > 0) "+" else ""
    ListItem(
        headlineContent = { Text(mv.type.libelle) },
        supportingContent = {
            Text(
                listOfNotNull(
                    formaterDateHeure(mv.date),
                    m.auteurNom,
                    mv.motif?.takeIf { it != mv.type.libelle },
                    "${Montant.formater(mv.coutUnitaire)} / $unite",
                ).joinToString(" · ")
            )
        },
        trailingContent = {
            Column {
                Text(
                    "$signe${Quantite.formater(mv.quantiteMilli)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (mv.quantiteMilli < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text("→ ${Quantite.formater(mv.quantiteApresMilli)}", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun OperationDialog(
    operation: OperationStock,
    produit: StockProduit,
    erreur: String?,
    onValider: (quantite: String, cout: String, motif: String) -> Unit,
    onAnnuler: () -> Unit,
) {
    var quantite by rememberSaveable { mutableStateOf("") }
    // Proposé : le coût moyen actuel, sinon le prix d'achat de la fiche produit
    var cout by rememberSaveable {
        mutableStateOf(produit.coutMoyenUnitaire.takeIf { it > 0 }?.toString() ?: produit.prixAchat?.toString().orEmpty())
    }
    var motif by rememberSaveable { mutableStateOf("") }
    val typeClavier = if (produit.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (operation == OperationStock.STOCK_INITIAL) "Stock initial" else "Déclarer une perte") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(quantite, { quantite = it }, "Quantité (${produit.uniteSymbole})", Modifier.fillMaxWidth(), typeClavier)
                when (operation) {
                    OperationStock.STOCK_INITIAL -> {
                        ChampTexte(cout, { cout = it }, "Coût d'achat unitaire (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        Text(
                            "Le coût sert au calcul du coût moyen (CMUP) et des marges.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OperationStock.PERTE -> {
                        ChampTexte(motif, { motif = it }, "Motif (casse, périmé, vol…)", Modifier.fillMaxWidth())
                    }
                }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(quantite, cout, motif) }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
