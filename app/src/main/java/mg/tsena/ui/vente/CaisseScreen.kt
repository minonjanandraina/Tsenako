package mg.tsena.ui.vente

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import mg.tsena.container
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationCaisseEntity
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.ResumeVentes
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.TypeOperationCaisse
import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.formaterDateHeure
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import mg.tsena.finance.FinanceRepository
import mg.tsena.vente.CalculsVente
import mg.tsena.vente.VenteRepository

@OptIn(ExperimentalCoroutinesApi::class)
class CaisseViewModel(private val ventes: VenteRepository) : ViewModel() {
    val pointsVente: StateFlow<List<PointVenteAvecDepot>> = ventes.observerPointsVente()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val choix = MutableStateFlow<String?>(null)

    /** Point de vente affiché : celui choisi, sinon le premier visible. */
    val pointVenteId: StateFlow<String?> = combine(choix, pointsVente) { id, liste ->
        liste.firstOrNull { it.pointVente.id == id }?.pointVente?.id ?: liste.firstOrNull()?.pointVente?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sessionOuverte: StateFlow<SessionAvecDetails?> = pointVenteId
        .flatMapLatest { id -> if (id == null) flowOf(null) else ventes.observerSessionOuverte(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val operations: StateFlow<List<OperationCaisseEntity>> = sessionOuverte
        .flatMapLatest { s -> if (s == null) flowOf(emptyList()) else ventes.observerOperations(s.session.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val resume: StateFlow<ResumeVentes> = sessionOuverte
        .flatMapLatest { s -> if (s == null) flowOf(ResumeVentes(0, 0)) else ventes.observerResumeVentes(s.session.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResumeVentes(0, 0))

    val historique: StateFlow<List<SessionAvecDetails>> = pointVenteId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else ventes.observerSessionsFermees(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Dialogue { OUVRIR, APPORT, RETRAIT, CLOTURER, PARAMETRES }

    var dialogue by mutableStateOf<Dialogue?>(null)
        private set
    var erreur by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var remiseMax by mutableStateOf(0)
        private set

    fun choisir(id: String) {
        choix.value = id
        message = null
    }

    fun ouvrir(d: Dialogue) {
        erreur = null
        message = null
        if (d == Dialogue.PARAMETRES) viewModelScope.launch { remiseMax = ventes.remiseMaxPourcent() }
        dialogue = d
    }

    fun fermer() {
        dialogue = null
    }

    fun ouvrirCaisse(fond: String) {
        val pv = pointVenteId.value ?: return
        val montant = Montant.parser(fond.ifBlank { "0" }) ?: run { erreur = "Montant invalide."; return }
        executer { ventes.ouvrirCaisse(pv, montant) }
    }

    fun mouvement(montantTexte: String, libelle: String) {
        val s = sessionOuverte.value ?: return
        val type = if (dialogue == Dialogue.APPORT) TypeOperationCaisse.APPORT else TypeOperationCaisse.RETRAIT
        val montant = Montant.parser(montantTexte) ?: run { erreur = "Montant invalide."; return }
        executer { ventes.mouvementManuel(s.session.id, type, montant, libelle) }
    }

    fun cloturer(compteTexte: String) {
        val s = sessionOuverte.value ?: return
        val compte = Montant.parser(compteTexte) ?: run { erreur = "Montant compté invalide."; return }
        executer {
            val ecart = ventes.cloturerCaisse(s.session.id, compte)
            message = when {
                ecart == 0L -> "Caisse clôturée, sans écart."
                ecart > 0 -> "Caisse clôturée. Excédent de ${Montant.formater(ecart)}."
                else -> "Caisse clôturée. Manque de ${Montant.formater(-ecart)}."
            }
        }
    }

    fun enregistrerRemiseMax(texte: String) {
        val pourcent = texte.trim().toIntOrNull() ?: run { erreur = "Pourcentage invalide."; return }
        executer { ventes.definirRemiseMax(pourcent) }
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
            initializer { CaisseViewModel(container.venteRepository) }
        }
    }
}

/** Totaux d'une session : solde espèces (RG-C2) et encaissements par mode (RG-C5). */
private data class TotauxCaisse(val soldeEspeces: Long, val parMode: Map<ModePaiement, Long>)

private fun totaux(fond: Long, operations: List<OperationCaisseEntity>): TotauxCaisse =
    TotauxCaisse(
        soldeEspeces = CalculsVente.soldeTheorique(fond, operations.filter { it.mode == ModePaiement.ESPECES }.map { it.type to it.montant }),
        parMode = operations.filter { it.mode != ModePaiement.ESPECES }
            .groupBy { it.mode }
            .mapValues { (_, ops) -> ops.sumOf { if (it.type.entree) it.montant else -it.montant } },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaisseScreen(
    peutParametrer: Boolean,
    peutVendre: Boolean,
    onRetour: () -> Unit,
    onVendre: (pointVenteId: String) -> Unit,
    vm: CaisseViewModel = viewModel(factory = CaisseViewModel.Factory),
) {
    val pointsVente by vm.pointsVente.collectAsStateWithLifecycle()
    val pointVenteId by vm.pointVenteId.collectAsStateWithLifecycle()
    val session by vm.sessionOuverte.collectAsStateWithLifecycle()
    val operations by vm.operations.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    val historique by vm.historique.collectAsStateWithLifecycle()
    val s = session
    val t = s?.let { totaux(it.session.fondInitial, operations) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Caisse") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (peutParametrer) {
                        IconButton(onClick = { vm.ouvrir(CaisseViewModel.Dialogue.PARAMETRES) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Paramètres de vente")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (s != null && peutVendre && pointVenteId != null) {
                Button(onClick = { pointVenteId?.let(onVendre) }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = null)
                    Text("  Nouvelle vente")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pointsVente.isEmpty()) {
                        Text("Aucun point de vente disponible. Un vendeur doit être rattaché à un point de vente (écran Utilisateurs).")
                    } else if (pointsVente.size > 1) {
                        Selecteur(
                            libelle = "Point de vente",
                            valeurAffichee = pointsVente.firstOrNull { it.pointVente.id == pointVenteId }?.pointVente?.nom.orEmpty(),
                            options = pointsVente,
                            texte = { it.pointVente.nom },
                            onSelection = { vm.choisir(it.pointVente.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(pointsVente.first().pointVente.nom, style = MaterialTheme.typography.titleLarge)
                    }
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }

            if (pointVenteId != null) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (s == null || t == null) {
                                Text("Caisse fermée", style = MaterialTheme.typography.titleMedium)
                                Text("Ouvrez la caisse en comptant le fond de caisse (espèces) pour commencer à vendre.")
                                Button(onClick = { vm.ouvrir(CaisseViewModel.Dialogue.OUVRIR) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Ouvrir la caisse")
                                }
                            } else {
                                Text("Caisse ouverte", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Ligne("Ouverte par", "${s.ouvertParNom}, ${formaterDateHeure(s.session.ouverture)}")
                                Ligne("Fond de caisse", Montant.formater(s.session.fondInitial))
                                Ligne("Ventes", "${resume.nb} · ${Montant.formater(resume.total)}")
                                if (resume.credit > 0) {
                                    Ligne("dont au comptant", Montant.formater(resume.total - resume.credit))
                                    Ligne("dont à crédit (à encaisser)", Montant.formater(resume.credit))
                                }
                                val remboursements = operations
                                    .filter { it.documentType == FinanceRepository.DOCUMENT_ENCAISSEMENT }
                                    .sumOf { it.montant }
                                if (remboursements > 0) Ligne("Remboursements de crédits reçus", Montant.formater(remboursements))
                                HorizontalDivider()
                                Ligne("Espèces en caisse", Montant.formater(t.soldeEspeces), important = true)
                                t.parMode.forEach { (mode, montant) -> Ligne(mode.libelle, Montant.formater(montant)) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { vm.ouvrir(CaisseViewModel.Dialogue.APPORT) }, modifier = Modifier.weight(1f)) {
                                        Text("Apport")
                                    }
                                    OutlinedButton(onClick = { vm.ouvrir(CaisseViewModel.Dialogue.RETRAIT) }, modifier = Modifier.weight(1f)) {
                                        Text("Retrait")
                                    }
                                }
                                Button(onClick = { vm.ouvrir(CaisseViewModel.Dialogue.CLOTURER) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Clôturer la caisse")
                                }
                            }
                        }
                    }
                }
            }

            if (s != null && operations.isNotEmpty()) {
                item { Titre("Opérations de la session") }
                items(operations, key = { it.id }) { op ->
                    ListItem(
                        headlineContent = { Text(op.libelle) },
                        supportingContent = { Text("${formaterDateHeure(op.date)} · ${op.mode.libelle}") },
                        trailingContent = {
                            Text(
                                (if (op.type.entree) "+" else "−") + Montant.formater(op.montant),
                                color = if (op.type.entree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }

            if (historique.isNotEmpty()) {
                item { Titre("Sessions clôturées") }
                items(historique, key = { it.session.id }) { h ->
                    val ecart = h.session.ecart ?: 0L
                    ListItem(
                        headlineContent = { Text("${formaterDateHeure(h.session.ouverture)} → ${h.session.fermeture?.let(::formaterDateHeure).orEmpty()}") },
                        supportingContent = {
                            Text("${h.ouvertParNom} · compté ${Montant.formater(h.session.soldeCompte ?: 0)}")
                        },
                        trailingContent = {
                            Text(
                                if (ecart == 0L) "Sans écart" else "Écart " + (if (ecart > 0) "+" else "") + Montant.formater(ecart),
                                color = if (ecart < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }

    when (vm.dialogue) {
        CaisseViewModel.Dialogue.OUVRIR -> SaisieMontantDialog(
            titre = "Ouvrir la caisse",
            explication = "Comptez les espèces présentes dans la caisse.",
            libelle = "Fond de caisse (Ar)",
            erreur = vm.erreur,
            avecLibelle = false,
            onValider = { montant, _ -> vm.ouvrirCaisse(montant) },
            onAnnuler = vm::fermer,
        )
        CaisseViewModel.Dialogue.APPORT, CaisseViewModel.Dialogue.RETRAIT -> {
            val apport = vm.dialogue == CaisseViewModel.Dialogue.APPORT
            SaisieMontantDialog(
                titre = if (apport) "Apport d'espèces" else "Retrait d'espèces",
                explication = if (apport) "Argent ajouté dans la caisse (ex. monnaie)." else "Argent sorti de la caisse (ex. versement au propriétaire).",
                libelle = "Montant (Ar)",
                erreur = vm.erreur,
                avecLibelle = true,
                onValider = vm::mouvement,
                onAnnuler = vm::fermer,
            )
        }
        CaisseViewModel.Dialogue.CLOTURER -> SaisieMontantDialog(
            titre = "Clôturer la caisse",
            explication = "Comptez les espèces présentes dans la caisse. Montant attendu : ${Montant.formater(t?.soldeEspeces ?: 0)}. " +
                "Après la clôture, plus aucune vente n'est possible sur cette session.",
            libelle = "Espèces comptées (Ar)",
            erreur = vm.erreur,
            avecLibelle = false,
            onValider = { montant, _ -> vm.cloturer(montant) },
            onAnnuler = vm::fermer,
        )
        CaisseViewModel.Dialogue.PARAMETRES -> SaisieMontantDialog(
            titre = "Paramètres de vente",
            explication = "Remise maximale qu'un vendeur peut accorder sans le code PIN d'un gérant ou du propriétaire.",
            libelle = "Remise maximale (% du total)",
            erreur = vm.erreur,
            avecLibelle = false,
            valeurInitiale = vm.remiseMax.toString(),
            onValider = { valeur, _ -> vm.enregistrerRemiseMax(valeur) },
            onAnnuler = vm::fermer,
        )
        null -> Unit
    }
}

@Composable
private fun Ligne(libelle: String, valeur: String, important: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, Modifier.weight(1f), style = if (important) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(valeur, style = if (important) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Titre(texte: String) {
    Text(texte, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun SaisieMontantDialog(
    titre: String,
    explication: String,
    libelle: String,
    erreur: String?,
    avecLibelle: Boolean,
    onValider: (montant: String, libelle: String) -> Unit,
    onAnnuler: () -> Unit,
    valeurInitiale: String = "",
) {
    var montant by rememberSaveable(titre, valeurInitiale) { mutableStateOf(valeurInitiale) }
    var motif by rememberSaveable(titre) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explication, style = MaterialTheme.typography.bodyMedium)
                ChampTexte(montant, { montant = it }, libelle, Modifier.fillMaxWidth(), KeyboardType.Number)
                if (avecLibelle) ChampTexte(motif, { motif = it }, "Motif", Modifier.fillMaxWidth())
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(montant, motif) }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
