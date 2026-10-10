package mg.tsena.backup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import mg.tsena.BuildConfig
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.domain.maintenant
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Sauvegarde partagée par WhatsApp et restauration (RG-X1b, RG-X1c). */
class BackupManager(
    private val context: Context,
    private val db: TsenaDatabase,
    private val audit: AuditLogger,
) {
    private val prefs = context.getSharedPreferences("tsena_prefs", Context.MODE_PRIVATE)

    private val dossierSauvegardes get() = File(context.cacheDir, "sauvegardes")
    private val dossierRestauration get() = File(context.cacheDir, "restauration")
    private val fichierRecu get() = File(dossierRestauration, "recu.zip")
    private val dossierExtrait get() = File(dossierRestauration, "extrait")

    /** Photos des produits (utilisé à partir du sprint S1). */
    val dossierPhotos: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    private val _derniereSauvegarde = MutableStateFlow(prefs.getLong(CLE_DERNIERE, 0L).takeIf { it > 0 })
    val derniereSauvegarde: StateFlow<Long?> = _derniereSauvegarde.asStateFlow()

    suspend fun creerSauvegarde(): File = withContext(Dispatchers.IO) {
        // Recopie le journal WAL dans le fichier principal avant de le copier
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val nomCommerce = db.commerceDao().get()?.nom.orEmpty()
        val date = maintenant()

        dossierSauvegardes.deleteRecursively()
        dossierSauvegardes.mkdirs()
        val nom = "tsena_sauvegarde_" + LocalDateTime.now().format(FORMAT_NOM) + ".zip"
        val fichier = File(dossierSauvegardes, nom)
        fichier.outputStream().buffered().use {
            BackupArchive.ecrire(
                it,
                context.getDatabasePath(TsenaDatabase.NOM_FICHIER),
                dossierPhotos,
                BackupMeta(TsenaDatabase.VERSION, BuildConfig.VERSION_NAME, date, nomCommerce),
            )
        }

        prefs.edit().putLong(CLE_DERNIERE, date).apply()
        _derniereSauvegarde.value = date
        audit.enregistrer(ActionAudit.SAUVEGARDE, nom)
        fichier
    }

    /** Intent de partage : directement vers WhatsApp s'il est installé, sinon menu de partage. */
    fun intentPartage(fichier: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fichiers", fichier)
        val envoi = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val whatsapp = PAQUETS_WHATSAPP.firstOrNull(::estInstalle)
        return if (whatsapp != null) envoi.setPackage(whatsapp) else Intent.createChooser(envoi, "Partager la sauvegarde")
    }

    /** Copie immédiatement le fichier reçu (l'accès à l'URI peut expirer), puis le vérifie. */
    suspend fun recevoirEtVerifier(uri: Uri): BackupMeta = withContext(Dispatchers.IO) {
        dossierRestauration.deleteRecursively()
        dossierExtrait.mkdirs()
        val entree = context.contentResolver.openInputStream(uri)
            ?: throw SauvegardeInvalideException("Impossible de lire le fichier.")
        entree.use { input -> fichierRecu.outputStream().use { input.copyTo(it) } }

        val meta = fichierRecu.inputStream().buffered().use { BackupArchive.lire(it, dossierExtrait) }
        if (meta.versionSchema > TsenaDatabase.VERSION) {
            throw SauvegardeInvalideException(
                "Cette sauvegarde vient d'une version plus récente de Tsena. Mettez l'application à jour."
            )
        }
        meta
    }

    /** Remplace la base et les photos par celles de la sauvegarde vérifiée. L'app doit ensuite redémarrer. */
    suspend fun appliquerRestauration() = withContext(Dispatchers.IO) {
        val baseExtraite = File(dossierExtrait, BackupArchive.ENTREE_BASE)
        check(baseExtraite.exists()) { "Aucune sauvegarde vérifiée à restaurer." }

        db.close()
        val chemin = context.getDatabasePath(TsenaDatabase.NOM_FICHIER)
        listOf("", "-wal", "-shm", "-journal").forEach { File(chemin.path + it).delete() }
        baseExtraite.copyTo(chemin, overwrite = true)

        val photos = File(context.filesDir, "photos")
        photos.deleteRecursively()
        File(dossierExtrait, BackupArchive.PREFIXE_PHOTOS).takeIf { it.isDirectory }?.copyRecursively(photos, overwrite = true)

        prefs.edit().remove(CLE_DERNIERE).commit()
        dossierRestauration.deleteRecursively()
    }

    fun annulerRestauration() {
        dossierRestauration.deleteRecursively()
    }

    /** Relance l'application pour rouvrir la base restaurée. */
    fun redemarrerApplication() {
        val lancement = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(lancement.component))
        Runtime.getRuntime().exit(0)
    }

    private fun estInstalle(paquet: String): Boolean = try {
        context.packageManager.getPackageInfo(paquet, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val CLE_DERNIERE = "derniere_sauvegarde"
        val PAQUETS_WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")
        val FORMAT_NOM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
    }
}
