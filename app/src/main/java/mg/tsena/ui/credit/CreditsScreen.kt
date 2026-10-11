package mg.tsena.ui.credit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import mg.tsena.container
import mg.tsena.credit.Anciennete
import mg.tsena.credit.CreditClient
import mg.tsena.credit.CreditRepository
import mg.tsena.credit.Credits
import mg.tsena.domain.Montant
import mg.tsena.domain.Telephone
import mg.tsena.domain.formaterDateHeure
import mg.tsena.ui.rapports.Barre
import mg.tsena.ui.rapports.GraphiqueBarres
import mg.tsena.ui.rapports.couleursGraphique

class CreditsViewModel(credits: CreditRepository) : ViewModel() {
    val clients: StateFlow<List<CreditClient>> = credits.observerCredits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        val Factory = viewModelFactory {
            initializer { CreditsViewModel(container.creditRepository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(
    nomCommerce: String,
    onRetour: () -> Unit,
    onClient: (clientId: String) -> Unit,
    vm: CreditsViewModel = viewModel(factory = CreditsViewModel.Factory),
) {
    val clients by vm.clients.collectAsStateWithLifecycle()
    var enRetardSeulement by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val c = couleursGraphique()
    // Clients qui doivent quelque chose : en retard d'abord, puis par montant dû
    val debiteurs = remember(clients, enRetardSeulement) {
        clients.filter { it.du > 0 && (!enRetardSeulement || it.enRetard) }
            .sortedWith(compareByDescending<CreditClient> { it.enRetard }.thenByDescending { it.du })
    }
    val tranches = remember(clients) { Credits.parAnciennete(clients.flatMap { it.ventes }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crédits clients") },
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
                        Text("À encaisser : ${Montant.formater(clients.sumOf { maxOf(0, it.du) })}", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${clients.count { it.du > 0 }} client(s) · ${clients.count { it.enRetard }} en retard (plus de ${Credits.DELAI_JOURS} jours)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        GraphiqueBarres(
                            barres = Anciennete.entries.mapIndexed { i, a ->
                                val montant = tranches[a] ?: 0L
                                Barre(a.court, "${a.libelle} : ${Montant.formater(montant)}", listOf(montant), listOf(c.ordinale[i]))
                            },
                            description = "Crédits impayés par ancienneté",
                            hauteur = 110,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !enRetardSeulement, onClick = { enRetardSeulement = false }, label = { Text("Tous") })
                    FilterChip(selected = enRetardSeulement, onClick = { enRetardSeulement = true }, label = { Text("En retard") })
                }
            }
            if (debiteurs.isEmpty()) item { Text("Aucun crédit en cours.", Modifier.padding(16.dp)) }
            items(debiteurs, key = { it.client.client.id }) { cc ->
                ListItem(
                    headlineContent = { Text(cc.client.client.nom) },
                    supportingContent = {
                        Text(
                            "${cc.impayees.size} vente(s) impayée(s)" + (cc.plusAncienJours?.let { " · la plus ancienne : $it j" } ?: ""),
                            color = if (cc.enRetard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Montant.formater(cc.du), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { relancer(context, nomCommerce, cc) }) { Text("Relancer") }
                        }
                    },
                    modifier = Modifier.clickable { onClient(cc.client.client.id) },
                )
            }
        }
    }
}

/** Message de rappel au client : ventes impayées et total dû. */
fun texteRelance(nomCommerce: String, cc: CreditClient): String = buildString {
    appendLine("Bonjour ${cc.client.client.nom},")
    appendLine("Petit rappel de $nomCommerce : il reste ${Montant.formater(cc.du)} à régler.")
    cc.impayees.forEach { v -> appendLine("- ${v.numero} du ${formaterDateHeure(v.date).substringBefore(' ')} : ${Montant.formater(v.reste)}") }
    append("Merci !")
}

/** Ouvre WhatsApp vers le numéro du client avec le rappel, sinon le menu de partage. */
fun relancer(context: Context, nomCommerce: String, cc: CreditClient) {
    val texte = texteRelance(nomCommerce, cc)
    val numero = cc.client.client.telephone.removePrefix("+")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$numero?text=${Uri.encode(texte)}")))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texte), "Relancer ${Telephone.formater(cc.client.client.telephone)}")
        )
    }
}
