package mg.tsena.rapports

import mg.tsena.stock.Valorisation
import mg.tsena.vente.CalculsVente
import java.math.BigDecimal
import java.math.RoundingMode

/** Ligne de vente validée avec les totaux de sa vente, pour les rapports. */
data class LigneVenteRapport(
    val venteId: String,
    val pointVenteNom: String,
    val produitId: String,
    val produitNom: String,
    val uniteSymbole: String,
    val quantiteMilli: Long,
    val quantiteRetourneeMilli: Long,
    val montant: Long,
    val coutUnitaire: Long,
    val totalBrut: Long,
    val totalNet: Long,
)

data class VentesProduit(
    val produitId: String,
    val nom: String,
    val uniteSymbole: String,
    val quantiteMilli: Long,
    val chiffreAffaires: Long,
    val marge: Long,
)

data class RapportVentes(
    val nbVentes: Int,
    val chiffreAffaires: Long,
    val coutAchat: Long,
    val parProduit: List<VentesProduit>,
    /** Point de vente → chiffre d'affaires */
    val parPointVente: List<Pair<String, Long>>,
) {
    val marge: Long get() = chiffreAffaires - coutAchat

    /** Taux de marge sur le chiffre d'affaires, en % (null sans vente). */
    val tauxMarge: Int? get() = if (chiffreAffaires > 0) (marge * 100 / chiffreAffaires).toInt() else null

    companion object {
        val VIDE = RapportVentes(0, 0, 0, emptyList(), emptyList())
    }
}

/**
 * Chiffre d'affaires et marge, sans Android (testés sur JVM). Les quantités retournées sont déduites ;
 * la remise de la vente est répartie au prorata des lignes ; la marge utilise le coût figé à la vente (RG-V3).
 */
object CalculRapports {
    fun ventes(lignes: List<LigneVenteRapport>): RapportVentes {
        if (lignes.isEmpty()) return RapportVentes.VIDE
        val calculees = lignes.map { l ->
            val quantite = l.quantiteMilli - l.quantiteRetourneeMilli
            val valeur = if (l.quantiteMilli <= 0 || quantite == l.quantiteMilli) {
                l.montant
            } else {
                BigDecimal.valueOf(l.montant).multiply(BigDecimal.valueOf(quantite))
                    .divide(BigDecimal.valueOf(l.quantiteMilli), 0, RoundingMode.HALF_UP).longValueExact()
            }
            val ca = CalculsVente.montantRetour(valeur, l.totalBrut, l.totalNet)
            Triple(l, ca, Valorisation.valeur(quantite, l.coutUnitaire))
        }
        val parProduit = calculees.groupBy { it.first.produitId }.map { (id, ls) ->
            val premiere = ls.first().first
            VentesProduit(
                produitId = id,
                nom = premiere.produitNom,
                uniteSymbole = premiere.uniteSymbole,
                quantiteMilli = ls.sumOf { it.first.quantiteMilli - it.first.quantiteRetourneeMilli },
                chiffreAffaires = ls.sumOf { it.second },
                marge = ls.sumOf { it.second - it.third },
            )
        }.sortedByDescending { it.chiffreAffaires }
        val parPointVente = calculees.groupBy { it.first.pointVenteNom }
            .map { (nom, ls) -> nom to ls.sumOf { it.second } }
            .sortedByDescending { it.second }
        return RapportVentes(
            nbVentes = lignes.map { it.venteId }.toSet().size,
            chiffreAffaires = calculees.sumOf { it.second },
            coutAchat = calculees.sumOf { it.third },
            parProduit = parProduit,
            parPointVente = parPointVente,
        )
    }
}

/** Stock d'un produit dans un dépôt, pour l'état des stocks (RG-K6). */
data class EtatStockLigne(
    val depotNom: String,
    val produitNom: String,
    val code: String,
    val uniteSymbole: String,
    val quantiteMilli: Long,
    val coutMoyenUnitaire: Long,
) {
    val valeur: Long get() = Valorisation.valeur(quantiteMilli, coutMoyenUnitaire)
}
