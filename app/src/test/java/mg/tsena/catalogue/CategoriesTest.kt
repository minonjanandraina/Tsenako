package mg.tsena.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    // boissons › sodas › colas ; epicerie
    private val parents = mapOf("boissons" to null, "sodas" to "boissons", "colas" to "sodas", "epicerie" to null)
    private val noms = mapOf("boissons" to "Boissons", "sodas" to "Sodas", "colas" to "Colas", "epicerie" to "Épicerie")

    @Test
    fun chemin() {
        assertEquals("Boissons › Sodas › Colas", Categories.chemin("colas", noms::get, parents::get))
        assertEquals("Épicerie", Categories.chemin("epicerie", noms::get, parents::get))
    }

    @Test
    fun `chemin protege contre les cycles`() {
        val cycliques = mapOf("a" to "b", "b" to "a")
        assertEquals("B › A", Categories.chemin("a", mapOf("a" to "A", "b" to "B")::get, cycliques::get))
    }

    @Test
    fun `detection des cycles`() {
        assertTrue(Categories.creeraitUnCycle("boissons", "colas", parents::get))
        assertTrue(Categories.creeraitUnCycle("sodas", "sodas", parents::get))
        assertFalse(Categories.creeraitUnCycle("colas", "epicerie", parents::get))
        assertFalse(Categories.creeraitUnCycle("epicerie", "boissons", parents::get))
    }

    @Test
    fun `appartenance a une categorie`() {
        assertTrue(Categories.estDans("colas", "boissons", parents::get))
        assertFalse(Categories.estDans("epicerie", "boissons", parents::get))
    }

    @Test
    fun `codes produits`() {
        assertEquals("P00001", Categories.prochainCode(emptyList()))
        assertEquals("P00008", Categories.prochainCode(listOf("P00007", "P00002", "RIZ-01")))
    }
}
