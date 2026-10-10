package mg.tsena.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import mg.tsena.domain.Permission
import mg.tsena.ui.appro.CommandeFormScreen
import mg.tsena.ui.appro.CommandeScreen
import mg.tsena.ui.appro.CommandesScreen
import mg.tsena.ui.appro.ConversionsScreen
import mg.tsena.ui.appro.FournisseursScreen
import mg.tsena.ui.appro.ReceptionFormScreen
import mg.tsena.ui.appro.ReceptionScreen
import mg.tsena.ui.appro.ReceptionsScreen
import mg.tsena.ui.appro.TransfertFormScreen
import mg.tsena.ui.appro.TransfertScreen
import mg.tsena.ui.appro.TransfertsScreen
import mg.tsena.ui.catalogue.CategoriesScreen
import mg.tsena.ui.catalogue.ProduitFormScreen
import mg.tsena.ui.catalogue.ProduitsScreen
import mg.tsena.ui.catalogue.UnitesScreen
import mg.tsena.ui.home.HomeScreen
import mg.tsena.ui.lieux.LieuxScreen
import mg.tsena.ui.login.LoginScreen
import mg.tsena.ui.restauration.RestaurationDialog
import mg.tsena.ui.restauration.RestaurationViewModel
import mg.tsena.ui.setup.SetupScreen
import mg.tsena.ui.stock.AlertesStockScreen
import mg.tsena.ui.stock.FicheStockScreen
import mg.tsena.ui.stock.InventaireScreen
import mg.tsena.ui.stock.InventairesScreen
import mg.tsena.ui.stock.StockScreen
import mg.tsena.ui.users.UsersScreen
import mg.tsena.ui.vente.CaisseScreen
import mg.tsena.ui.vente.ClientsScreen
import mg.tsena.ui.vente.PrixPointVenteScreen
import mg.tsena.ui.vente.VenteDetailScreen
import mg.tsena.ui.vente.VenteScreen
import mg.tsena.ui.vente.VentesScreen

object Routes {
    const val PRODUITS = "produits"
    const val PRODUIT = "produit?id={id}"
    const val CATEGORIES = "categories"
    const val UNITES = "unites"
    const val LIEUX = "lieux"
    const val STOCKS = "stocks"
    const val ALERTES = "stocks/alertes"
    const val FICHE_STOCK = "stocks/fiche/{produitId}/{depotId}"
    const val INVENTAIRES = "inventaires"
    const val INVENTAIRE = "inventaires/{id}"
    const val FOURNISSEURS = "fournisseurs"
    const val CONVERSIONS = "conversions"
    const val COMMANDES = "commandes"
    const val COMMANDE_FORM = "commande-saisie?id={id}"
    const val COMMANDE = "commandes/{id}"
    const val RECEPTIONS = "receptions"
    const val RECEPTION_FORM = "reception-saisie?commandeId={commandeId}"
    const val RECEPTION = "receptions/{id}"
    const val TRANSFERTS = "transferts"
    const val TRANSFERT_FORM = "transfert-saisie"
    const val TRANSFERT = "transferts/{id}"
    const val CAISSE = "caisse"
    const val VENTE = "caisse/vente/{pointVenteId}"
    const val VENTES = "ventes"
    const val VENTE_DETAIL = "ventes/{id}"
    const val CLIENTS = "clients"
    const val PRIX_POINTS_VENTE = "prix-points-vente"

    fun produit(id: String?) = if (id == null) "produit" else "produit?id=$id"
    fun ficheStock(produitId: String, depotId: String) = "stocks/fiche/$produitId/$depotId"
    fun inventaire(id: String) = "inventaires/$id"
    fun commandeForm(id: String?) = if (id == null) "commande-saisie" else "commande-saisie?id=$id"
    fun commande(id: String) = "commandes/$id"
    fun receptionForm(commandeId: String?) = if (commandeId == null) "reception-saisie" else "reception-saisie?commandeId=$commandeId"
    fun reception(id: String) = "receptions/$id"
    fun transfert(id: String) = "transferts/$id"
    fun vente(pointVenteId: String) = "caisse/vente/$pointVenteId"
    fun venteDetail(id: String) = "ventes/$id"
}

