package mg.tsena.ui.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.SQLException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.UtilisateurConnecte
import mg.tsena.backup.BackupManager
import mg.tsena.container
import mg.tsena.data.db.entity.CommerceEntity
import mg.tsena.domain.Permission
import mg.tsena.domain.formaterDateHeure
import mg.tsena.domain.maintenant
import mg.tsena.stock.StockRepository
import mg.tsena.ui.Routes
import java.io.IOException

class HomeViewModel(
    private val auth: AuthRepository,
    private val backup: BackupManager,
    stock: StockRepository,
) : ViewModel() {
    val derniereSauvegarde: StateFlow<Long?> = backup.derniereSauvegarde
    val nbAlertes: StateFlow<Int> = stock.observerAlertes().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _partage = Channel<Intent>(Channel.BUFFERED)
    val partage: Flow<Intent> = _partage.receiveAsFlow()

    var sauvegardeEnCours by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun sauvegarder() {
        sauvegardeEnCours = true
        message = null
        viewModelScope.launch {
            try {
                _partage.send(backup.intentPartage(backup.creerSauvegarde()))
            } catch (e: IOException) {
                message = "Échec de la sauvegarde : ${e.message}"
            } catch (e: SQLException) {
                message = "Échec de la sauvegarde : ${e.message}"
            } finally {
                sauvegardeEnCours = false
            }
        }
    }

    fun deconnecter() {
        viewModelScope.launch { auth.deconnecter() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel(container.authRepository, container.backupManager, container.stockRepository) }
        }
    }
}

private const val UN_JOUR_MS = 24 * 60 * 60 * 1000L

/** Modules prévus dans les sprints suivants (Claude.md §6). */
private val MODULES_A_VENIR = listOf(
    "Approvisionnement & transferts" to "S3",
    "Ventes & caisse" to "S4",
    "Charges & dépenses" to "S5",
    "Comptabilité" to "S6",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    commerce: CommerceEntity,
    utilisateur: UtilisateurConnecte,
    onUtilisateurs: () -> Unit,
    onRestaurer: () -> Unit,
    onOuvrir: (route: String) -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.partage.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(intent, "Partager la sauvegarde"))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(commerce.nom)
                        Text(utilisateur.nom, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = vm::deconnecter) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Se déconnecter")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (utilisateur.peut(Permission.GERER_PRODUITS) || utilisateur.peut(Permission.GERER_REFERENTIEL)) {
                Text("Catalogue", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    if (utilisateur.peut(Permission.GERER_PRODUITS)) {
                        EntreeMenu("Produits", "Photos, prix, codes") { onOuvrir(Routes.PRODUITS) }
                        EntreeMenu("Catégories", "Classement des produits") { onOuvrir(Routes.CATEGORIES) }
                        EntreeMenu("Unités", "Pièce, kg, litre, sac…") { onOuvrir(Routes.UNITES) }
                    }
                    if (utilisateur.peut(Permission.GERER_REFERENTIEL)) {
                        EntreeMenu("Dépôts et points de vente", "Salles de stockage, boutiques") { onOuvrir(Routes.LIEUX) }
                    }
                }
            }

            if (utilisateur.peut(Permission.GERER_STOCK)) {
                val nbAlertes by vm.nbAlertes.collectAsStateWithLifecycle()
                Text("Stocks", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    EntreeMenu("Stock par dépôt", "Quantités, coût moyen, pertes") { onOuvrir(Routes.STOCKS) }
                    EntreeMenu("Inventaires", "Compter le stock et corriger les écarts") { onOuvrir(Routes.INVENTAIRES) }
                    ListItem(
                        headlineContent = { Text("Alertes de stock") },
                        supportingContent = {
                            Text(
                                if (nbAlertes == 0) "Aucun produit sous son seuil" else "$nbAlertes produit(s) sous le seuil",
                                color = if (nbAlertes == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = if (nbAlertes == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        },
                        modifier = Modifier.clickable { onOuvrir(Routes.ALERTES) },
                    )
                }
            }

            if (utilisateur.peut(Permission.SAUVEGARDER)) {
                CarteSauvegarde(vm, peutRestaurer = utilisateur.peut(Permission.RESTAURER), onRestaurer = onRestaurer)
            }

            if (utilisateur.peut(Permission.GERER_UTILISATEURS)) {
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Utilisateurs") },
                        supportingContent = { Text("Comptes, rôles et codes PIN") },
                        leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                        trailingContent = { OutlinedButton(onClick = onUtilisateurs) { Text("Gérer") } },
                    )
                }
            }

            Text("Bientôt disponible", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                MODULES_A_VENIR.forEach { (module, sprint) ->
                    ListItem(
                        headlineContent = { Text(module) },
                        trailingContent = { Text(sprint, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntreeMenu(titre: String, description: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(titre) },
        supportingContent = { Text(description) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun CarteSauvegarde(vm: HomeViewModel, peutRestaurer: Boolean, onRestaurer: () -> Unit) {
    val derniere by vm.derniereSauvegarde.collectAsStateWithLifecycle()
    val enRetard = derniere.let { it == null || maintenant() - it > UN_JOUR_MS }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
            Text(derniere?.let { "Dernière sauvegarde : ${formaterDateHeure(it)}" } ?: "Aucune sauvegarde")
            if (enRetard) {
                ListItem(
                    headlineContent = { Text("Pensez à sauvegarder vos données aujourd'hui.") },
                    leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null) },
                )
            }
            vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::sauvegarder, enabled = !vm.sauvegardeEnCours, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(
                    if (vm.sauvegardeEnCours) "  Préparation…" else "  Sauvegarder et envoyer par WhatsApp",
                )
            }
            if (peutRestaurer) {
                OutlinedButton(onClick = onRestaurer, modifier = Modifier.fillMaxWidth()) {
                    Text("Restaurer une sauvegarde")
                }
            }
        }
    }
}
