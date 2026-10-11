package mg.tsena.erreurs

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mg.tsena.BuildConfig
import mg.tsena.domain.formaterDateHeure
import mg.tsena.domain.maintenant
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Plantages pendant les tests terrain : le rapport du dernier arrêt inattendu est écrit dans un fichier,
 * puis proposé à l'envoi (WhatsApp) au prochain démarrage. Aucune donnée n'est envoyée sans action de l'utilisateur.
 */
class JournalErreurs(context: Context) {
    private val fichier = File(context.filesDir, "erreurs/dernier_plantage.txt")

    private val _rapport = MutableStateFlow(lire())
    /** Rapport du dernier plantage non encore traité */
    val rapport: StateFlow<String?> = _rapport.asStateFlow()

    /** À appeler au démarrage de l'application. */
    fun installer() {
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erreur ->
            try {
                ecrire(thread, erreur)
            } catch (_: Throwable) {
                // Ne jamais masquer le plantage d'origine
            }
            precedent?.uncaughtException(thread, erreur)
        }
    }

    fun effacer() {
        fichier.delete()
        _rapport.value = null
    }

    fun intentEnvoi(texte: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Tsena : rapport d'erreur")
            .putExtra(Intent.EXTRA_TEXT, texte),
        "Envoyer le rapport d'erreur",
    )

    private fun ecrire(thread: Thread, erreur: Throwable) {
        val trace = StringWriter().also { erreur.printStackTrace(PrintWriter(it)) }.toString()
        fichier.parentFile?.mkdirs()
        fichier.writeText(
            buildString {
                appendLine("Tsena ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Date : ${formaterDateHeure(maintenant())}")
                appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Fil : ${thread.name}")
                appendLine()
                // Limite pour rester partageable par message
                append(trace.take(TAILLE_MAX))
            }
        )
    }

    private fun lire(): String? = fichier.takeIf { it.exists() }?.readText()?.ifBlank { null }

    private companion object {
        const val TAILLE_MAX = 12_000
    }
}
