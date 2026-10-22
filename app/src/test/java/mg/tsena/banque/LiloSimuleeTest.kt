package mg.tsena.banque

import kotlinx.coroutines.runBlocking
import mg.tsena.domain.RegleMetierException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class LiloSimuleeTest {
    private val lilo = LiloSimulee(Random(42)) { LocalDate.of(2026, 10, 22) }

    @Test
    fun `numero de compte Lilo a dix chiffres`() = runBlocking {
        val numero = lilo.ouvrirCompte("Boutique")
        assertTrue(numero, Regex("LILO-\\d{10}").matches(numero))
    }

    @Test
    fun `reference datee du jour`() = runBlocking {
        val reference = lilo.executer(OrdreBancaire("LILO-0000000001", 10_000, entree = true, libelle = "WTB"))
        assertTrue(reference, Regex("LL261022\\d{6}").matches(reference))
    }

    @Test
    fun `montant nul ou au-dela du plafond refuse`() {
        assertThrows(RegleMetierException::class.java) {
            runBlocking { lilo.executer(OrdreBancaire("c", 0, entree = false, libelle = "x")) }
        }
        assertThrows(RegleMetierException::class.java) {
            runBlocking { lilo.executer(OrdreBancaire("c", LiloSimulee.PLAFOND_OPERATION + 1, entree = false, libelle = "x")) }
        }
        assertEquals("Lilo", lilo.banque)
    }
}
