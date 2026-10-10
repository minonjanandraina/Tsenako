package mg.tsena.vente

import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.TypeOperationCaisse
import mg.tsena.data.db.entity.TypePaiement
import mg.tsena.data.db.entity.TypeVente
import java.math.BigDecimal
import java.math.RoundingMode

/** Calculs de vente et de caisse sans Android (testés sur JVM). Montants en Ariary, quantités en millièmes. */
object CalculsVente {
    /** RG-V1 / RG-P9 : prix selon le type de vente ; null si le produit n'est pas vendu en gros. */
    fun prixApplique(type: TypeVente, prixDetail: Long, prixGros: Long?): Long? =
        when (type) {
            TypeVente.DETAIL -> prixDetail
            TypeVente.GROS -> prixGros
        }

    /** RG-V5 : la remise reste dans la limite si remise ≤ total × pourcentage. */
    fun remiseDansLimite(totalBrut: Long, remise: Long, pourcentMax: Int): Boolean =
        BigDecimal.valueOf(remise).multiply(BigDecimal.valueOf(100)) <= BigDecimal.valueOf(totalBrut).multiply(BigDecimal.valueOf(pourcentMax.toLong()))

    /** Sans crédit : comptant ; tout à crédit : crédit ; sinon mixte. */
    fun typePaiement(modes: Collection<ModePaiement>): TypePaiement =
        when {
            ModePaiement.CREDIT !in modes -> TypePaiement.COMPTANT
            modes.all { it == ModePaiement.CREDIT } -> TypePaiement.CREDIT
            else -> TypePaiement.MIXTE
        }

    /** Monnaie à rendre sur un paiement en espèces ; null si le montant reçu est insuffisant. */
    fun rendu(recu: Long, du: Long): Long? = if (recu >= du) recu - du else null

    /**
     * Montant remboursé pour un retour : valeur des quantités au prix de la ligne, diminuée de la part
     * de remise de la vente (prorata total net / total brut), arrondie à l'Ariary.
     */
    fun montantRetour(valeurLignes: Long, totalBrut: Long, totalNet: Long): Long {
        if (totalBrut <= 0 || totalNet == totalBrut) return valeurLignes
        return BigDecimal.valueOf(valeurLignes).multiply(BigDecimal.valueOf(totalNet))
            .divide(BigDecimal.valueOf(totalBrut), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** RG-C2 : solde théorique d'un mode = fond initial (espèces) + entrées − sorties. */
    fun soldeTheorique(fondInitial: Long, operations: List<Pair<TypeOperationCaisse, Long>>): Long =
        fondInitial + operations.sumOf { (type, montant) -> if (type.entree) montant else -montant }
}
