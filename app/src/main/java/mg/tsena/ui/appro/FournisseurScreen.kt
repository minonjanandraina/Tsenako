package mg.tsena.ui.appro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import mg.tsena.appro.ApproRepository
import mg.tsena.container
import mg.tsena.data.db.entity.FournisseurAvecSolde
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.PaiementFournisseurEntity
import mg.tsena.data.db.entity.ReceptionAPayer
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Telephone
import mg.tsena.domain.formaterDateHeure
import mg.tsena.domain.maintenant
import mg.tsena.finance.Echeances
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.finance.CoffreRepository
import mg.tsena.finance.FinanceRepository
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import mg.tsena.ui.finance.ChoixReglement

class FournisseurViewModel(
    private val fournisseurId: String,
    private val appro: ApproRepository,
    private val finance: FinanceRepository,
    coffre: CoffreRepository,
) : ViewModel() {
    val fournisseur: StateFlow<FournisseurAvecSolde?> = appro.observerFournisseurs()
        .map { liste -> liste.firstOrNull { it.fournisseur.id == fournisseurId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val receptions: StateFlow<List<ReceptionAPayer>> = finance.observerReceptionsAPayer(fournisseurId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val paiements: StateFlow<List<PaiementFournisseurEntity>> = finance.observerPaiementsFournisseur(fournisseurId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val caisses: StateFlow<List<SessionAvecDetails>> = finance.observerCaissesOuvertes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val soldesCoffre: StateFlow<Map<ModePaiement, Long>> = coffre.observerSoldes()
        .map { liste -> liste.associate { it.mode to it.solde } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    enum class Dialogue { MODIFIER, PAYER }

    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    /** Réception proposée au paiement */
    var receptionAPayer by mutableStateOf<ReceptionAPayer?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun modifier() {
        erreur = null
        dialogue = Dialogue.MODIFIER
    }

    /** [reception] null = acompte général. */
    fun payer(reception: ReceptionAPayer?) {
        erreur = null
        message = null
        receptionAPayer = reception
        dialogue = Dialogue.PAYER
    }

    fun fermer() {
        dialogue = null
    }

    fun enregistrer(nom: String, telephone: String, adresse: String, nif: String, delai: String, actif: Boolean) {
        val jours = delai.trim().ifEmpty { "0" }.toIntOrNull() ?: run { erreur = "Délai de paiement invalide (nombre de jours)."; return }
        executer { appro.enregistrerFournisseur(fournisseurId, nom, telephone, adresse, nif, jours, actif) }
    }

    fun enregistrerPaiement(montantTexte: String, mode: ModePaiement, pointVenteId: String?, reference: String, source: SourceFonds) {
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        val receptionId = receptionAPayer?.reception?.id
        executer {
            finance.payerFournisseur(fournisseurId, receptionId, montant, mode, pointVenteId, reference, source)
            message = "Paiement de ${Montant.formater(montant)} enregistré."
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
        fun factory(fournisseurId: String) = viewModelFactory {
            initializer { FournisseurViewModel(fournisseurId, container.approRepository, container.financeRepository, container.coffreRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FournisseurScreen(
    fournisseurId: String,
    peutPayer: Boolean,
    onRetour: () -> Unit,
    onReception: (receptionId: String) -> Unit,
    vm: FournisseurViewModel = viewModel(key = "fournisseur_$fournisseurId", factory = FournisseurViewModel.factory(fournisseurId)),
) {
    val fournisseur by vm.fournisseur.collectAsStateWithLifecycle()
    val receptions by vm.receptions.collectAsStateWithLifecycle()
    val paiements by vm.paiements.collectAsStateWithLifecycle()
    val caisses by vm.caisses.collectAsStateWithLifecycle()
    val soldesCoffre by vm.soldesCoffre.collectAsStateWithLifecycle()
    val f = fournisseur
    val maintenant = maintenant()
    val aPayer = receptions.filter { it.reste > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(f?.fournisseur?.nom ?: "Fournisseur") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = vm::modifier) { Icon(Icons.Filled.Edit, contentDescription = "Modifier") }
                },
            )
        },
        bottomBar = {
            if (peutPayer && (f?.solde ?: 0) > 0) {
                Button(onClick = { vm.payer(aPayer.firstOrNull()) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Payer le fournisseur")
                }
            }
        },
    ) { padding ->
        if (f == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LigneInfo("Téléphone", f.fournisseur.telephone?.let(Telephone::formater) ?: "—")
                        f.fournisseur.adresse?.let { LigneInfo("Adresse", it) }
                        f.fournisseur.nif?.let { LigneInfo("NIF", it) }
                        LigneInfo("Paiement", if (f.fournisseur.delaiPaiementJours == 0) "Comptant" else "${f.fournisseur.delaiPaiementJours} jours")
                        LigneInfo("Achats", Montant.formater(f.totalAchats))
                        if (f.totalRetours > 0) LigneInfo("Retours", "−" + Montant.formater(f.totalRetours))
                        LigneInfo("Payé", Montant.formater(f.totalPaye))
                        Text(
                            when {
                                f.solde > 0 -> "Reste à payer : ${Montant.formater(f.solde)}"
                                f.solde < 0 -> "Avoir chez le fournisseur : ${Montant.formater(-f.solde)}"
                                else -> "Rien à payer"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (f.solde > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            item { TitreSection("Réceptions") }
            if (receptions.isEmpty()) item { Text("Aucune réception.", Modifier.padding(horizontal = 16.dp)) }
            items(receptions.reversed(), key = { it.reception.id }) { r ->
                val retard = Echeances.enRetard(r.reception.date, f.fournisseur.delaiPaiementJours, r.reste, maintenant)
                ListItem(
                    overlineContent = { Text("${r.reception.numero} · ${formaterDateHeure(r.reception.date)}") },
                    headlineContent = { Text(Montant.formater(r.reception.montantTotal)) },
                    supportingContent = {
                        Text(
                            when {
                                r.reste <= 0 -> "Payée"
                                retard -> "Reste ${Montant.formater(r.reste)} · en retard depuis le " +
                                    formaterDateHeure(Echeances.echeance(r.reception.date, f.fournisseur.delaiPaiementJours))
                                else -> "Reste ${Montant.formater(r.reste)} · échéance " +
                                    formaterDateHeure(Echeances.echeance(r.reception.date, f.fournisseur.delaiPaiementJours))
                            },
                            color = if (retard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        if (peutPayer && r.reste > 0) TextButton(onClick = { vm.payer(r) }) { Text("Payer") }
                    },
                    modifier = Modifier.clickable { onReception(r.reception.id) },
                )
            }
            if (paiements.isNotEmpty()) {
                item { TitreSection("Paiements") }
                items(paiements, key = { it.id }) { p ->
                    val reception = receptions.firstOrNull { it.reception.id == p.receptionId }?.reception?.numero
                    ListItem(
                        overlineContent = { Text("${p.numero} · ${formaterDateHeure(p.date)}") },
                        headlineContent = { Text(Montant.formater(p.montant)) },
                        supportingContent = {
                            Text(listOfNotNull(p.modePaiement.libelle, reception ?: "Acompte", p.reference).joinToString(" · "))
                        },
                    )
                }
            }
        }
    }

    when (vm.dialogue) {
        FournisseurViewModel.Dialogue.MODIFIER -> f?.let { FournisseurDialog(it.fournisseur, vm.erreur, vm::enregistrer, vm::fermer) }
        FournisseurViewModel.Dialogue.PAYER -> PaiementFournisseurDialog(
            soldeDu = f?.solde ?: 0,
            receptions = aPayer,
            receptionInitiale = vm.receptionAPayer,
            caisses = caisses,
            soldesCoffre = soldesCoffre,
            erreur = vm.erreur,
            onReception = { vm.payer(it) },
            onValider = vm::enregistrerPaiement,
            onAnnuler = vm::fermer,
        )
        null -> Unit
    }
}

@Composable
private fun PaiementFournisseurDialog(
    soldeDu: Long,
    receptions: List<ReceptionAPayer>,
    receptionInitiale: ReceptionAPayer?,
    caisses: List<SessionAvecDetails>,
    soldesCoffre: Map<ModePaiement, Long>,
    erreur: String?,
    onReception: (ReceptionAPayer?) -> Unit,
    onValider: (montant: String, mode: ModePaiement, pointVenteId: String?, reference: String, source: SourceFonds) -> Unit,
    onAnnuler: () -> Unit,
) {
    val maximum = receptionInitiale?.reste ?: soldeDu
    var montant by rememberSaveable(receptionInitiale?.reception?.id) { mutableStateOf(maximum.toString()) }
    var mode by rememberSaveable { mutableStateOf(ModePaiement.ESPECES) }
    var pointVenteId by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf(SourceFonds.CAISSE) }
    var reference by rememberSaveable { mutableStateOf("") }
    val pvEffectif = pointVenteId ?: caisses.firstOrNull()?.pointVenteId
    val options: List<ReceptionAPayer?> = receptions + listOf(null)

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Payer le fournisseur") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Solde dû : ${Montant.formater(soldeDu)}")
                Selecteur(
                    libelle = "Pour",
                    valeurAffichee = libelleReception(receptionInitiale),
                    options = options,
                    texte = ::libelleReception,
                    onSelection = onReception,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChampTexte(montant, { montant = it }, "Montant (au plus ${Montant.formater(maximum)})", Modifier.fillMaxWidth(), KeyboardType.Number)
                ChoixReglement(
                    mode, { mode = it }, caisses, pvEffectif, { pointVenteId = it },
                    source = source, onSource = { source = it }, soldesCoffre = soldesCoffre,
                )
                ChampTexte(reference, { reference = it }, "Référence (facultatif)", Modifier.fillMaxWidth())
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(montant, mode, pvEffectif, reference, source) }) { Text("Payer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

private fun libelleReception(r: ReceptionAPayer?): String =
    r?.let { "${it.reception.numero} (reste ${Montant.formater(it.reste)})" } ?: "Acompte (sans réception précise)"
