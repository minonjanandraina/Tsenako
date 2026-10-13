package mg.tsena.ui.vente

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.catalogue.Categories
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.credit.CreditClient
import mg.tsena.credit.CreditRepository
import mg.tsena.data.db.entity.ClientAvecCredit
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.ProduitVente
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.data.db.entity.TypeVente
import mg.tsena.domain.AutorisationRequiseException
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.stock.Valorisation
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampPin
import mg.tsena.ui.common.ChampTexte
import mg.tsena.vente.CalculsVente
import mg.tsena.vente.LigneVenteSaisie
import mg.tsena.vente.PaiementSaisi
import mg.tsena.vente.SaisieVente
import mg.tsena.vente.VenteRepository

data class LignePanier(val produit: ProduitVente, val quantiteMilli: Long, val prixUnitaire: Long) {
    val montant: Long get() = Valorisation.valeur(quantiteMilli, prixUnitaire)
}

/** Choix du mode dans le dialogue d'encaissement. */
enum class ChoixPaiement(val libelle: String) {
    ESPECES("Espèces"),
    MOBILE_MONEY("Mobile money"),
    CARTE("Carte"),
    CREDIT("Crédit"),
    MIXTE("Plusieurs"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class VenteViewModel(
    private val pointVenteId: String,
    /** Vente à corriger : le panier en est pré-rempli et la validation remplace l'ancienne vente */
    private val corrigeId: String?,
    private val ventes: VenteRepository,
    private val photos: PhotoManager,
    creditRepository: CreditRepository,
    catalogue: CatalogueRepository,
) : ViewModel() {
    val categories: StateFlow<List<TypeProduitEntity>> = catalogue.observerCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Filtre par type de produit : catégorie principale, puis éventuellement une sous-catégorie (null = tous) */
    var categorieRacine by mutableStateOf<String?>(null)
        private set
    var sousCategorie by mutableStateOf<String?>(null)
        private set

    fun filtrerCategorie(racine: String?) {
        categorieRacine = racine
        sousCategorie = null
    }

    fun filtrerSousCategorie(id: String?) {
        sousCategorie = id
    }

    /** Suivi des crédits par client, pour afficher l'impayé du client choisi */
    val credits: StateFlow<Map<String, CreditClient>> = creditRepository.observerCredits()
        .map { liste -> liste.associateBy { it.client.client.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val pointVente: StateFlow<PointVenteAvecDepot?> = ventes.observerPointsVente()
        .map { liste -> liste.firstOrNull { it.pointVente.id == pointVenteId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val session: StateFlow<SessionAvecDetails?> = ventes.observerSessionOuverte(pointVenteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val catalogue: StateFlow<List<ProduitVente>> = pointVente
        .flatMapLatest { pv -> if (pv == null) flowOf(emptyList()) else ventes.observerCatalogue(pv.pointVente.id, pv.pointVente.depotId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val clients: StateFlow<List<ClientAvecCredit>> = ventes.observerClients()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var type by mutableStateOf(TypeVente.DETAIL)
        private set
    var client by mutableStateOf<ClientAvecCredit?>(null)
        private set
    var panier by mutableStateOf(listOf<LignePanier>())
        private set
    var recherche by mutableStateOf("")
    var choixClientOuvert by mutableStateOf(false)
    var encaissementOuvert by mutableStateOf(false)
        private set
    /** Produit dont on saisit la quantité */
    var quantiteEnSaisie by mutableStateOf<ProduitVente?>(null)
        private set
    /** Vente en attente du code PIN d'un gérant (RG-V5) */
    var enAttenteAutorisation by mutableStateOf<SaisieVente?>(null)
        private set
    var messageAutorisation by mutableStateOf<String?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var enCours by mutableStateOf(false)
        private set
    /** Vente enregistrée : (id, numéro du total) */
    var venteTerminee by mutableStateOf<String?>(null)
        private set

    val totalBrut: Long get() = panier.sumOf { it.montant }

    /** Numéro de la vente corrigée, pour le bandeau */
    var numeroCorrige by mutableStateOf<String?>(null)
        private set

    init {
        if (corrigeId != null) {
            viewModelScope.launch {
                try {
                    val c = ventes.preparerCorrection(corrigeId)
                    numeroCorrige = c.vente.numero
                    type = c.vente.typeVente
                    client = c.clientId?.let { id -> ventes.observerClients().first().firstOrNull { it.client.id == id } }
                    panier = c.lignes.map { (p, q) -> LignePanier(p, q, CalculsVente.prixApplique(type, p.prixDetail, p.prixGros) ?: p.prixDetail) }
                } catch (e: RegleMetierException) {
                    erreur = e.message
                }
            }
        }
    }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun prix(p: ProduitVente): Long? = CalculsVente.prixApplique(type, p.prixDetail, p.prixGros)

    /** RG-V0 : le type ne change plus une fois la première ligne ajoutée. */
    fun choisirType(t: TypeVente) {
        if (panier.isNotEmpty()) {
            message = "Videz le panier pour changer le type de vente."
            return
        }
        type = t
        message = null
    }

    fun choisirClient(c: ClientAvecCredit?) {
        client = c
        choixClientOuvert = false
    }

    /** Appui sur un produit : +1 pour une unité entière, saisie de la quantité sinon. */
    fun ajouter(p: ProduitVente) {
        message = null
        if (prix(p) == null) {
            message = "« ${p.nom} » n'a pas de prix de gros."
            return
        }
        if (p.uniteDecimale) {
            erreur = null
            quantiteEnSaisie = p
            return
        }
        val existante = panier.firstOrNull { it.produit.produitId == p.produitId }
        definirQuantite(p, (existante?.quantiteMilli ?: 0L) + 1000)
    }

    fun modifierQuantite(ligne: LignePanier) {
        erreur = null
        quantiteEnSaisie = ligne.produit
    }

    fun saisirQuantite(texte: String) {
        val p = quantiteEnSaisie ?: return
        val q = Quantite.parser(texte, p.uniteDecimale) ?: run {
            erreur = if (p.uniteDecimale) "Quantité invalide." else "Quantité invalide (nombre entier)."
            return
        }
        definirQuantite(p, q)
        quantiteEnSaisie = null
    }

    fun fermerQuantite() {
        quantiteEnSaisie = null
    }

    /** Quantité 0 = retire la ligne. */
    private fun definirQuantite(p: ProduitVente, quantiteMilli: Long) {
        val prix = prix(p) ?: return
        panier = when {
            quantiteMilli <= 0 -> panier.filterNot { it.produit.produitId == p.produitId }
            panier.any { it.produit.produitId == p.produitId } ->
                panier.map { if (it.produit.produitId == p.produitId) it.copy(quantiteMilli = quantiteMilli) else it }
            else -> panier + LignePanier(p, quantiteMilli, prix)
        }
        if (quantiteMilli > p.stockMilli) message = "Attention : stock de « ${p.nom} » : ${Quantite.formater(p.stockMilli)} ${p.uniteSymbole}."
    }

    fun vider() {
        panier = emptyList()
        message = null
    }

    fun ouvrirEncaissement() {
        erreur = null
        message = null
        when {
            panier.isEmpty() -> message = "Le panier est vide."
            type == TypeVente.GROS && client == null -> {
                message = "Vente en gros : choisissez d'abord le client."
                choixClientOuvert = true
            }
            else -> encaissementOuvert = true
        }
    }

    fun fermerEncaissement() {
        encaissementOuvert = false
    }

    fun encaisser(remise: Long, paiements: List<PaiementSaisi>) {
        valider(
            SaisieVente(
                pointVenteId = pointVenteId,
                type = type,
                clientId = client?.client?.id,
                lignes = panier.map { LigneVenteSaisie(it.produit.produitId, it.quantiteMilli) },
                remise = remise,
                paiements = paiements,
            )
        )
    }

    fun autoriser(pin: String) {
        val saisie = enAttenteAutorisation ?: return
        valider(saisie.copy(pinAutorisation = pin))
    }

    fun annulerAutorisation() {
        enAttenteAutorisation = null
    }

    private fun valider(saisie: SaisieVente) {
        enCours = true
        erreur = null
        viewModelScope.launch {
            try {
                venteTerminee = if (corrigeId != null) {
                    ventes.corrigerVente(corrigeId, saisie, "Erreur de saisie")
                } else {
                    ventes.validerVente(saisie)
                }
                encaissementOuvert = false
                enAttenteAutorisation = null
            } catch (e: AutorisationRequiseException) {
                enAttenteAutorisation = saisie
                messageAutorisation = e.message
            } catch (e: RegleMetierException) {
                erreur = e.message
                enAttenteAutorisation = null
            } finally {
                enCours = false
            }
        }
    }

    /** Après une vente : panier vidé, même type et sans client. */
    fun nouvelleVente() {
        venteTerminee = null
        panier = emptyList()
        client = null
        type = TypeVente.DETAIL
        recherche = ""
    }

    companion object {
        fun factory(pointVenteId: String, corrigeId: String?) = viewModelFactory {
            initializer {
                VenteViewModel(pointVenteId, corrigeId, container.venteRepository, container.photoManager, container.creditRepository, container.catalogueRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenteScreen(
    pointVenteId: String,
    corrigeId: String?,
    onRetour: () -> Unit,
    onTicket: (venteId: String) -> Unit,
    vm: VenteViewModel = viewModel(
        key = "vente_${pointVenteId}_${corrigeId.orEmpty()}",
        factory = VenteViewModel.factory(pointVenteId, corrigeId),
    ),
) {
    val pv by vm.pointVente.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val catalogue by vm.catalogue.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val credits by vm.credits.collectAsStateWithLifecycle()
    var ongletPanier by rememberSaveable { mutableStateOf(false) }

    val categories by vm.categories.collectAsStateWithLifecycle()
    val parentDe = remember(categories) { categories.associate { it.id to it.parentId } }
    // Seuls les articles en stock sont proposés à la vente
    val enStock = remember(catalogue) { catalogue.filter { it.stockMilli > 0 } }
    // Catégories principales et sous-catégories qui ont au moins un article en stock
    val racines = remember(categories, enStock) {
        categories.filter { c -> c.parentId == null && enStock.any { Categories.estDans(it.typeProduitId, c.id, parentDe::get) } }
            .sortedBy { it.nom.lowercase() }
    }
    val sousCategories = remember(categories, enStock, vm.categorieRacine) {
        val racine = vm.categorieRacine ?: return@remember emptyList<TypeProduitEntity>()
        categories.filter { c -> c.parentId == racine && enStock.any { Categories.estDans(it.typeProduitId, c.id, parentDe::get) } }
            .sortedBy { it.nom.lowercase() }
    }
    val filtres = remember(enStock, vm.recherche, vm.categorieRacine, vm.sousCategorie, parentDe) {
        val t = vm.recherche.trim()
        val categorie = vm.sousCategorie ?: vm.categorieRacine
        enStock.filter {
            (categorie == null || Categories.estDans(it.typeProduitId, categorie, parentDe::get)) &&
                (t.isEmpty() || it.nom.contains(t, ignoreCase = true) || it.code.contains(t, ignoreCase = true) || it.codeBarre == t)
        }
    }
    val nbArticles = vm.panier.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (corrigeId != null) "Corriger la vente" else "Vente ${vm.type.libelle.lowercase()}")
                        Text(pv?.pointVente?.nom.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(Montant.formater(vm.totalBrut), style = MaterialTheme.typography.titleLarge)
                }
                Button(
                    onClick = vm::ouvrirEncaissement,
                    enabled = session != null && nbArticles > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Encaisser") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            vm.numeroCorrige?.let {
                Text(
                    "Correction de la vente $it : modifiez le panier puis encaissez. L'ancienne vente sera annulée.",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (session == null) {
                Text(
                    "La caisse de ce point de vente est fermée : ouvrez-la avant de vendre.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TypeVente.entries.forEach { t ->
                    FilterChip(selected = vm.type == t, onClick = { vm.choisirType(t) }, label = { Text(t.libelle) })
                }
                OutlinedButton(onClick = { vm.choixClientOuvert = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Person, contentDescription = null)
                    Text(" " + (vm.client?.client?.nom ?: if (vm.type == TypeVente.GROS) "Client requis" else "Client"), maxLines = 1)
                }
            }
            // Suivi client : impayé du client choisi (ventes à crédit non soldées)
            vm.client?.let { choisi ->
                val cc = credits[choisi.client.id]
                if (cc != null && cc.du > 0) {
                    Text(
                        "Impayé de ${choisi.client.nom} : ${Montant.formater(cc.du)}" +
                            (cc.plusAncienJours?.let { " · plus ancien : $it j" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (cc.enRetard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    Text(
                        "${choisi.client.nom} : aucun impayé",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }

            TabRow(selectedTabIndex = if (ongletPanier) 1 else 0) {
                Tab(selected = !ongletPanier, onClick = { ongletPanier = false }, text = { Text("Produits") })
                Tab(selected = ongletPanier, onClick = { ongletPanier = true }, text = { Text("Panier ($nbArticles)") })
            }

            if (!ongletPanier) {
                // Filtre par type de produit : « Tous » puis les catégories, et leurs sous-catégories
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = vm.categorieRacine == null, onClick = { vm.filtrerCategorie(null) }, label = { Text("Tous") })
                    racines.forEach { c ->
                        FilterChip(selected = vm.categorieRacine == c.id, onClick = { vm.filtrerCategorie(c.id) }, label = { Text(c.nom) })
                    }
                }
                if (sousCategories.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = vm.sousCategorie == null, onClick = { vm.filtrerSousCategorie(null) }, label = { Text("Toutes") })
                        sousCategories.forEach { c ->
                            FilterChip(selected = vm.sousCategorie == c.id, onClick = { vm.filtrerSousCategorie(c.id) }, label = { Text(c.nom) })
                        }
                    }
                }
                OutlinedTextField(
                    value = vm.recherche,
                    onValueChange = { vm.recherche = it },
                    placeholder = { Text("Nom, code ou code-barre") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    if (filtres.isEmpty()) {
                        item {
                            Text(
                                if (enStock.isEmpty()) "Aucun article en stock dans ce point de vente." else "Aucun article en stock pour ce filtre.",
                                Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(filtres, key = { it.produitId }) { p ->
                        val prix = vm.prix(p)
                        val dansPanier = vm.panier.firstOrNull { it.produit.produitId == p.produitId }?.quantiteMilli
                        ListItem(
                            headlineContent = { Text(p.nom) },
                            supportingContent = {
                                Text(
                                    "Stock : ${Quantite.formater(p.stockMilli)} ${p.uniteSymbole}" +
                                        (dansPanier?.let { " · Panier : ${Quantite.formater(it)}" } ?: ""),
                                    color = if (p.stockMilli <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = { PhotoProduit(vm.fichierPhoto(p.photoChemin), Modifier.size(56.dp)) },
                            trailingContent = {
                                Text(
                                    prix?.let { "${Montant.formater(it)} / ${p.uniteSymbole}" } ?: "Pas de prix de gros",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (prix == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            modifier = Modifier.clickable { vm.ajouter(p) },
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (vm.panier.isEmpty()) item { Text("Le panier est vide.", Modifier.padding(16.dp)) }
                    items(vm.panier, key = { it.produit.produitId }) { l ->
                        ListItem(
                            headlineContent = { Text(l.produit.nom) },
                            supportingContent = {
                                Text("${Quantite.formater(l.quantiteMilli)} ${l.produit.uniteSymbole} × ${Montant.formater(l.prixUnitaire)}")
                            },
                            leadingContent = { PhotoProduit(vm.fichierPhoto(l.produit.photoChemin), Modifier.size(48.dp)) },
                            trailingContent = { Text(Montant.formater(l.montant), style = MaterialTheme.typography.titleSmall) },
                            modifier = Modifier.clickable { vm.modifierQuantite(l) },
                        )
                    }
                    if (vm.panier.isNotEmpty()) {
                        item { TextButton(onClick = vm::vider, modifier = Modifier.padding(8.dp)) { Text("Vider le panier") } }
                    }
                }
            }
        }
    }

    if (vm.choixClientOuvert) {
        ChoixClientDialog(
            clients = clients,
            onChoisir = { vm.choisirClient(it) },
            onRetirer = if (vm.client != null) ({ vm.choisirClient(null) }) else null,
            onAnnuler = { vm.choixClientOuvert = false },
        )
    }

    vm.quantiteEnSaisie?.let { p ->
        val actuelle = vm.panier.firstOrNull { it.produit.produitId == p.produitId }?.quantiteMilli
        var texte by rememberSaveable(p.produitId) { mutableStateOf(actuelle?.let { Quantite.formater(it) }.orEmpty()) }
        AlertDialog(
            onDismissRequest = vm::fermerQuantite,
            title = { Text(p.nom) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stock : ${Quantite.formater(p.stockMilli)} ${p.uniteSymbole}")
                    ChampTexte(
                        texte, { texte = it }, "Quantité (${p.uniteSymbole})", Modifier.fillMaxWidth(),
                        if (p.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number,
                    )
                    Text("0 retire le produit du panier.", style = MaterialTheme.typography.bodySmall)
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.saisirQuantite(texte) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = vm::fermerQuantite) { Text("Annuler") } },
        )
    }

    if (vm.encaissementOuvert) {
        EncaissementDialog(
            totalBrut = vm.totalBrut,
            avecClient = vm.client != null,
            erreur = vm.erreur,
            enCours = vm.enCours,
            onValider = vm::encaisser,
            onAnnuler = vm::fermerEncaissement,
        )
    }

    if (vm.enAttenteAutorisation != null) {
        var pin by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::annulerAutorisation,
            title = { Text("Autorisation nécessaire") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.messageAutorisation?.let { Text(it) }
                    ChampPin(pin, { pin = it }, "Code PIN du gérant ou du propriétaire", Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.autoriser(pin) }, enabled = !vm.enCours) { Text("Autoriser") } },
            dismissButton = { TextButton(onClick = vm::annulerAutorisation) { Text("Annuler") } },
        )
    }

    vm.venteTerminee?.let { id ->
        AlertDialog(
            onDismissRequest = vm::nouvelleVente,
            title = { Text("Vente enregistrée") },
            text = { Text("Le stock et la caisse sont à jour.") },
            confirmButton = { TextButton(onClick = vm::nouvelleVente) { Text("Nouvelle vente") } },
            dismissButton = {
                TextButton(onClick = {
                    vm.nouvelleVente()
                    onTicket(id)
                }) { Text("Voir le ticket") }
            },
        )
    }
}

/** Remise et paiement (RG-C5 : un montant par mode ; RG-V4 : crédit seulement avec un client). */
@Composable
private fun EncaissementDialog(
    totalBrut: Long,
    avecClient: Boolean,
    erreur: String?,
    enCours: Boolean,
    onValider: (remise: Long, paiements: List<PaiementSaisi>) -> Unit,
    onAnnuler: () -> Unit,
) {
    var remiseTexte by rememberSaveable { mutableStateOf("") }
    var choix by rememberSaveable { mutableStateOf(ChoixPaiement.ESPECES) }
    var recuTexte by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var especes by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var carte by rememberSaveable { mutableStateOf("") }
    var credit by rememberSaveable { mutableStateOf("") }
    var erreurLocale by remember { mutableStateOf<String?>(null) }

    val remise = Montant.parser(remiseTexte.ifBlank { "0" })
    val net = remise?.let { totalBrut - it }
    val choixPossibles = ChoixPaiement.entries.filter { it != ChoixPaiement.CREDIT || avecClient }

    fun montant(texte: String): Long? = Montant.parser(texte.ifBlank { "0" })

    fun valider() {
        erreurLocale = null
        if (remise == null || net == null || net < 0) {
            erreurLocale = "Remise invalide."
            return
        }
        val paiements = when (choix) {
            ChoixPaiement.ESPECES -> listOf(PaiementSaisi(ModePaiement.ESPECES, net))
            ChoixPaiement.MOBILE_MONEY -> listOf(PaiementSaisi(ModePaiement.MOBILE_MONEY, net, reference))
            ChoixPaiement.CARTE -> listOf(PaiementSaisi(ModePaiement.CARTE, net))
            ChoixPaiement.CREDIT -> listOf(PaiementSaisi(ModePaiement.CREDIT, net))
            ChoixPaiement.MIXTE -> {
                val montants = listOf(
                    ModePaiement.ESPECES to montant(especes),
                    ModePaiement.MOBILE_MONEY to montant(mobile),
                    ModePaiement.CARTE to montant(carte),
                    ModePaiement.CREDIT to if (avecClient) montant(credit) else 0L,
                )
                if (montants.any { it.second == null }) {
                    erreurLocale = "Montant invalide."
                    return
                }
                montants.filter { (it.second ?: 0L) > 0 }.map { (mode, m) ->
                    PaiementSaisi(mode, m ?: 0L, if (mode == ModePaiement.MOBILE_MONEY) reference else null)
                }
            }
        }
        onValider(remise, paiements)
    }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Encaisser") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LigneMontant("Total", totalBrut)
                ChampTexte(remiseTexte, { remiseTexte = it }, "Remise (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                net?.let { LigneMontant("Net à payer", it, important = true) }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choixPossibles.chunked(3).forEach { rangee ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rangee.forEach { c -> FilterChip(selected = choix == c, onClick = { choix = c }, label = { Text(c.libelle) }) }
                        }
                    }
                }
                when (choix) {
                    ChoixPaiement.ESPECES -> {
                        ChampTexte(recuTexte, { recuTexte = it }, "Montant reçu (facultatif)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        val recu = Montant.parser(recuTexte)
                        if (recu != null && net != null) {
                            val rendu = CalculsVente.rendu(recu, net)
                            Text(
                                rendu?.let { "À rendre : ${Montant.formater(it)}" } ?: "Montant reçu insuffisant.",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (rendu == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    ChoixPaiement.MOBILE_MONEY -> ChampTexte(reference, { reference = it }, "Référence de transaction (facultatif)", Modifier.fillMaxWidth())
                    ChoixPaiement.CREDIT -> Text("Le montant sera ajouté au crédit du client.", style = MaterialTheme.typography.bodySmall)
                    ChoixPaiement.CARTE -> Unit
                    ChoixPaiement.MIXTE -> {
                        ChampTexte(especes, { especes = it }, "Espèces (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        ChampTexte(mobile, { mobile = it }, "Mobile money (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        if ((montant(mobile) ?: 0) > 0) {
                            ChampTexte(reference, { reference = it }, "Référence mobile money (facultatif)", Modifier.fillMaxWidth())
                        }
                        ChampTexte(carte, { carte = it }, "Carte (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        if (avecClient) ChampTexte(credit, { credit = it }, "À crédit (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                        val saisi = listOf(especes, mobile, carte, if (avecClient) credit else "").sumOf { montant(it) ?: 0L }
                        if (net != null) {
                            val reste = net - saisi
                            Text(
                                if (reste == 0L) "Montants complets." else if (reste > 0) "Reste à répartir : ${Montant.formater(reste)}" else "Trop saisi : ${Montant.formater(-reste)}",
                                color = if (reste == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                (erreurLocale ?: erreur)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { valider() }, enabled = !enCours) { Text("Valider la vente") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Retour") } },
    )
}

@Composable
private fun LigneMontant(libelle: String, montant: Long, important: Boolean = false) {
    val style = if (important) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, style = style, modifier = Modifier.weight(1f))
        Text(Montant.formater(montant), style = style)
    }
}

/** Ticket de caisse en texte, partagé par WhatsApp. */
fun texteTicket(
    nomCommerce: String,
    numero: String,
    date: String,
    pointVente: String,
    client: String?,
    lignes: List<Triple<String, String, Long>>,
    totalBrut: Long,
    remise: Long,
    totalNet: Long,
    paiements: List<Pair<String, Long>>,
): String = buildString {
    appendLine(nomCommerce)
    appendLine("Ticket $numero — $date")
    appendLine(pointVente)
    client?.let { appendLine("Client : $it") }
    appendLine()
    lignes.forEach { (nom, quantite, montant) -> appendLine("$nom  $quantite  ${Montant.formater(montant)}") }
    appendLine()
    if (remise > 0) {
        appendLine("Total : ${Montant.formater(totalBrut)}")
        appendLine("Remise : -${Montant.formater(remise)}")
    }
    appendLine("À payer : ${Montant.formater(totalNet)}")
    paiements.forEach { (mode, montant) -> appendLine("$mode : ${Montant.formater(montant)}") }
    appendLine()
    append("Merci de votre visite !")
}

