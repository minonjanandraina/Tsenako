package mg.tsena.finance

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Échéances et périodes, sans Android (testées sur JVM). */
object Echeances {
    private const val JOUR_MS = 24 * 60 * 60 * 1000L

    /** Date limite de paiement d'une réception selon le délai du fournisseur. */
    fun echeance(dateReception: Long, delaiJours: Int): Long = dateReception + delaiJours * JOUR_MS

    /** Une réception est en retard s'il reste à payer après son échéance. */
    fun enRetard(dateReception: Long, delaiJours: Int, reste: Long, maintenant: Long): Boolean =
        reste > 0 && maintenant > echeance(dateReception, delaiJours)

    /** Bornes [début, fin[ d'un mois, en millisecondes, dans le fuseau donné. */
    fun bornesMois(mois: YearMonth, fuseau: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> =
        mois.atDay(1).atStartOfDay(fuseau).toInstant().toEpochMilli() to
            mois.plusMonths(1).atDay(1).atStartOfDay(fuseau).toInstant().toEpochMilli()

    fun moisDe(millis: Long, fuseau: ZoneId = ZoneId.systemDefault()): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(millis).atZone(fuseau).toLocalDate())
}
