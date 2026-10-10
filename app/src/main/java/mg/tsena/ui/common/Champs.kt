package mg.tsena.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

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
