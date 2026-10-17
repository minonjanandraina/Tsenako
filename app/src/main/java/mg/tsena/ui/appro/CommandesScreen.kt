package mg.tsena.ui.appro

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.appro.ApproRepository
import mg.tsena.appro.Approvisionnement
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.CommandeAvecDetails
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.data.db.entity.LigneCommandeAvecProduit
import mg.tsena.data.db.entity.ReceptionAvecDetails
import mg.tsena.data.db.entity.StatutCommande
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

// --- Liste des commandes ---

class CommandesViewModel(appro: ApproRepository) : ViewModel() {
    val commandes: StateFlow<List<CommandeAvecDetails>> = appro.observerCommandes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = viewModelFactory {
            initializer { CommandesViewModel(container.approRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandesScreen(
    onRetour: () -> Unit,
    onNouvelle: () -> Unit,
    onOuvrir: (commandeId: String) -> Unit,
    vm: CommandesViewModel = viewModel(factory = CommandesViewModel.Factory),
) {
    val commandes by vm.commandes.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Commandes fournisseurs") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNouvelle) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle commande") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (commandes.isEmpty()) {
                item { Text("Aucune commande. Appuyez sur + pour commander à un fournisseur.", Modifier.padding(16.dp)) }
            }
            items(commandes, key = { it.commande.id }) { c ->
                ListItem(
                    overlineContent = { Text("${c.commande.numero} · ${formaterDateHeure(c.commande.date)}") },
                    headlineContent = { Text(c.fournisseurNom) },
                    supportingContent = { Text("${c.nbLignes} produit(s) · ${c.depotNom}") },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Montant.formater(c.montantTotal), style = MaterialTheme.typography.titleSmall)
                            TexteStatutCommande(c.commande.statut)
                        }
                    },
                    modifier = Modifier.clickable { onOuvrir(c.commande.id) },
                )
            }
        }
    }
}

