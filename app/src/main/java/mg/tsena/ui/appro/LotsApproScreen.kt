package mg.tsena.ui.appro

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.appro.LotApproRepository
import mg.tsena.catalogue.PhotoManager
import mg.tsena.container
import mg.tsena.data.db.entity.AchatLotAppro
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.LigneLotAppro
import mg.tsena.data.db.entity.LotApproAvecTotaux
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.StatutLotAppro
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.CoffreRepository
import mg.tsena.lieux.LieuRepository
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File

// --- Liste des appros ---

class LotsApproViewModel(private val lots: LotApproRepository, lieux: LieuRepository, coffre: CoffreRepository) : ViewModel() {
    val liste: StateFlow<List<LotApproAvecTotaux>> = lots.observerLots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val depots: StateFlow<List<DepotEntity>> = lieux.observerDepots()
        .map { d -> d.filter { it.actif } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val especesCoffre: StateFlow<Long> = coffre.observerSoldes()
        .map { s -> s.firstOrNull { it.mode == ModePaiement.ESPECES }?.solde ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    var erreur by mutableStateOf<String?>(null)
        private set

    fun effacerErreur() {
        erreur = null
    }

    fun creer(budgetTexte: String, source: DepotEntity?, destination: DepotEntity?, note: String, onCree: (String) -> Unit) {
        val budget = Montant.parser(budgetTexte) ?: run { erreur = "Budget invalide."; return }
        if (source == null || destination == null) {
            erreur = "Choisissez le dépôt d'achat et le dépôt de destination."
            return
        }
        erreur = null
        viewModelScope.launch {
            try {
                onCree(lots.creer(budget, source.id, destination.id, note))
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LotsApproViewModel(container.lotApproRepository, container.lieuRepository, container.coffreRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotsApproScreen(
    onRetour: () -> Unit,
    onOuvrir: (lotId: String) -> Unit,
    vm: LotsApproViewModel = viewModel(factory = LotsApproViewModel.Factory),
) {
    val lots by vm.liste.collectAsStateWithLifecycle()
    val depots by vm.depots.collectAsStateWithLifecycle()
    val coffre by vm.especesCoffre.collectAsStateWithLifecycle()
    var creation by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appros") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.effacerErreur(); creation = true }) { Icon(Icons.Filled.Add, contentDescription = "Nouvel appro") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Espèces au coffre : ${Montant.formater(coffre)}",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (lots.isEmpty()) {
                item { Text("Aucun appro. Créez-en un avec son budget, puis ajoutez les achats.", modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(lots, key = { it.lot.id }) { l ->
                ListItem(
                    headlineContent = { Text("${l.lot.nom} · ${l.lot.numero}") },
                    supportingContent = {
                        Column {
                            Text("Budget ${Montant.formater(l.lot.budget)} · payé ${Montant.formater(l.totalPaye)} · ${l.nbAchats} achat(s)")
                            Text("Étiquetés : ${l.nbEtiquetes} / ${l.nbProduits} · ${l.depotSourceNom} → ${l.depotDestinationNom}", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingContent = { Text(l.lot.statut.libelle, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.clickable { onOuvrir(l.lot.id) },
                )
                HorizontalDivider()
            }
        }
    }

    if (creation) {
        var budget by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
        var destinationId by rememberSaveable { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { creation = false },
            title = { Text("Nouvel appro") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Budget en espèces pris au coffre (${Montant.formater(coffre)} disponibles).", style = MaterialTheme.typography.bodySmall)
                    ChampTexte(budget, { budget = it }, "Budget (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                    Selecteur(
                        libelle = "Achats reçus et étiquetés dans",
                        valeurAffichee = depots.firstOrNull { it.id == sourceId }?.nom.orEmpty(),
                        options = depots,
                        texte = { it.nom },
                        onSelection = { sourceId = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Selecteur(
                        libelle = "Envoyé ensuite à",
                        valeurAffichee = depots.firstOrNull { it.id == destinationId }?.nom.orEmpty(),
                        options = depots,
                        texte = { it.nom },
                        onSelection = { destinationId = it.id },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChampTexte(note, { note = it }, "Note (facultatif)", Modifier.fillMaxWidth())
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.creer(budget, depots.firstOrNull { it.id == sourceId }, depots.firstOrNull { it.id == destinationId }, note) { id ->
                        creation = false
                        onOuvrir(id)
                    }
                }) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { creation = false }) { Text("Annuler") } },
        )
    }
}

// --- Fiche d'un appro ---

class LotApproViewModel(
    private val lotId: String,
    private val lots: LotApproRepository,
    coffre: CoffreRepository,
    private val photos: PhotoManager,
) : ViewModel() {
    val lot: StateFlow<LotApproAvecTotaux?> = lots.observerLot(lotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val achats: StateFlow<List<AchatLotAppro>> = lots.observerAchats(lotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lignes: StateFlow<List<LigneLotAppro>> = lots.observerLignes(lotId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val especesCoffre: StateFlow<Long> = coffre.observerSoldes()
        .map { s -> s.firstOrNull { it.mode == ModePaiement.ESPECES }?.solde ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var transfertEnvoye by mutableStateOf<String?>(null)
        private set

    fun fichierPhoto(chemin: String): File = photos.fichier(chemin)

    fun effacerErreur() {
        erreur = null
    }

    private fun executer(onFait: () -> Unit = {}, action: suspend () -> Unit) {
        erreur = null
        message = null
        viewModelScope.launch {
            try {
                action()
                onFait()
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    fun etiqueter(ligne: LigneLotAppro, quantiteTexte: String, motif: String, onFait: () -> Unit) {
        val q = Quantite.parser(quantiteTexte, ligne.uniteDecimale) ?: run { erreur = "Quantité invalide."; return }
        executer(onFait) { lots.etiqueter(lotId, ligne.produitId, q, motif) }
    }

    fun modifierBudget(texte: String, onFait: () -> Unit) {
        val budget = Montant.parser(texte) ?: run { erreur = "Budget invalide."; return }
        executer(onFait) { lots.modifierBudget(lotId, budget) }
    }

    fun envoyer() {
        executer {
            transfertEnvoye = lots.envoyer(lotId)
            message = "Appro envoyé : le transfert est en transit, à réceptionner à destination."
        }
    }

    fun annuler(onFait: () -> Unit) {
        executer(onFait) { lots.annuler(lotId) }
    }

    companion object {
        fun factory(lotId: String) = viewModelFactory {
            initializer { LotApproViewModel(lotId, container.lotApproRepository, container.coffreRepository, container.photoManager) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotApproScreen(
    lotId: String,
    peutModifierBudget: Boolean,
    onRetour: () -> Unit,
    onAjouterAchat: () -> Unit,
    onAchat: (receptionId: String) -> Unit,
    onTransfert: (transfertId: String) -> Unit,
    vm: LotApproViewModel = viewModel(key = "lot_appro_$lotId", factory = LotApproViewModel.factory(lotId)),
) {
    val lotAvec by vm.lot.collectAsStateWithLifecycle()
    val achats by vm.achats.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val coffre by vm.especesCoffre.collectAsStateWithLifecycle()
    var ligneEnEtiquetage by rememberSaveable { mutableStateOf<String?>(null) }
    var budgetOuvert by rememberSaveable { mutableStateOf(false) }
    var envoiOuvert by rememberSaveable { mutableStateOf(false) }
    val l = lotAvec

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(l?.let { "${it.lot.nom} · ${it.lot.numero}" } ?: "Appro") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        if (l == null) return@Scaffold
        val enCours = l.lot.statut == StatutLotAppro.EN_COURS
        val restants = lignes.count { !it.etiquete }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Ligne2("Budget", Montant.formater(l.lot.budget))
                        Ligne2("Payé aux fournisseurs", Montant.formater(l.totalPaye))
                        Ligne2("Budget restant", Montant.formater(l.budgetRestant))
                        Ligne2("Achats de l'appro", Montant.formater(l.totalAchats))
                        Ligne2("Espèces au coffre", Montant.formater(coffre))
                        Text("${l.depotSourceNom} → ${l.depotDestinationNom} · ${l.lot.statut.libelle}", style = MaterialTheme.typography.bodySmall)
                        if (enCours && peutModifierBudget) {
                            TextButton(onClick = { vm.effacerErreur(); budgetOuvert = true }) { Text("Modifier le budget") }
                        }
                    }
                }
                vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
                vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            }

            item { Titre2("Achats (${achats.size})") }
            if (enCours) {
                item {
                    Button(onClick = onAjouterAchat, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("Ajouter un achat") }
                }
            }
            items(achats, key = { it.reception.id }) { a ->
                ListItem(
                    headlineContent = { Text("${a.fournisseurNom} · ${a.reception.numero}") },
                    supportingContent = {
                        Text(
                            "${formaterDateHeure(a.reception.date)} · " +
                                if (a.totalPaye >= a.reception.montantTotal) "payé" else "reste ${Montant.formater(a.reception.montantTotal - a.totalPaye)} à payer",
                        )
                    },
                    trailingContent = { Text(Montant.formater(a.reception.montantTotal)) },
                    modifier = Modifier.clickable { onAchat(a.reception.id) },
                )
                HorizontalDivider()
            }

            item { Titre2("Étiquetage et comptage") }
            if (lignes.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { (lignes.size - restants).toFloat() / lignes.size }, modifier = Modifier.fillMaxWidth())
                        Text(
                            if (restants == 0) "Tout est étiqueté et compté." else "Reste à étiqueter : $restants produit(s) sur ${lignes.size}.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                item { Text("Les produits achetés apparaîtront ici.", modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(lignes, key = { it.produitId }) { ligne ->
                ListItem(
                    headlineContent = { Text(ligne.nom) },
                    supportingContent = {
                        Column {
                            Text("Acheté : ${Quantite.formater(ligne.quantiteAcheteeMilli)} ${ligne.uniteSymbole}")
                            if (ligne.etiquete) {
                                val ecart = ligne.ecartMilli ?: 0L
                                Text(
                                    "Compté : ${Quantite.formater(ligne.quantiteCompteeMilli ?: 0L)} ${ligne.uniteSymbole}" +
                                        if (ecart != 0L) " · écart ${if (ecart > 0) "+" else "−"}${Quantite.formater(kotlin.math.abs(ecart))} (${ligne.motifEcart.orEmpty()})" else "",
                                    color = if (ecart != 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text("À étiqueter", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    leadingContent = { PhotoProduit(vm.fichierPhoto(ligne.photoChemin), Modifier.size(48.dp)) },
                    modifier = if (enCours) Modifier.clickable { vm.effacerErreur(); ligneEnEtiquetage = ligne.produitId } else Modifier,
                )
                HorizontalDivider()
            }

            if (enCours) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.effacerErreur(); envoiOuvert = true }, enabled = lignes.isNotEmpty() && restants == 0, modifier = Modifier.fillMaxWidth()) {
                            Text("Envoyer à ${l.depotDestinationNom}")
                        }
                        if (l.nbAchats == 0) {
                            OutlinedButton(onClick = { vm.annuler(onRetour) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Annuler cet appro", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else if (l.lot.transfertId != null) {
                item {
                    OutlinedButton(onClick = { onTransfert(l.lot.transfertId!!) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Voir le transfert")
                    }
                }
            }
        }
    }

    val ligneChoisie = lignes.firstOrNull { it.produitId == ligneEnEtiquetage }
    if (ligneChoisie != null) {
        var quantite by rememberSaveable(ligneChoisie.produitId) {
            mutableStateOf(Quantite.formater(ligneChoisie.quantiteCompteeMilli ?: ligneChoisie.quantiteAcheteeMilli))
        }
        var motif by rememberSaveable(ligneChoisie.produitId) { mutableStateOf(ligneChoisie.motifEcart.orEmpty()) }
        AlertDialog(
            onDismissRequest = { ligneEnEtiquetage = null },
            title = { Text(ligneChoisie.nom) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Acheté : ${Quantite.formater(ligneChoisie.quantiteAcheteeMilli)} ${ligneChoisie.uniteSymbole}. " +
                            "Après avoir posé les codes produit, comptez les articles réellement prêts à partir.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChampTexte(
                        quantite, { quantite = it }, "Quantité comptée (${ligneChoisie.uniteSymbole})", Modifier.fillMaxWidth(),
                        if (ligneChoisie.uniteDecimale) KeyboardType.Decimal else KeyboardType.Number,
                    )
                    ChampTexte(motif, { motif = it }, "Motif de l'écart (si différent de l'achat)", Modifier.fillMaxWidth())
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.etiqueter(ligneChoisie, quantite, motif) { ligneEnEtiquetage = null } }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { ligneEnEtiquetage = null }) { Text("Annuler") } },
        )
    }

    if (budgetOuvert && l != null) {
        var budget by rememberSaveable { mutableStateOf(l.lot.budget.toString()) }
        AlertDialog(
            onDismissRequest = { budgetOuvert = false },
            title = { Text("Budget de l'appro") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChampTexte(budget, { budget = it }, "Budget (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.modifierBudget(budget) { budgetOuvert = false } }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { budgetOuvert = false }) { Text("Annuler") } },
        )
    }

    if (envoiOuvert && l != null) {
        AlertDialog(
            onDismissRequest = { envoiOuvert = false },
            title = { Text("Envoyer l'appro ?") },
            text = {
                Text(
                    "Les écarts de comptage sont enregistrés (manque en perte, surplus en ajustement) puis un transfert " +
                        "part vers ${l.depotDestinationNom} avec les quantités comptées. Ensuite, l'appro ne peut plus être modifié.",
                )
            },
            confirmButton = { TextButton(onClick = { envoiOuvert = false; vm.envoyer() }) { Text("Envoyer") } },
            dismissButton = { TextButton(onClick = { envoiOuvert = false }) { Text("Retour") } },
        )
    }
}

@Composable
private fun Ligne2(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(libelle)
        Text(valeur, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Titre2(texte: String) {
    Text(texte, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}
