package mg.tsena.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * Masque le clavier dès qu'on touche la zone ailleurs que dans un champ (bouton, puce, espace vide) :
 * le toucher est observé avant les enfants, sans le consommer. Un champ touché reprend aussitôt le focus.
 */
fun Modifier.masquerClavierAuToucher(): Modifier = composed {
    val focus = LocalFocusManager.current
    pointerInput(focus) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) focus.clearFocus()
        }
    }
}

/** Saisie d'un code PIN : chiffres uniquement, 6 au maximum, masqués. */
@Composable
fun ChampPin(
    valeur: String,
    onChange: (String) -> Unit,
    libelle: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
) {
    OutlinedTextField(
        value = valeur,
        onValueChange = { saisie -> onChange(saisie.filter { it.isDigit() }.take(6)) },
        label = { Text(libelle) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        modifier = modifier,
    )
}

@Composable
fun ChampTexte(
    valeur: String,
    onChange: (String) -> Unit,
    libelle: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = valeur,
        onValueChange = onChange,
        label = { Text(libelle) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}
