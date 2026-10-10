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
import mg.tsena.ui.users.UsersScreen

object Routes {
    const val PRODUITS = "produits"
    const val PRODUIT = "produit?id={id}"
    const val CATEGORIES = "categories"
    const val UNITES = "unites"
    const val LIEUX = "lieux"

    fun produit(id: String?) = if (id == null) "produit" else "produit?id=$id"
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
    }
}
