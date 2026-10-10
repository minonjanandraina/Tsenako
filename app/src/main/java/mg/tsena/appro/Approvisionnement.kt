package mg.tsena.appro

import mg.tsena.data.db.entity.StatutCommande
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Calculs d'approvisionnement sans Android (testés sur JVM). Quantités en millièmes, montants en Ariary. */
object Approvisionnement {
    private val MILLE = BigInteger.valueOf(1000)

    /**
     * Quantité saisie dans une unité d'achat (millièmes) → millièmes de l'unité du produit.
     * [facteurMilli] : millièmes de l'unité du produit contenus dans 1 unité d'achat.
     * null si le résultat n'est pas exprimable (fraction de millième, ou fraction d'une unité entière).
     */
    fun versUniteProduit(quantiteSaisieMilli: Long, facteurMilli: Long, uniteProduitDecimale: Boolean): Long? {
        val produit = BigInteger.valueOf(quantiteSaisieMilli) * BigInteger.valueOf(facteurMilli)
        val (resultat, reste) = produit.divideAndRemainder(MILLE)
        if (reste.signum() != 0) return null
        if (!uniteProduitDecimale && resultat.mod(MILLE).signum() != 0) return null
        return if (resultat.bitLength() < 63) resultat.toLong() else null
    }

    /** Coût unitaire d'entrée en stock : montant de la ligne / quantité, arrondi à l'Ariary. */
    fun prixUnitaire(montant: Long, quantiteMilli: Long): Long {
        require(quantiteMilli > 0) { "La quantité doit être positive." }
        return BigDecimal.valueOf(montant).movePointRight(3)
            .divide(BigDecimal.valueOf(quantiteMilli), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** Part du montant d'une ligne de commande correspondant à [quantiteMilli] (exacte pour la ligne entière). */
    fun montantAuProrata(montantLigne: Long, quantiteLigneMilli: Long, quantiteMilli: Long): Long {
        if (quantiteMilli == quantiteLigneMilli || quantiteLigneMilli <= 0) return montantLigne
        return BigDecimal.valueOf(montantLigne).multiply(BigDecimal.valueOf(quantiteMilli))
            .divide(BigDecimal.valueOf(quantiteLigneMilli), 0, RoundingMode.HALF_UP).longValueExact()
    }

    /** Quantité restant à recevoir sur une ligne de commande (jamais négative). */
    fun reliquat(commandeeMilli: Long, recueMilli: Long): Long = maxOf(0, commandeeMilli - recueMilli)

    /**
     * RG-A2 : statut d'une commande après une réception, d'après les couples (commandée, reçue) de ses lignes.
     * Reçue quand toutes les lignes sont complètes, sinon partielle.
     */
    fun statutApresReception(lignes: List<Pair<Long, Long>>): StatutCommande =
        if (lignes.all { (commandee, recue) -> recue >= commandee }) StatutCommande.RECUE else StatutCommande.PARTIELLE

    /** Numéro de document séquentiel : (« CF », 12) → « CF-000012 ». */
    fun numero(prefixe: String, rang: Int): String = "$prefixe-" + rang.toString().padStart(6, '0')
}
