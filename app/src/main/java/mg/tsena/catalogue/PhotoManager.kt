package mg.tsena.catalogue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mg.tsena.domain.nouvelId
import java.io.File
import java.io.InputStream

class PhotoInvalideException(message: String) : Exception(message)

/**
 * Photos des produits (RG-P2) et justificatifs de dépenses (RG-D3) : redimensionnées, redressées selon l'EXIF et compressées en JPEG,
 * dans files/photos/produits/. Le dossier photos est inclus dans la sauvegarde.
 */
class PhotoManager(private val context: Context) {
    val racine: File get() = dossierPhotos(context).apply { mkdirs() }

    fun fichier(cheminRelatif: String): File = File(racine, cheminRelatif)

    /** Fichier temporaire où l'appareil photo écrit, exposé via FileProvider. */
    fun nouvelleCibleAppareilPhoto(): Uri {
        val dossier = File(context.cacheDir, "photos_temp").apply { mkdirs() }
        val fichier = File(dossier, "appareil_${nouvelId()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fichiers", fichier)
    }

    /** Importe une image (galerie ou appareil photo) dans [dossier] et renvoie son chemin relatif. */
    suspend fun importer(source: Uri, dossier: String = DOSSIER_PRODUITS): String = withContext(Dispatchers.IO) {
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ouvrir(source).use { BitmapFactory.decodeStream(it, null, bornes) }
        if (bornes.outWidth <= 0 || bornes.outHeight <= 0) throw PhotoInvalideException("Image illisible.")

        var echantillon = 1
        while (maxOf(bornes.outWidth, bornes.outHeight) / (echantillon * 2) >= TAILLE_MAX) echantillon *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = echantillon }
        val image = ouvrir(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw PhotoInvalideException("Image illisible.")
        val rotation = ouvrir(source).use { ExifInterface(it).rotationDegrees }

        val finale = redimensionner(image, rotation)
        val relatif = "$dossier/${nouvelId()}.jpg"
        val cible = File(racine, relatif).apply { parentFile?.mkdirs() }
        cible.outputStream().use { finale.compress(Bitmap.CompressFormat.JPEG, QUALITE_JPEG, it) }
        if (finale !== image) finale.recycle()
        image.recycle()
        nettoyerTemporaires()
        relatif
    }

    fun supprimer(cheminRelatif: String?) {
        if (cheminRelatif.isNullOrBlank()) return
        File(racine, cheminRelatif).delete()
    }

    private fun ouvrir(source: Uri): InputStream =
        context.contentResolver.openInputStream(source) ?: throw PhotoInvalideException("Impossible d'ouvrir l'image.")

    private fun redimensionner(image: Bitmap, rotation: Int): Bitmap {
        val echelle = minOf(1f, TAILLE_MAX.toFloat() / maxOf(image.width, image.height))
        if (echelle == 1f && rotation == 0) return image
        val matrice = Matrix().apply {
            postScale(echelle, echelle)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrice, true)
    }

    private fun nettoyerTemporaires() {
        File(context.cacheDir, "photos_temp").listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val TAILLE_MAX = 1024
        private const val QUALITE_JPEG = 80
        private const val DOSSIER_PRODUITS = "produits"
        /** Justificatifs de dépenses (RG-D3) */
        const val DOSSIER_JUSTIFICATIFS = "justificatifs"

        fun dossierPhotos(context: Context): File = File(context.filesDir, "photos")
    }
}
