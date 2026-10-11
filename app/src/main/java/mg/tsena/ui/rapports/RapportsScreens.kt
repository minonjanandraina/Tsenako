package mg.tsena.ui.rapports

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.container
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.rapports.EtatStockLigne
import mg.tsena.rapports.EtatsExercice
import mg.tsena.rapports.RapportRepository
import mg.tsena.rapports.RapportVentes
import mg.tsena.rapports.TableauDeBord
import mg.tsena.ui.common.Selecteur
import mg.tsena.ui.compta.SelecteurMois
import java.time.YearMonth

// --- Tableau de bord ---

class TableauDeBordViewModel(rapports: RapportRepository) : ViewModel() {
    val tableau: StateFlow<TableauDeBord?> = rapports.observerTableauDeBord()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = viewModelFactory {
            initializer { TableauDeBordViewModel(container.rapportRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableauDeBordScreen(
    onRetour: () -> Unit,
    onVentes: () -> Unit,
    onStocks: () -> Unit,
    onEtats: () -> Unit,
    onAlertes: () -> Unit,
    vm: TableauDeBordViewModel = viewModel(factory = TableauDeBordViewModel.Factory),
) {
    val tableau by vm.tableau.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tableau de bord") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        val t = tableau
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (t == null) return@LazyColumn
            item {
                Carte("Aujourd'hui") {
                    Chiffre("Ventes", "${t.jour.nbVentes} · ${Montant.formater(t.jour.chiffreAffaires)}")
                    Chiffre("Marge", Montant.formater(t.jour.marge))
                }
            }
            item {
                Carte("Ce mois-ci", onClick = onVentes) {
                    Chiffre("Chiffre d'affaires", Montant.formater(t.mois.chiffreAffaires), important = true)
                    Chiffre("Marge brute", Montant.formater(t.mois.marge) + (t.mois.tauxMarge?.let { " ($it %)" } ?: ""))
                    Chiffre("Dépenses", Montant.formater(t.depensesMois))
                    Chiffre("Marge − dépenses", Montant.formater(t.mois.marge - t.depensesMois))
                    t.mois.parProduit.take(3).forEach { p ->
                        Text("• ${p.nom} : ${Montant.formater(p.chiffreAffaires)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Carte("Situation", onClick = onStocks) {
                    Chiffre("Valeur du stock", Montant.formater(t.valeurStock))
                    Chiffre("Crédits clients à encaisser", Montant.formater(t.creancesClients))
                    Chiffre("Dettes fournisseurs", Montant.formater(t.dettesFournisseurs))
                }
            }
            if (t.nbAlertes > 0) {
                item {
                    ListItem(
                        headlineContent = { Text("${t.nbAlertes} produit(s) sous le seuil d'alerte") },
                        leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable(onClick = onAlertes),
                    )
                }
            }
            item {
                Carte("États financiers", onClick = onEtats) {
                    Text("Balance, compte de résultat, bilan, flux de trésorerie (PCG 2005)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Carte(titre: String, onClick: (() -> Unit)? = null, contenu: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titre, style = MaterialTheme.typography.titleMedium)
            contenu()
        }
    }
}

@Composable
private fun Chiffre(libelle: String, valeur: String, important: Boolean = false) {
    val style = if (important) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(valeur, style = style)
    }
}

// --- Rapport des ventes ---

@OptIn(ExperimentalCoroutinesApi::class)
class RapportVentesViewModel(rapports: RapportRepository) : ViewModel() {
    val mois = MutableStateFlow(YearMonth.now())
    val rapport: StateFlow<RapportVentes> = mois
        .flatMapLatest { rapports.observerVentes(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RapportVentes.VIDE)

    companion object {
        val Factory = viewModelFactory {
            initializer { RapportVentesViewModel(container.rapportRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RapportVentesScreen(onRetour: () -> Unit, vm: RapportVentesViewModel = viewModel(factory = RapportVentesViewModel.Factory)) {
    val mois by vm.mois.collectAsStateWithLifecycle()
    val r by vm.rapport.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ventes et marges") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SelecteurMois(mois) { vm.mois.value = it } }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Carte("Total") {
                        Chiffre("Ventes", r.nbVentes.toString())
                        Chiffre("Chiffre d'affaires", Montant.formater(r.chiffreAffaires), important = true)
                        Chiffre("Coût d'achat", Montant.formater(r.coutAchat))
                        Chiffre("Marge brute", Montant.formater(r.marge) + (r.tauxMarge?.let { " ($it %)" } ?: ""))
                    }
                    if (r.parPointVente.size > 1) {
                        Carte("Par point de vente") { r.parPointVente.forEach { (nom, ca) -> Chiffre(nom, Montant.formater(ca)) } }
                    }
                    Text(
                        "Retours déduits, remises réparties sur les articles, marge calculée au coût d'achat moyen au moment de la vente.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { Text("Par produit", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
            if (r.parProduit.isEmpty()) item { Text("Aucune vente ce mois-ci.", Modifier.padding(horizontal = 16.dp)) }
            items(r.parProduit, key = { it.produitId }) { p ->
                ListItem(
                    headlineContent = { Text(p.nom) },
                    supportingContent = { Text("${Quantite.formater(p.quantiteMilli)} ${p.uniteSymbole} · marge ${Montant.formater(p.marge)}") },
                    trailingContent = { Text(Montant.formater(p.chiffreAffaires), style = MaterialTheme.typography.titleSmall) },
                )
            }
        }
    }
}

// --- État des stocks ---

class EtatStocksViewModel(rapports: RapportRepository) : ViewModel() {
    val lignes: StateFlow<List<EtatStockLigne>> = rapports.observerEtatStocks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = viewModelFactory {
            initializer { EtatStocksViewModel(container.rapportRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtatStocksScreen(onRetour: () -> Unit, vm: EtatStocksViewModel = viewModel(factory = EtatStocksViewModel.Factory)) {
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val parDepot = remember(lignes) { lignes.groupBy { it.depotNom } }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("État des stocks") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { partager(context, "État des stocks", texteEtatStocks(lignes)) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Partager")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Carte("Valeur totale au coût moyen (CMUP)") {
                        Chiffre("Tous les dépôts", Montant.formater(lignes.sumOf { it.valeur }), important = true)
                        parDepot.forEach { (depot, ls) -> Chiffre(depot, Montant.formater(ls.sumOf { it.valeur })) }
                    }
                }
            }
            parDepot.forEach { (depot, ls) ->
                item(key = "depot_$depot") { Text(depot, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
                items(ls, key = { "${it.depotNom}_${it.code}" }) { l ->
                    ListItem(
                        headlineContent = { Text(l.produitNom) },
                        supportingContent = {
                            Text("${l.code} · ${Quantite.formater(l.quantiteMilli)} ${l.uniteSymbole} × ${Montant.formater(l.coutMoyenUnitaire)}")
                        },
                        trailingContent = { Text(Montant.formater(l.valeur), style = MaterialTheme.typography.titleSmall) },
                    )
                }
            }
        }
    }
}

private fun texteEtatStocks(lignes: List<EtatStockLigne>): String = buildString {
    appendLine("État des stocks (valeur au CMUP)")
    lignes.groupBy { it.depotNom }.forEach { (depot, ls) ->
        appendLine()
        appendLine("$depot : ${Montant.formater(ls.sumOf { it.valeur })}")
        ls.forEach { appendLine("- ${it.produitNom} : ${Quantite.formater(it.quantiteMilli)} ${it.uniteSymbole} = ${Montant.formater(it.valeur)}") }
    }
    appendLine()
    append("Total : ${Montant.formater(lignes.sumOf { it.valeur })}")
}

// --- États financiers ---

class EtatsFinanciersViewModel(private val rapports: RapportRepository) : ViewModel() {
    val exercices: StateFlow<List<ExerciceEntity>> = rapports.observerExercices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var etats by mutableStateOf<EtatsExercice?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun charger(exerciceId: String) {
        erreur = null
        viewModelScope.launch {
            try {
                etats = rapports.etats(exerciceId)
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { EtatsFinanciersViewModel(container.rapportRepository) }
        }
    }
}

private val ONGLETS = listOf("Résultat", "Bilan", "Trésorerie", "Balance")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtatsFinanciersScreen(
    nomCommerce: String,
    onRetour: () -> Unit,
    vm: EtatsFinanciersViewModel = viewModel(factory = EtatsFinanciersViewModel.Factory),
) {
    val exercices by vm.exercices.collectAsStateWithLifecycle()
    var onglet by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    val e = vm.etats

    // Exercice le plus récent par défaut
    androidx.compose.runtime.LaunchedEffect(exercices) {
        if (vm.etats == null) exercices.firstOrNull()?.let { vm.charger(it.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("États financiers") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (e != null) {
                        IconButton(onClick = { partager(context, ONGLETS[onglet], texteEtat(nomCommerce, e, onglet)) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Partager")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (exercices.isEmpty()) {
                Text("Aucune écriture comptable pour l'instant.", Modifier.padding(16.dp))
                return@Column
            }
            Selecteur(
                libelle = "Exercice",
                valeurAffichee = e?.exercice?.let { "${it.dateDebut} → ${it.dateFin}" }.orEmpty(),
                options = exercices,
                texte = { "${it.dateDebut} → ${it.dateFin}" + if (it.statut.name == "CLOTURE") " (clôturé)" else "" },
                onSelection = { vm.charger(it.id) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            TabRow(selectedTabIndex = onglet, modifier = Modifier.padding(top = 8.dp)) {
                ONGLETS.forEachIndexed { i, titre -> Tab(selected = onglet == i, onClick = { onglet = i }, text = { Text(titre) }) }
            }
            if (e == null) return@Column
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                when (onglet) {
                    0 -> compteResultat(e)
                    1 -> bilan(e)
                    2 -> flux(e)
                    else -> balance(e)
                }
            }
        }
    }
}

private fun LazyListScope.compteResultat(e: EtatsExercice) {
    val cr = e.compteResultat
    item {
        Section("Compte de résultat par nature")
        LigneEtat("Chiffre d'affaires", cr.chiffreAffaires.montant)
        Detail(cr.chiffreAffaires.comptes)
        LigneEtat("Consommations de l'exercice", -cr.consommations.montant)
        Detail(cr.consommations.comptes)
        LigneEtat("Valeur ajoutée d'exploitation", cr.valeurAjoutee, total = true)
        LigneEtat("Charges de personnel", -cr.chargesPersonnel.montant)
        LigneEtat("Impôts, taxes et versements assimilés", -cr.impotsTaxes.montant)
        LigneEtat("Excédent brut d'exploitation", cr.excedentBrut, total = true)
        LigneEtat("Autres produits opérationnels", cr.autresProduits.montant)
        LigneEtat("Autres charges opérationnelles", -cr.autresCharges.montant)
        LigneEtat("Résultat opérationnel", cr.resultatOperationnel, total = true)
        LigneEtat("Résultat financier", cr.resultatFinancier)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LigneEtat(if (cr.resultatNet >= 0) "Résultat net : bénéfice" else "Résultat net : perte", cr.resultatNet, total = true)
    }
}

private fun LazyListScope.bilan(e: EtatsExercice) {
    val b = e.bilan
    item {
        Section("Actif")
        LigneEtat("Immobilisations", b.immobilisations.montant)
        LigneEtat("Stocks", b.stocks.montant)
        LigneEtat("Clients et autres débiteurs", b.creances.montant)
        Detail(b.creances.comptes)
        LigneEtat("Trésorerie", b.tresorerieActif.montant)
        Detail(b.tresorerieActif.comptes)
        LigneEtat("Total actif", b.totalActif, total = true)
        Section("Capitaux propres et passifs")
        LigneEtat("Capital et compte de l'exploitant", b.capital.montant)
        LigneEtat("Résultats antérieurs", b.resultatsAnterieurs.montant)
        LigneEtat("Résultat de l'exercice", b.resultatExercice)
        LigneEtat("Fournisseurs", b.dettesFournisseurs.montant)
        LigneEtat("Autres dettes", b.autresDettes.montant)
        LigneEtat("Trésorerie passif", b.tresoreriePassif.montant)
        LigneEtat("Total passif", b.totalPassif, total = true)
        if (b.totalActif != b.totalPassif) {
            Text("Écart actif / passif : ${Montant.formater(b.totalActif - b.totalPassif)}", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun LazyListScope.flux(e: EtatsExercice) {
    val f = e.flux
    item {
        Section("Flux de trésorerie (méthode directe)")
        LigneEtat("Trésorerie au début", f.tresorerieDebut)
        LigneEtat("Encaissements des clients", f.encaissementsClients)
        LigneEtat("Paiements aux fournisseurs", f.paiementsFournisseurs)
        LigneEtat("Paiements au personnel", f.paiementsPersonnel)
        LigneEtat("Impôts et taxes payés", f.impotsPayes)
        LigneEtat("Autres flux opérationnels", f.autresOperationnels)
        LigneEtat("Flux des activités opérationnelles", f.fluxOperationnels, total = true)
        LigneEtat("Flux des activités d'investissement", f.fluxInvestissement, total = true)
        LigneEtat("Flux des activités de financement", f.fluxFinancement, total = true)
        LigneEtat("Variation de trésorerie", f.variation)
        LigneEtat("Trésorerie à la fin", f.tresorerieFin, total = true)
    }
}

private fun LazyListScope.balance(e: EtatsExercice) {
    item {
        Section("Balance (cumul jusqu'à la fin de l'exercice)")
        Row(Modifier.fillMaxWidth()) {
            Text("Compte", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("Solde D", Modifier.weight(0.5f), style = MaterialTheme.typography.labelMedium)
            Text("Solde C", Modifier.weight(0.5f), style = MaterialTheme.typography.labelMedium)
        }
    }
    items(e.balance, key = { it.compte }) { l ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("${l.compte} ${l.libelle}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(if (l.soldeDebiteur > 0) Montant.formater(l.soldeDebiteur) else "", Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall)
            Text(if (l.soldeCrediteur > 0) Montant.formater(l.soldeCrediteur) else "", Modifier.weight(0.5f), style = MaterialTheme.typography.bodySmall)
        }
    }
    item {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LigneEtat("Total des soldes débiteurs", e.balance.sumOf { it.soldeDebiteur }, total = true)
        LigneEtat("Total des soldes créditeurs", e.balance.sumOf { it.soldeCrediteur }, total = true)
    }
}

@Composable
private fun Section(titre: String) {
    Text(titre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun LigneEtat(libelle: String, montant: Long, total: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(libelle, Modifier.weight(1f), fontWeight = if (total) FontWeight.Bold else FontWeight.Normal)
        Text(
            Montant.formater(montant),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (total) FontWeight.Bold else FontWeight.Normal,
            color = if (montant < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Detail(comptes: List<Pair<String, Long>>) {
    comptes.forEach { (compte, montant) ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp)) {
            Text(compte, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(Montant.formater(montant), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Texte de l'état affiché, pour l'envoyer au comptable (WhatsApp, e-mail…). */
private fun texteEtat(nomCommerce: String, e: EtatsExercice, onglet: Int): String = buildString {
    appendLine(nomCommerce)
    appendLine("${ONGLETS[onglet]} — exercice ${e.exercice.dateDebut} au ${e.exercice.dateFin}")
    appendLine()
    fun ligne(libelle: String, montant: Long) = appendLine("$libelle : ${Montant.formater(montant)}")
    when (onglet) {
        0 -> with(e.compteResultat) {
            ligne("Chiffre d'affaires", chiffreAffaires.montant)
            ligne("Consommations", -consommations.montant)
            ligne("Valeur ajoutée", valeurAjoutee)
            ligne("Charges de personnel", -chargesPersonnel.montant)
            ligne("Impôts et taxes", -impotsTaxes.montant)
            ligne("Excédent brut d'exploitation", excedentBrut)
            ligne("Autres produits opérationnels", autresProduits.montant)
            ligne("Autres charges opérationnelles", -autresCharges.montant)
            ligne("Résultat opérationnel", resultatOperationnel)
            ligne("Résultat financier", resultatFinancier)
            ligne("Résultat net", resultatNet)
        }
        1 -> with(e.bilan) {
            appendLine("ACTIF")
            ligne("Immobilisations", immobilisations.montant)
            ligne("Stocks", stocks.montant)
            ligne("Clients et autres débiteurs", creances.montant)
            ligne("Trésorerie", tresorerieActif.montant)
            ligne("Total actif", totalActif)
            appendLine()
            appendLine("CAPITAUX PROPRES ET PASSIFS")
            ligne("Capital et compte de l'exploitant", capital.montant)
            ligne("Résultats antérieurs", resultatsAnterieurs.montant)
            ligne("Résultat de l'exercice", resultatExercice)
            ligne("Fournisseurs", dettesFournisseurs.montant)
            ligne("Autres dettes", autresDettes.montant)
            ligne("Trésorerie passif", tresoreriePassif.montant)
            ligne("Total passif", totalPassif)
        }
        2 -> with(e.flux) {
            ligne("Trésorerie au début", tresorerieDebut)
            ligne("Encaissements clients", encaissementsClients)
            ligne("Paiements fournisseurs", paiementsFournisseurs)
            ligne("Paiements au personnel", paiementsPersonnel)
            ligne("Impôts et taxes", impotsPayes)
            ligne("Autres flux opérationnels", autresOperationnels)
            ligne("Flux opérationnels", fluxOperationnels)
            ligne("Flux d'investissement", fluxInvestissement)
            ligne("Flux de financement", fluxFinancement)
            ligne("Trésorerie à la fin", tresorerieFin)
        }
        else -> e.balance.forEach { l ->
            appendLine("${l.compte} ${l.libelle} : D ${Montant.formater(l.soldeDebiteur)} / C ${Montant.formater(l.soldeCrediteur)}")
        }
    }
}

private fun partager(context: android.content.Context, titre: String, texte: String) {
    val envoi = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, titre).putExtra(Intent.EXTRA_TEXT, texte)
    context.startActivity(Intent.createChooser(envoi, titre))
}
