package mg.tsena.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule
    val dossier = TemporaryFolder()

    private val meta = BackupMeta(versionSchema = 1, versionApp = "0.1.0", dateCreation = 1_700_000_000_000, nomCommerce = "Épicerie Rasoa")

    private fun fausseBase(): File = dossier.newFile("tsena.db").apply {
        writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(100) { it.toByte() })
    }

    @Test
    fun `aller-retour base et photos`() {
        val photos = dossier.newFolder("photos")
        File(photos, "produits").mkdirs()
        File(photos, "produits/riz.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val base = fausseBase()

        val zip = ByteArrayOutputStream().also { BackupArchive.ecrire(it, base, photos, meta) }.toByteArray()
        val destination = dossier.newFolder("extrait")
        val lu = BackupArchive.lire(ByteArrayInputStream(zip), destination)

        assertEquals(meta, lu)
        assertTrue(File(destination, BackupArchive.ENTREE_BASE).readBytes().contentEquals(base.readBytes()))
        assertTrue(File(destination, "photos/produits/riz.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test(expected = SauvegardeInvalideException::class)
    fun `fichier qui n'est pas une archive`() {
        BackupArchive.lire(ByteArrayInputStream("bonjour".toByteArray()), dossier.newFolder("extrait"))
    }

    @Test(expected = SauvegardeInvalideException::class)
    fun `base absente`() {
        val zip = archive(BackupArchive.ENTREE_META to "version_schema=1\ndate_creation=1\n".toByteArray())
        BackupArchive.lire(ByteArrayInputStream(zip), dossier.newFolder("extrait"))
    }

    @Test
    fun `chemin malveillant refuse`() {
        val destination = dossier.newFolder("extrait")
        val zip = archive(
            BackupArchive.ENTREE_META to "version_schema=1\ndate_creation=1\n".toByteArray(),
            "photos/../../pirate.txt" to "x".toByteArray(),
        )
        try {
            BackupArchive.lire(ByteArrayInputStream(zip), destination)
            throw AssertionError("Une exception était attendue")
        } catch (e: SauvegardeInvalideException) {
            assertFalse(File(destination.parentFile, "pirate.txt").exists())
        }
    }

    private fun archive(vararg elements: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { sortie ->
            ZipOutputStream(sortie).use { zip ->
                elements.forEach { (nom, contenu) ->
                    zip.putNextEntry(ZipEntry(nom))
                    zip.write(contenu)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
