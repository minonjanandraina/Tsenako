package mg.tsena.domain

import java.math.BigDecimal

/**
 * Quantités stockées en entier, en millièmes d'unité (1,5 kg = 1500), pour éviter les erreurs d'arrondi.
 * Colonnes suffixées `_milli`.
 */
object Quantite {
    const val ECHELLE = 3

    /** Lit une saisie (« 1,5 » ou « 1.5 ») ; null si invalide, négative ou décimale pour une unité entière. */
    fun parser(texte: String, decimale: Boolean): Long? {
        val t = texte.trim().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return 0
        val valeur = t.toBigDecimalOrNull()?.stripTrailingZeros() ?: return null
        if (valeur.signum() < 0) return null
        if (valeur.scale() > (if (decimale) ECHELLE else 0)) return null
        return valeur.movePointRight(ECHELLE).longValueExact()
    }

    fun formater(milli: Long): String =
        BigDecimal.valueOf(milli, ECHELLE).stripTrailingZeros().toPlainString().replace('.', ',')
}

/** Montants en Ariary entiers, sans décimales (RG-K8). */
object Montant {
    fun parser(texte: String): Long? {
        val t = texte.replace(" ", "").replace(" ", "")
        if (t.isEmpty() || !t.all { it.isDigit() }) return null
        return t.toLongOrNull()
    }

    /** 12500 → « 12 500 Ar » */
    fun formater(ariary: Long): String {
        val chiffres = kotlin.math.abs(ariary).toString().reversed().chunked(3).joinToString(" ").reversed()
        return (if (ariary < 0) "-" else "") + chiffres + " Ar"
    }
}
