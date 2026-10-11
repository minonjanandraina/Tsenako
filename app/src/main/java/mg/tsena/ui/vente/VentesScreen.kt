package mg.tsena.ui.vente

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.credit.CreditRepository
import mg.tsena.credit.EtatCreditVente
import mg.tsena.data.db.entity.LigneVenteAvecProduit
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.PaiementVenteEntity
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.RetourVenteEntity
import mg.tsena.data.db.entity.StatutSession
import mg.tsena.data.db.entity.StatutVente
import mg.tsena.data.db.entity.VenteAvecDetails
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import mg.tsena.vente.VenteRepository

// --- Historique des ventes ---

/** [pointVenteImpose] : point de vente du vendeur (RG-X2) ; null = l'utilisateur voit tous les points de vente. */
@OptIn(ExperimentalCoroutinesApi::class)
class VentesViewModel(pointVenteImpose: String?, repository: VenteRepository) : ViewModel() {
    val pointsVente: StateFlow<List<PointVenteAvecDepot>> = repository.observerPointsVente()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null = tous */
    val filtre = MutableStateFlow(pointVenteImpose)

    val ventes: StateFlow<List<VenteAvecDetails>> = filtre
        .flatMapLatest { id -> repository.observerVentes(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(pointVenteImpose: String?) = viewModelFactory {
            initializer { VentesViewModel(pointVenteImpose, container.venteRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VentesScreen(
    /** Point de vente du vendeur, ou null s'il voit tous les points de vente */
    pointVenteImpose: String?,
    onRetour: () -> Unit,
    onOuvrir: (venteId: String) -> Unit,
    vm: VentesViewModel = viewModel(factory = VentesViewModel.factory(pointVenteImpose)),
) {
    val pointsVente by vm.pointsVente.collectAsStateWithLifecycle()
    val filtre by vm.filtre.collectAsStateWithLifecycle()
    val ventes by vm.ventes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ventes") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (pointVenteImpose == null && pointsVente.size > 1) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = filtre == null, onClick = { vm.filtre.value = null }, label = { Text("Tous") })
                        pointsVente.forEach { pv ->
                            FilterChip(
                                selected = filtre == pv.pointVente.id,
                                onClick = { vm.filtre.value = pv.pointVente.id },
                                label = { Text(pv.pointVente.nom) },
                            )
                        }
                    }
                }
            }
            if (ventes.isEmpty()) item { Text("Aucune vente.", Modifier.padding(16.dp)) }
            items(ventes, key = { it.vente.id }) { v ->
                val annulee = v.vente.statut == StatutVente.ANNULEE
                ListItem(
                    overlineContent = { Text("${v.vente.numero} · ${formaterDateHeure(v.vente.date)}") },
                    headlineContent = { Text(v.clientNom ?: "Vente ${v.vente.typeVente.libelle.lowercase()}") },
                    supportingContent = { Text(listOfNotNull(v.pointVenteNom, v.vendeurNom).joinToString(" · ")) },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Montant.formater(v.vente.totalNet), style = MaterialTheme.typography.titleSmall)
                            if (annulee) Text("Annulée", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.clickable { onOuvrir(v.vente.id) },
                )
            }
        }
    }
}

// --- Fiche d'une vente (ticket) ---

class VenteDetailViewModel(
    private val venteId: String,
    private val ventes: VenteRepository,
    private val photos: PhotoManager,
    credits: CreditRepository,
) : ViewModel() {
    /** Suivi du crédit de cette vente (null si elle n'est pas à crédit) */
    val credit: StateFlow<EtatCreditVente?> = credits.observerCredits()
        .map { liste -> liste.flatMap { it.ventes }.firstOrNull { it.venteId == venteId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val vente: StateFlow<VenteAvecDetails?> = ventes.observerVente(venteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lignes: StateFlow<List<LigneVenteAvecProduit>> = ventes.observerLignesVente(venteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val paiements: StateFlow<List<PaiementVenteEntity>> = ventes.observerPaiements(venteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val retours: StateFlow<List<RetourVenteEntity>> = ventes.observerRetours(venteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Dialogue { ANNULER, RETOUR }

    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val _partage = Channel<Intent>(Channel.BUFFERED)
    val partage: Flow<Intent> = _partage.receiveAsFlow()

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun ouvrir(d: Dialogue) {
        erreur = null
        message = null
        dialogue = d
    }

    fun fermer() {
        dialogue = null
    }

    fun annuler(motif: String) {
        executer {
            ventes.annulerVente(venteId, motif)
            message = "Vente annulée : stock et caisse corrigés."
        }
    }

    fun retourner(saisies: Map<String, String>, motif: String, mode: ModePaiement) {
        val quantites = mutableMapOf<String, Long>()
        lignes.value.forEach { l ->
            val texte = saisies[l.ligne.id]?.takeIf { it.isNotBlank() } ?: return@forEach
            quantites[l.ligne.id] = Quantite.parser(texte, l.uniteDecimale) ?: run {
                erreur = "Quantité invalide pour « ${l.nom} »."
                return
            }
        }
        executer {
            val montant = ventes.retourner(venteId, quantites, motif, mode)
            message = "Retour enregistré : ${Montant.formater(montant)} " +
                if (mode == ModePaiement.CREDIT) "déduits du crédit du client." else "à rendre au client (${mode.libelle.lowercase()})."
        }
    }

    fun partager(nomCommerce: String) {
        val v = vente.value ?: return
        val texte = texteTicket(
            nomCommerce = nomCommerce,
            numero = v.vente.numero,
            date = formaterDateHeure(v.vente.date),
            pointVente = v.pointVenteNom,
            client = v.clientNom,
            lignes = lignes.value.map { l ->
                Triple(l.nom, "${Quantite.formater(l.ligne.quantiteMilli)} ${l.uniteSymbole} × ${Montant.formater(l.ligne.prixUnitaire)}", l.ligne.montant)
            },
            totalBrut = v.vente.totalBrut,
            remise = v.vente.remise,
            totalNet = v.vente.totalNet,
            paiements = paiements.value.map { it.mode.libelle to it.montant },
        )
        val intent = v.clientTelephone?.let { tel ->
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${tel.removePrefix("+")}?text=${Uri.encode(texte)}"))
        } ?: Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texte), "Envoyer le ticket")
        viewModelScope.launch { _partage.send(intent) }
    }

    private fun executer(action: suspend () -> Unit) {
        erreur = null
        viewModelScope.launch {
            try {
                action()
                dialogue = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        fun factory(venteId: String) = viewModelFactory {
            initializer { VenteDetailViewModel(venteId, container.venteRepository, container.photoManager, container.creditRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenteDetailScreen(
    venteId: String,
    nomCommerce: String,
    peutAnnuler: Boolean,
    onRetour: () -> Unit,
    vm: VenteDetailViewModel = viewModel(key = "vente_detail_$venteId", factory = VenteDetailViewModel.factory(venteId)),
) {
    val vente by vm.vente.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val paiements by vm.paiements.collectAsStateWithLifecycle()
    val retours by vm.retours.collectAsStateWithLifecycle()
    val credit by vm.credit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.partage.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(intent, "Envoyer le ticket"))
            }
        }
    }
    val v = vente

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(v?.vente?.numero ?: "Vente") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { vm.partager(nomCommerce) }) { Icon(Icons.Filled.Share, contentDescription = "Envoyer le ticket") }
                },
            )
        },
    ) { padding ->
        if (v == null) return@Scaffold
        val validee = v.vente.statut == StatutVente.VALIDEE
        val resteARetourner = lignes.any { it.ligne.quantiteMilli > it.ligne.quantiteRetourneeMilli }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Info("Date", formaterDateHeure(v.vente.date))
                    Info("Point de vente", v.pointVenteNom)
                    Info("Type", v.vente.typeVente.libelle)
                    v.clientNom?.let { Info("Client", it) }
                    v.vendeurNom?.let { Info("Vendeur", it) }
                    if (!validee) {
                        Text(
                            "Annulée le ${v.vente.dateAnnulation?.let(::formaterDateHeure).orEmpty()} : ${v.vente.motifAnnulation.orEmpty()}",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            item { Titre("Articles") }
            items(lignes, key = { it.ligne.id }) { l ->
                ListItem(
                    headlineContent = { Text(l.nom) },
                    supportingContent = {
                        Column {
                            Text("${Quantite.formater(l.ligne.quantiteMilli)} ${l.uniteSymbole} × ${Montant.formater(l.ligne.prixUnitaire)}")
                            if (l.ligne.quantiteRetourneeMilli > 0) {
                                Text(
                                    "Retourné : ${Quantite.formater(l.ligne.quantiteRetourneeMilli)} ${l.uniteSymbole}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    leadingContent = { PhotoProduit(vm.fichierPhoto(l.photoChemin), Modifier.size(48.dp)) },
                    trailingContent = { Text(Montant.formater(l.ligne.montant), style = MaterialTheme.typography.titleSmall) },
                )
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (v.vente.remise > 0) {
                        Info("Total", Montant.formater(v.vente.totalBrut))
                        Info("Remise", "−" + Montant.formater(v.vente.remise))
                    }
                    Info("Net payé", Montant.formater(v.vente.totalNet))
                    paiements.forEach { p -> Info(p.mode.libelle, Montant.formater(p.montant) + (p.reference?.let { " ($it)" } ?: "")) }
                    credit?.takeIf { validee }?.let { e ->
                        if (e.retours > 0) Info("Retours sur le crédit", "−" + Montant.formater(e.retours))
                        Info("Remboursé", Montant.formater(e.rembourse))
                        Text(
                            if (e.reste > 0) "Reste à payer : ${Montant.formater(e.reste)} (depuis ${e.ageJours} jour(s))" else "Crédit soldé",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (e.reste > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (retours.isNotEmpty()) {
                item { Titre("Retours") }
                items(retours, key = { it.id }) { r ->
                    ListItem(
                        headlineContent = { Text("${r.numero} · ${r.motif}") },
                        supportingContent = { Text("${formaterDateHeure(r.date)} · ${r.modeRemboursement.libelle}") },
                        trailingContent = { Text("−" + Montant.formater(r.montant)) },
                    )
                }
            }
            if (validee) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (resteARetourner) {
                            OutlinedButton(onClick = { vm.ouvrir(VenteDetailViewModel.Dialogue.RETOUR) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Retour d'articles")
                            }
                        }
                        // RG-V6 : annulation tant que la caisse de la vente est ouverte et sans retour
                        if (peutAnnuler && v.sessionStatut == StatutSession.OUVERTE && retours.isEmpty()) {
                            OutlinedButton(onClick = { vm.ouvrir(VenteDetailViewModel.Dialogue.ANNULER) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Annuler la vente", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    when (vm.dialogue) {
        VenteDetailViewModel.Dialogue.ANNULER -> {
            var motif by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = vm::fermer,
                title = { Text("Annuler la vente ?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Les articles reviennent en stock et l'argent encaissé sort de la caisse. La vente reste visible, annulée.")
                        ChampTexte(motif, { motif = it }, "Motif", Modifier.fillMaxWidth())
                        vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.annuler(motif) }) { Text("Annuler la vente") } },
                dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
            )
        }
        VenteDetailViewModel.Dialogue.RETOUR -> RetourDialog(
            lignes = lignes,
            avecClient = v?.vente?.clientId != null,
            erreur = vm.erreur,
            onValider = vm::retourner,
            onAnnuler = vm::fermer,
        )
        null -> Unit
    }
}

@Composable
private fun Info(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(valeur, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun RetourDialog(
    lignes: List<LigneVenteAvecProduit>,
    avecClient: Boolean,
    erreur: String?,
    onValider: (saisies: Map<String, String>, motif: String, mode: ModePaiement) -> Unit,
    onAnnuler: () -> Unit,
) {
    val saisies = remember { mutableStateMapOf<String, String>() }
    var motif by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ModePaiement.ESPECES) }
    val modes = listOfNotNull(ModePaiement.ESPECES, ModePaiement.MOBILE_MONEY, if (avecClient) ModePaiement.CREDIT else null)

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Retour d'articles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quantités rapportées par le client (laisser vide si aucune). Les articles reviennent en stock.", style = MaterialTheme.typography.bodySmall)
                lignes.filter { it.ligne.quantiteMilli > it.ligne.quantiteRetourneeMilli }.forEach { l ->
                    val reste = l.ligne.quantiteMilli - l.ligne.quantiteRetourneeMilli
                    ChampTexte(
                        saisies[l.ligne.id].orEmpty(),
                        { saisies[l.ligne.id] = it },
                        "${l.nom} (au plus ${Quantite.formater(reste)} ${l.uniteSymbole})",
                        Modifier.fillMaxWidth(),
                        if (l.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number,
                    )
                }
                ChampTexte(motif, { motif = it }, "Motif (défaut, erreur…)", Modifier.fillMaxWidth())
                Text("Remboursement", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            label = { Text(if (m == ModePaiement.CREDIT) "Sur le crédit" else m.libelle) },
                        )
                    }
                }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(saisies.toMap(), motif, mode) }) { Text("Valider le retour") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
