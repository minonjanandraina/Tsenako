package mg.tsena.ui.rapports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mg.tsena.domain.Montant

/**
 * Couleurs des graphiques (palette de référence validée : séries 1 et 2 côte à côte, rampe ordinale bleue),
 * choisies séparément pour le thème clair et le thème sombre.
 */
class CouleursGraphique(
    val serie1: Color,
    val serie2: Color,
    /** Rampe ordinale du moins au plus marqué (ancienneté) */
    val ordinale: List<Color>,
    val ligneBase: Color,
    val texteDiscret: Color,
)

@Composable
fun couleursGraphique(): CouleursGraphique =
    if (isSystemInDarkTheme()) {
        CouleursGraphique(
            serie1 = Color(0xFF3987E5),
            serie2 = Color(0xFFD95926),
            ordinale = listOf(Color(0xFF184F95), Color(0xFF256ABF), Color(0xFF3987E5), Color(0xFF86B6EF)),
            ligneBase = Color(0xFF383835),
            texteDiscret = Color(0xFF898781),
        )
    } else {
        CouleursGraphique(
            serie1 = Color(0xFF2A78D6),
            serie2 = Color(0xFFEB6834),
            ordinale = listOf(Color(0xFF86B6EF), Color(0xFF3987E5), Color(0xFF256ABF), Color(0xFF184F95)),
            ligneBase = Color(0xFFC3C2B7),
            texteDiscret = Color(0xFF898781),
        )
    }

/** Barre d'un graphique : une ou deux valeurs empilées (série 1 en bas). */
class Barre(val libelle: String, val detail: String, val valeurs: List<Long>, val couleurs: List<Color>)

/**
 * Barres verticales, éventuellement empilées. Un appui sur une barre affiche sa valeur au-dessus du graphique ;
 * [etiquetteToutesLes] : une étiquette d'axe toutes les n barres.
 */
@Composable
fun GraphiqueBarres(
    barres: List<Barre>,
    description: String,
    modifier: Modifier = Modifier,
    hauteur: Int = 160,
    etiquetteToutesLes: Int = 1,
) {
    val c = couleursGraphique()
    val surface = MaterialTheme.colorScheme.surface
    var selection by remember(barres) { mutableStateOf<Int?>(null) }
    val maximum = barres.maxOfOrNull { b -> b.valeurs.sum() }?.takeIf { it > 0 } ?: 1L

    Column(modifier) {
        // Valeur de la barre touchée (ou maximum par défaut), en encre de texte
        Text(
            selection?.let { i -> barres.getOrNull(i)?.detail } ?: "Maximum : ${Montant.formater(barres.maxOfOrNull { it.valeurs.sum() } ?: 0)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (selection == null) c.texteDiscret else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(hauteur.dp)
                .semantics { contentDescription = description }
                .pointerInput(barres) {
                    detectTapGestures { position ->
                        val largeurSlot = size.width.toFloat() / barres.size.coerceAtLeast(1)
                        val i = (position.x / largeurSlot).toInt().coerceIn(0, barres.lastIndex)
                        selection = if (selection == i) null else i
                    }
                },
        ) {
            val largeurSlot = size.width / barres.size.coerceAtLeast(1)
            val largeurBarre = (largeurSlot * 0.7f).coerceAtMost(48.dp.toPx())
            val ecart = 2.dp.toPx()
            barres.forEachIndexed { i, b ->
                val x = i * largeurSlot + (largeurSlot - largeurBarre) / 2
                var bas = size.height
                b.valeurs.forEachIndexed { j, v ->
                    if (v <= 0) return@forEachIndexed
                    val h = size.height * v / maximum
                    val dessus = j == b.valeurs.indexOfLast { it > 0 }
                    val couleur = b.couleurs[j].let { if (selection != null && selection != i) it.copy(alpha = 0.45f) else it }
                    segment(couleur, x, bas - h, largeurBarre, h, arrondiHaut = dessus)
                    // Écart de 2 dp (couleur du fond) entre deux segments empilés
                    bas -= h
                    if (!dessus) drawRect(surface, Offset(x, bas - ecart / 2), Size(largeurBarre, ecart))
                }
            }
            drawLine(c.ligneBase, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            barres.forEachIndexed { i, b ->
                Text(
                    if (i % etiquetteToutesLes == 0) b.libelle else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.texteDiscret,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Segment de barre ; seul le bout du dessus est arrondi (4 dp), la base reste posée sur l'axe. */
private fun DrawScope.segment(couleur: Color, x: Float, y: Float, largeur: Float, hauteur: Float, arrondiHaut: Boolean) {
    val rayon = 4.dp.toPx().coerceAtMost(hauteur / 2)
    if (arrondiHaut && hauteur > rayon) {
        drawRoundRect(couleur, Offset(x, y), Size(largeur, hauteur), CornerRadius(rayon, rayon))
        drawRect(couleur, Offset(x, y + hauteur / 2), Size(largeur, hauteur / 2))
    } else {
        drawRect(couleur, Offset(x, y), Size(largeur, hauteur))
    }
}

/** Légende : obligatoire dès deux séries. */
@Composable
fun Legende(elements: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        elements.forEach { (libelle, couleur) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(couleur, RoundedCornerShape(2.dp)))
                Text(" $libelle", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Barres horizontales libellées (classements) : nom, barre proportionnelle, valeur en texte. */
@Composable
fun BarresHorizontales(elements: List<Triple<String, Long, String>>, couleur: Color) {
    val maximum = elements.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        elements.forEach { (libelle, valeur, texte) ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(libelle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(texte, style = MaterialTheme.typography.bodySmall)
                }
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (valeur.toFloat() / maximum).coerceIn(0.02f, 1f))
                        .height(10.dp)
                        .background(couleur, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
                )
            }
        }
    }
}
