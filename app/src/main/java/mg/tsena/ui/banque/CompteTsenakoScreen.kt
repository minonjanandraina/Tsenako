package mg.tsena.ui.banque

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.appro.ApproRepository
import mg.tsena.banque.BanqueRepository
import mg.tsena.container
import mg.tsena.data.db.entity.CompteBancaireEntity
import mg.tsena.data.db.entity.FournisseurAvecSolde
import mg.tsena.data.db.entity.OperationBancaireEntity
import mg.tsena.data.db.entity.ReceptionAPayer
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.finance.FinanceRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur

@OptIn(ExperimentalCoroutinesApi::class)
class CompteTsenakoViewModel(
    private val banque: BanqueRepository,
    appro: ApproRepository,
    finance: FinanceRepository,
) : ViewModel() {
    val compte: StateFlow<CompteBancaireEntity?> = banque.observerCompte()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val solde: StateFlow<Long> = banque.observerSolde()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val operations: StateFlow<List<OperationBancaireEntity>> = banque.observerOperations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Fournisseurs à qui l'on doit encore de l'argent */
    val fournisseursAPayer: StateFlow<List<FournisseurAvecSolde>> = appro.observerFournisseurs()
        .map { liste -> liste.filter { it.solde > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val fournisseurId = MutableStateFlow<String?>(null)
    val receptionsAPayer: StateFlow<List<ReceptionAPayer>> = fournisseurId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else finance.observerReceptionsAPayer(id) }
        .map { liste -> liste.filter { it.reste > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val banqueNom: String get() = banque.passerelle.banque
    val simulation: Boolean get() = banque.passerelle.simulation

    enum class Dialogue { APPROVISIONNER, PAYER }

    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun ouvrir(d: Dialogue) {
        erreur = null
        message = null
        if (d == Dialogue.PAYER) fournisseurId.value = null
        dialogue = d
    }

    fun fermer() {
        dialogue = null
    }

    fun choisirFournisseur(id: String) {
        fournisseurId.value = id
    }

    fun ouvrirCompte() = executer { banque.ouvrirCompte() }

    fun approvisionner(montantTexte: String) {
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        executer {
            banque.approvisionner(montant)
            message = "${Montant.formater(montant)} arrivés sur le compte Tsenako."
        }
    }

    fun payer(fournisseurId: String?, receptionId: String?, montantTexte: String, reference: String) {
        if (fournisseurId == null) { erreur = "Choisissez le fournisseur."; return }
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        executer {
            banque.payerFournisseur(fournisseurId, receptionId, montant, reference)
            message = "Virement de ${Montant.formater(montant)} envoyé au fournisseur."
        }
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
        val Factory = viewModelFactory {
            initializer { CompteTsenakoViewModel(container.banqueRepository, container.approRepository, container.financeRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompteTsenakoScreen(
    onRetour: () -> Unit,
    vm: CompteTsenakoViewModel = viewModel(factory = CompteTsenakoViewModel.Factory),
) {
    val compte by vm.compte.collectAsStateWithLifecycle()
    val solde by vm.solde.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val fournisseurs by vm.fournisseursAPayer.collectAsStateWithLifecycle()
    val receptions by vm.receptionsAPayer.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compte Tsenako") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val c = compte
            if (c == null) {
                item { OuvertureCompte(vm.banqueNom, vm.simulation, vm.erreur, vm::ouvrirCompte) }
                return@LazyColumn
            }
            item { CarteCompte(c, solde, vm.simulation) }
            item { InfoApprovisionnement(c) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.ouvrir(CompteTsenakoViewModel.Dialogue.PAYER) }, modifier = Modifier.weight(1f)) {
                        Text("Payer un fournisseur")
                    }
                    if (vm.simulation) {
                        OutlinedButton(onClick = { vm.ouvrir(CompteTsenakoViewModel.Dialogue.APPROVISIONNER) }, modifier = Modifier.weight(1f)) {
                            Text("Simuler un WTB")
                        }
                    }
                }
            }
            vm.message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary) } }
            item { Text("Opérations", style = MaterialTheme.typography.titleSmall) }
            if (operations.isEmpty()) {
                item { Text("Aucune opération pour l'instant.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(operations, key = { it.id }) { o ->
                ListItem(
                    headlineContent = { Text("${o.type.libelle} · ${o.numero}") },
                    supportingContent = { Text("${formaterDateHeure(o.date)} · ${o.libelle}\nRéf. ${o.reference}") },
                    trailingContent = {
                        Text(
                            (if (o.type.entree) "+ " else "− ") + Montant.formater(o.montant),
                            color = if (o.type.entree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }

    when (vm.dialogue) {
        CompteTsenakoViewModel.Dialogue.APPROVISIONNER -> ApprovisionnementDialog(vm.erreur, vm::approvisionner, vm::fermer)
        CompteTsenakoViewModel.Dialogue.PAYER -> PaiementTsenakoDialog(
            solde = solde,
            fournisseurs = fournisseurs,
            receptions = receptions,
            erreur = vm.erreur,
            onFournisseur = vm::choisirFournisseur,
            onValider = vm::payer,
            onAnnuler = vm::fermer,
        )
        null -> Unit
    }
}

@Composable
private fun OuvertureCompte(banque: String, simulation: Boolean, erreur: String?, onOuvrir: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Votre compte Tsenako", style = MaterialTheme.typography.titleMedium)
            Text(
                "Un compte bancaire pour votre commerce, tenu par la banque $banque. Approvisionnez-le depuis votre " +
                    "mobile money et payez vos fournisseurs directement depuis Tsena.",
            )
            if (simulation) {
                Text("Version de démonstration : aucun argent réel ne circule.", style = MaterialTheme.typography.bodySmall)
            }
            erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onOuvrir, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir mon compte Tsenako") }
        }
    }
}

@Composable
private fun CarteCompte(compte: CompteBancaireEntity, solde: Long, simulation: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${compte.intitule} · Banque ${compte.banque}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (simulation) AssistChip(onClick = {}, label = { Text("Simulation") })
            }
            Text("N° ${compte.numero}", style = MaterialTheme.typography.bodyMedium)
            Text("Solde", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Text(Montant.formater(solde), style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** RG-B3 : information seulement ; les WTB et BTW se font depuis le menu mobile money de l'opérateur. */
@Composable
private fun InfoApprovisionnement(compte: CompteBancaireEntity) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Approvisionner votre compte", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Depuis votre mobile money, faites un WTB (Wallet to Bank) vers le compte ${compte.banque} n° ${compte.numero}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Pour remettre de l'argent sur votre mobile money, faites un BTW (Bank to Wallet).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ApprovisionnementDialog(erreur: String?, onValider: (String) -> Unit, onAnnuler: () -> Unit) {
    var montant by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Simuler un WTB") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Simule l'arrivée d'argent envoyé depuis votre mobile money. Comptabilisé comme un apport du propriétaire.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChampTexte(montant, { montant = it }, "Montant (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(montant) }) { Text("Approvisionner") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun PaiementTsenakoDialog(
    solde: Long,
    fournisseurs: List<FournisseurAvecSolde>,
    receptions: List<ReceptionAPayer>,
    erreur: String?,
    onFournisseur: (String) -> Unit,
    onValider: (fournisseurId: String?, receptionId: String?, montant: String, reference: String) -> Unit,
    onAnnuler: () -> Unit,
) {
    var fournisseurId by rememberSaveable { mutableStateOf<String?>(null) }
    var receptionId by rememberSaveable { mutableStateOf<String?>(null) }
    var montant by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    val fournisseur = fournisseurs.firstOrNull { it.fournisseur.id == fournisseurId }
    val reception = receptions.firstOrNull { it.reception.id == receptionId }
    val options: List<ReceptionAPayer?> = receptions + listOf(null)

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Payer un fournisseur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sur le compte Tsenako : ${Montant.formater(solde)}")
                if (fournisseurs.isEmpty()) {
                    Text("Aucun fournisseur à payer.", color = MaterialTheme.colorScheme.error)
                } else {
                    Selecteur(
                        libelle = "Fournisseur",
                        valeurAffichee = fournisseur?.let { "${it.fournisseur.nom} (dû ${Montant.formater(it.solde)})" }.orEmpty(),
                        options = fournisseurs,
                        texte = { "${it.fournisseur.nom} (dû ${Montant.formater(it.solde)})" + if (it.fournisseur.idTsenako == null) " · sans id Tsenako" else "" },
                        onSelection = {
                            fournisseurId = it.fournisseur.id
                            receptionId = null
                            montant = minOf(it.solde, solde).toString()
                            onFournisseur(it.fournisseur.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (fournisseur != null) {
                    val id = fournisseur.fournisseur.idTsenako
                    if (id == null) {
                        Text("Ce fournisseur n'a pas d'id Tsenako : renseignez-le dans sa fiche.", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Virement vers l'id Tsenako $id", style = MaterialTheme.typography.bodySmall)
                    }
                    Selecteur(
                        libelle = "Pour",
                        valeurAffichee = libelleReception(reception),
                        options = options,
                        texte = ::libelleReception,
                        onSelection = { r ->
                            receptionId = r?.reception?.id
                            montant = minOf(r?.reste ?: fournisseur.solde, solde).toString()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChampTexte(montant, { montant = it }, "Montant (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                    ChampTexte(reference, { reference = it }, "Référence (facultatif)", Modifier.fillMaxWidth())
                }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(fournisseurId, receptionId, montant, reference) }) { Text("Payer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

private fun libelleReception(r: ReceptionAPayer?): String =
    r?.let { "${it.reception.numero} (reste ${Montant.formater(it.reste)})" } ?: "Acompte (sans réception précise)"
