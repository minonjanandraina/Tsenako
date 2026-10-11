package mg.tsena.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mg.tsena.R
import mg.tsena.auth.UtilisateurConnecte
import mg.tsena.domain.Permission
import mg.tsena.ui.Routes

/**
 * Grandes icônes de l'accueil ; chacune ouvre la liste de ses écrans.
 * [couleur] : fond de la grande icône (texte blanc, contraste suffisant en thème clair comme sombre).
 */
enum class Module(val titre: String, val couleur: Color) {
    CATALOGUE("Catalogue", Color(0xFF3949AB)),
    STOCKS("Stocks", Color(0xFF00796B)),
    VENTES("Ventes", Color(0xFFE65100)),
    CREDITS("Crédits clients", Color(0xFFAD1457)),
    APPRO("Approvisionnement", Color(0xFF6D4C41)),
    DEPENSES("Dépenses", Color(0xFFC62828)),
    COMPTABILITE("Comptabilité", Color(0xFF6A1B9A)),
}

/** Couleur de la grande icône « Utilisateurs ». */
val COULEUR_UTILISATEURS = Color(0xFF455A64)

/** Écran proposé dans un module. [alerte] : texte affiché en rouge (alertes, transferts à recevoir). */
class EntreeModule(
    val titre: String,
    val description: String,
    val route: String,
    val alerte: Boolean = false,
    val icone: @Composable () -> Painter,
)

@Composable
fun iconeModule(module: Module): Painter = when (module) {
    Module.CATALOGUE -> painterResource(R.drawable.ic_produits)
    Module.STOCKS -> painterResource(R.drawable.ic_stock)
    Module.VENTES -> rememberVectorPainter(Icons.Filled.ShoppingCart)
    Module.CREDITS -> painterResource(R.drawable.ic_credit)
    Module.APPRO -> painterResource(R.drawable.ic_appro)
    Module.DEPENSES -> painterResource(R.drawable.ic_depenses)
    Module.COMPTABILITE -> painterResource(R.drawable.ic_comptabilite)
}

