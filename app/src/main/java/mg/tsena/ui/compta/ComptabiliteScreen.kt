package mg.tsena.ui.compta

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.compta.ComptaRepository
import mg.tsena.container
import mg.tsena.data.db.entity.CompteAvecSolde
import mg.tsena.data.db.entity.CompteEntity
import mg.tsena.data.db.entity.EcritureEntity
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.LigneEcritureAvecCompte
import mg.tsena.data.db.entity.MouvementCompte
import mg.tsena.data.db.entity.StatutExercice
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.ui.common.ChampTexte
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FORMAT_MOIS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)

// --- Accueil de la comptabilité : exercices et variation de stock ---

class ComptabiliteViewModel(private val compta: ComptaRepository) : ViewModel() {
    val exercices: StateFlow<List<ExerciceEntity>> = compta.observerExercices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var aCloturer by mutableStateOf<ExerciceEntity?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun mettreAJourStock() {
        executer {
            val ecart = compta.mettreAJourStock()
            message = if (ecart == 0L) "La valeur du stock était déjà à jour." else "Variation de stock passée : ${Montant.formater(ecart)}."
        }
    }

    fun demanderCloture(e: ExerciceEntity) {
        erreur = null
        aCloturer = e
    }

    fun fermer() {
        aCloturer = null
    }

    fun cloturer() {
        val e = aCloturer ?: return
        executer {
            val resultat = compta.cloturerExercice(e.id)
            aCloturer = null
            message = if (resultat >= 0) "Exercice clôturé : bénéfice de ${Montant.formater(resultat)}." else "Exercice clôturé : perte de ${Montant.formater(-resultat)}."
        }
    }

