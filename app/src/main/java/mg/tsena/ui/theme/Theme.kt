package mg.tsena.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Clair = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B2E0F),
    secondary = Color(0xFFF9A825),
    onSecondary = Color.Black,
)

private val Sombre = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0B2E0F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFFFD54F),
    onSecondary = Color.Black,
)

@Composable
fun TsenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Sombre else Clair, content = content)
}