/** Écrans d'un module accessibles à l'utilisateur (liste vide = module masqué). */
fun entreesModule(module: Module, u: UtilisateurConnecte, nbAlertes: Int, nbEnTransit: Int): List<EntreeModule> {
    fun si(permission: Permission, vararg entrees: EntreeModule) = if (u.peut(permission)) entrees.toList() else emptyList()
    return when (module) {
        Module.CATALOGUE ->
            si(
                Permission.GERER_PRODUITS,
                EntreeModule("Produits", "Photos, prix d'achat et de vente, codes", Routes.PRODUITS) { painterResource(R.drawable.ic_produits) },
                EntreeModule("Catégories", "Classement des produits", Routes.CATEGORIES) { painterResource(R.drawable.ic_categories) },
                EntreeModule("Unités", "Pièce, kg, litre, sac…", Routes.UNITES) { painterResource(R.drawable.ic_unites) },
            ) + si(
                Permission.GERER_REFERENTIEL,
                EntreeModule("Dépôts et points de vente", "Salles de stockage, boutiques", Routes.LIEUX) { painterResource(R.drawable.ic_lieux) },
            )
        Module.STOCKS -> si(
            Permission.GERER_STOCK,
            EntreeModule("Stock par dépôt", "Quantités, coût moyen, pertes", Routes.STOCKS) { painterResource(R.drawable.ic_stock) },
            EntreeModule("Inventaires", "Compter le stock et corriger les écarts", Routes.INVENTAIRES) { painterResource(R.drawable.ic_inventaire) },
            EntreeModule(
                "Alertes de stock",
                if (nbAlertes == 0) "Aucun produit sous son seuil" else "$nbAlertes produit(s) sous le seuil",
                Routes.ALERTES,
                alerte = nbAlertes > 0,
            ) { rememberVectorPainter(Icons.Filled.Warning) },
        )
        Module.VENTES ->
            (if (u.peut(Permission.VENDRE) || u.peut(Permission.GERER_CAISSE)) {
                listOf(EntreeModule("Caisse et vente", "Ouvrir la caisse, vendre, clôturer", Routes.CAISSE) { rememberVectorPainter(Icons.Filled.ShoppingCart) })
            } else {
                emptyList()
            }) + si(
                Permission.VENDRE,
                EntreeModule("Ventes", "Tickets, retours, annulations", Routes.VENTES) { painterResource(R.drawable.ic_ventes) },
                EntreeModule("Clients", "Clients en gros et à crédit", Routes.CLIENTS) { rememberVectorPainter(Icons.Filled.Person) },
            ) + si(
                Permission.GERER_PRODUITS,
                EntreeModule("Prix par point de vente", "Prix particuliers d'une boutique", Routes.PRIX_POINTS_VENTE) { painterResource(R.drawable.ic_prix) },
            )
        Module.CREDITS ->
            if (u.peut(Permission.VENDRE) || u.peut(Permission.GERER_CAISSE)) {
                listOf(
                    EntreeModule("Suivi des crédits", "Impayés par client et par vente, retards, relances", Routes.CREDITS) {
                        painterResource(R.drawable.ic_credit)
                    },
                    EntreeModule("Clients", "Fiches, achats et remboursements", Routes.CLIENTS) { rememberVectorPainter(Icons.Filled.Person) },
                )
            } else {
                emptyList()
            }
        Module.APPRO -> si(
            Permission.GERER_APPRO,
            EntreeModule("Commandes", "Commander aux fournisseurs", Routes.COMMANDES) { painterResource(R.drawable.ic_commande) },
            EntreeModule("Réceptions", "Entrées de marchandise, achats", Routes.RECEPTIONS) { painterResource(R.drawable.ic_reception) },
            EntreeModule(
                "Transferts entre dépôts",
                if (nbEnTransit == 0) "Déplacer du stock" else "$nbEnTransit transfert(s) en transit à réceptionner",
                Routes.TRANSFERTS,
                alerte = nbEnTransit > 0,
            ) { painterResource(R.drawable.ic_transfert) },
            EntreeModule("Fournisseurs", "Coordonnées, total des achats", Routes.FOURNISSEURS) { painterResource(R.drawable.ic_appro) },
            EntreeModule("Unités d'achat", "Carton, sac… convertis en unités de stock", Routes.CONVERSIONS) { painterResource(R.drawable.ic_unites) },
        )
        Module.DEPENSES -> si(
            Permission.GERER_DEPENSES,
            EntreeModule("Dépenses", "Loyer, électricité, transport, salaires…", Routes.DEPENSES) { painterResource(R.drawable.ic_depenses) },
            EntreeModule("Fournisseurs à payer", "Dettes, échéances et paiements", Routes.FOURNISSEURS) { painterResource(R.drawable.ic_appro) },
        )
        Module.COMPTABILITE -> si(
            Permission.VOIR_COMPTABILITE,
            EntreeModule("Performance", "Graphiques : ventes par jour, comptant et crédit, meilleurs produits", Routes.PERFORMANCE) {
                painterResource(R.drawable.ic_performance)
            },
            EntreeModule("Tableau de bord", "Ventes, marges, stock, dettes et créances", Routes.TABLEAU_DE_BORD) { painterResource(R.drawable.ic_stock) },
            EntreeModule("États financiers", "Bilan, compte de résultat, flux de trésorerie", Routes.ETATS_FINANCIERS) {
                painterResource(R.drawable.ic_comptabilite)
            },
            EntreeModule("Comptabilité", "Journaux, plan comptable PCG 2005, exercices", Routes.COMPTABILITE) { painterResource(R.drawable.ic_comptabilite) },
        )
    }
}

/** Grande icône de l'accueil, sur fond [couleur] ; [alerte] dans une pastille blanche. */
@Composable
fun TuileModule(titre: String, icone: Painter, alerte: String?, couleur: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(120.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = couleur, contentColor = Color.White),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icone, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.White)
            Text(
                titre,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
            alerte?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB71C1C),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(Color.White, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Liste des écrans d'un module, ouverte depuis sa grande icône. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleScreen(
    module: Module,
    utilisateur: UtilisateurConnecte,
    onRetour: () -> Unit,
    onOuvrir: (route: String) -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val nbAlertes by vm.nbAlertes.collectAsStateWithLifecycle()
    val nbEnTransit by vm.nbTransfertsEnTransit.collectAsStateWithLifecycle()
    val entrees = entreesModule(module, utilisateur, nbAlertes, nbEnTransit)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(module.titre) },
                navigationIcon = {
                    IconButton(onClick = onRetour) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                entrees.forEach { e ->
                    ListItem(
                        headlineContent = { Text(e.titre) },
                        supportingContent = {
                            Text(
                                e.description,
                                color = if (e.alerte) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Icon(
                                e.icone(),
                                contentDescription = null,
                                tint = if (e.alerte) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.clickable { onOuvrir(e.route) },
                    )
                }
            }
        }
    }
}
