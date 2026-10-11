package mg.tsena.ui.rapports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import mg.tsena.container
import mg.tsena.credit.Anciennete
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.rapports.DonneesPerformance
import mg.tsena.rapports.RapportRepository
import mg.tsena.ui.compta.SelecteurMois
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FORMAT_JOUR = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
private val FORMAT_MOIS_COURT = DateTimeFormatter.ofPattern("MMM", Locale.FRENCH)
private val FORMAT_MOIS_LONG = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModel(rapports: RapportRepository) : ViewModel() {
    val mois = MutableStateFlow(YearMonth.now())
    val donnees: StateFlow<DonneesPerformance?> = mois
        .flatMapLatest { rapports.observerPerformance(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory = viewModelFactory {
            initializer { PerformanceViewModel(container.rapportRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    onRetour: () -> Unit,
    onCredits: () -> Unit,
    vm: PerformanceViewModel = viewModel(factory = PerformanceViewModel.Factory),
) {
    val mois by vm.mois.collectAsStateWithLifecycle()
    val donnees by vm.donnees.collectAsStateWithLifecycle()
    val c = couleursGraphique()
    var tableauJours by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance") },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        val d = donnees
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SelecteurMois(mois) { vm.mois.value = it } }
            if (d == null) return@LazyColumn
            val v = d.ventes
            val mixte = d.sixMois.last().second

            // Chiffres clés du mois
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChiffreCle("Chiffre d'affaires", Montant.formater(v.chiffreAffaires), Modifier.weight(1f))
                        ChiffreCle("Marge brute", Montant.formater(v.marge) + (v.tauxMarge?.let { " · $it %" } ?: ""), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChiffreCle(
                            "Vendu à crédit",
                            if (mixte.total > 0) "${Montant.formater(mixte.credit)} · ${mixte.credit * 100 / mixte.total} %" else Montant.formater(0),
                            Modifier.weight(1f),
                        )
                        ChiffreCle("Crédits à encaisser", Montant.formater(d.creances), Modifier.weight(1f), onClick = onCredits)
                    }
                    Text("${v.nbVentes} vente(s) en ${mois.format(FORMAT_MOIS_LONG)}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Chiffre d'affaires par jour (une seule série : pas de légende)
            item {
                CarteGraphique("Chiffre d'affaires par jour") {
                    GraphiqueBarres(
                        barres = d.parJour.map { (jour, ca) ->
                            Barre(jour.dayOfMonth.toString(), "${jour.format(FORMAT_JOUR)} : ${Montant.formater(ca)}", listOf(ca), listOf(c.serie1))
                        },
                        description = "Chiffre d'affaires par jour de ${mois.format(FORMAT_MOIS_LONG)}",
                        etiquetteToutesLes = 5,
                    )
                    TextButton(onClick = { tableauJours = !tableauJours }) {
                        Text(if (tableauJours) "Masquer les chiffres" else "Voir les chiffres")
                    }
                    if (tableauJours) {
                        d.parJour.filter { it.second > 0 }.forEach { (jour, ca) -> LigneValeur(jour.format(FORMAT_JOUR), Montant.formater(ca)) }
                    }
                }
            }

            // Comptant et crédit sur six mois (deux séries : légende)
            item {
                CarteGraphique("Comptant et crédit, six derniers mois") {
                    Legende(listOf("Comptant" to c.serie1, "Crédit" to c.serie2))
                    GraphiqueBarres(
                        barres = d.sixMois.map { (m, parts) ->
                            Barre(
                                m.format(FORMAT_MOIS_COURT),
                                "${m.format(FORMAT_MOIS_LONG)} : comptant ${Montant.formater(parts.comptant)}, crédit ${Montant.formater(parts.credit)}",
                                listOf(parts.comptant, parts.credit),
                                listOf(c.serie1, c.serie2),
                            )
                        },
                        description = "Ventes au comptant et à crédit des six derniers mois",
                    )
                }
            }

            // Meilleurs produits
            item {
                CarteGraphique("Meilleurs produits du mois") {
                    if (v.parProduit.isEmpty()) {
                        Text("Aucune vente ce mois-ci.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        BarresHorizontales(
                            v.parProduit.take(5).map { p ->
                                Triple(
                                    p.nom,
                                    p.chiffreAffaires,
                                    "${Montant.formater(p.chiffreAffaires)} · ${Quantite.formater(p.quantiteMilli)} ${p.uniteSymbole} · marge ${Montant.formater(p.marge)}",
                                )
                            },
                            c.serie1,
                        )
                    }
                }
            }

            // Impayés par ancienneté (rampe ordinale)
            item {
                CarteGraphique("Crédits impayés par ancienneté", onClick = onCredits) {
                    GraphiqueBarres(
                        barres = Anciennete.entries.mapIndexed { i, a ->
                            val montant = d.impayesParAnciennete[a] ?: 0L
                            Barre(a.court, "${a.libelle} : ${Montant.formater(montant)}", listOf(montant), listOf(c.ordinale[i]))
                        },
                        description = "Crédits clients impayés par ancienneté",
                        hauteur = 120,
                    )
                    if (d.clientsEnRetard > 0) {
                        Text(
                            "${d.clientsEnRetard} client(s) avec un crédit de plus de 30 jours",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChiffreCle(libelle: String, valeur: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Card(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Column(Modifier.padding(12.dp)) {
            Text(libelle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valeur, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CarteGraphique(titre: String, onClick: (() -> Unit)? = null, contenu: @Composable () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(titre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            contenu()
        }
    }
}

@Composable
private fun LigneValeur(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(libelle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(valeur, style = MaterialTheme.typography.bodySmall)
    }
}