    private fun executer(action: suspend () -> Unit) {
        erreur = null
        message = null
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
            initializer { ComptabiliteViewModel(container.comptaRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComptabiliteScreen(
    peutCloturer: Boolean,
    onRetour: () -> Unit,
    onJournaux: () -> Unit,
    onPlan: () -> Unit,
    vm: ComptabiliteViewModel = viewModel(factory = ComptabiliteViewModel.Factory),
) {
    val exercices by vm.exercices.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Comptabilité") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    ListItem(
                        headlineContent = { Text("Journaux") },
                        supportingContent = { Text("Écritures générées par les ventes, achats, caisse, dépenses…") },
                        modifier = Modifier.clickable(onClick = onJournaux),
                    )
                    ListItem(
                        headlineContent = { Text("Plan comptable") },
                        supportingContent = { Text("Comptes PCG 2005, soldes et grand livre") },
                        modifier = Modifier.clickable(onClick = onPlan),
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Les achats sont comptés en charge (607). Le compte de stock 37 est ajusté à la valeur du stock " +
                            "calculée par l'application à chaque inventaire, à la clôture, ou avec ce bouton.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::mettreAJourStock, modifier = Modifier.fillMaxWidth()) {
                        Text("Mettre à jour la valeur du stock")
                    }
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            item { Text("Exercices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
            if (exercices.isEmpty()) item { Text("Aucune écriture pour l'instant.", Modifier.padding(horizontal = 16.dp)) }
            items(exercices, key = { it.id }) { e ->
                val cloture = e.statut == StatutExercice.CLOTURE
                ListItem(
                    headlineContent = { Text("${e.dateDebut} → ${e.dateFin}") },
                    supportingContent = { Text(if (cloture) "Clôturé le ${e.dateCloture?.let(::formaterDateHeure).orEmpty()}" else "Ouvert") },
                    trailingContent = {
                        if (!cloture && peutCloturer) TextButton(onClick = { vm.demanderCloture(e) }) { Text("Clôturer") }
                    },
                )
            }
        }
    }

    vm.aCloturer?.let { e ->
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Clôturer l'exercice ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Exercice ${e.dateDebut} → ${e.dateFin}. La variation de stock et le résultat (120 / 129) seront passés. " +
                            "Aucune écriture ne pourra plus être ajoutée à cet exercice. Faites d'abord un inventaire et une sauvegarde."
                    )
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::cloturer) { Text("Clôturer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}

// --- Journaux ---

@OptIn(ExperimentalCoroutinesApi::class)
class JournauxViewModel(compta: ComptaRepository) : ViewModel() {
    val mois = MutableStateFlow(YearMonth.now())
    val journal = MutableStateFlow<Journal?>(null)

    val ecritures: StateFlow<List<EcritureEntity>> = combine(mois, journal) { m, j -> m to j }
        .flatMapLatest { (m, j) -> compta.observerEcritures(m, j) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lignes: StateFlow<List<LigneEcritureAvecCompte>> = combine(mois, journal) { m, j -> m to j }
        .flatMapLatest { (m, j) -> compta.observerLignes(m, j) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = viewModelFactory {
            initializer { JournauxViewModel(container.comptaRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournauxScreen(onRetour: () -> Unit, vm: JournauxViewModel = viewModel(factory = JournauxViewModel.Factory)) {
    val mois by vm.mois.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    val ecritures by vm.ecritures.collectAsStateWithLifecycle()
    val lignes by vm.lignes.collectAsStateWithLifecycle()
    val parEcriture = remember(lignes) { lignes.groupBy { it.ligne.ecritureId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journaux") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SelecteurMois(mois) { vm.mois.value = it }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = journal == null, onClick = { vm.journal.value = null }, label = { Text("Tous") })
                    Journal.entries.forEach { j ->
                        FilterChip(selected = journal == j, onClick = { vm.journal.value = j }, label = { Text(j.name) })
                    }
                }
                Text(
                    journal?.libelle?.let { "Journal $it" } ?: "Tous les journaux",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                val totalDebit = lignes.sumOf { it.ligne.debit }
                val totalCredit = lignes.sumOf { it.ligne.credit }
                Text(
                    "Total débit ${Montant.formater(totalDebit)} · crédit ${Montant.formater(totalCredit)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (ecritures.isEmpty()) item { Text("Aucune écriture ce mois-ci.", Modifier.padding(horizontal = 16.dp)) }
            items(ecritures, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("N° ${e.numero} · ${e.journal.name} · ${formaterDateHeure(e.date)}", style = MaterialTheme.typography.labelMedium)
                        Text(e.libelle, style = MaterialTheme.typography.titleSmall)
                        HorizontalDivider()
                        parEcriture[e.id].orEmpty().forEach { l -> LigneMontants(l.ligne.compteNumero, l.compteLibelle, l.ligne.debit, l.ligne.credit) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LigneMontants(numero: String, libelle: String, debit: Long, credit: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            (if (credit > 0) "    " else "") + "$numero $libelle",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(if (debit > 0) Montant.formater(debit) else "", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
            if (credit > 0) "  " + Montant.formater(credit) else "",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun SelecteurMois(mois: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(mois.minusMonths(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
        }
        Text(mois.format(FORMAT_MOIS).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(mois.plusMonths(1)) }, enabled = mois < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
        }
    }
}

// --- Plan comptable ---

class PlanComptableViewModel(private val compta: ComptaRepository) : ViewModel() {
    val comptes: StateFlow<List<CompteAvecSolde>> = compta.observerComptes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var creationOuverte by mutableStateOf(false)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun ouvrirCreation() {
        erreur = null
        creationOuverte = true
    }

    fun fermer() {
        creationOuverte = false
    }

    fun creer(numero: String, libelle: String) {
        viewModelScope.launch {
            try {
                compta.creerSousCompte(numero, libelle)
                creationOuverte = false
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { PlanComptableViewModel(container.comptaRepository) }
        }
    }
}

private val CLASSES = mapOf(
    1 to "Classe 1 · Capitaux",
    2 to "Classe 2 · Immobilisations",
    3 to "Classe 3 · Stocks",
    4 to "Classe 4 · Tiers",
    5 to "Classe 5 · Trésorerie",
    6 to "Classe 6 · Charges",
    7 to "Classe 7 · Produits",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanComptableScreen(
    peutCreer: Boolean,
    onRetour: () -> Unit,
    onCompte: (numero: String) -> Unit,
    vm: PlanComptableViewModel = viewModel(factory = PlanComptableViewModel.Factory),
) {
    val comptes by vm.comptes.collectAsStateWithLifecycle()
    val parClasse = remember(comptes) { comptes.groupBy { it.compte.classe }.toSortedMap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plan comptable") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
        floatingActionButton = {
            if (peutCreer) FloatingActionButton(onClick = vm::ouvrirCreation) { Icon(Icons.Filled.Add, contentDescription = "Nouveau sous-compte") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            parClasse.forEach { (classe, liste) ->
                item(key = "classe_$classe") {
                    Text(
                        CLASSES[classe] ?: "Classe $classe",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(liste, key = { it.compte.id }) { c -> LigneCompte(c.compte, c.solde) { onCompte(c.compte.numero) } }
            }
        }
    }

    if (vm.creationOuverte) {
        var numero by rememberSaveable { mutableStateOf("") }
        var libelle by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::fermer,
            title = { Text("Nouveau sous-compte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Le numéro commence par celui d'un compte existant (ex. 5171 MVola sous 517 Mobile money).", style = MaterialTheme.typography.bodySmall)
                    ChampTexte(numero, { numero = it }, "Numéro", Modifier.fillMaxWidth(), KeyboardType.Number)
                    ChampTexte(libelle, { libelle = it }, "Libellé", Modifier.fillMaxWidth())
                    vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.creer(numero, libelle) }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
        )
    }
}

@Composable
private fun LigneCompte(compte: CompteEntity, solde: Long, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("${compte.numero}  ${compte.libelle}") },
        supportingContent = { if (!compte.deBase) Text("Sous-compte", style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            if (solde != 0L) {
                Text(
                    Montant.formater(kotlin.math.abs(solde)) + if (solde > 0) " D" else " C",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// --- Grand livre d'un compte ---

class GrandLivreViewModel(numero: String, compta: ComptaRepository) : ViewModel() {
    val compte: StateFlow<CompteEntity?> = compta.observerCompte(numero)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val mouvements: StateFlow<List<MouvementCompte>> = compta.observerGrandLivre(numero)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(numero: String) = viewModelFactory {
            initializer { GrandLivreViewModel(numero, container.comptaRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrandLivreScreen(
    numero: String,
    onRetour: () -> Unit,
    vm: GrandLivreViewModel = viewModel(key = "grand_livre_$numero", factory = GrandLivreViewModel.factory(numero)),
) {
    val compte by vm.compte.collectAsStateWithLifecycle()
    val mouvements by vm.mouvements.collectAsStateWithLifecycle()
    // Solde cumulé après chaque mouvement (débit − crédit), affiché du plus récent au plus ancien
    val avecSolde = remember(mouvements) {
        var cumul = 0L
        mouvements.map { m ->
            cumul += m.debit - m.credit
            m to cumul
        }.reversed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Grand livre $numero")
                        Text(compte?.libelle.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val solde = avecSolde.firstOrNull()?.second ?: 0L
                Text(
                    "Solde : " + Montant.formater(kotlin.math.abs(solde)) + when {
                        solde > 0 -> " débiteur"
                        solde < 0 -> " créditeur"
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (mouvements.isEmpty()) item { Text("Aucun mouvement.", Modifier.padding(horizontal = 16.dp)) }
            items(avecSolde, key = { it.first.ecritureId }) { (m, cumul) ->
                ListItem(
                    overlineContent = { Text("N° ${m.numero} · ${m.journal.name} · ${formaterDateHeure(m.date)}") },
                    headlineContent = { Text(m.libelle) },
                    supportingContent = { Text("Solde ${Montant.formater(cumul)}", style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        Text(
                            if (m.debit > 0) "D " + Montant.formater(m.debit) else "C " + Montant.formater(m.credit),
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                )
            }
        }
    }
}
