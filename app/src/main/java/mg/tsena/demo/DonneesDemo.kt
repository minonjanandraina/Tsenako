package mg.tsena.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.flow.first
import mg.tsena.AppContainer
import mg.tsena.appro.LigneAchat
import mg.tsena.catalogue.SaisieProduit
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.TypeVente
import mg.tsena.domain.Role
import mg.tsena.stock.Valorisation
import mg.tsena.vente.LigneVenteSaisie
import mg.tsena.vente.PaiementSaisi
import mg.tsena.vente.SaisieVente

/**
 * Boutique de vêtements de démonstration « Tsenasabotsy », avec un seul dépôt « tsenasabotsy ».
 * Tout passe par les repositories : règles métier, stock (CMUP), caisse et écritures comptables restent cohérents.
 * Réservé aux versions de test (bouton affiché seulement en debug).
 */
class DonneesDemo(private val c: AppContainer) {
    private class Produit(
        val cle: String,
        val nom: String,
        val categorie: String,
        val unite: String,
        val achat: Long,
        val vente: Long,
        val gros: Long?,
        /** Quantité de départ, en unités (× 1000 pour les millièmes) */
        val stock: Long,
        val seuil: Long,
    )

    /** Couleur des photos générées, par catégorie */
    private val couleurs = mapOf(
        "Robes" to 0xFFAD1457.toInt(),
        "Jupes" to 0xFFD81B60.toInt(),
        "Chemisiers" to 0xFFEC407A.toInt(),
        "Chemises" to 0xFF1565C0.toInt(),
        "Pantalons" to 0xFF283593.toInt(),
        "T-shirts" to 0xFF0277BD.toInt(),
        "Enfants" to 0xFFF9A825.toInt(),
        "Accessoires" to 0xFF6D4C41.toInt(),
        "Tissus" to 0xFF2E7D32.toInt(),
    )

    private val produits = listOf(
        Produit("robe_lamba", "Robe en lamba hoany", "Robes", "pce", 18_000, 35_000, 30_000, 12, 3),
        Produit("robe_soiree", "Robe de soirée", "Robes", "pce", 45_000, 85_000, 75_000, 4, 2),
        Produit("jupe_longue", "Jupe longue plissée", "Jupes", "pce", 12_000, 25_000, 21_000, 15, 4),
        Produit("chemisier", "Chemisier en coton", "Chemisiers", "pce", 10_000, 22_000, 18_000, 10, 3),
        Produit("chemise_homme", "Chemise homme manches longues", "Chemises", "pce", 14_000, 28_000, 24_000, 14, 4),
        Produit("jean", "Pantalon jean", "Pantalons", "pce", 22_000, 40_000, 35_000, 8, 3),
        Produit("tshirt", "T-shirt coton", "T-shirts", "pce", 6_000, 12_000, 9_500, 60, 12),
        Produit("ensemble_bebe", "Ensemble bébé", "Enfants", "pce", 9_000, 18_000, 15_000, 10, 3),
        Produit("robe_fillette", "Robe fillette", "Enfants", "pce", 8_000, 16_000, 13_000, 9, 3),
        // Stock sous le seuil : apparaît dans les alertes
        Produit("chapeau", "Chapeau en raphia", "Accessoires", "pce", 7_000, 15_000, null, 3, 5),
        Produit("ceinture", "Ceinture en cuir", "Accessoires", "pce", 8_000, 18_000, null, 20, 4),
        Produit("chaussettes", "Chaussettes (lot de 3)", "Accessoires", "pqt", 3_000, 6_000, 5_000, 30, 6),
        Produit("wax", "Tissu wax", "Tissus", "m", 6_000, 12_000, 10_000, 50, 10),
        Produit("lamba", "Lamba hoany (pagne)", "Tissus", "pce", 5_000, 10_000, 8_500, 25, 5),
    )

