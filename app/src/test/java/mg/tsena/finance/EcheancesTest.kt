package mg.tsena.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneOffset

class EcheancesTest {
    private val jour = 24 * 60 * 60 * 1000L

    @Test
    fun `retard apres le delai seulement s il reste a payer`() {
        assertTrue(Echeances.enRetard(0, 30, 1_000, 31 * jour))
        assertFalse(Echeances.enRetard(0, 30, 1_000, 29 * jour))
        assertFalse(Echeances.enRetard(0, 30, 0, 31 * jour))
    }

    @Test
    fun `paiement comptant en retard des le lendemain`() {
        assertTrue(Echeances.enRetard(0, 0, 500, 1))
    }

    @Test
    fun `bornes du mois de fevrier 2026`() {
        val (debut, fin) = Echeances.bornesMois(YearMonth.of(2026, 2), ZoneOffset.UTC)
        assertEquals(28 * jour, fin - debut)
        assertEquals(YearMonth.of(2026, 2), Echeances.moisDe(debut, ZoneOffset.UTC))
        assertEquals(YearMonth.of(2026, 3), Echeances.moisDe(fin, ZoneOffset.UTC))
    }
}
