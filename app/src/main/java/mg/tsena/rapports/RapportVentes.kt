package mg.tsena.rapports

import mg.tsena.stock.Valorisation
import mg.tsena.vente.CalculsVente
import java.math.BigDecimal
import java.math.RoundingMode

/** Ligne de vente validée avec les totaux de sa vente, pour les rapports. */
data class LigneVenteRapport(
    val venteId: String,
    val date: Long,
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
    /** Part de la vente payée à crédit */
    val creditVente: Long,
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

/** Chiffre d'affaires d'une période, séparé entre comptant et crédit. */
data class PartsVentes(val comptant: Long, val credit: Long) {
    val total: Long get() = comptant + credit
}

/** Agrégats du tableau de performance, sans Android (testés sur JVM). */
object Performance {
    /** Chiffre d'affaires de chaque vente (retours et remises déduits), avec sa date et sa part à crédit. */
    private data class CaVente(val date: Long, val ca: Long, val credit: Long, val totalNet: Long)

    private fun parVente(lignes: List<LigneVenteRapport>): List<CaVente> =
        lignes.groupBy { it.venteId }.map { (_, ls) ->
            val premiere = ls.first()
            CaVente(premiere.date, CalculRapports.ventes(ls).chiffreAffaires, premiere.creditVente, premiere.totalNet)
        }

    /** CA par jour, selon [jour] (date de la vente → clé du jour). */
    fun <K> caPar(lignes: List<LigneVenteRapport>, cle: (Long) -> K): Map<K, Long> =
        parVente(lignes).groupBy { cle(it.date) }.mapValues { (_, vs) -> vs.sumOf { it.ca } }

    /**
     * Comptant / crédit : la part à crédit d'une vente suit la proportion crédit / total net,
     * appliquée à son chiffre d'affaires après retours.
     */
    fun <K> partsPar(lignes: List<LigneVenteRapport>, cle: (Long) -> K): Map<K, PartsVentes> =
        parVente(lignes).groupBy { cle(it.date) }.mapValues { (_, vs) ->
            val credit = vs.sumOf { v ->
                if (v.totalNet <= 0) {
                    0L
                } else {
                    BigDecimal.valueOf(v.ca).multiply(BigDecimal.valueOf(v.credit))
                        .divide(BigDecimal.valueOf(v.totalNet), 0, RoundingMode.HALF_UP).longValueExact()
                }
            }
            PartsVentes(vs.sumOf { it.ca } - credit, credit)
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