    suspend fun charger() {
        // Commerce et propriétaire (ouvre la session du propriétaire)
        c.authRepository.initialiser("Tsenasabotsy", "Hery Rakoto", "0340000001", PIN_PROPRIETAIRE)

        // Un seul dépôt « tsenasabotsy », qui alimente le point de vente « Tsenasabotsy »
        c.lieuRepository.enregistrerDepot(null, "tsenasabotsy", "Sabotsy Namehana", true)
        val depotId = c.database.lieuDao().observerDepots().first().first { it.nom == "tsenasabotsy" }.id
        c.lieuRepository.enregistrerPointVente(null, "Tsenasabotsy", "Sabotsy Namehana", depotId, true)
        val pvId = c.database.lieuDao().observerPointsVente().first().first { it.pointVente.nom == "Tsenasabotsy" }.pointVente.id

        c.authRepository.creerUtilisateur("Lova (gérante)", "0340000002", Role.GERANT, "2222")
        c.authRepository.creerUtilisateur("Fara (vendeuse)", "0340000003", Role.VENDEUR, "3333", pvId)
        c.authRepository.creerUtilisateur("Toky (magasinier)", "0340000004", Role.MAGASINIER, "4444")

        // Catégories
        val femmes = c.catalogueRepository.enregistrerCategorie(null, "Femmes", null)
        val hommes = c.catalogueRepository.enregistrerCategorie(null, "Hommes", null)
        val categories = mapOf(
            "Robes" to c.catalogueRepository.enregistrerCategorie(null, "Robes", femmes),
            "Jupes" to c.catalogueRepository.enregistrerCategorie(null, "Jupes", femmes),
            "Chemisiers" to c.catalogueRepository.enregistrerCategorie(null, "Chemisiers", femmes),
            "Chemises" to c.catalogueRepository.enregistrerCategorie(null, "Chemises", hommes),
            "Pantalons" to c.catalogueRepository.enregistrerCategorie(null, "Pantalons", hommes),
            "T-shirts" to c.catalogueRepository.enregistrerCategorie(null, "T-shirts", hommes),
            "Enfants" to c.catalogueRepository.enregistrerCategorie(null, "Enfants", null),
            "Accessoires" to c.catalogueRepository.enregistrerCategorie(null, "Accessoires", null),
            "Tissus" to c.catalogueRepository.enregistrerCategorie(null, "Tissus", null),
        )
        val unites = c.database.uniteDao().observer().first().associateBy { it.symbole }

        // Produits avec photo générée, puis stock de départ au prix d'achat
        val ids = produits.associate { p ->
            val unite = unites.getValue(p.unite)
            val id = c.catalogueRepository.enregistrerProduit(
                SaisieProduit(
                    id = null,
                    nom = p.nom,
                    code = "",
                    codeBarre = "",
                    categorieId = categories.getValue(p.categorie),
                    uniteId = unite.id,
                    prixAchat = p.achat,
                    prixVente = p.vente,
                    prixVenteGros = p.gros,
                    seuilAlerteMilli = p.seuil * 1000,
                    photoChemin = c.photoManager.enregistrerImage(photo(p.nom, couleurs.getValue(p.categorie))),
                    actif = true,
                )
            )
            c.stockRepository.saisirStockInitial(id, depotId, p.stock * 1000, p.achat)
            p.cle to id
        }

        // Fournisseurs et unité d'achat
        c.approRepository.enregistrerFournisseur(null, "Textiles Analakely", "0341122334", "Analakely, Antananarivo", "", 30, true)
        c.approRepository.enregistrerFournisseur(null, "Raphia Ambositra", "0321122334", "Ambositra", "", 0, true)
        c.approRepository.enregistrerFournisseur(null, "Friperie Mahajanga", "0331122334", "Mahajanga", "", 15, true)
        val fournisseurs = c.database.fournisseurDao().observerActifs().first().associateBy { it.nom }
        val textiles = fournisseurs.getValue("Textiles Analakely").id
        c.approRepository.ajouterConversion(ids.getValue("tshirt"), unites.getValue("ctn").id, 24_000)

        // Commande de réassort, envoyée puis reçue en partie
        val commande = c.approRepository.enregistrerCommande(
            null, textiles, depotId, "Réassort de la rentrée",
            listOf(
                LigneAchat(ids.getValue("tshirt"), 48_000, 48 * 6_000),
                LigneAchat(ids.getValue("jean"), 10_000, 10 * 22_000),
            ),
        )
        c.approRepository.marquerCommandeEnvoyee(commande)
        val reception = c.approRepository.validerReception(
            commande, textiles, depotId, "BL 2026-118",
            listOf(
                LigneAchat(ids.getValue("tshirt"), 48_000, 48 * 6_000),
                LigneAchat(ids.getValue("jean"), 6_000, 6 * 22_000),
            ),
        )

        // Clients (gros et crédit)
        val rasoa = c.venteRepository.enregistrerClient(null, "Rasoa Boutique", "0349876543")
        c.venteRepository.enregistrerClient(null, "Jean Randria", "0329876543")
        c.venteRepository.enregistrerClient(null, "Mamy Couture", "0339876543")

        // Caisse ouverte et quelques ventes
        c.venteRepository.ouvrirCaisse(pvId, 50_000)
        vente(pvId, TypeVente.DETAIL, null, listOf("robe_lamba" to 1L, "ceinture" to 1L), ids, remise = 0) { total ->
            listOf(PaiementSaisi(ModePaiement.ESPECES, total))
        }
        vente(pvId, TypeVente.DETAIL, null, listOf("chemise_homme" to 2L), ids, remise = 0) { total ->
            listOf(PaiementSaisi(ModePaiement.MOBILE_MONEY, total, "MVola 0349911223"))
        }
        vente(pvId, TypeVente.DETAIL, null, listOf("wax" to 3L, "lamba" to 1L), ids, remise = 2_000) { total ->
            listOf(PaiementSaisi(ModePaiement.ESPECES, total))
        }
        // Vente en gros : moitié en espèces, le reste à crédit
        vente(pvId, TypeVente.GROS, rasoa, listOf("tshirt" to 10L, "jupe_longue" to 5L), ids, remise = 0) { total ->
            listOf(PaiementSaisi(ModePaiement.ESPECES, total / 2), PaiementSaisi(ModePaiement.CREDIT, total - total / 2))
        }

        // Dépenses et paiement partiel du fournisseur
        val categoriesDepense = c.database.categorieDepenseDao().observer().first().associateBy { it.nom }
        c.financeRepository.enregistrerDepense(
            categoriesDepense.getValue("Transport").id, 15_000, "Taxi-brousse pour le réassort", ModePaiement.ESPECES, pvId, null,
        )
        c.financeRepository.enregistrerDepense(
            categoriesDepense.getValue("Eau et électricité").id, 45_000, "Facture JIRAMA", ModePaiement.BANQUE, null, null,
        )
        c.financeRepository.payerFournisseur(textiles, reception, 150_000, ModePaiement.BANQUE, null, "Virement")
        // Compte Tsenako (banque Lilo simulée) approvisionné par un WTB fictif
        c.banqueRepository.ouvrirCompte()
        c.banqueRepository.approvisionner(500_000)
    }

