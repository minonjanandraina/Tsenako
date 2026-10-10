package mg.tsena

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import mg.tsena.ui.TsenaRoot
import mg.tsena.ui.restauration.RestaurationViewModel
import mg.tsena.ui.theme.TsenaTheme

class MainActivity : ComponentActivity() {
    private val restauration: RestaurationViewModel by viewModels { RestaurationViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) traiterFichierRecu(intent)
        setContent {
            TsenaTheme {
                TsenaRoot(restauration)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        traiterFichierRecu(intent)
    }

    /** Sauvegarde ouverte depuis WhatsApp (« Ouvrir avec ») ou partagée vers Tsena. */
    private fun traiterFichierRecu(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        restauration.recevoirFichier(uri)
    }
}
