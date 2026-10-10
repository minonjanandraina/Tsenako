package mg.tsena.ui.appro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.appro.ApproRepository
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.CommandeAvecDetails
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.data.db.entity.LigneReceptionAvecProduit
import mg.tsena.data.db.entity.ReceptionAvecDetails
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File

// --- Liste des réceptions ---

class ReceptionsViewModel(appro: ApproRepository) : ViewModel() {
    val receptions: StateFlow<List<ReceptionAvecDetails>> = appro.observerReceptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = viewModelFactory {
            initializer { ReceptionsViewModel(container.approRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceptionsScreen(
    onRetour: () -> Unit,
    onNouvelle: () -> Unit,
    onOuvrir: (receptionId: String) -> Unit,
    vm: ReceptionsViewModel = viewModel(factory = ReceptionsViewModel.Factory),
) {
    val receptions by vm.receptions.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réceptions") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNouvelle) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle réception") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Pour recevoir une commande, ouvrez-la depuis « Commandes ». Le bouton + enregistre un achat sans commande.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (receptions.isEmpty()) item { Text("Aucune réception.", Modifier.padding(horizontal = 16.dp)) }
            items(receptions, key = { it.reception.id }) { r ->
                ListItem(
                    overlineContent = { Text("${r.reception.numero} · ${formaterDateHeure(r.reception.date)}") },
                    headlineContent = { Text(r.fournisseurNom) },
                    supportingContent = { Text(listOfNotNull(r.depotNom, r.commandeNumero).joinToString(" · ")) },
                    trailingContent = { Text(Montant.formater(r.reception.montantTotal), style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.clickable { onOuvrir(r.reception.id) },
                )
            }
        }
    }
}

// --- Saisie d'une réception ---

@OptIn(ExperimentalCoroutinesApi::class)
class ReceptionFormViewModel(
    private val commandeId: String?,
    private val appro: ApproRepository,
    stock: StockRepository,
    lieux: LieuRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val fournisseurs: StateFlow<List<FournisseurEntity>> = appro.observerFournisseursActifs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val depots: StateFlow<List<DepotEntity>> = lieux.observerDepots()
        .map { liste -> liste.filter { it.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conversions: StateFlow<Map<String, List<ConversionAvecUnite>>> = appro.observerConversions()
        .map { liste -> liste.groupBy { it.conversion.produitId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val depotId = MutableStateFlow<String?>(null)
    val produits: StateFlow<List<StockProduit>> = depotId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else stock.observerStocks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val editeur = EditeurLignes()
    /** Commande reçue : fournisseur et dépôt imposés */
    var commande by mutableStateOf<CommandeAvecDetails?>(null)
        private set
    var fournisseurId by mutableStateOf<String?>(null)
    var reference by mutableStateOf("")
    var confirmationOuverte by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var enCours by mutableStateOf(false)
        private set
    var termine by mutableStateOf<String?>(null)
        private set

    init {
        if (commandeId != null) {
            viewModelScope.launch {
                val c = appro.observerCommande(commandeId).first() ?: return@launch
                commande = c
                fournisseurId = c.commande.fournisseurId
                depotId.value = c.commande.depotId
                // Proposé : le reste à recevoir de chaque ligne, au prix convenu
                editeur.lignes = appro.lignesCommande(commandeId).mapNotNull { it.versReliquat() }
            }
        }
    }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun demanderValidation() {
        erreur = null
        when {
            fournisseurId == null -> erreur = "Choisissez un fournisseur."
            depotId.value == null -> erreur = "Choisissez le dépôt de réception."
            editeur.lignes.isEmpty() -> erreur = "Ajoutez au moins un produit."
            else -> confirmationOuverte = true
        }
    }

    fun fermer() {
        confirmationOuverte = false
    }

    fun valider() {
        val fournisseur = fournisseurId ?: return
        val depot = depotId.value ?: return
        enCours = true
        viewModelScope.launch {
            try {
                termine = appro.validerReception(commandeId, fournisseur, depot, reference, editeur.lignes.map { it.versAchat() })
            } catch (e: RegleMetierException) {
                erreur = e.message
                confirmationOuverte = false
            } finally {
                enCours = false
            }
        }
    }

    companion object {
        fun factory(commandeId: String?) = viewModelFactory {
            initializer {
                ReceptionFormViewModel(commandeId, container.approRepository, container.stockRepository, container.lieuRepository, container.photoManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceptionFormScreen(
    commandeId: String?,
    onRetour: () -> Unit,
    onValidee: (receptionId: String) -> Unit,
    vm: ReceptionFormViewModel = viewModel(key = "reception_form_$commandeId", factory = ReceptionFormViewModel.factory(commandeId)),
) {
    val fournisseurs by vm.fournisseurs.collectAsStateWithLifecycle()
    val depots by vm.depots.collectAsStateWithLifecycle()
    val depotId by vm.depotId.collectAsStateWithLifecycle()
    val produits by vm.produits.collectAsStateWithLifecycle()
    val conversions by vm.conversions.collectAsStateWithLifecycle()
    val editeur = vm.editeur
    val commande = vm.commande
    val total = editeur.lignes.sumOf { it.montant }

    LaunchedEffect(vm.termine) { vm.termine?.let(onValidee) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Réception")
                        commande?.let { Text("Commande ${it.commande.numero}", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            BarreTotal(total = total, erreur = vm.erreur, libelle = "Valider la réception", actif = !vm.enCours, onValider = vm::demanderValidation)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (commande != null) {
                        LigneInfo("Fournisseur", commande.fournisseurNom)
                        LigneInfo("Dépôt", commande.depotNom)
                        Text(
                            "Les quantités restant à recevoir sont proposées : corrigez-les selon ce qui est réellement livré, " +
                                "retirez les produits absents.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Selecteur(
                            libelle = "Fournisseur",
                            valeurAffichee = fournisseurs.firstOrNull { it.id == vm.fournisseurId }?.nom.orEmpty(),
                            options = fournisseurs,
                            texte = { it.nom },
                            onSelection = { vm.fournisseurId = it.id },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Selecteur(
                            libelle = "Dépôt de réception",
                            valeurAffichee = depots.firstOrNull { it.id == depotId }?.nom.orEmpty(),
                            options = depots,
                            texte = { it.nom },
                            onSelection = { vm.depotId.value = it.id },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ChampTexte(vm.reference, { vm.reference = it }, "N° de facture ou de bon de livraison (facultatif)", Modifier.fillMaxWidth())
                }
            }
            lignesEditables(
                editeur = editeur,
                avecMontant = true,
                fichierPhoto = vm::fichierPhoto,
                peutAjouter = depotId != null,
                messageSansDepot = "Choisissez le dépôt de réception pour ajouter des produits.",
            )
        }
    }

    DialoguesLignes(
        editeur = editeur,
        produits = produits,
        conversions = conversions,
        avecPrix = true,
        libelleStock = "En stock",
        fichierPhoto = vm::fichierPhoto,
    )

    if (vm.confirmationOuverte) {
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Valider la réception ?") },
            text = {
                Text(
                    "${editeur.lignes.size} produit(s) entreront en stock pour ${Montant.formater(total)}, " +
                        "à payer au fournisseur. Une réception validée ne peut plus être modifiée."
                )
            },
            confirmButton = { TextButton(onClick = vm::valider, enabled = !vm.enCours) { Text("Valider") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
        )
    }
}

// --- Fiche d'une réception ---

class ReceptionViewModel(receptionId: String, appro: ApproRepository, private val photos: PhotoManager) : ViewModel() {
    val reception: StateFlow<ReceptionAvecDetails?> = appro.observerReception(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lignes: StateFlow<List<LigneReceptionAvecProduit>> = appro.observerLignesReception(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    companion object {
        fun factory(receptionId: String) = viewModelFactory {
            initializer { ReceptionViewModel(receptionId, container.approRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceptionScreen(
    receptionId: String,
    onRetour: () -> Unit,
    vm: ReceptionViewModel = viewModel(key = "reception_$receptionId", factory = ReceptionViewModel.factory(receptionId)),
) {
    val reception by vm.reception.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val r = reception

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.reception?.numero ?: "Réception") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        if (r == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.fournisseurNom, style = MaterialTheme.typography.titleLarge)
                    LigneInfo("Date", formaterDateHeure(r.reception.date))
                    LigneInfo("Dépôt", r.depotNom)
                    r.commandeNumero?.let { LigneInfo("Commande", it) }
                    r.reception.reference?.let { LigneInfo("Référence", it) }
                    r.auteurNom?.let { LigneInfo("Saisie par", it) }
                    LigneInfo("Montant", Montant.formater(r.reception.montantTotal))
                }
            }
            item { TitreSection("Produits reçus") }
            items(lignes, key = { it.ligne.id }) { l ->
                LigneDocument(
                    nom = l.nom,
                    photo = vm.fichierPhoto(l.photoChemin),
                    quantite = "${Quantite.formater(l.ligne.quantiteMilli)} ${l.uniteSymbole} × ${Montant.formater(l.ligne.prixUnitaire)}",
                    detail = null,
                    montant = l.ligne.montant,
                )
            }
            item {
                Text(
                    "Paiement du fournisseur : disponible dans une prochaine version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                )
            }
        }
    }
}