    /** Vente avec les prix de la boutique ; [paiements] reçoit le total net. */
    private suspend fun vente(
        pvId: String,
        type: TypeVente,
        clientId: String?,
        lignes: List<Pair<String, Long>>,
        ids: Map<String, String>,
        remise: Long,
        paiements: (Long) -> List<PaiementSaisi>,
    ) {
        val brut = lignes.sumOf { (cle, quantite) ->
            val p = produits.first { it.cle == cle }
            Valorisation.valeur(quantite * 1000, if (type == TypeVente.GROS) p.gros ?: p.vente else p.vente)
        }
        c.venteRepository.validerVente(
            SaisieVente(
                pointVenteId = pvId,
                type = type,
                clientId = clientId,
                lignes = lignes.map { (cle, quantite) -> LigneVenteSaisie(ids.getValue(cle), quantite * 1000) },
                remise = remise,
                paiements = paiements(brut - remise),
            )
        )
    }

    /** Photo de démonstration : fond de la couleur de la catégorie et nom du produit. */
    private fun photo(nom: String, couleur: Int): Bitmap {
        val taille = 512
        val image = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
        val toile = Canvas(image)
        toile.drawColor(couleur)
        val pinceau = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // Initiales en grand, nom en dessous sur deux lignes au plus
        pinceau.textSize = 160f
        val initiales = nom.split(" ").filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }
        toile.drawText(initiales, taille / 2f, 250f, pinceau)
        pinceau.textSize = 40f
        val mots = nom.split(" ")
        val milieu = (mots.size + 1) / 2
        listOf(mots.take(milieu).joinToString(" "), mots.drop(milieu).joinToString(" "))
            .filter { it.isNotBlank() }
            .forEachIndexed { i, ligne -> toile.drawText(ligne, taille / 2f, 360f + i * 50f, pinceau) }
        return image
    }

    companion object {
        const val PIN_PROPRIETAIRE = "1234"

        /** Comptes créés, affichés sur l'écran de premier lancement. */
        const val COMPTES = "Hery Rakoto (propriétaire) : 1234 · Lova (gérante) : 2222 · " +
            "Fara (vendeuse) : 3333 · Toky (magasinier) : 4444"
    }
}
