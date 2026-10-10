package mg.tsena.ui.vente

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.PrixProduitPointVente
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import mg.tsena.vente.VenteRepository
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PrixPointVenteViewModel(private val ventes: VenteRepository, private val photos: PhotoManager) : ViewModel() {
    val pointsVente: StateFlow<List<PointVenteAvecDepot>> = ventes.observerPointsVente()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val choix = MutableStateFlow<String?>(null)
    val pointVenteId: StateFlow<String?> = combine(choix, pointsVente) { id, liste ->
        liste.firstOrNull { it.pointVente.id == id }?.pointVente?.id ?: liste.firstOrNull()?.pointVente?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val prix: StateFlow<List<PrixProduitPointVente>> = pointVenteId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else ventes.observerPrixPointVente(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var recherche by mutableStateOf("")
    var edition by mutableStateOf<PrixProduitPointVente?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun choisir(id: String) {
        choix.value = id
    }

    fun modifier(p: PrixProduitPointVente) {
        erreur = null
        edition = p
    }

    fun fermer() {
        edition = null
    }

    /** Champ vide = prix du produit. */
    fun enregistrer(detail: String, gros: String) {
        val p = edition ?: return
        val pv = pointVenteId.value ?: return
        val prixDetail = if (detail.isBlank()) null else Montant.parser(detail) ?: run { erreur = "Prix de détail invalide."; return }
        val prixGros = if (gros.isBlank()) null else Montant.parser(gros) ?: run { erreur = "Prix de gros invalide."; return }
        viewModelScope.launch {
            try {
                ventes.definirPrixPointVente(pv, p.produitId, prixDetail, prixGros)
                edition = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { PrixPointVenteViewModel(container.venteRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrixPointVenteScreen(onRetour: () -> Unit, vm: PrixPointVenteViewModel = viewModel(factory = PrixPointVenteViewModel.Factory)) {
    val pointsVente by vm.pointsVente.collectAsStateWithLifecycle()
    val pointVenteId by vm.pointVenteId.collectAsStateWithLifecycle()
    val prix by vm.prix.collectAsStateWithLifecycle()
    val filtres = remember(prix, vm.recherche) {
        val t = vm.recherche.trim()
        prix.filter { t.isEmpty() || it.nom.contains(t, ignoreCase = true) || it.code.contains(t, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prix par point de vente") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Selecteur(
                    libelle = "Point de vente",
                    valeurAffichee = pointsVente.firstOrNull { it.pointVente.id == pointVenteId }?.pointVente?.nom.orEmpty(),
                    options = pointsVente,
                    texte = { it.pointVente.nom },
                    onSelection = { vm.choisir(it.pointVente.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Sans prix particulier, le point de vente applique les prix de la fiche produit.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = vm.recherche,
                    onValueChange = { vm.recherche = it },
                    placeholder = { Text("Nom ou code") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtres, key = { it.produitId }) { p ->
                    val particulier = p.prixDetail != null || p.prixGros != null
                    ListItem(
                        headlineContent = { Text(p.nom) },
                        supportingContent = {
                            Text(
                                "Détail ${Montant.formater(p.prixDetail ?: p.prixVente)}" +
                                    ((p.prixGros ?: p.prixVenteGros)?.let { " · Gros ${Montant.formater(it)}" } ?: " · Pas de gros"),
                            )
                        },
                        leadingContent = { PhotoProduit(vm.fichierPhoto(p.photoChemin), Modifier.size(48.dp)) },
                        trailingContent = {
                            if (particulier) Text("Prix du point de vente", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { vm.modifier(p) },
                    )
                }
            }
        }
    }

    vm.edition?.let { p ->
        var detail by rememberSaveable(p.produitId) { mutableStateOf(p.prixDetail?.toString().orEmpty()) }
        var gros by rememberSaveable(p.produitId) { mutableStateOf(p.prixGros?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text(p.nom) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
                    ChampTexte(detail, { detail = it }, "Prix de détail (fiche : ${Montant.formater(p.prixVente)})", Modifier.fillMaxWidth(), KeyboardType.Number)
                    ChampTexte(
                        gros, { gros = it },
                        "Prix de gros (fiche : ${p.prixVenteGros?.let(Montant::formater) ?: "aucun"})",
                        Modifier.fillMaxWidth(), KeyboardType.Number,
                    )
                    Text("Laissez vide pour appliquer le prix de la fiche produit.", style = MaterialTheme.typography.bodySmall)
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.enregistrer(detail, gros) }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}
