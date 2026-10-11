package mg.tsena.credit

/** Vente à crédit d'un client : montant initial et retours déjà déduits de ce crédit. */
data class CreditVente(
    val venteId: String,
    val numero: String,
    val clientId: String,
    val date: Long,
    val montant: Long,
    val retoursCredit: Long,
)

/** Suivi d'une vente à crédit : ce qui a été remboursé et ce qui reste dû. */
data class EtatCreditVente(
    val venteId: String,
    val numero: String,
    val date: Long,
    val montant: Long,
    val retours: Long,
    val rembourse: Long,
    val ageJours: Int,
) {
    val reste: Long get() = montant - retours - rembourse
}

/** Tranches d'ancienneté des impayés. */
enum class Anciennete(val libelle: String, val court: String) {
    MOINS_30("0 à 30 jours", "0-30 j"),
    DE_31_A_60("31 à 60 jours", "31-60 j"),
    DE_61_A_90("61 à 90 jours", "61-90 j"),
    PLUS_90("Plus de 90 jours", "> 90 j"),
}

/**
 * Suivi des ventes à crédit, sans Android (testé sur JVM). Un retour sur crédit réduit la vente concernée ;
 * les remboursements du client soldent ses ventes de la plus ancienne à la plus récente.
 */
object Credits {
    private const val JOUR_MS = 24 * 60 * 60 * 1000L

    /** Délai au-delà duquel un crédit est signalé en retard. */
    const val DELAI_JOURS = 30

    fun suivre(ventes: List<CreditVente>, rembourseTotal: Long, maintenant: Long): List<EtatCreditVente> {
        var disponible = rembourseTotal
        return ventes.sortedBy { it.date }.map { v ->
            val retours = minOf(v.retoursCredit, v.montant)
            val du = v.montant - retours
            val impute = minOf(maxOf(disponible, 0), du)
            disponible -= impute
            EtatCreditVente(
                venteId = v.venteId,
                numero = v.numero,
                date = v.date,
                montant = v.montant,
                retours = retours,
                rembourse = impute,
                ageJours = ((maintenant - v.date) / JOUR_MS).toInt().coerceAtLeast(0),
            )
        }
    }

    fun anciennete(ageJours: Int): Anciennete = when {
        ageJours <= 30 -> Anciennete.MOINS_30
        ageJours <= 60 -> Anciennete.DE_31_A_60
        ageJours <= 90 -> Anciennete.DE_61_A_90
        else -> Anciennete.PLUS_90
    }

    /** Montants restant dus par tranche d'ancienneté (toutes les tranches, éventuellement à zéro). */
    fun parAnciennete(etats: List<EtatCreditVente>): Map<Anciennete, Long> {
        val sommes = etats.filter { it.reste > 0 }.groupBy { anciennete(it.ageJours) }.mapValues { (_, es) -> es.sumOf { it.reste } }
        return Anciennete.entries.associateWith { sommes[it] ?: 0L }
    }
}

/** Total remboursé par un client. */
data class TotalClient(val clientId: String, val total: Long)
