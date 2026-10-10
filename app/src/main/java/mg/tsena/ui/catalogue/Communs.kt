package mg.tsena.ui.catalogue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import mg.tsena.catalogue.Categories
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File

/** Catégorie avec son chemin complet (« Boissons › Sodas »), triées par chemin. */
data class CategorieAffichee(val categorie: TypeProduitEntity, val chemin: String)

fun List<TypeProduitEntity>.avecChemins(): List<CategorieAffichee> {
    val parId = associateBy { it.id }
    return map { c ->
        CategorieAffichee(c, Categories.chemin(c.id, { parId[it]?.nom }, { parId[it]?.parentId }))
    }.sortedBy { it.chemin.lowercase() }
}

@Composable
fun PhotoProduit(fichier: File?, modifier: Modifier = Modifier) {
    val forme = modifier.clip(RoundedCornerShape(8.dp))
    if (fichier == null) {
        Box(forme.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("Photo", style = MaterialTheme.typography.labelMedium)
        }
    } else {
        AsyncImage(model = fichier, contentDescription = null, contentScale = ContentScale.Crop, modifier = forme)
    }
}

/**
 * Création ou modification d'une catégorie. [categories] : parents possibles
 * (sans la catégorie modifiée ni ses sous-catégories).
 */
@Composable
fun CategorieDialog(
    titre: String,
    nomInitial: String,
    parentInitial: String?,
    categories: List<CategorieAffichee>,
    erreur: String?,
    onValider: (nom: String, parentId: String?) -> Unit,
    onAnnuler: () -> Unit,
) {
    var nom by rememberSaveable { mutableStateOf(nomInitial) }
    var parentId by rememberSaveable { mutableStateOf(parentInitial) }
    val options: List<CategorieAffichee?> = listOf(null) + categories

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChampTexte(nom, { nom = it }, "Nom", Modifier.fillMaxWidth())
                Selecteur(
                    libelle = "Dans la catégorie",
                    valeurAffichee = categories.firstOrNull { it.categorie.id == parentId }?.chemin ?: AUCUNE,
                    options = options,
                    texte = { it?.chemin ?: AUCUNE },
                    onSelection = { parentId = it?.categorie?.id },
                    modifier = Modifier.fillMaxWidth(),
                )
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onValider(nom, parentId) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

private const val AUCUNE = "Aucune (catégorie principale)"
