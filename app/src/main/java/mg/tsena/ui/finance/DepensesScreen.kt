package mg.tsena.ui.finance

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.PhotoInvalideException
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.CategorieDepenseEntity
import mg.tsena.data.db.entity.DepenseAvecDetails
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.data.db.entity.StatutDepense
import mg.tsena.data.db.entity.TotalCategorie
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.CoffreRepository
import mg.tsena.finance.FinanceRepository
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FORMAT_MOIS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)

// --- Liste des dépenses du mois ---

@OptIn(ExperimentalCoroutinesApi::class)
class DepensesViewModel(private val finance: FinanceRepository, private val photos: PhotoManager) : ViewModel() {
    val mois = MutableStateFlow(YearMonth.now())

    val depenses: StateFlow<List<DepenseAvecDetails>> = mois
        .flatMapLatest { finance.observerDepenses(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totaux: StateFlow<List<TotalCategorie>> = mois
        .flatMapLatest { finance.observerTotauxDepenses(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var detail by mutableStateOf<DepenseAvecDetails?>(null)
        private set
    var annulationOuverte by mutableStateOf(false)
        private set
    var parametresOuverts by mutableStateOf(false)
        private set
    var seuil by mutableStateOf(0L)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun decaler(nbMois: Long) {
        mois.value = mois.value.plusMonths(nbMois)
    }

    fun ouvrir(d: DepenseAvecDetails) {
        erreur = null
        detail = d
    }

    fun fermer() {
        detail = null
        annulationOuverte = false
        parametresOuverts = false
    }

    fun demanderAnnulation() {
        erreur = null
        annulationOuverte = true
    }

    fun annuler(motif: String) {
        val d = detail ?: return
        executer {
            finance.annulerDepense(d.depense.id, motif)
            fermer()
        }
    }

    fun ouvrirParametres() {
        erreur = null
        viewModelScope.launch { seuil = finance.seuilJustificatif() }
        parametresOuverts = true
    }

    fun enregistrerSeuil(texte: String) {
        val montant = Montant.parser(texte) ?: run { erreur = "Montant invalide."; return }
        executer {
            finance.definirSeuilJustificatif(montant)
            parametresOuverts = false
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
        val Factory = viewModelFactory {
            initializer { DepensesViewModel(container.financeRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepensesScreen(
    peutParametrer: Boolean,
    onRetour: () -> Unit,
    onNouvelle: () -> Unit,
    onCategories: () -> Unit,
    vm: DepensesViewModel = viewModel(factory = DepensesViewModel.Factory),
) {
    val mois by vm.mois.collectAsStateWithLifecycle()
    val depenses by vm.depenses.collectAsStateWithLifecycle()
    val totaux by vm.totaux.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dépenses") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    TextButton(onClick = onCategories) { Text("Catégories") }
                    if (peutParametrer) {
                        IconButton(onClick = vm::ouvrirParametres) { Icon(Icons.Filled.Settings, contentDescription = "Paramètres") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNouvelle) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle dépense") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.decaler(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent") }
                    Text(
                        mois.format(FORMAT_MOIS).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.decaler(1) }, enabled = mois < YearMonth.now()) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text("Total du mois", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(Montant.formater(totaux.sumOf { it.total }), style = MaterialTheme.typography.titleMedium)
                        }
                        totaux.forEach { t ->
                            Row {
                                Text(t.categorieNom, modifier = Modifier.weight(1f))
                                Text(Montant.formater(t.total))
                            }
                        }
                    }
                }
            }
            if (depenses.isEmpty()) item { Text("Aucune dépense ce mois-ci.", Modifier.padding(horizontal = 16.dp)) }
            items(depenses, key = { it.depense.id }) { d ->
                val annulee = d.depense.statut == StatutDepense.ANNULEE
                ListItem(
                    overlineContent = { Text("${d.depense.numero} · ${formaterDateHeure(d.depense.date)}") },
                    headlineContent = { Text(d.categorieNom) },
                    supportingContent = {
                        Text(listOfNotNull(d.depense.description, if (annulee) "Annulée" else null).joinToString(" · "))
                    },
                    trailingContent = { Text(Montant.formater(d.depense.montant), style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.clickable { vm.ouvrir(d) }.alpha(if (annulee) 0.5f else 1f),
                )
            }
        }
    }

    vm.detail?.let { d ->
        if (vm.annulationOuverte) {
            var motif by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = vm::fermer,
                title = { Text("Annuler la dépense ${d.depense.numero} ?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Si elle a été payée par la caisse, l'argent y revient (caisse encore ouverte uniquement).")
                        ChampTexte(motif, { motif = it }, "Motif", Modifier.fillMaxWidth())
                        vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.annuler(motif) }) { Text("Annuler la dépense") } },
                dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = vm::fermer,
                title = { Text("${d.depense.numero} · ${Montant.formater(d.depense.montant)}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${d.categorieNom} (compte ${d.compteNumero})", style = MaterialTheme.typography.titleSmall)
                        Text(d.depense.description)
                        Text("${formaterDateHeure(d.depense.date)} · ${d.depense.modePaiement.libelle}" +
                            (if (d.depense.source == SourceFonds.COFFRE && d.depense.modePaiement != ModePaiement.BANQUE) " · Coffre" else "") +
                            (d.pointVenteNom?.let { " · $it" } ?: ""))
                        d.auteurNom?.let { Text("Saisie par $it") }
                        if (d.depense.statut == StatutDepense.ANNULEE) {
                            Text("Annulée : ${d.depense.motifAnnulation.orEmpty()}", color = MaterialTheme.colorScheme.error)
                        }
                        d.depense.justificatifChemin?.let { chemin ->
                            PhotoProduit(vm.fichierPhoto(chemin), Modifier.fillMaxWidth().height(240.dp))
                        } ?: Text("Sans justificatif", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = vm::fermer) { Text("Fermer") } },
                dismissButton = {
                    if (d.depense.statut == StatutDepense.VALIDEE) {
                        TextButton(onClick = vm::demanderAnnulation) { Text("Annuler la dépense", color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
    }

    if (vm.parametresOuverts) {
        var texte by rememberSaveable(vm.seuil) { mutableStateOf(vm.seuil.toString()) }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Justificatifs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Au-delà de ce montant, la photo du justificatif est obligatoire pour enregistrer une dépense.")
                    ChampTexte(texte, { texte = it }, "Montant (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.enregistrerSeuil(texte) }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}

// --- Saisie d'une dépense ---

class DepenseFormViewModel(
    private val finance: FinanceRepository,
    private val coffre: CoffreRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val categories: StateFlow<List<CategorieDepenseEntity>> = finance.observerCategories()
        .map { liste -> liste.filter { it.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val caisses: StateFlow<List<SessionAvecDetails>> = finance.observerCaissesOuvertes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val soldesCoffre: StateFlow<Map<ModePaiement, Long>> = coffre.observerSoldes()
        .map { liste -> liste.associate { it.mode to it.solde } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    var categorieId by mutableStateOf<String?>(null)
    var source by mutableStateOf(SourceFonds.CAISSE)
    var montant by mutableStateOf("")
    var description by mutableStateOf("")
    var mode by mutableStateOf(ModePaiement.ESPECES)
    var pointVenteId by mutableStateOf<String?>(null)
    var justificatif by mutableStateOf<String?>(null)
        private set
    var photoEnCours by mutableStateOf(false)
        private set
    var seuil by mutableStateOf<Long?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var enCours by mutableStateOf(false)
        private set
    var terminee by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { seuil = finance.seuilJustificatif() }
    }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun cibleAppareilPhoto(): Uri = photos.nouvelleCibleAppareilPhoto()

    fun signaler(message: String) {
        erreur = message
    }

    fun importerPhoto(source: Uri) {
        photoEnCours = true
        erreur = null
        viewModelScope.launch {
            try {
                val chemin = photos.importer(source, PhotoManager.DOSSIER_JUSTIFICATIFS)
                photos.supprimer(justificatif)
                justificatif = chemin
            } catch (e: PhotoInvalideException) {
                erreur = "Impossible d'importer la photo : ${e.message}"
            } finally {
                photoEnCours = false
            }
        }
    }

    fun enregistrer(caisseParDefaut: String?) {
        val categorie = categorieId ?: run { erreur = "Choisissez une catégorie."; return }
        val m = Montant.parser(montant) ?: run { erreur = "Montant invalide."; return }
        enCours = true
        erreur = null
        viewModelScope.launch {
            try {
                finance.enregistrerDepense(categorie, m, description, mode, pointVenteId ?: caisseParDefaut, justificatif, source)
                justificatif = null
                terminee = true
            } catch (e: RegleMetierException) {
                erreur = e.message
            } finally {
                enCours = false
            }
        }
    }

    /** Photo prise mais dépense abandonnée : le fichier est supprimé. */
    override fun onCleared() {
        photos.supprimer(justificatif)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { DepenseFormViewModel(container.financeRepository, container.coffreRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepenseFormScreen(onTermine: () -> Unit, vm: DepenseFormViewModel = viewModel(factory = DepenseFormViewModel.Factory)) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val caisses by vm.caisses.collectAsStateWithLifecycle()
    val soldesCoffre by vm.soldesCoffre.collectAsStateWithLifecycle()
    val caisseParDefaut = caisses.firstOrNull()?.pointVenteId

    LaunchedEffect(vm.terminee) { if (vm.terminee) onTermine() }

    var cibleAppareil by rememberSaveable { mutableStateOf<Uri?>(null) }
    val appareilPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { reussi ->
        if (reussi) cibleAppareil?.let(vm::importerPhoto)
    }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::importerPhoto)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle dépense") },
                navigationIcon = {
                    IconButton(onClick = onTermine) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val largeur = Modifier.fillMaxWidth()
            Selecteur(
                libelle = "Catégorie",
                valeurAffichee = categories.firstOrNull { it.id == vm.categorieId }?.nom.orEmpty(),
                options = categories,
                texte = { it.nom },
                onSelection = { vm.categorieId = it.id },
                modifier = largeur,
            )
            ChampTexte(vm.montant, { vm.montant = it }, "Montant (Ar)", largeur, KeyboardType.Number)
            ChampTexte(vm.description, { vm.description = it }, "Description (ex. loyer d'octobre)", largeur)
            ChoixReglement(
                mode = vm.mode,
                onMode = { vm.mode = it },
                caisses = caisses,
                pointVenteId = vm.pointVenteId ?: caisseParDefaut,
                onPointVente = { vm.pointVenteId = it },
                source = vm.source,
                onSource = { vm.source = it },
                soldesCoffre = soldesCoffre,
            )

            Text("Justificatif (facture, reçu)", style = MaterialTheme.typography.titleSmall)
            vm.seuil?.let {
                Text("Recommandé ; obligatoire au-delà de ${Montant.formater(it)}.", style = MaterialTheme.typography.bodySmall)
            }
            vm.justificatif?.let { chemin ->
                Box(largeur.height(200.dp), contentAlignment = Alignment.Center) {
                    PhotoProduit(vm.fichierPhoto(chemin), Modifier.fillMaxSize())
                }
            }
            if (vm.photoEnCours) CircularProgressIndicator()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val cible = vm.cibleAppareilPhoto()
                        cibleAppareil = cible
                        try {
                            appareilPhoto.launch(cible)
                        } catch (e: ActivityNotFoundException) {
                            vm.signaler("Aucune application d'appareil photo disponible.")
                        }
                    },
                    enabled = !vm.photoEnCours,
                    modifier = Modifier.weight(1f),
                ) { Text("Appareil photo") }
                OutlinedButton(
                    onClick = { galerie.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !vm.photoEnCours,
                    modifier = Modifier.weight(1f),
                ) { Text("Galerie") }
            }

            vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.enregistrer(caisseParDefaut) }, enabled = !vm.enCours && !vm.photoEnCours, modifier = largeur) {
                Text("Enregistrer la dépense")
            }
        }
    }
}

// --- Catégories de dépenses ---

class CategoriesDepenseViewModel(private val finance: FinanceRepository) : ViewModel() {
    val categories: StateFlow<List<CategorieDepenseEntity>> = finance.observerCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var edition by mutableStateOf<CategorieDepenseEntity?>(null)
        private set
    var nouvelle by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun creer() {
        erreur = null
        edition = null
        nouvelle = true
    }

    fun modifier(c: CategorieDepenseEntity) {
        erreur = null
        nouvelle = false
        edition = c
    }

    fun fermer() {
        edition = null
        nouvelle = false
    }

    fun enregistrer(nom: String, compte: String, actif: Boolean) {
        val id = edition?.id
        viewModelScope.launch {
            try {
                finance.enregistrerCategorie(id, nom, compte, actif)
                fermer()
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { CategoriesDepenseViewModel(container.financeRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesDepenseScreen(onRetour: () -> Unit, vm: CategoriesDepenseViewModel = viewModel(factory = CategoriesDepenseViewModel.Factory)) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catégories de dépenses") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = vm::creer) { Icon(Icons.Filled.Add, contentDescription = "Nouvelle catégorie") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Chaque catégorie est rattachée à un compte de charge du plan comptable (PCG 2005), utilisé par la comptabilité.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(categories, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.nom) },
                    supportingContent = { Text("Compte ${c.compteNumero}" + if (c.actif) "" else " · Désactivée") },
                    modifier = Modifier.clickable { vm.modifier(c) }.alpha(if (c.actif) 1f else 0.5f),
                )
            }
        }
    }

    if (vm.nouvelle || vm.edition != null) {
        val c = vm.edition
        var nom by rememberSaveable(c?.id) { mutableStateOf(c?.nom.orEmpty()) }
        var compte by rememberSaveable(c?.id) { mutableStateOf(c?.compteNumero ?: "6") }
        var actif by rememberSaveable(c?.id) { mutableStateOf(c?.actif ?: true) }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text(if (c == null) "Nouvelle catégorie" else "Modifier la catégorie") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                    ChampTexte(compte, { compte = it }, "Compte de charge (classe 6)", Modifier.fillMaxWidth(), KeyboardType.Number)
                    if (c != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Active", Modifier.weight(1f))
                            Switch(checked = actif, onCheckedChange = { actif = it })
                        }
                    }
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.enregistrer(nom, compte, actif) }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}
