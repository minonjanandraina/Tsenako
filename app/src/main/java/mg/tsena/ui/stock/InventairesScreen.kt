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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.InventaireAvecDepot
import mg.tsena.data.db.entity.LigneInventaireAvecProduit
import mg.tsena.data.db.entity.StatutInventaire
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File

// --- Liste des inventaires ---

class InventairesViewModel(private val stock: StockRepository, lieux: LieuRepository) : ViewModel() {
    val inventaires: StateFlow<List<InventaireAvecDepot>> = stock.observerInventaires()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val depots: StateFlow<List<DepotEntity>> = lieux.observerDepots()
        .map { liste -> liste.filter { it.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var creationOuverte by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    /** Inventaire créé, à ouvrir une fois */
    var cree by mutableStateOf<String?>(null)
        private set

    fun ouvrirCreation() {
        erreur = null
        creationOuverte = true
    }

    fun fermerCreation() {
        creationOuverte = false
    }

    fun creer(depotId: String?) {
        if (depotId == null) {
            erreur = "Choisissez un dépôt."
            return
        }
        viewModelScope.launch {
            try {
                cree = stock.creerInventaire(depotId)
                creationOuverte = false
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    fun creeOuvert() {
        cree = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { InventairesViewModel(container.stockRepository, container.lieuRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventairesScreen(
    onRetour: () -> Unit,
    onOuvrir: (inventaireId: String) -> Unit,
    vm: InventairesViewModel = viewModel(factory = InventairesViewModel.Factory),
) {
    val inventaires by vm.inventaires.collectAsStateWithLifecycle()
    val depots by vm.depots.collectAsStateWithLifecycle()

    LaunchedEffect(vm.cree) {
        vm.cree?.let {
            vm.creeOuvert()
            onOuvrir(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventaires") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::ouvrirCreation) { Icon(Icons.Filled.Add, contentDescription = "Nouvel inventaire") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (inventaires.isEmpty()) {
                item { Text("Aucun inventaire. Appuyez sur + pour compter le stock d'un dépôt.", Modifier.padding(16.dp)) }
            }
            items(inventaires, key = { it.inventaire.id }) { i ->
                val valide = i.inventaire.statut == StatutInventaire.VALIDE
                ListItem(
                    headlineContent = { Text(i.depotNom) },
                    supportingContent = {
                        Text(
                            if (valide) {
                                "Validé le ${formaterDateHeure(i.inventaire.dateValidation ?: i.inventaire.date)}"
                            } else {
                                "En cours depuis le ${formaterDateHeure(i.inventaire.date)} · ${i.nbComptees}/${i.nbLignes} comptés"
                            }
                        )
                    },
                    trailingContent = {
                        Text(
                            if (valide) "Validé" else "En cours",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (valide) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable { onOuvrir(i.inventaire.id) },
                )
            }
        }
    }

    if (vm.creationOuverte) {
        var depotId by rememberSaveable { mutableStateOf(depots.firstOrNull()?.id) }
        AlertDialog(
            onDismissRequest = vm::fermerCreation,
            title = { Text("Nouvel inventaire") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selecteur(
                        libelle = "Dépôt à compter",
                        valeurAffichee = depots.firstOrNull { it.id == depotId }?.nom.orEmpty(),
                        options = depots,
                        texte = { it.nom },
                        onSelection = { depotId = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Tous les produits actifs seront listés. Comptez-les, puis validez : les écarts corrigeront le stock.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.creer(depotId) }) { Text("Commencer") } },
            dismissButton = { TextButton(onClick = vm::fermerCreation) { Text("Annuler") } },
        )
    }
}

// --- Comptage d'un inventaire ---

class InventaireViewModel(
    inventaireId: String,
    private val stock: StockRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    private val id = inventaireId

    val inventaire: StateFlow<InventaireAvecDepot?> = stock.observerInventaire(inventaireId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lignes: StateFlow<List<LigneInventaireAvecProduit>> = stock.observerLignesInventaire(inventaireId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var recherche by mutableStateOf("")
    var nonComptesSeulement by mutableStateOf(false)
    var ligneEnSaisie by mutableStateOf<LigneInventaireAvecProduit?>(null)
        private set
    var confirmation by mutableStateOf<Confirmation?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var termine by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    enum class Confirmation { VALIDER, ABANDONNER }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun saisir(ligne: LigneInventaireAvecProduit) {
        erreur = null
        ligneEnSaisie = ligne
    }

    fun fermer() {
        ligneEnSaisie = null
        confirmation = null
    }

    fun demander(c: Confirmation) {
        erreur = null
        confirmation = c
    }

    /** Texte vide = efface le comptage. */
    fun enregistrerComptage(texte: String) {
        val ligne = ligneEnSaisie ?: return
        val quantite = if (texte.isBlank()) {
            null
        } else {
            Quantite.parser(texte, ligne.uniteDecimale) ?: run {
                erreur = if (ligne.uniteDecimale) "Quantité invalide." else "Quantité invalide (nombre entier de ${ligne.uniteSymbole})."
                return
            }
        }
        executer {
            stock.saisirComptage(ligne.ligne.id, quantite)
            ligneEnSaisie = null
        }
    }

    fun confirmer() {
        when (confirmation) {
            Confirmation.VALIDER -> executer {
                val n = stock.validerInventaire(id)
                confirmation = null
                message = "Inventaire validé : $n ajustement(s) de stock."
            }
            Confirmation.ABANDONNER -> executer {
                stock.abandonnerInventaire(id)
                confirmation = null
                termine = true
            }
            null -> Unit
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
        fun factory(inventaireId: String) = viewModelFactory {
            initializer { InventaireViewModel(inventaireId, container.stockRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventaireScreen(
    inventaireId: String,
    onRetour: () -> Unit,
    vm: InventaireViewModel = viewModel(key = inventaireId, factory = InventaireViewModel.factory(inventaireId)),
) {
    val inventaire by vm.inventaire.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val brouillon = inventaire?.inventaire?.statut == StatutInventaire.BROUILLON

    LaunchedEffect(vm.termine) { if (vm.termine) onRetour() }

    val filtrees = remember(lignes, vm.recherche, vm.nonComptesSeulement) {
        val texte = vm.recherche.trim()
        lignes.filter { l ->
            (texte.isEmpty() || l.nom.contains(texte, ignoreCase = true) || l.code.contains(texte, ignoreCase = true)) &&
                (!vm.nonComptesSeulement || l.ligne.quantiteCompteeMilli == null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Inventaire")
                        Text(inventaire?.depotNom.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            if (brouillon) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.demander(InventaireViewModel.Confirmation.ABANDONNER) }, modifier = Modifier.weight(1f)) {
                        Text("Abandonner")
                    }
                    Button(onClick = { vm.demander(InventaireViewModel.Confirmation.VALIDER) }, modifier = Modifier.weight(1f)) {
                        Text("Valider")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inventaire?.let { i ->
                    Text(
                        if (brouillon) {
                            "${i.nbComptees} produit(s) compté(s) sur ${i.nbLignes}. Les produits non comptés ne seront pas modifiés."
                        } else {
                            "Validé le ${formaterDateHeure(i.inventaire.dateValidation ?: i.inventaire.date)}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                OutlinedTextField(
                    value = vm.recherche,
                    onValueChange = { vm.recherche = it },
                    placeholder = { Text("Nom ou code") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (brouillon) {
                    FilterChip(
                        selected = vm.nonComptesSeulement,
                        onClick = { vm.nonComptesSeulement = !vm.nonComptesSeulement },
                        label = { Text("Non comptés") },
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtrees, key = { it.ligne.id }) { l ->
                    LigneInventaire(l, brouillon, vm.fichierPhoto(l.photoChemin)) { vm.saisir(l) }
                }
            }
        }
    }

    vm.ligneEnSaisie?.let { l -> ComptageDialog(l, vm.erreur, vm::enregistrerComptage, vm::fermer) }

    vm.confirmation?.let { c ->
        val avecEcart = lignes.count { l -> l.ligne.quantiteCompteeMilli?.let { it != l.stockActuelMilli } == true }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text(if (c == InventaireViewModel.Confirmation.VALIDER) "Valider l'inventaire ?" else "Abandonner l'inventaire ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (c == InventaireViewModel.Confirmation.VALIDER) {
                            "$avecEcart produit(s) avec un écart : le stock sera corrigé pour correspondre au comptage. " +
                                "Cette opération est définitive."
                        } else {
                            "Les comptages saisis seront perdus. Le stock n'est pas modifié."
                        }
                    )
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmer) {
                    Text(if (c == InventaireViewModel.Confirmation.VALIDER) "Valider" else "Abandonner")
                }
            },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
        )
    }
}

@Composable
private fun LigneInventaire(l: LigneInventaireAvecProduit, brouillon: Boolean, photo: File, onClick: () -> Unit) {
    val comptee = l.ligne.quantiteCompteeMilli
    // En cours : écart avec le stock actuel ; validé : écart figé à la validation
    val ecart = if (brouillon) comptee?.let { it - l.stockActuelMilli } else l.ligne.ecartMilli
    val theorique = if (brouillon) l.stockActuelMilli else l.ligne.quantiteTheoriqueMilli
    ListItem(
        headlineContent = { Text(l.nom) },
        supportingContent = { Text("${l.code} · Stock : ${Quantite.formater(theorique)} ${l.uniteSymbole}") },
        leadingContent = { PhotoProduit(photo, Modifier.size(48.dp)) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    comptee?.let { "${Quantite.formater(it)} ${l.uniteSymbole}" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (ecart != null && ecart != 0L) {
                    Text(
                        (if (ecart > 0) "+" else "") + Quantite.formater(ecart),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ecart < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        modifier = if (brouillon) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun ComptageDialog(
    ligne: LigneInventaireAvecProduit,
    erreur: String?,
    onValider: (String) -> Unit,
    onAnnuler: () -> Unit,
) {
    var texte by rememberSaveable(ligne.ligne.id) {
        mutableStateOf(ligne.ligne.quantiteCompteeMilli?.let { Quantite.formater(it) }.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(ligne.nom) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stock enregistré : ${Quantite.formater(ligne.stockActuelMilli)} ${ligne.uniteSymbole}")
                ChampTexte(
                    texte, { texte = it }, "Quantité comptée (${ligne.uniteSymbole})", Modifier.fillMaxWidth(),
                    if (ligne.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number,
                )
                Text("Laisser vide pour annuler le comptage de ce produit.", style = MaterialTheme.typography.bodySmall)
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(texte) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
