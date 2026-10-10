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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.appro.ApproRepository
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.LigneTransfertAvecProduit
import mg.tsena.data.db.entity.StatutTransfert
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.data.db.entity.TransfertAvecDepots
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File

// --- Liste des transferts ---

class TransfertsViewModel(appro: ApproRepository) : ViewModel() {
    val transferts: StateFlow<List<TransfertAvecDepots>> = appro.observerTransferts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = viewModelFactory {
            initializer { TransfertsViewModel(container.approRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfertsScreen(
    onRetour: () -> Unit,
    onNouveau: () -> Unit,
    onOuvrir: (transfertId: String) -> Unit,
    vm: TransfertsViewModel = viewModel(factory = TransfertsViewModel.Factory),
) {
    val transferts by vm.transferts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transferts entre dépôts") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNouveau) { Icon(Icons.Filled.Add, contentDescription = "Nouveau transfert") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (transferts.isEmpty()) {
                item { Text("Aucun transfert. Appuyez sur + pour déplacer du stock d'un dépôt à un autre.", Modifier.padding(16.dp)) }
            }
            items(transferts, key = { it.transfert.id }) { t ->
                ListItem(
                    overlineContent = { Text("${t.transfert.numero} · ${formaterDateHeure(t.transfert.date)}") },
                    headlineContent = { Text("${t.sourceNom} → ${t.destinationNom}") },
                    supportingContent = { Text("${t.nbLignes} produit(s)") },
                    trailingContent = { TexteStatutTransfert(t.transfert.statut) },
                    modifier = Modifier.clickable { onOuvrir(t.transfert.id) },
                )
            }
        }
    }
}

@Composable
private fun TexteStatutTransfert(statut: StatutTransfert) {
    Text(
        statut.libelle,
        style = MaterialTheme.typography.labelMedium,
        color = if (statut == StatutTransfert.EN_TRANSIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Envoi d'un transfert ---

@OptIn(ExperimentalCoroutinesApi::class)
class TransfertFormViewModel(
    private val appro: ApproRepository,
    stock: StockRepository,
    lieux: LieuRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val depots: StateFlow<List<DepotEntity>> = lieux.observerDepots()
        .map { liste -> liste.filter { it.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conversions: StateFlow<Map<String, List<ConversionAvecUnite>>> = appro.observerConversions()
        .map { liste -> liste.groupBy { it.conversion.produitId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val sourceId = MutableStateFlow<String?>(null)
    /** Produits disponibles dans le dépôt source */
    val produits: StateFlow<List<StockProduit>> = sourceId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else stock.observerStocks(id) }
        .map { liste -> liste.filter { it.quantiteMilli > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val editeur = EditeurLignes()
    var destinationId by mutableStateOf<String?>(null)
    var note by mutableStateOf("")
    var confirmationOuverte by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var enCours by mutableStateOf(false)
        private set
    var termine by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    /** Changer de dépôt source vide les lignes : les stocks disponibles ne sont plus les mêmes. */
    fun choisirSource(id: String) {
        if (id != sourceId.value) editeur.lignes = emptyList()
        sourceId.value = id
    }

    fun demanderEnvoi() {
        erreur = null
        when {
            sourceId.value == null -> erreur = "Choisissez le dépôt de départ."
            destinationId == null -> erreur = "Choisissez le dépôt d'arrivée."
            destinationId == sourceId.value -> erreur = "Le dépôt d'arrivée doit être différent du dépôt de départ."
            editeur.lignes.isEmpty() -> erreur = "Ajoutez au moins un produit."
            else -> confirmationOuverte = true
        }
    }

    fun fermer() {
        confirmationOuverte = false
    }

    fun envoyer() {
        val source = sourceId.value ?: return
        val destination = destinationId ?: return
        enCours = true
        viewModelScope.launch {
            try {
                termine = appro.envoyerTransfert(source, destination, note, editeur.lignes.map { it.versEnvoi() })
            } catch (e: RegleMetierException) {
                erreur = e.message
                confirmationOuverte = false
            } finally {
                enCours = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                TransfertFormViewModel(container.approRepository, container.stockRepository, container.lieuRepository, container.photoManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfertFormScreen(
    onRetour: () -> Unit,
    onEnvoye: (transfertId: String) -> Unit,
    vm: TransfertFormViewModel = viewModel(factory = TransfertFormViewModel.Factory),
) {
    val depots by vm.depots.collectAsStateWithLifecycle()
    val sourceId by vm.sourceId.collectAsStateWithLifecycle()
    val produits by vm.produits.collectAsStateWithLifecycle()
    val conversions by vm.conversions.collectAsStateWithLifecycle()
    val editeur = vm.editeur

    LaunchedEffect(vm.termine) { vm.termine?.let(onEnvoye) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouveau transfert") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            BarreTotal(total = null, erreur = vm.erreur, libelle = "Envoyer", actif = !vm.enCours, onValider = vm::demanderEnvoi)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selecteur(
                        libelle = "Depuis le dépôt",
                        valeurAffichee = depots.firstOrNull { it.id == sourceId }?.nom.orEmpty(),
                        options = depots,
                        texte = { it.nom },
                        onSelection = { vm.choisirSource(it.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Selecteur(
                        libelle = "Vers le dépôt",
                        valeurAffichee = depots.firstOrNull { it.id == vm.destinationId }?.nom.orEmpty(),
                        options = depots.filter { it.id != sourceId },
                        texte = { it.nom },
                        onSelection = { vm.destinationId = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChampTexte(vm.note, { vm.note = it }, "Note (facultatif)", Modifier.fillMaxWidth())
                }
            }
            lignesEditables(
                editeur = editeur,
                avecMontant = false,
                fichierPhoto = vm::fichierPhoto,
                peutAjouter = sourceId != null,
                messageSansDepot = "Choisissez le dépôt de départ pour ajouter des produits.",
            )
        }
    }

    DialoguesLignes(
        editeur = editeur,
        produits = produits,
        conversions = conversions,
        avecPrix = false,
        libelleStock = "Disponible",
        fichierPhoto = vm::fichierPhoto,
    )

    if (vm.confirmationOuverte) {
        val source = depots.firstOrNull { it.id == sourceId }?.nom.orEmpty()
        val destination = depots.firstOrNull { it.id == vm.destinationId }?.nom.orEmpty()
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Envoyer le transfert ?") },
            text = {
                Text(
                    "${editeur.lignes.size} produit(s) sortiront du stock de « $source ». Ils entreront dans « $destination » " +
                        "quand la réception y sera confirmée."
                )
            },
            confirmButton = { TextButton(onClick = vm::envoyer, enabled = !vm.enCours) { Text("Envoyer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
        )
    }
}

// --- Fiche d'un transfert ---

class TransfertViewModel(
    private val transfertId: String,
    private val appro: ApproRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val transfert: StateFlow<TransfertAvecDepots?> = appro.observerTransfert(transfertId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lignes: StateFlow<List<LigneTransfertAvecProduit>> = appro.observerLignesTransfert(transfertId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Dialogue { RECEVOIR, ANNULER }

    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun ouvrir(d: Dialogue) {
        erreur = null
        dialogue = d
    }

    fun fermer() {
        dialogue = null
    }

    /** [saisies] : quantité reçue saisie par id de ligne. */
    fun recevoir(saisies: Map<String, String>, motif: String) {
        val quantites = mutableMapOf<String, Long>()
        lignes.value.forEach { l ->
            val texte = saisies[l.ligne.id] ?: return@forEach
            quantites[l.ligne.id] = Quantite.parser(texte, l.uniteDecimale) ?: run {
                erreur = "Quantité reçue invalide pour « ${l.nom} »."
                return
            }
        }
        executer {
            appro.recevoirTransfert(transfertId, quantites, motif)
            dialogue = null
        }
    }

    fun annuler() {
        executer {
            appro.annulerTransfert(transfertId)
            dialogue = null
        }
    }

    private fun executer(action: suspend () -> Unit) {
        erreur = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        fun factory(transfertId: String) = viewModelFactory {
            initializer { TransfertViewModel(transfertId, container.approRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfertScreen(
    transfertId: String,
    onRetour: () -> Unit,
    vm: TransfertViewModel = viewModel(key = "transfert_$transfertId", factory = TransfertViewModel.factory(transfertId)),
) {
    val transfert by vm.transfert.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val t = transfert
    val enTransit = t?.transfert?.statut == StatutTransfert.EN_TRANSIT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.transfert?.numero ?: "Transfert") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            if (enTransit) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.ouvrir(TransfertViewModel.Dialogue.ANNULER) }, modifier = Modifier.weight(1f)) {
                        Text("Annuler")
                    }
                    Button(onClick = { vm.ouvrir(TransfertViewModel.Dialogue.RECEVOIR) }, modifier = Modifier.weight(1f)) {
                        Text("Réceptionner")
                    }
                }
            }
        },
    ) { padding ->
        if (t == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${t.sourceNom} → ${t.destinationNom}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TexteStatutTransfert(t.transfert.statut)
                    }
                    LigneInfo("Envoyé le", formaterDateHeure(t.transfert.date))
                    t.transfert.dateReception?.let { LigneInfo("Reçu le", formaterDateHeure(it)) }
                    t.transfert.note?.let { LigneInfo("Note", it) }
                    t.transfert.motifEcart?.let { LigneInfo("Écart", it) }
                }
            }
            item { TitreSection("Produits") }
            items(lignes, key = { it.ligne.id }) { l ->
                val recue = l.ligne.quantiteRecueMilli
                val manque = recue?.let { l.ligne.quantiteEnvoyeeMilli - it } ?: 0L
                LigneDocument(
                    nom = l.nom,
                    photo = vm.fichierPhoto(l.photoChemin),
                    quantite = "Envoyé : ${Quantite.formater(l.ligne.quantiteEnvoyeeMilli)} ${l.uniteSymbole}",
                    detail = recue?.let { "Reçu : ${Quantite.formater(it)} ${l.uniteSymbole}" },
                    montant = null,
                    complement = if (manque > 0) "Manque : ${Quantite.formater(manque)} ${l.uniteSymbole}" else null,
                )
            }
        }
    }

    when (vm.dialogue) {
        TransfertViewModel.Dialogue.RECEVOIR -> ReceptionTransfertDialog(lignes, vm.erreur, vm::recevoir, vm::fermer)
        TransfertViewModel.Dialogue.ANNULER -> AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Annuler le transfert ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Les produits reviennent dans le stock de « ${t?.sourceNom.orEmpty()} ».")
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::annuler) { Text("Annuler le transfert") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
        )
        null -> Unit
    }
}

/** Quantités réellement reçues (proposées : tout l'envoi) et raison d'un éventuel manque (RG-T3). */
@Composable
private fun ReceptionTransfertDialog(
    lignes: List<LigneTransfertAvecProduit>,
    erreur: String?,
    onValider: (saisies: Map<String, String>, motif: String) -> Unit,
    onAnnuler: () -> Unit,
) {
    val saisies = remember(lignes) {
        mutableStateMapOf<String, String>().apply {
            lignes.forEach { put(it.ligne.id, Quantite.formater(it.ligne.quantiteEnvoyeeMilli)) }
        }
    }
    var motif by rememberSaveable { mutableStateOf("") }
    val avecManque = lignes.any { l ->
        val saisie = saisies[l.ligne.id]?.let { Quantite.parser(it, l.uniteDecimale) }
        saisie != null && saisie < l.ligne.quantiteEnvoyeeMilli
    }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Réceptionner le transfert") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Corrigez les quantités s'il manque de la marchandise.", style = MaterialTheme.typography.bodySmall)
                lignes.forEach { l ->
                    ChampTexte(
                        saisies[l.ligne.id].orEmpty(),
                        { saisies[l.ligne.id] = it },
                        "${l.nom} (${l.uniteSymbole}, envoyé : ${Quantite.formater(l.ligne.quantiteEnvoyeeMilli)})",
                        Modifier.fillMaxWidth(),
                        if (l.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number,
                    )
                }
                if (avecManque) {
                    ChampTexte(motif, { motif = it }, "Raison du manque (casse, vol…)", Modifier.fillMaxWidth())
                    Text("Le manque sera enregistré en perte au dépôt d'arrivée.", style = MaterialTheme.typography.bodySmall)
                }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(saisies.toMap(), if (avecManque) motif else "") }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Retour") } },
    )
}
