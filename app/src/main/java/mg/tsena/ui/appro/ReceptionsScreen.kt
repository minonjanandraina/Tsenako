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
import androidx.compose.material3.Checkbox
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
import java.io.File
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
import mg.tsena.appro.LotApproRepository
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.CommandeAvecDetails
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.data.db.entity.LigneReceptionAvecProduit
import mg.tsena.data.db.entity.LigneReceptionRetournable
import mg.tsena.data.db.entity.ReceptionAPayer
import mg.tsena.data.db.entity.ReceptionAvecDetails
import mg.tsena.data.db.entity.RetourFournisseurEntity
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.FinanceRepository
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur

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
    /** Appro dont l'achat fait partie : dépôt imposé, paiement direct possible */
    private val lotId: String?,
    private val appro: ApproRepository,
    private val lots: LotApproRepository,
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
    /** Achat d'un appro : nom pour le titre, et paiement immédiat en espèces depuis le coffre */
    var nomLot by mutableStateOf<String?>(null)
        private set
    var payerDirectement by mutableStateOf(true)
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
        if (lotId != null) {
            viewModelScope.launch {
                val l = lots.observerLot(lotId).first() ?: return@launch
                nomLot = "${l.lot.nom} (${l.lot.numero})"
                depotId.value = l.lot.depotSourceId
            }
        }
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
                val achats = editeur.lignes.map { it.versAchat() }
                termine = if (lotId != null) {
                    lots.ajouterAchat(lotId, fournisseur, reference, achats, payerDirectement)
                } else {
                    appro.validerReception(commandeId, fournisseur, depot, reference, achats)
                }
            } catch (e: RegleMetierException) {
                erreur = e.message
                confirmationOuverte = false
            } finally {
                enCours = false
            }
        }
    }

    companion object {
        fun factory(commandeId: String?, lotId: String?) = viewModelFactory {
            initializer {
                ReceptionFormViewModel(commandeId, lotId, container.approRepository, container.lotApproRepository, container.stockRepository, container.lieuRepository, container.photoManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceptionFormScreen(
    commandeId: String?,
    lotId: String?,
    onRetour: () -> Unit,
    onValidee: (receptionId: String) -> Unit,
    vm: ReceptionFormViewModel = viewModel(
        key = "reception_form_${commandeId}_$lotId",
        factory = ReceptionFormViewModel.factory(commandeId, lotId),
    ),
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
                        Text(if (lotId != null) "Achat de l'appro" else "Réception")
                        vm.nomLot?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                        if (lotId == null) {
                            Selecteur(
                                libelle = "Dépôt de réception",
                                valeurAffichee = depots.firstOrNull { it.id == depotId }?.nom.orEmpty(),
                                options = depots,
                                texte = { it.nom },
                                onSelection = { vm.depotId.value = it.id },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LigneInfo("Dépôt d'achat", depots.firstOrNull { it.id == depotId }?.nom.orEmpty())
                        }
                    }
                    if (lotId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = vm.payerDirectement, onCheckedChange = { vm.payerDirectement = it })
                            Text("Payer le fournisseur tout de suite (espèces du coffre)")
                        }
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

class ReceptionViewModel(
    private val receptionId: String,
    private val appro: ApproRepository,
    finance: FinanceRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val reception: StateFlow<ReceptionAvecDetails?> = appro.observerReception(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lignes: StateFlow<List<LigneReceptionAvecProduit>> = appro.observerLignesReception(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val aPayer: StateFlow<ReceptionAPayer?> = finance.observerReceptionAPayer(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val retours: StateFlow<List<RetourFournisseurEntity>> = appro.observerRetoursFournisseur(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val retournables: StateFlow<List<LigneReceptionRetournable>> = appro.observerLignesRetournables(receptionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var retourOuvert by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun ouvrirRetour() {
        erreur = null
        message = null
        retourOuvert = true
    }

    fun fermerRetour() {
        retourOuvert = false
    }

    fun retourner(saisies: Map<String, String>, motif: String) {
        val quantites = mutableMapOf<String, Long>()
        retournables.value.forEach { l ->
            val texte = saisies[l.ligne.id]?.takeIf { it.isNotBlank() } ?: return@forEach
            quantites[l.ligne.id] = Quantite.parser(texte, l.uniteDecimale) ?: run {
                erreur = "Quantité invalide pour « ${libelleProduit(l.code, l.nom)} »."
                return
            }
        }
        erreur = null
        viewModelScope.launch {
            try {
                val montant = appro.retournerAuFournisseur(receptionId, quantites, motif)
                retourOuvert = false
                message = "Retour enregistré : ${Montant.formater(montant)} déduits de la dette fournisseur."
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        fun factory(receptionId: String) = viewModelFactory {
            initializer {
                ReceptionViewModel(receptionId, container.approRepository, container.financeRepository, container.photoManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceptionScreen(
    receptionId: String,
    peutRetourner: Boolean,
    onRetour: () -> Unit,
    vm: ReceptionViewModel = viewModel(key = "reception_$receptionId", factory = ReceptionViewModel.factory(receptionId)),
) {
    val reception by vm.reception.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val aPayer by vm.aPayer.collectAsStateWithLifecycle()
    val retours by vm.retours.collectAsStateWithLifecycle()
    val retournables by vm.retournables.collectAsStateWithLifecycle()
    val r = reception
    val resteARetourner = retournables.any { it.ligne.quantiteMilli > it.ligne.quantiteRetourneeMilli }

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
                    aPayer?.let { p ->
                        if (p.totalRetours > 0) LigneInfo("Retours", "−" + Montant.formater(p.totalRetours))
                        LigneInfo("Payé", Montant.formater(p.totalPaye))
                        Text(
                            if (p.reste > 0) "Reste à payer : ${Montant.formater(p.reste)}" else "Payée",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (p.reste > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            item { TitreSection("Produits reçus") }
            items(lignes, key = { it.ligne.id }) { l ->
                LigneDocument(
                    code = l.code,
                    nom = l.nom,
                    photo = vm.fichierPhoto(l.photoChemin),
                    quantite = "${Quantite.formater(l.ligne.quantiteMilli)} ${l.uniteSymbole} × ${Montant.formater(l.ligne.prixUnitaire)}",
                    detail = null,
                    montant = l.ligne.montant,
                    complement = if (l.ligne.quantiteRetourneeMilli > 0) {
                        "Retourné : ${Quantite.formater(l.ligne.quantiteRetourneeMilli)} ${l.uniteSymbole}"
                    } else {
                        null
                    },
                )
            }
            if (retours.isNotEmpty()) {
                item { TitreSection("Retours au fournisseur") }
                items(retours, key = { it.id }) { rf ->
                    ListItem(
                        headlineContent = { Text("${rf.numero} · ${rf.motif}") },
                        supportingContent = { Text(formaterDateHeure(rf.date)) },
                        trailingContent = { Text("−" + Montant.formater(rf.montant)) },
                    )
                }
            }
            if (peutRetourner && resteARetourner) {
                item {
                    OutlinedButton(onClick = vm::ouvrirRetour, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Retourner au fournisseur")
                    }
                }
            }
        }
    }

    if (vm.retourOuvert) {
        RetourFournisseurDialog(retournables, vm.erreur, vm::retourner, vm::fermerRetour)
    }
}

@Composable
private fun RetourFournisseurDialog(
    lignes: List<LigneReceptionRetournable>,
    erreur: String?,
    onValider: (saisies: Map<String, String>, motif: String) -> Unit,
    onAnnuler: () -> Unit,
) {
    val saisies = remember { mutableStateMapOf<String, String>() }
    var motif by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Retour au fournisseur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Quantités rendues (laisser vide si aucune). Elles sortent du stock et la dette envers le fournisseur diminue.",
                    style = MaterialTheme.typography.bodySmall,
                )
                lignes.filter { it.ligne.quantiteMilli > it.ligne.quantiteRetourneeMilli }.forEach { l ->
                    val reste = l.ligne.quantiteMilli - l.ligne.quantiteRetourneeMilli
                    ChampTexte(
                        saisies[l.ligne.id].orEmpty(),
                        { saisies[l.ligne.id] = it },
                        "${libelleProduit(l.code, l.nom)} (au plus ${Quantite.formater(reste)} ${l.uniteSymbole})",
                        Modifier.fillMaxWidth(),
                        if (l.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number,
                    )
                }
                ChampTexte(motif, { motif = it }, "Motif (abîmé, erreur de livraison…)", Modifier.fillMaxWidth())
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(saisies.toMap(), motif) }) { Text("Valider le retour") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
