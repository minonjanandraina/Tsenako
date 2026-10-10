package mg.tsena.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Liste déroulante en lecture seule. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Selecteur(
    libelle: String,
    valeurAffichee: String,
    options: List<T>,
    texte: (T) -> String,
    onSelection: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ouvert by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = ouvert, onExpandedChange = { ouvert = it }, modifier = modifier) {
        OutlinedTextField(
            value = valeurAffichee,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(libelle) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ouvert) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(texte(option)) },
                    onClick = {
                        onSelection(option)
                        ouvert = false
                    },
                )
            }
        }
    }
}
