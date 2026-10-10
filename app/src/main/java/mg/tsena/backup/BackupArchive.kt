package mg.tsena.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupMeta(
    val versionSchema: Int,
    val versionApp: String,
    val dateCreation: Long,
    val nomCommerce: String,
)

class SauvegardeInvalideException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Format de l'archive de sauvegarde (RG-X1b) :
 *  - meta.properties   : versions et date
 *  - database/tsena.db : base Room
 *  - photos/...        : photos des produits
 *
 * Code JVM pur (sans Android) pour être testable unitairement.
 */
object BackupArchive {
    const val ENTREE_META = "meta.properties"
    const val ENTREE_BASE = "database/tsena.db"
    const val PREFIXE_PHOTOS = "photos/"

    private const val TAILLE_MAX = 2L * 1024 * 1024 * 1024
    private val ENTETE_SQLITE = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun ecrire(sortie: OutputStream, base: File, dossierPhotos: File, meta: BackupMeta) {
        ZipOutputStream(sortie).use { zip ->
            zip.putNextEntry(ZipEntry(ENTREE_META))
            metaVersProperties(meta).store(zip, "Sauvegarde Tsena")
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTREE_BASE))
            base.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            if (dossierPhotos.isDirectory) {
                dossierPhotos.walkTopDown().filter { it.isFile }.forEach { photo ->
                    zip.putNextEntry(ZipEntry(PREFIXE_PHOTOS + photo.relativeTo(dossierPhotos).invariantSeparatorsPath))
                    photo.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Extrait l'archive dans [destination] après vérification ; lève [SauvegardeInvalideException] sinon. */
    fun lire(entree: InputStream, destination: File): BackupMeta {
        val racine = destination.canonicalFile
        var meta: BackupMeta? = null
        var baseTrouvee = false
        var total = 0L
        try {
            ZipInputStream(entree).use { zip ->
                while (true) {
                    val element = zip.nextEntry ?: break
                    val nom = element.name
                    if (element.isDirectory) continue
                    when {
                        nom == ENTREE_META -> meta = propertiesVersMeta(Properties().apply { load(zip) })
                        nom == ENTREE_BASE || nom.startsWith(PREFIXE_PHOTOS) -> {
                            val cible = File(racine, nom).canonicalFile
                            if (!cible.path.startsWith(racine.path + File.separator)) {
                                throw SauvegardeInvalideException("Chemin invalide dans l'archive : $nom")
                            }
                            cible.parentFile?.mkdirs()
                            cible.outputStream().use { total += copierAvecLimite(zip, it, TAILLE_MAX - total) }
                            if (nom == ENTREE_BASE) baseTrouvee = true
                        }
                        else -> Unit // éléments inconnus ignorés
                    }
                }
            }
        } catch (e: ZipException) {
            throw SauvegardeInvalideException("Le fichier n'est pas une sauvegarde Tsena valide.", e)
        }
        val resultat = meta ?: throw SauvegardeInvalideException("Le fichier n'est pas une sauvegarde Tsena valide.")
        if (!baseTrouvee || !estBaseSqlite(File(racine, ENTREE_BASE))) {
            throw SauvegardeInvalideException("La sauvegarde ne contient pas de base de données valide.")
        }
        return resultat
    }

    private fun copierAvecLimite(entree: InputStream, sortie: OutputStream, restant: Long): Long {
        val tampon = ByteArray(DEFAULT_BUFFER_SIZE)
        var copie = 0L
        while (true) {
            val lu = entree.read(tampon)
            if (lu < 0) break
            copie += lu
            if (copie > restant) throw SauvegardeInvalideException("La sauvegarde est trop volumineuse.")
            sortie.write(tampon, 0, lu)
        }
        return copie
    }

    private fun estBaseSqlite(fichier: File): Boolean {
        if (fichier.length() < ENTETE_SQLITE.size) return false
        val entete = ByteArray(ENTETE_SQLITE.size)
        fichier.inputStream().use { it.read(entete) }
        return entete.contentEquals(ENTETE_SQLITE)
    }

    private fun metaVersProperties(meta: BackupMeta) = Properties().apply {
        setProperty("version_schema", meta.versionSchema.toString())
        setProperty("version_app", meta.versionApp)
        setProperty("date_creation", meta.dateCreation.toString())
        setProperty("nom_commerce", meta.nomCommerce)
    }

    private fun propertiesVersMeta(p: Properties): BackupMeta {
        val versionSchema = p.getProperty("version_schema")?.toIntOrNull()
        val dateCreation = p.getProperty("date_creation")?.toLongOrNull()
        if (versionSchema == null || dateCreation == null) {
            throw SauvegardeInvalideException("Informations de sauvegarde illisibles.")
        }
        return BackupMeta(
            versionSchema = versionSchema,
            versionApp = p.getProperty("version_app").orEmpty(),
            dateCreation = dateCreation,
            nomCommerce = p.getProperty("nom_commerce").orEmpty(),
        )
    }
}