private val TYPES_SAUVEGARDE = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
fun TsenaRoot(
    restauration: RestaurationViewModel,
    app: AppViewModel = viewModel(factory = AppViewModel.Factory),
) {
    val etat by app.etat.collectAsStateWithLifecycle()
    val etatRestauration by restauration.etat.collectAsStateWithLifecycle()
    val selecteur = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(restauration::recevoirFichier)
    }
    val choisirSauvegarde = { selecteur.launch(TYPES_SAUVEGARDE) }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (val e = etat) {
                EtatApp.Chargement -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                EtatApp.NonInitialise -> SetupScreen(onRestaurer = choisirSauvegarde)
                is EtatApp.Deconnecte -> LoginScreen(nomCommerce = e.commerce.nom)
                is EtatApp.Connecte -> EcransConnectes(e, onRestaurer = choisirSauvegarde)
            }
        }
    }

    // RG-X1c : restauration possible au premier lancement (nouveau téléphone) ou par le propriétaire.
    // Si personne n'est connecté, le fichier reçu attend la connexion.
    when (val e = etat) {
        EtatApp.Chargement, is EtatApp.Deconnecte -> Unit
        EtatApp.NonInitialise -> RestaurationDialog(etatRestauration, autorise = true, restauration)
        is EtatApp.Connecte ->
            RestaurationDialog(etatRestauration, autorise = e.utilisateur.peut(Permission.RESTAURER), restauration)
    }
}