@Composable
fun TexteStatutCommande(statut: StatutCommande) {
    Text(
        statut.libelle,
        style = MaterialTheme.typography.labelMedium,
        color = when (statut) {
            StatutCommande.BROUILLON, StatutCommande.ENVOYEE, StatutCommande.PARTIELLE -> MaterialTheme.colorScheme.primary
            StatutCommande.RECUE, StatutCommande.ANNULEE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

// --- Saisie d'une commande (création ou brouillon) ---

@OptIn(ExperimentalCoroutinesApi::class)
class CommandeFormViewModel(
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
    var fournisseurId by mutableStateOf<String?>(null)
    var note by mutableStateOf("")
    var numero by mutableStateOf<String?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var enCours by mutableStateOf(false)
        private set
    /** Id de la commande enregistrée */
    var termine by mutableStateOf<String?>(null)
        private set

    init {
        if (commandeId != null) {
            viewModelScope.launch {
                val commande = appro.getCommande(commandeId) ?: return@launch
                numero = commande.numero
                fournisseurId = commande.fournisseurId
                depotId.value = commande.depotId
                note = commande.note.orEmpty()
                editeur.lignes = appro.lignesCommande(commandeId).map { LigneSaisie.depuis(it, it.ligne.quantiteMilli, it.ligne.montant) }
            }
        }
    }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun enregistrer() {
        val fournisseur = fournisseurId ?: run { erreur = "Choisissez un fournisseur."; return }
        val depot = depotId.value ?: run { erreur = "Choisissez le dépôt de livraison."; return }
        enCours = true
        erreur = null
        viewModelScope.launch {
            try {
                termine = appro.enregistrerCommande(commandeId, fournisseur, depot, note, editeur.lignes.map { it.versAchat() })
            } catch (e: RegleMetierException) {
                erreur = e.message
            } finally {
                enCours = false
            }
        }
    }

    companion object {
        fun factory(commandeId: String?) = viewModelFactory {
            initializer {
                CommandeFormViewModel(commandeId, container.approRepository, container.stockRepository, container.lieuRepository, container.photoManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandeFormScreen(
    commandeId: String?,
    onRetour: () -> Unit,
    onEnregistree: (commandeId: String) -> Unit,
    vm: CommandeFormViewModel = viewModel(key = "commande_form_$commandeId", factory = CommandeFormViewModel.factory(commandeId)),
) {
    val fournisseurs by vm.fournisseurs.collectAsStateWithLifecycle()
    val depots by vm.depots.collectAsStateWithLifecycle()
    val depotId by vm.depotId.collectAsStateWithLifecycle()
    val produits by vm.produits.collectAsStateWithLifecycle()
    val conversions by vm.conversions.collectAsStateWithLifecycle()
    val editeur = vm.editeur

    LaunchedEffect(vm.termine) { vm.termine?.let(onEnregistree) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.numero?.let { "Commande $it" } ?: "Nouvelle commande") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            BarreTotal(
                total = editeur.lignes.sumOf { it.montant },
                erreur = vm.erreur,
                libelle = "Enregistrer le brouillon",
                actif = !vm.enCours,
                onValider = vm::enregistrer,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Selecteur(
                        libelle = "Fournisseur",
                        valeurAffichee = fournisseurs.firstOrNull { it.id == vm.fournisseurId }?.nom.orEmpty(),
                        options = fournisseurs,
                        texte = { it.nom },
                        onSelection = { vm.fournisseurId = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Selecteur(
                        libelle = "Dépôt de livraison",
                        valeurAffichee = depots.firstOrNull { it.id == depotId }?.nom.orEmpty(),
                        options = depots,
                        texte = { it.nom },
                        onSelection = { vm.depotId.value = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChampTexte(vm.note, { vm.note = it }, "Note (facultatif)", Modifier.fillMaxWidth())
                }
            }
            lignesEditables(
                editeur = editeur,
                avecMontant = true,
                fichierPhoto = vm::fichierPhoto,
                peutAjouter = depotId != null,
                messageSansDepot = "Choisissez le dépôt de livraison pour ajouter des produits.",
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
}

// --- Fiche d'une commande ---

class CommandeViewModel(
    private val commandeId: String,
    private val appro: ApproRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val commande: StateFlow<CommandeAvecDetails?> = appro.observerCommande(commandeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lignes: StateFlow<List<LigneCommandeAvecProduit>> = appro.observerLignesCommande(commandeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val receptions: StateFlow<List<ReceptionAvecDetails>> = appro.observerReceptionsCommande(commandeId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Confirmation { ANNULER, SOLDER }

    var confirmation by mutableStateOf<Confirmation?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    private val _partage = Channel<Intent>(Channel.BUFFERED)
    val partage: Flow<Intent> = _partage.receiveAsFlow()

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun demander(c: Confirmation) {
        erreur = null
        confirmation = c
    }

    fun fermer() {
        confirmation = null
    }

    fun confirmer() {
        val c = confirmation ?: return
        executer {
            when (c) {
                Confirmation.ANNULER -> appro.annulerCommande(commandeId)
                Confirmation.SOLDER -> appro.solderCommande(commandeId)
            }
            confirmation = null
        }
    }

    /** Partage le bon de commande (WhatsApp du fournisseur si son numéro est connu) ; un brouillon passe à « envoyée ». */
    fun envoyer(nomCommerce: String) {
        val c = commande.value ?: return
        val texte = texteBonCommande(nomCommerce, c, lignes.value)
        executer {
            if (c.commande.statut == StatutCommande.BROUILLON) appro.marquerCommandeEnvoyee(commandeId)
            _partage.send(intentPartage(texte, c.fournisseurTelephone))
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
        fun factory(commandeId: String) = viewModelFactory {
            initializer { CommandeViewModel(commandeId, container.approRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandeScreen(
    commandeId: String,
    nomCommerce: String,
    onRetour: () -> Unit,
    onModifier: () -> Unit,
    onRecevoir: () -> Unit,
    onReception: (receptionId: String) -> Unit,
    vm: CommandeViewModel = viewModel(key = "commande_$commandeId", factory = CommandeViewModel.factory(commandeId)),
) {
    val commande by vm.commande.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val receptions by vm.receptions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.partage.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(intent, "Envoyer la commande"))
            }
        }
    }

    val c = commande
    val statut = c?.commande?.statut
    val recevable = statut in setOf(StatutCommande.BROUILLON, StatutCommande.ENVOYEE, StatutCommande.PARTIELLE)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(c?.commande?.numero ?: "Commande") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (statut == StatutCommande.ENVOYEE || statut == StatutCommande.PARTIELLE) {
                        IconButton(onClick = { vm.envoyer(nomCommerce) }) { Icon(Icons.Filled.Share, contentDescription = "Renvoyer au fournisseur") }
                    }
                },
            )
        },
        bottomBar = {
            if (recevable) {
                Button(onClick = onRecevoir, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(if (statut == StatutCommande.PARTIELLE) "Recevoir le reste" else "Recevoir la marchandise")
                }
            }
        },
    ) { padding ->
        if (c == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.fournisseurNom, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TexteStatutCommande(c.commande.statut)
                    }
                    LigneInfo("Date", formaterDateHeure(c.commande.date))
                    LigneInfo("Livraison", c.depotNom)
                    c.commande.note?.let { LigneInfo("Note", it) }
                    LigneInfo("Montant", Montant.formater(c.montantTotal))
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when (c.commande.statut) {
                        StatutCommande.BROUILLON -> {
                            Button(onClick = { vm.envoyer(nomCommerce) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Share, contentDescription = null)
                                Text("  Envoyer au fournisseur")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onModifier, modifier = Modifier.weight(1f)) { Text("Modifier") }
                                OutlinedButton(onClick = { vm.demander(CommandeViewModel.Confirmation.ANNULER) }, modifier = Modifier.weight(1f)) {
                                    Text("Annuler")
                                }
                            }
                        }
                        StatutCommande.ENVOYEE -> OutlinedButton(
                            onClick = { vm.demander(CommandeViewModel.Confirmation.ANNULER) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Annuler la commande") }
                        StatutCommande.PARTIELLE -> OutlinedButton(
                            onClick = { vm.demander(CommandeViewModel.Confirmation.SOLDER) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Solder (le reste ne sera pas livré)") }
                        StatutCommande.RECUE, StatutCommande.ANNULEE -> Unit
                    }
                }
            }
            item { TitreSection("Produits") }
            items(lignes, key = { it.ligne.id }) { l ->
                val suivi = c.commande.statut != StatutCommande.BROUILLON && c.commande.statut != StatutCommande.ANNULEE
                LigneDocument(
                    code = l.code,
                    nom = l.nom,
                    photo = vm.fichierPhoto(l.photoChemin),
                    quantite = "${Quantite.formater(l.ligne.quantiteMilli)} ${l.uniteSymbole} × ${Montant.formater(l.ligne.prixUnitaire)}",
                    detail = null,
                    montant = l.ligne.montant,
                    complement = if (suivi) "Reçu : ${Quantite.formater(l.ligne.quantiteRecueMilli)} ${l.uniteSymbole}" else null,
                )
            }
            if (receptions.isNotEmpty()) {
                item { TitreSection("Réceptions") }
                items(receptions, key = { it.reception.id }) { r ->
                    ListItem(
                        headlineContent = { Text(r.reception.numero) },
                        supportingContent = { Text(formaterDateHeure(r.reception.date)) },
                        trailingContent = { Text(Montant.formater(r.reception.montantTotal), style = MaterialTheme.typography.titleSmall) },
                        modifier = Modifier.clickable { onReception(r.reception.id) },
                    )
                }
            }
        }
    }

    vm.confirmation?.let { conf ->
        val annuler = conf == CommandeViewModel.Confirmation.ANNULER
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text(if (annuler) "Annuler la commande ?" else "Solder la commande ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (annuler) {
                            "La commande sera marquée comme annulée. Pensez à prévenir le fournisseur."
                        } else {
                            "Les quantités restantes ne seront plus attendues : la commande passe à « Reçue »."
                        }
                    )
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmer) { Text(if (annuler) "Annuler la commande" else "Solder") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
        )
    }
}

/** Texte du bon de commande envoyé au fournisseur. */
private fun texteBonCommande(nomCommerce: String, c: CommandeAvecDetails, lignes: List<LigneCommandeAvecProduit>): String =
    buildString {
        appendLine("Bon de commande ${c.commande.numero}")
        appendLine(nomCommerce)
        appendLine("Date : ${formaterDateHeure(c.commande.date)}")
        appendLine("Livraison : ${c.depotNom}")
        appendLine()
        lignes.forEach { l ->
            appendLine(
                "- ${libelleProduit(l.code, l.nom)} : ${Quantite.formater(l.ligne.quantiteMilli)} ${l.uniteSymbole}" +
                    if (l.ligne.montant > 0) " × ${Montant.formater(l.ligne.prixUnitaire)} = ${Montant.formater(l.ligne.montant)}" else ""
            )
        }
        appendLine()
        append("Total : ${Montant.formater(c.montantTotal)}")
        c.commande.note?.let { append("\n$it") }
    }

/** Conversation WhatsApp avec le fournisseur si son numéro est connu, sinon menu de partage. */
private fun intentPartage(texte: String, telephone: String?): Intent =
    if (telephone != null) {
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${telephone.removePrefix("+")}?text=${Uri.encode(texte)}"))
    } else {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texte), "Envoyer la commande")
    }

// --- Éléments communs aux formulaires de documents ---

@Composable
fun TitreSection(titre: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 16.dp))
        Text(titre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    }
}

/** Lignes d'un document en saisie : un appui ouvre la ligne, « Ajouter » ouvre le choix du produit. */
fun androidx.compose.foundation.lazy.LazyListScope.lignesEditables(
    editeur: EditeurLignes,
    avecMontant: Boolean,
    fichierPhoto: (String) -> File,
    peutAjouter: Boolean,
    messageSansDepot: String,
) {
    item {
        Column {
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Produits (${editeur.lignes.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { editeur.choixProduitOuvert = true }, enabled = peutAjouter) { Text("+ Ajouter") }
            }
            if (!peutAjouter) {
                Text(messageSansDepot, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    items(editeur.lignes, key = { it.produitId }) { l ->
        LigneDocument(
            code = l.code,
            nom = l.nom,
            photo = fichierPhoto(l.photoChemin),
            quantite = "${Quantite.formater(l.quantiteMilli)} ${l.uniteSymbole}",
            detail = l.detail,
            montant = if (avecMontant) l.montant else null,
            modifier = Modifier.clickable { editeur.modifier(l) },
        )
    }
}

/** Choix du produit puis saisie de la ligne. */
@Composable
fun DialoguesLignes(
    editeur: EditeurLignes,
    produits: List<StockProduit>,
    conversions: Map<String, List<ConversionAvecUnite>>,
    avecPrix: Boolean,
    libelleStock: String,
    fichierPhoto: (String) -> File,
) {
    if (editeur.choixProduitOuvert) {
        ChoixProduitDialog(produits, libelleStock, fichierPhoto, editeur::choisir) { editeur.choixProduitOuvert = false }
    }
    editeur.enEdition?.let { ligne ->
        val stock = produits.firstOrNull { it.produitId == ligne.produitId }
        LigneDialog(
            ligne = ligne,
            conversions = conversions[ligne.produitId].orEmpty(),
            avecPrix = avecPrix,
            information = stock?.let { "$libelleStock : ${Quantite.formater(it.quantiteMilli)} ${it.uniteSymbole}" },
            onValider = editeur::enregistrer,
            onSupprimer = { editeur.supprimer(ligne.produitId) },
            onAnnuler = editeur::fermer,
        )
    }
}

/** Barre du bas des formulaires : total éventuel, erreur et bouton de validation. */
@Composable
fun BarreTotal(total: Long?, erreur: String?, libelle: String, actif: Boolean, onValider: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        total?.let {
            Row {
                Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(Montant.formater(it), style = MaterialTheme.typography.titleMedium)
            }
        }
        Button(onClick = onValider, enabled = actif, modifier = Modifier.fillMaxWidth()) { Text(libelle) }
    }
}

/** Reliquat d'une ligne de commande, proposé à la réception. */
fun LigneCommandeAvecProduit.versReliquat(): LigneSaisie? {
    val reste = Approvisionnement.reliquat(ligne.quantiteMilli, ligne.quantiteRecueMilli)
    if (reste <= 0) return null
    return LigneSaisie.depuis(this, reste, Approvisionnement.montantAuProrata(ligne.montant, ligne.quantiteMilli, reste))
}
