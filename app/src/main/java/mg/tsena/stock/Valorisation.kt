package mg.tsena.stock

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Calculs de stock sans Android (testés sur JVM). Quantités en millièmes, montants en Ariary. */
object Valorisation {
    /**
     * RG-S2 : coût moyen unitaire pondéré après une entrée, arrondi à l'Ariary.
     * Si le stock avant l'entrée est nul ou négatif, le coût de l'entrée devient le nouveau CMUP.
     */
    fun nouveauCmup(quantiteAvant: Long, cmupAvant: Long, quantiteEntree: Long, coutEntree: Long): Long {
        require(quantiteEntree > 0) { "Une entrée a une quantité positive." }
        if (quantiteAvant <= 0) return coutEntree
        // Produits en BigInteger : quantité (millièmes) × coût peut dépasser Long pour de gros stocks
        val valeur = BigInteger.valueOf(quantiteAvant) * BigInteger.valueOf(cmupAvant) +
            BigInteger.valueOf(quantiteEntree) * BigInteger.valueOf(coutEntree)
        val total = BigInteger.valueOf(quantiteAvant + quantiteEntree)
        return BigDecimal(valeur).divide(BigDecimal(total), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** Valeur d'une quantité (millièmes) au coût unitaire donné, arrondie à l'Ariary. */
    fun valeur(quantiteMilli: Long, coutUnitaire: Long): Long =
        BigDecimal.valueOf(quantiteMilli).multiply(BigDecimal.valueOf(coutUnitaire))
            .movePointLeft(3).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** RG-S3 : une sortie est refusée si elle rend le stock négatif, sauf si le commerce l'autorise. */
    fun sortiePossible(quantiteAvant: Long, quantiteSortie: Long, negatifAutorise: Boolean): Boolean =
        negatifAutorise || quantiteAvant - quantiteSortie >= 0

    /** RG-S4 : alerte quand la quantité atteint le seuil ; un seuil à 0 signifie « pas d'alerte ». */
    fun enAlerte(quantiteMilli: Long, seuilMilli: Long): Boolean = seuilMilli > 0 && quantiteMilli <= seuilMilli
}