@Composable
private fun EcransConnectes(etat: EtatApp.Connecte, onRestaurer: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "accueil") {
        composable("accueil") {
            HomeScreen(
                commerce = etat.commerce,
                utilisateur = etat.utilisateur,
                onUtilisateurs = { nav.navigate("utilisateurs") },
                onRestaurer = onRestaurer,
                onOuvrir = { route -> nav.navigate(route) },
            )
        }
        composable("utilisateurs") {
            UsersScreen(utilisateurConnecte = etat.utilisateur, onRetour = { nav.popBackStack() })
        }
        composable(Routes.PRODUITS) {
            ProduitsScreen(
                onRetour = { nav.popBackStack() },
                onNouveau = { nav.navigate(Routes.produit(null)) },
                onModifier = { id -> nav.navigate(Routes.produit(id)) },
            )
        }
        composable(
            Routes.PRODUIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entree ->
            ProduitFormScreen(produitId = entree.arguments?.getString("id"), onTermine = { nav.popBackStack() })
        }
        composable(Routes.CATEGORIES) { CategoriesScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.UNITES) { UnitesScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.LIEUX) { LieuxScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.STOCKS) {
            StockScreen(
                peutParametrer = etat.utilisateur.peut(Permission.PARAMETRER_COMMERCE),
                stockNegatifAutorise = etat.commerce.stockNegatifAutorise,
                onRetour = { nav.popBackStack() },
                onFiche = { produitId, depotId -> nav.navigate(Routes.ficheStock(produitId, depotId)) },
                onInventaires = { nav.navigate(Routes.INVENTAIRES) },
            )
        }
        composable(Routes.ALERTES) {
            AlertesStockScreen(
                onRetour = { nav.popBackStack() },
                onFiche = { produitId, depotId -> nav.navigate(Routes.ficheStock(produitId, depotId)) },
            )
        }
        composable(Routes.FICHE_STOCK) { entree ->
            FicheStockScreen(
                produitId = entree.arguments?.getString("produitId").orEmpty(),
                depotId = entree.arguments?.getString("depotId").orEmpty(),
                onRetour = { nav.popBackStack() },
            )
        }
        composable(Routes.INVENTAIRES) {
            InventairesScreen(onRetour = { nav.popBackStack() }, onOuvrir = { id -> nav.navigate(Routes.inventaire(id)) })
        }
        composable(Routes.INVENTAIRE) { entree ->
            InventaireScreen(inventaireId = entree.arguments?.getString("id").orEmpty(), onRetour = { nav.popBackStack() })
        }
        composable(Routes.FOURNISSEURS) { FournisseursScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.CONVERSIONS) { ConversionsScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.COMMANDES) {
            CommandesScreen(
                onRetour = { nav.popBackStack() },
                onNouvelle = { nav.navigate(Routes.commandeForm(null)) },
                onOuvrir = { id -> nav.navigate(Routes.commande(id)) },
            )
        }
        composable(
            Routes.COMMANDE_FORM,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entree ->
            val id = entree.arguments?.getString("id")
            CommandeFormScreen(
                commandeId = id,
                onRetour = { nav.popBackStack() },
                onEnregistree = { commandeId ->
                    nav.popBackStack()
                    // Création : ouvrir la fiche ; modification : la fiche est déjà dessous
                    if (id == null) nav.navigate(Routes.commande(commandeId))
                },
            )
        }
        composable(Routes.COMMANDE) { entree ->
            val id = entree.arguments?.getString("id").orEmpty()
            CommandeScreen(
                commandeId = id,
                nomCommerce = etat.commerce.nom,
                onRetour = { nav.popBackStack() },
                onModifier = { nav.navigate(Routes.commandeForm(id)) },
                onRecevoir = { nav.navigate(Routes.receptionForm(id)) },
                onReception = { receptionId -> nav.navigate(Routes.reception(receptionId)) },
            )
        }
        composable(Routes.RECEPTIONS) {
            ReceptionsScreen(
                onRetour = { nav.popBackStack() },
                onNouvelle = { nav.navigate(Routes.receptionForm(null)) },
                onOuvrir = { id -> nav.navigate(Routes.reception(id)) },
            )
        }
        composable(
            Routes.RECEPTION_FORM,
            arguments = listOf(navArgument("commandeId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entree ->
            ReceptionFormScreen(
                commandeId = entree.arguments?.getString("commandeId"),
                onRetour = { nav.popBackStack() },
                onValidee = { receptionId ->
                    nav.popBackStack()
                    nav.navigate(Routes.reception(receptionId))
                },
            )
        }
        composable(Routes.RECEPTION) { entree ->
            ReceptionScreen(receptionId = entree.arguments?.getString("id").orEmpty(), onRetour = { nav.popBackStack() })
        }
        composable(Routes.TRANSFERTS) {
            TransfertsScreen(
                onRetour = { nav.popBackStack() },
                onNouveau = { nav.navigate(Routes.TRANSFERT_FORM) },
                onOuvrir = { id -> nav.navigate(Routes.transfert(id)) },
            )
        }
        composable(Routes.TRANSFERT_FORM) {
            TransfertFormScreen(
                onRetour = { nav.popBackStack() },
                onEnvoye = { transfertId ->
                    nav.popBackStack()
                    nav.navigate(Routes.transfert(transfertId))
                },
            )
        }
        composable(Routes.CAISSE) {
            CaisseScreen(
                peutParametrer = etat.utilisateur.peut(Permission.PARAMETRER_COMMERCE),
                peutVendre = etat.utilisateur.peut(Permission.VENDRE),
                onRetour = { nav.popBackStack() },
                onVendre = { pointVenteId -> nav.navigate(Routes.vente(pointVenteId)) },
            )
        }
        composable(Routes.VENTE) { entree ->
            VenteScreen(
                pointVenteId = entree.arguments?.getString("pointVenteId").orEmpty(),
                onRetour = { nav.popBackStack() },
                onTicket = { venteId -> nav.navigate(Routes.venteDetail(venteId)) },
            )
        }
        composable(Routes.VENTES) {
            VentesScreen(
                pointVenteImpose = if (etat.utilisateur.peut(Permission.VOIR_TOUS_POINTS_VENTE)) null else etat.utilisateur.pointVenteId ?: "",
                onRetour = { nav.popBackStack() },
                onOuvrir = { venteId -> nav.navigate(Routes.venteDetail(venteId)) },
            )
        }
        composable(Routes.VENTE_DETAIL) { entree ->
            VenteDetailScreen(
                venteId = entree.arguments?.getString("id").orEmpty(),
                nomCommerce = etat.commerce.nom,
                peutAnnuler = etat.utilisateur.peut(Permission.ANNULER_VENTE),
                onRetour = { nav.popBackStack() },
            )
        }
        composable(Routes.CLIENTS) { ClientsScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.PRIX_POINTS_VENTE) { PrixPointVenteScreen(onRetour = { nav.popBackStack() }) }
        composable(Routes.TRANSFERT) { entree ->
            TransfertScreen(transfertId = entree.arguments?.getString("id").orEmpty(), onRetour = { nav.popBackStack() })
        }
    }
}
