package mg.tsena.ui.lieux

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import mg.tsena.container
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.domain.RegleMetierException
import mg.tsena.lieux.LieuRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur

sealed interface EditionLieu {
    data class Depot(val depot: DepotEntity?) : EditionLieu
    data class PointVente(val pointVente: PointVenteAvecDepot?) : EditionLieu
}

class LieuxViewModel(private val lieux: LieuRepository) : ViewModel() {
    val depots: StateFlow<List<DepotEntity>> = lieux.observerDepots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pointsVente: StateFlow<List<PointVenteAvecDepot>> = lieux.observerPointsVente()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var edition by mutableStateOf<EditionLieu?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun ouvrir(e: EditionLieu) {
        erreur = null
        edition = e
    }

    fun fermer() {
        edition = null
    }

    fun enregistrerDepot(nom: String, adresse: String, actif: Boolean) {
        val id = (edition as? EditionLieu.Depot)?.depot?.id
        executer { lieux.enregistrerDepot(id, nom, adresse, actif) }
    }

    fun enregistrerPointVente(nom: String, adresse: String, depotId: String?, actif: Boolean) {
        val id = (edition as? EditionLieu.PointVente)?.pointVente?.pointVente?.id
        executer { lieux.enregistrerPointVente(id, nom, adresse, depotId, actif) }
    }

    private fun executer(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                edition = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LieuxViewModel(container.lieuRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LieuxScreen(onRetour: () -> Unit, vm: LieuxViewModel = viewModel(factory = LieuxViewModel.Factory)) {
    val depots by vm.depots.collectAsStateWithLifecycle()
    val pointsVente by vm.pointsVente.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dépôts et points de vente") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                EnTeteSection("Points de vente") { vm.ouvrir(EditionLieu.PointVente(null)) }
            }
            if (pointsVente.isEmpty()) item { TexteVide("Aucun point de vente.") }
            items(pointsVente, key = { "pv_" + it.pointVente.id }) { pv ->
                ListItem(
                    headlineContent = { Text(pv.pointVente.nom) },
                    supportingContent = {
                        Text("Dépôt de vente : ${pv.depotNom}" + if (pv.pointVente.actif) "" else " · Désactivé")
                    },
                    modifier = Modifier
                        .clickable { vm.ouvrir(EditionLieu.PointVente(pv)) }
                        .alpha(if (pv.pointVente.actif) 1f else 0.5f),
                )
            }

            item {
                EnTeteSection("Dépôts (salles de stockage)") { vm.ouvrir(EditionLieu.Depot(null)) }
            }
            if (depots.isEmpty()) item { TexteVide("Aucun dépôt.") }
            items(depots, key = { "d_" + it.id }) { d ->
                val rattache = pointsVente.firstOrNull { it.pointVente.id == d.pointVenteId }?.pointVente?.nom
                ListItem(
                    headlineContent = { Text(d.nom) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                d.adresse,
                                rattache?.let { "Magasin de $it" },
                                if (d.actif) null else "Désactivé",
                            ).joinToString(" · ").ifEmpty { "Dépôt" }
                        )
                    },
                    modifier = Modifier
                        .clickable { vm.ouvrir(EditionLieu.Depot(d)) }
                        .alpha(if (d.actif) 1f else 0.5f),
                )
            }
        }
    }

    when (val e = vm.edition) {
        null -> Unit
        is EditionLieu.Depot -> DepotDialog(e.depot, vm.erreur, vm::enregistrerDepot, vm::fermer)
        is EditionLieu.PointVente -> PointVenteDialog(e.pointVente, depots.filter { it.actif }, vm.erreur, vm::enregistrerPointVente, vm::fermer)
    }
}

@Composable
private fun EnTeteSection(titre: String, onAjouter: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(titre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAjouter) { Text("+ Ajouter") }
    }
}

@Composable
private fun TexteVide(texte: String) {
    Text(texte, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun DepotDialog(
    depot: DepotEntity?,
    erreur: String?,
    onValider: (nom: String, adresse: String, actif: Boolean) -> Unit,
    onAnnuler: () -> Unit,
) {
    var nom by rememberSaveable { mutableStateOf(depot?.nom.orEmpty()) }
    var adresse by rememberSaveable { mutableStateOf(depot?.adresse.orEmpty()) }
    var actif by rememberSaveable { mutableStateOf(depot?.actif ?: true) }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (depot == null) "Nouveau dépôt" else "Modifier le dépôt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                ChampTexte(adresse, { adresse = it }, "Adresse (facultatif)", Modifier.fillMaxWidth())
                if (depot != null) LigneActif(actif) { actif = it }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(nom, adresse, actif) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun PointVenteDialog(
    existant: PointVenteAvecDepot?,
    depotsActifs: List<DepotEntity>,
    erreur: String?,
    onValider: (nom: String, adresse: String, depotId: String?, actif: Boolean) -> Unit,
    onAnnuler: () -> Unit,
) {
    val pv = existant?.pointVente
    var nom by rememberSaveable { mutableStateOf(pv?.nom.orEmpty()) }
    var adresse by rememberSaveable { mutableStateOf(pv?.adresse.orEmpty()) }
    // null à la création = créer un dépôt « Magasin <nom> »
    var depotId by rememberSaveable { mutableStateOf(pv?.depotId) }
    var actif by rememberSaveable { mutableStateOf(pv?.actif ?: true) }

    val nouveauDepot = "Nouveau dépôt « Magasin ${nom.trim().ifEmpty { "…" }} »"
    val options: List<DepotEntity?> = (if (pv == null) listOf(null) else emptyList<DepotEntity?>()) + depotsActifs
    val affiche = depotsActifs.firstOrNull { it.id == depotId }?.nom ?: if (pv == null) nouveauDepot else existant?.depotNom.orEmpty()

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (pv == null) "Nouveau point de vente" else "Modifier le point de vente") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                ChampTexte(adresse, { adresse = it }, "Adresse (facultatif)", Modifier.fillMaxWidth())
                Selecteur(
                    libelle = "Dépôt de vente",
                    valeurAffichee = affiche,
                    options = options,
                    texte = { it?.nom ?: nouveauDepot },
                    onSelection = { depotId = it?.id },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Les ventes de ce point de vente prélèveront le stock de ce dépôt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (pv != null) LigneActif(actif) { actif = it }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(nom, adresse, depotId, actif) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun LigneActif(actif: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Actif", Modifier.weight(1f))
        Switch(checked = actif, onCheckedChange = onChange)
    }
}
