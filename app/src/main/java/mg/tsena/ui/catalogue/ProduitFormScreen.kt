package mg.tsena.ui.catalogue

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.catalogue.PhotoInvalideException
import mg.tsena.catalogue.PhotoManager
import mg.tsena.catalogue.SaisieProduit
import mg.tsena.container
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.data.db.entity.UniteEntity
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File
import java.io.IOException

data class FormProduit(
    val nom: String = "",
    val code: String = "",
    val codeBarre: String = "",
    val categorieId: String? = null,
    val uniteId: String? = null,
    val prix: String = "",
    val prixGros: String = "",
    val seuil: String = "",
    val photo: String? = null,
    val actif: Boolean = true,
)

class ProduitFormViewModel(
    private val produitId: String?,
    private val catalogue: CatalogueRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val categories: StateFlow<List<TypeProduitEntity>> = catalogue.observerCategories()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unites: StateFlow<List<UniteEntity>> = catalogue.observerUnites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val estNouveau = produitId == null
    var form by mutableStateOf(FormProduit())
        private set
    var chargement by mutableStateOf(produitId != null)
        private set
    var photoEnCours by mutableStateOf(false)
        private set
    var enregistrement by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var enregistre by mutableStateOf(false)
        private set

    var dialogueCategorie by mutableStateOf(false)
        private set
    var erreurCategorie by mutableStateOf<String?>(null)
        private set

    /** Photo importée mais pas encore enregistrée : supprimée si l'utilisateur abandonne. */
    private var photoNonEnregistree: String? = null

    init {
        if (produitId != null) {
            viewModelScope.launch {
                val p = catalogue.getProduit(produitId)
                if (p == null) {
                    erreur = "Produit introuvable."
                } else {
                    val unite = catalogue.getUnite(p.uniteId)
                    form = FormProduit(
                        nom = p.nom,
                        code = p.code,
                        codeBarre = p.codeBarre.orEmpty(),
                        categorieId = p.typeProduitId,
                        uniteId = p.uniteId,
                        prix = p.prixVente.toString(),
                        prixGros = p.prixVenteGros?.toString().orEmpty(),
                        seuil = if (p.seuilAlerteMilli == 0L) "" else Quantite.formater(p.seuilAlerteMilli),
                        photo = p.photoChemin,
                        actif = p.actif,
                    )
                    if (unite == null) erreur = "Unité du produit introuvable."
                }
                chargement = false
            }
        }
    }

    fun modifier(changement: FormProduit.() -> FormProduit) {
        form = form.changement()
        erreur = null
    }

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun cibleAppareilPhoto(): Uri = photos.nouvelleCibleAppareilPhoto()

    fun importerPhoto(source: Uri) {
        photoEnCours = true
        erreur = null
        viewModelScope.launch {
            try {
                val chemin = photos.importer(source)
                photos.supprimer(photoNonEnregistree)
                photoNonEnregistree = chemin
                form = form.copy(photo = chemin)
            } catch (e: PhotoInvalideException) {
                erreur = e.message
            } catch (e: IOException) {
                erreur = "Impossible d'importer la photo : ${e.message}"
            } finally {
                photoEnCours = false
            }
        }
    }

    fun signaler(message: String) {
        erreur = message
    }

    fun enregistrer() {
        val f = form
        val unite = unites.value.firstOrNull { it.id == f.uniteId }
        val prix = Montant.parser(f.prix)
        // Vide = pas de vente en gros pour ce produit
        val prixGros = if (f.prixGros.isBlank()) null else Montant.parser(f.prixGros)
        val seuil = Quantite.parser(f.seuil, unite?.decimale ?: false)
        erreur = when {
            f.photo == null -> "Ajoutez une photo du produit."
            f.categorieId == null -> "Choisissez une catégorie."
            unite == null -> "Choisissez une unité."
            prix == null -> "Prix de vente au détail invalide (nombre entier en Ariary)."
            f.prixGros.isNotBlank() && prixGros == null -> "Prix de vente en gros invalide (nombre entier en Ariary)."
            seuil == null -> if (unite.decimale) "Seuil d'alerte invalide." else "Le seuil d'alerte doit être un nombre entier pour l'unité « ${unite.nom} »."
            else -> null
        }
        if (erreur != null || prix == null || seuil == null) return

        enregistrement = true
        viewModelScope.launch {
            try {
                catalogue.enregistrerProduit(
                    SaisieProduit(
                        id = produitId,
                        nom = f.nom,
                        code = f.code,
                        codeBarre = f.codeBarre,
                        categorieId = f.categorieId,
                        uniteId = f.uniteId,
                        prixVente = prix,
                        prixVenteGros = prixGros,
                        seuilAlerteMilli = seuil,
                        photoChemin = f.photo,
                        actif = f.actif,
                    )
                )
                photoNonEnregistree = null
                enregistre = true
            } catch (e: RegleMetierException) {
                erreur = e.message
            } finally {
                enregistrement = false
            }
        }
    }

    fun ouvrirDialogueCategorie() {
        erreurCategorie = null
        dialogueCategorie = true
    }

    fun fermerDialogueCategorie() {
        dialogueCategorie = false
    }

    fun creerCategorie(nom: String, parentId: String?) {
        viewModelScope.launch {
            try {
                val id = catalogue.enregistrerCategorie(null, nom, parentId)
                form = form.copy(categorieId = id)
                dialogueCategorie = false
            } catch (e: RegleMetierException) {
                erreurCategorie = e.message
            }
        }
    }

    override fun onCleared() {
        photos.supprimer(photoNonEnregistree)
    }

    companion object {
        fun factory(produitId: String?) = viewModelFactory {
            initializer { ProduitFormViewModel(produitId, container.catalogueRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProduitFormScreen(
    produitId: String?,
    onTermine: () -> Unit,
    vm: ProduitFormViewModel = viewModel(key = "produit_$produitId", factory = ProduitFormViewModel.factory(produitId)),
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val unites by vm.unites.collectAsStateWithLifecycle()
    val categoriesAffichees = remember(categories) { categories.avecChemins() }
    val f = vm.form

    LaunchedEffect(vm.enregistre) { if (vm.enregistre) onTermine() }
    // Unité par défaut à la création : la pièce
    LaunchedEffect(unites) {
        if (vm.estNouveau && vm.form.uniteId == null && unites.isNotEmpty()) {
            vm.modifier { copy(uniteId = (unites.firstOrNull { it.symbole == "pce" } ?: unites.first()).id) }
        }
    }

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
                title = { Text(if (vm.estNouveau) "Nouveau produit" else "Modifier le produit") },
                navigationIcon = {
                    IconButton(onClick = onTermine) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        if (vm.chargement) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val largeur = Modifier.fillMaxWidth()

            Box(largeur.height(200.dp), contentAlignment = Alignment.Center) {
                PhotoProduit(f.photo?.let(vm::fichierPhoto), Modifier.fillMaxSize())
                if (vm.photoEnCours) CircularProgressIndicator()
            }
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

            ChampTexte(f.nom, { v -> vm.modifier { copy(nom = v) } }, "Nom du produit", largeur)

            Selecteur(
                libelle = "Catégorie",
                valeurAffichee = categoriesAffichees.firstOrNull { it.categorie.id == f.categorieId }?.chemin.orEmpty(),
                options = categoriesAffichees,
                texte = { it.chemin },
                onSelection = { c -> vm.modifier { copy(categorieId = c.categorie.id) } },
                modifier = largeur,
            )
            if (categories.isEmpty()) {
                Text("Aucune catégorie : créez-en une.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = vm::ouvrirDialogueCategorie) { Text("+ Nouvelle catégorie") }

            val unite = unites.firstOrNull { it.id == f.uniteId }
            Selecteur(
                libelle = "Unité",
                valeurAffichee = unite?.let { "${it.nom} (${it.symbole})" }.orEmpty(),
                options = unites,
                texte = { "${it.nom} (${it.symbole})" },
                onSelection = { u -> vm.modifier { copy(uniteId = u.id) } },
                modifier = largeur,
            )

            ChampTexte(
                f.prix, { v -> vm.modifier { copy(prix = v.filter { it.isDigit() }) } },
                "Prix de vente au détail (Ar) par ${unite?.symbole ?: "unité"}", largeur, KeyboardType.Number,
            )
            ChampTexte(
                f.prixGros, { v -> vm.modifier { copy(prixGros = v.filter { it.isDigit() }) } },
                "Prix de vente en gros (Ar) par ${unite?.symbole ?: "unité"} — facultatif", largeur, KeyboardType.Number,
            )
            Text(
                "Sans prix de gros, le produit ne pourra pas être vendu en gros.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChampTexte(
                f.seuil, { v -> vm.modifier { copy(seuil = v) } },
                "Seuil d'alerte de stock (${unite?.symbole ?: "unité"})", largeur,
                if (unite?.decimale == true) KeyboardType.Decimal else KeyboardType.Number,
            )
            ChampTexte(f.code, { v -> vm.modifier { copy(code = v) } }, "Code (vide = automatique)", largeur)
            ChampTexte(f.codeBarre, { v -> vm.modifier { copy(codeBarre = v) } }, "Code-barre (facultatif)", largeur, KeyboardType.Number)

            if (!vm.estNouveau) {
                Row(largeur, verticalAlignment = Alignment.CenterVertically) {
                    Text("Produit actif (en vente)", Modifier.weight(1f))
                    Switch(checked = f.actif, onCheckedChange = { v -> vm.modifier { copy(actif = v) } })
                }
            }

            vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(onClick = vm::enregistrer, enabled = !vm.enregistrement && !vm.photoEnCours, modifier = largeur) {
                Text(if (vm.enregistrement) "Enregistrement…" else "Enregistrer")
            }
        }
    }

    if (vm.dialogueCategorie) {
        CategorieDialog(
            titre = "Nouvelle catégorie",
            nomInitial = "",
            parentInitial = null,
            categories = categoriesAffichees,
            erreur = vm.erreurCategorie,
            onValider = vm::creerCategorie,
            onAnnuler = vm::fermerDialogueCategorie,
        )
    }
}
