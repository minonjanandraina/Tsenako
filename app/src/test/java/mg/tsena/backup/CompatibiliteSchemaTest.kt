package mg.tsena.backup

import mg.tsena.backup.CompatibiliteSchema.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibiliteSchemaTest {
    @Test
    fun `meme version et meme empreinte`() {
        assertEquals(Verdict.Compatible, CompatibiliteSchema.verifier(1, "abc", 1, "abc", emptyList()))
    }

    @Test
    fun `version de test incompatible`() {
        assertTrue(CompatibiliteSchema.verifier(1, "ancien", 1, "abc", emptyList()) is Verdict.Refusee)
    }

    @Test
    fun `version plus recente refusee`() {
        assertTrue(CompatibiliteSchema.verifier(3, "x", 2, "y", emptyList()) is Verdict.Refusee)
    }

    @Test
    fun `ancienne version acceptee si toutes les migrations existent`() {
        assertEquals(Verdict.Compatible, CompatibiliteSchema.verifier(1, "v1", 3, "v3", listOf(1 to 2, 2 to 3)))
        assertTrue(CompatibiliteSchema.verifier(1, "v1", 3, "v3", listOf(2 to 3)) is Verdict.Refusee)
    }

    @Test
    fun `fichier sans donnees Tsena`() {
        assertTrue(CompatibiliteSchema.verifier(0, null, 1, "abc", emptyList()) is Verdict.Refusee)
    }
}
