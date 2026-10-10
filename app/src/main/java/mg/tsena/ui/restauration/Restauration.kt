package mg.tsena.ui.restauration

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mg.tsena.backup.BackupManager
import mg.tsena.backup.BackupMeta
import mg.tsena.backup.SauvegardeInvalideException
import mg.tsena.container
import mg.tsena.domain.formaterDateHeure
import java.io.IOException

sealed interface EtatRestauration {
    data object Aucune : EtatRestauration
    data object Lecture : EtatRestauration
    data class AConfirmer(val meta: BackupMeta) : EtatRestauration
    data object EnCours : EtatRestauration
    data class Erreur(val message: String) : EtatRestauration
}

class RestaurationViewModel(private val backup: BackupManager) : ViewModel() {
    private val _etat = MutableStateFlow<EtatRestauration>(EtatRestauration.Aucune)
    val etat: StateFlow<EtatRestauration> = _etat.asStateFlow()

    fun recevoirFichier(uri: Uri) {
        _etat.value = EtatRestauration.Lecture
        viewModelScope.launch {
            _etat.value = try {
                EtatRestauration.AConfirmer(backup.recevoirEtVerifier(uri))
            } catch (e: SauvegardeInvalideException) {
                EtatRestauration.Erreur(e.message ?: "Sauvegarde invalide.")
            } catch (e: IOException) {
                EtatRestauration.Erreur("Impossible de lire le fichier : ${e.message}")
            }
        }
    }

    fun confirmer() {
        _etat.value = EtatRestauration.EnCours
        viewModelScope.launch {
            try {
                backup.appliquerRestauration()
                backup.redemarrerApplication()
            } catch (e: IOException) {
                _etat.value = EtatRestauration.Erreur("Échec de la restauration : ${e.message}")
            } catch (e: IllegalStateException) {
                _etat.value = EtatRestauration.Erreur("Échec de la restauration : ${e.message}")
            }
        }
    }

    fun annuler() {
        backup.annulerRestauration()
        _etat.value = EtatRestauration.Aucune
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RestaurationViewModel(container.backupManager) }
        }
    }
}

@Composable
fun RestaurationDialog(etat: EtatRestauration, autorise: Boolean, vm: RestaurationViewModel) {
    when (etat) {
        EtatRestauration.Aucune -> Unit
        EtatRestauration.Lecture -> Attente("Vérification de la sauvegarde…")
        EtatRestauration.EnCours -> Attente("Restauration en cours…")
        is EtatRestauration.Erreur -> Message(etat.message, vm::annuler)
        is EtatRestauration.AConfirmer ->
            if (!autorise) {
                Message("Seul le propriétaire peut restaurer une sauvegarde.", vm::annuler)
            } else {
                AlertDialog(
                    onDismissRequest = vm::annuler,
                    title = { Text("Restaurer la sauvegarde ?") },
                    text = {
                        Text(
                            "Commerce : ${etat.meta.nomCommerce}\n" +
                                "Date : ${formaterDateHeure(etat.meta.dateCreation)}\n\n" +
                                "Toutes les données actuelles de ce téléphone seront remplacées. " +
                                "Cette action est irréversible."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = vm::confirmer,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Restaurer") }
                    },
                    dismissButton = { TextButton(onClick = vm::annuler) { Text("Annuler") } },
                )
            }
    }
}

@Composable
private fun Attente(texte: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(texte)
            }
        },
    )
}

@Composable
private fun Message(texte: String, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        title = { Text("Restauration impossible") },
        text = { Text(texte) },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
    )
}
