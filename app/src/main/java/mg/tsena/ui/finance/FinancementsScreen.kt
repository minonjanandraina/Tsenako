package mg.tsena.ui.finance

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.container
import mg.tsena.data.db.entity.FinancementAvecSolde
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.RemboursementEmpruntEntity
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.data.db.entity.StatutFinancement
import mg.tsena.data.db.entity.TypeFinancement
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.CoffreRepository
import mg.tsena.finance.FinanceRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.masquerClavierAuToucher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FORMAT_JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun formaterJour(millis: Long): String = FORMAT_JOUR.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalCoroutinesApi::class)
class FinancementsViewModel(private val finance: FinanceRepository, coffre: CoffreRepository) : ViewModel() {
    val financements: StateFlow<List<FinancementAvecSolde>> = finance.observerFinancements()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val caisses: StateFlow<List<SessionAvecDetails>> = finance.observerCaissesOuvertes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val soldesCoffre: StateFlow<Map<ModePaiement, Long>> = coffre.observerSoldes()
        .map { liste -> liste.associate { it.mode to it.solde } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val detailId = MutableStateFlow<String?>(null)

    /** Financement ouvert, relu à chaque changement (reste dû après un remboursement). */
    val detail: StateFlow<FinancementAvecSolde?> = combine(detailId, financements) { id, liste -> liste.firstOrNull { it.financement.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val remboursements: StateFlow<List<RemboursementEmpruntEntity>> = detailId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else finance.observerRemboursements(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Dialogue { NOUVEAU, REMBOURSER, ANNULER }

    /** Type du financement en cours de saisie (dialogue NOUVEAU) */
    var typeSaisi by mutableStateOf(TypeFinancement.CAPITAL)
        private set
    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set

    fun nouveau(type: TypeFinancement) {
        erreur = null
        typeSaisi = type
        detailId.value = null
        dialogue = Dialogue.NOUVEAU
    }

    fun ouvrir(f: FinancementAvecSolde) {
        erreur = null
        dialogue = null
        detailId.value = f.financement.id
    }

    fun demander(d: Dialogue) {
        erreur = null
        dialogue = d
    }

    fun fermer() {
        dialogue = null
        detailId.value = null
    }

    fun enregistrer(
        tiers: String,
        etablissementCredit: Boolean,
        montantTexte: String,
        dureeMoisTexte: String,
        description: String,
        mode: ModePaiement,
        source: SourceFonds,
        pointVenteId: String?,
    ) {
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        val duree = dureeMoisTexte.trim().takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: run { erreur = "Durée invalide."; return } }
        if (duree != null && duree <= 0) { erreur = "La durée doit être d'au moins un mois."; return }
        val echeance = duree?.let {
            val zone = ZoneId.systemDefault()
            LocalDate.now(zone).plusMonths(it).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        executer { finance.enregistrerFinancement(typeSaisi, tiers, etablissementCredit, montant, mode, pointVenteId, echeance, description, source) }
    }

    fun rembourser(
        capitalTexte: String,
        interetsTexte: String,
        mode: ModePaiement,
        source: SourceFonds,
        pointVenteId: String?,
        reference: String,
    ) {
        val f = detail.value ?: return
        val capital = Montant.parser(capitalTexte.ifBlank { "0" }) ?: run { erreur = "Capital invalide."; return }
        val interets = Montant.parser(interetsTexte.ifBlank { "0" }) ?: run { erreur = "Intérêts invalides."; return }
        executer(fermerDetail = false) { finance.rembourserEmprunt(f.financement.id, capital, interets, mode, pointVenteId, reference, source) }
    }

    fun annuler(motif: String) {
        val f = detail.value ?: return
        executer { finance.annulerFinancement(f.financement.id, motif) }
    }

    private fun executer(fermerDetail: Boolean = true, action: suspend () -> Unit) {
        erreur = null
        viewModelScope.launch {
            try {
                action()
                dialogue = null
                if (fermerDetail) detailId.value = null
            } catch (e: RegleMetierException) {
                erreur = e.message
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { FinancementsViewModel(container.financeRepository, container.coffreRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancementsScreen(onRetour: () -> Unit, vm: FinancementsViewModel = viewModel(factory = FinancementsViewModel.Factory)) {
    val financements by vm.financements.collectAsStateWithLifecycle()
    val caisses by vm.caisses.collectAsStateWithLifecycle()
    val soldesCoffre by vm.soldesCoffre.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val remboursements by vm.remboursements.collectAsStateWithLifecycle()
    val valides = financements.filter { it.financement.statut == StatutFinancement.VALIDE }
    val emprunts = valides.filter { it.financement.type == TypeFinancement.EMPRUNT }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capital et emprunts") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Ligne("Capital injecté", valides.filter { it.financement.type == TypeFinancement.CAPITAL }.sumOf { it.financement.montant })
                        Ligne("Emprunts reçus", emprunts.sumOf { it.financement.montant })
                        Ligne("Capital remboursé", emprunts.sumOf { it.capitalRembourse })
                        Ligne("Intérêts payés", emprunts.sumOf { it.interetsPayes })
                        HorizontalDivider()
                        Ligne("Reste à rembourser", emprunts.sumOf { it.reste }, important = true)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.nouveau(TypeFinancement.CAPITAL) }, modifier = Modifier.weight(1f)) { Text("Injecter du capital") }
                    OutlinedButton(onClick = { vm.nouveau(TypeFinancement.EMPRUNT) }, modifier = Modifier.weight(1f)) { Text("Nouvel emprunt") }
                }
                Text(
                    "Capital : argent apporté définitivement par le propriétaire (compte 101). Emprunt : argent prêté, " +
                        "à rembourser (164 banque / microfinance, 168 famille ou autre ; intérêts en 661).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (financements.isEmpty()) item { Text("Aucun apport ni emprunt enregistré.", Modifier.padding(horizontal = 16.dp)) }
            items(financements, key = { it.financement.id }) { f ->
                val e = f.financement
                val annule = e.statut == StatutFinancement.ANNULE
                val situation = when {
                    annule -> "Annulé"
                    e.type == TypeFinancement.CAPITAL -> lieu(e.modePaiement, e.source)
                    f.reste == 0L -> "Entièrement remboursé"
                    else -> "Reste dû ${Montant.formater(f.reste)}" + (e.echeance?.let { " · échéance ${formaterJour(it)}" } ?: "")
                }
                val enRetard = !annule && f.reste > 0 && e.echeance != null && e.echeance < System.currentTimeMillis()
                ListItem(
                    overlineContent = { Text("${e.numero} · ${formaterDateHeure(e.date)}") },
                    headlineContent = { Text("${e.type.libelle} · ${e.tiers}") },
                    supportingContent = {
                        Text(
                            situation + if (enRetard) " · en retard" else "",
                            color = if (enRetard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Text(Montant.formater(e.montant), style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.clickable { vm.ouvrir(f) }.alpha(if (annule) 0.5f else 1f),
                )
            }
        }
    }

    when (vm.dialogue) {
        FinancementsViewModel.Dialogue.NOUVEAU -> NouveauFinancementDialog(vm, caisses, soldesCoffre)
        FinancementsViewModel.Dialogue.REMBOURSER -> detail?.let { RemboursementDialog(vm, it, caisses, soldesCoffre) }
        FinancementsViewModel.Dialogue.ANNULER -> detail?.let { f ->
            var motif by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = vm::fermer,
                modifier = Modifier.masquerClavierAuToucher(),
                title = { Text("Annuler ${f.financement.numero} ?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("À utiliser pour corriger une erreur de saisie. Si l'argent est entré dans une caisse, il en ressort (caisse encore ouverte uniquement).")
                        ChampTexte(motif, { motif = it }, "Motif", Modifier.fillMaxWidth())
                        vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.annuler(motif) }) { Text("Annuler la saisie") } },
                dismissButton = { TextButton(onClick = vm::fermer) { Text("Retour") } },
            )
        }
        null -> detail?.let { f ->
            val e = f.financement
            AlertDialog(
                onDismissRequest = vm::fermer,
                title = { Text("${e.numero} · ${Montant.formater(e.montant)}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${e.type.libelle} · ${e.tiers} (compte ${e.compteNumero})", style = MaterialTheme.typography.titleSmall)
                        Text("${formaterDateHeure(e.date)} · ${lieu(e.modePaiement, e.source)}")
                        e.description?.let { Text(it) }
                        if (e.statut == StatutFinancement.ANNULE) {
                            Text("Annulé : ${e.motifAnnulation.orEmpty()}", color = MaterialTheme.colorScheme.error)
                        }
                        if (e.type == TypeFinancement.EMPRUNT) {
                            e.echeance?.let { Text("Échéance : ${formaterJour(it)}") }
                            Ligne("Capital remboursé", f.capitalRembourse)
                            Ligne("Intérêts payés", f.interetsPayes)
                            Ligne("Reste dû", f.reste, important = true)
                            if (remboursements.isNotEmpty()) {
                                Text("Remboursements", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                                remboursements.forEach { r ->
                                    Text(
                                        "${r.numero} · ${formaterDateHeure(r.date)} · capital ${Montant.formater(r.capital)}" +
                                            (if (r.interets > 0) ", intérêts ${Montant.formater(r.interets)}" else "") + " · ${lieu(r.modePaiement, r.source)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Row {
                        if (f.reste > 0) {
                            TextButton(onClick = { vm.demander(FinancementsViewModel.Dialogue.REMBOURSER) }) { Text("Rembourser") }
                        }
                        TextButton(onClick = vm::fermer) { Text("Fermer") }
                    }
                },
                dismissButton = {
                    if (e.statut == StatutFinancement.VALIDE && remboursements.isEmpty()) {
                        TextButton(onClick = { vm.demander(FinancementsViewModel.Dialogue.ANNULER) }) {
                            Text("Annuler", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun NouveauFinancementDialog(vm: FinancementsViewModel, caisses: List<SessionAvecDetails>, soldesCoffre: Map<ModePaiement, Long>) {
    val emprunt = vm.typeSaisi == TypeFinancement.EMPRUNT
    var tiers by rememberSaveable { mutableStateOf("") }
    var etablissement by rememberSaveable { mutableStateOf(true) }
    var montant by rememberSaveable { mutableStateOf("") }
    var duree by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ModePaiement.ESPECES) }
    var source by rememberSaveable { mutableStateOf(SourceFonds.COFFRE) }
    var pointVenteId by rememberSaveable { mutableStateOf<String?>(null) }
    val caisse = pointVenteId ?: caisses.firstOrNull()?.pointVenteId

    AlertDialog(
        onDismissRequest = vm::fermer,
        modifier = Modifier.masquerClavierAuToucher(),
        title = { Text(if (emprunt) "Nouvel emprunt" else "Injection de capital") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val largeur = Modifier.fillMaxWidth()
                if (emprunt) {
                    ChampTexte(tiers, { tiers = it }, "Prêteur (banque, microfinance, personne…)", largeur)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = etablissement, onClick = { etablissement = true }, label = { Text("Banque / microfinance") })
                        FilterChip(selected = !etablissement, onClick = { etablissement = false }, label = { Text("Famille, autre") })
                    }
                } else {
                    ChampTexte(tiers, { tiers = it }, "Apporté par (vide = propriétaire)", largeur)
                }
                ChampTexte(montant, { montant = it }, "Montant (Ar)", largeur, KeyboardType.Number)
                if (emprunt) ChampTexte(duree, { duree = it }, "Durée en mois (facultatif)", largeur, KeyboardType.Number)
                ChampTexte(description, { description = it }, "Note (facultatif)", largeur)
                ChoixReglement(
                    mode = mode,
                    onMode = { mode = it },
                    caisses = caisses,
                    pointVenteId = caisse,
                    onPointVente = { pointVenteId = it },
                    source = source,
                    onSource = { source = it },
                    soldesCoffre = soldesCoffre,
                )
                vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.enregistrer(tiers, etablissement, montant, duree, description, mode, source, caisse) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = vm::fermer) { Text("Annuler") } },
    )
}

@Composable
private fun RemboursementDialog(
    vm: FinancementsViewModel,
    f: FinancementAvecSolde,
    caisses: List<SessionAvecDetails>,
    soldesCoffre: Map<ModePaiement, Long>,
) {
    var capital by rememberSaveable { mutableStateOf(f.reste.toString()) }
    var interets by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ModePaiement.ESPECES) }
    var source by rememberSaveable { mutableStateOf(SourceFonds.COFFRE) }
    var pointVenteId by rememberSaveable { mutableStateOf<String?>(null) }
    val caisse = pointVenteId ?: caisses.firstOrNull()?.pointVenteId

    AlertDialog(
        onDismissRequest = vm::fermer,
        modifier = Modifier.masquerClavierAuToucher(),
        title = { Text("Rembourser ${f.financement.tiers}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val largeur = Modifier.fillMaxWidth()
                Text("Reste dû : ${Montant.formater(f.reste)}")
                ChampTexte(capital, { capital = it }, "Capital remboursé (Ar)", largeur, KeyboardType.Number)
                ChampTexte(interets, { interets = it }, "Intérêts payés (Ar)", largeur, KeyboardType.Number)
                ChampTexte(reference, { reference = it }, "Référence (facultatif)", largeur)
                ChoixReglement(
                    mode = mode,
                    onMode = { mode = it },
                    caisses = caisses,
                    pointVenteId = caisse,
                    onPointVente = { pointVenteId = it },
                    source = source,
                    onSource = { source = it },
                    soldesCoffre = soldesCoffre,
                )
                vm.erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.rembourser(capital, interets, mode, source, caisse, reference) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = { vm.ouvrir(f) }) { Text("Retour") } },
    )
}

/** Mode de paiement et, pour les espèces ou le mobile money, coffre ou caisse. */
private fun lieu(mode: ModePaiement, source: SourceFonds): String =
    if (mode == ModePaiement.BANQUE) mode.libelle else "${mode.libelle} · ${source.libelle}"

@Composable
private fun Ligne(libelle: String, montant: Long, important: Boolean = false) {
    val style = if (important) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, Modifier.weight(1f), style = style)
        Text(Montant.formater(montant), style = style)
    }
}
