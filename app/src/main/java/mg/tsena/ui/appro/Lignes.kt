package mg.tsena.ui.appro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mg.tsena.appro.Approvisionnement
import mg.tsena.appro.LigneAchat
import mg.tsena.appro.LigneEnvoi
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.LigneCommandeAvecProduit
import mg.tsena.data.db.entity.StockProduit
import mg.tsena.domain.Montant
import mg.tsena.domain.Quantite
import mg.tsena.stock.Valorisation
import mg.tsena.ui.catalogue.PhotoProduit
import mg.tsena.ui.common.ChampTexte
import mg.tsena.ui.common.Selecteur
import java.io.File

/** Ligne en cours de saisie dans un document (commande, réception, transfert), dans l'unité du produit. */
data class LigneSaisie(
    val produitId: String,
    val code: String,
    val nom: String,
    val photoChemin: String,
    val uniteSymbole: String,
    val uniteDecimale: Boolean,
    val quantiteMilli: Long = 0,
    val montant: Long = 0,
    /** Saisie d'origine en unité d'achat, ex. « 2 ctn × 12 000 Ar » */
    val detail: String? = null,
    /** Prix d'achat de la fiche produit, proposé pour une nouvelle ligne */
    val prixAchat: Long? = null,
) {
    fun versAchat() = LigneAchat(produitId, quantiteMilli, montant)
    fun versEnvoi() = LigneEnvoi(produitId, quantiteMilli)

    companion object {
        fun depuis(p: StockProduit) =
            LigneSaisie(p.produitId, p.code, p.nom, p.photoChemin, p.uniteSymbole, p.uniteDecimale, prixAchat = p.prixAchat)

        fun depuis(l: LigneCommandeAvecProduit, quantiteMilli: Long, montant: Long) =
            LigneSaisie(l.ligne.produitId, l.code, l.nom, l.photoChemin, l.uniteSymbole, l.uniteDecimale, quantiteMilli, montant)
    }
}

/** Lignes d'un document en cours de saisie, une seule par produit. */
class EditeurLignes {
    var lignes by mutableStateOf(listOf<LigneSaisie>())
    var choixProduitOuvert by mutableStateOf(false)
    /** Ligne ouverte dans le dialogue ; quantité 0 = nouvelle ligne */
    var enEdition by mutableStateOf<LigneSaisie?>(null)
        private set

    fun choisir(p: StockProduit) {
        choixProduitOuvert = false
        enEdition = lignes.firstOrNull { it.produitId == p.produitId } ?: LigneSaisie.depuis(p)
    }

    fun modifier(ligne: LigneSaisie) {
        enEdition = ligne
    }

    fun enregistrer(ligne: LigneSaisie) {
        lignes = if (lignes.any { it.produitId == ligne.produitId }) {
            lignes.map { if (it.produitId == ligne.produitId) ligne else it }
        } else {
            lignes + ligne
        }
        enEdition = null
    }

    fun supprimer(produitId: String) {
        lignes = lignes.filterNot { it.produitId == produitId }
        enEdition = null
    }

    fun fermer() {
        enEdition = null
        choixProduitOuvert = false
    }
}

/** Liste de choix d'un produit actif, avec son stock dans le dépôt concerné. */
@Composable
fun ChoixProduitDialog(
    produits: List<StockProduit>,
    libelleStock: String,
    fichierPhoto: (String) -> File,
    onChoisir: (StockProduit) -> Unit,
    onAnnuler: () -> Unit,
) {
    var recherche by rememberSaveable { mutableStateOf("") }
    val filtres = remember(produits, recherche) {
        val t = recherche.trim()
        produits.filter { t.isEmpty() || it.nom.contains(t, ignoreCase = true) || it.code.contains(t, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Choisir un produit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = recherche,
                    onValueChange = { recherche = it },
                    placeholder = { Text("Nom ou code") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtres.isEmpty()) Text("Aucun produit.", Modifier.padding(8.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(filtres, key = { it.produitId }) { p ->
                        ListItem(
                            headlineContent = { Text(p.nom) },
                            supportingContent = { Text("${p.code} · $libelleStock : ${Quantite.formater(p.quantiteMilli)} ${p.uniteSymbole}") },
                            leadingContent = { PhotoProduit(fichierPhoto(p.photoChemin), Modifier.size(40.dp)) },
                            modifier = Modifier.clickable { onChoisir(p) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

/**
 * Saisie de la quantité (et du prix si [avecPrix]) d'une ligne. La quantité peut être saisie dans une unité
 * d'achat du produit ([conversions]) ; elle est convertie dans l'unité du produit.
 */
@Composable
fun LigneDialog(
    ligne: LigneSaisie,
    conversions: List<ConversionAvecUnite>,
    avecPrix: Boolean,
    /** Information affichée sous le titre, ex. stock disponible */
    information: String?,
    onValider: (LigneSaisie) -> Unit,
    onSupprimer: (() -> Unit)?,
    onAnnuler: () -> Unit,
) {
    val nouvelle = ligne.quantiteMilli == 0L
    var conversionId by rememberSaveable(ligne.produitId) { mutableStateOf<String?>(null) }
    var quantite by rememberSaveable(ligne.produitId) {
        mutableStateOf(if (nouvelle) "" else Quantite.formater(ligne.quantiteMilli))
    }
    var prix by rememberSaveable(ligne.produitId) {
        mutableStateOf(
            if (nouvelle) ligne.prixAchat?.toString().orEmpty() else Approvisionnement.prixUnitaire(ligne.montant, ligne.quantiteMilli).toString()
        )
    }
    var erreur by remember { mutableStateOf<String?>(null) }

    val conversion = conversions.firstOrNull { it.conversion.id == conversionId }
    val symbole = conversion?.uniteSymbole ?: ligne.uniteSymbole
    val decimale = conversion?.uniteDecimale ?: ligne.uniteDecimale
    val quantiteSaisie = Quantite.parser(quantite, decimale)
    val prixSaisi = Montant.parser(prix)
    val total = if (quantiteSaisie != null && prixSaisi != null) Valorisation.valeur(quantiteSaisie, prixSaisi) else null

    fun valider() {
        val q = quantiteSaisie
        if (q == null || q <= 0) {
            erreur = if (decimale) "Quantité invalide." else "Quantité invalide (nombre entier de $symbole)."
            return
        }
        val base = if (conversion == null) {
            q
        } else {
            Approvisionnement.versUniteProduit(q, conversion.conversion.facteurMilli, ligne.uniteDecimale) ?: run {
                erreur = "Cette quantité ne donne pas un nombre entier de ${ligne.uniteSymbole}."
                return
            }
        }
        if (!avecPrix) {
            val detail = conversion?.let { "${Quantite.formater(q)} ${it.uniteSymbole}" }
            onValider(ligne.copy(quantiteMilli = base, montant = 0, detail = detail))
            return
        }
        val p = prixSaisi ?: run {
            erreur = "Prix d'achat invalide."
            return
        }
        val montant = Valorisation.valeur(q, p)
        val detail = conversion?.let { "${Quantite.formater(q)} ${it.uniteSymbole} × ${Montant.formater(p)}" }
        onValider(ligne.copy(quantiteMilli = base, montant = montant, detail = detail))
    }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(ligne.nom) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                information?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (conversions.isNotEmpty()) {
                    val options: List<ConversionAvecUnite?> = listOf(null) + conversions
                    Selecteur(
                        libelle = "Unité de saisie",
                        valeurAffichee = conversion?.let { libelleConversion(it) } ?: ligne.uniteSymbole,
                        options = options,
                        texte = { it?.let(::libelleConversion) ?: ligne.uniteSymbole },
                        onSelection = { choix ->
                            // Prix d'achat de la fiche encore proposé : converti pour l'unité choisie
                            val base = ligne.prixAchat
                            val actuel = conversion?.let { c -> base?.let { prixPourUnite(it, c.conversion.facteurMilli) } } ?: base
                            if (nouvelle && base != null && prix == actuel?.toString()) {
                                prix = (choix?.let { prixPourUnite(base, it.conversion.facteurMilli) } ?: base).toString()
                            }
                            conversionId = choix?.conversion?.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ChampTexte(
                    quantite, { quantite = it }, "Quantité ($symbole)", Modifier.fillMaxWidth(),
                    if (decimale) KeyboardType.Decimal else KeyboardType.Number,
                )
                if (avecPrix) {
                    ChampTexte(prix, { prix = it }, "Prix d'achat par $symbole (Ar)", Modifier.fillMaxWidth(), KeyboardType.Number)
                    total?.let { Text("Montant : ${Montant.formater(it)}", style = MaterialTheme.typography.titleSmall) }
                }
                if (conversion != null && quantiteSaisie != null && quantiteSaisie > 0) {
                    Approvisionnement.versUniteProduit(quantiteSaisie, conversion.conversion.facteurMilli, ligne.uniteDecimale)?.let {
                        Text("Soit ${Quantite.formater(it)} ${ligne.uniteSymbole}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                erreur?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { valider() }) { Text("OK") } },
        dismissButton = {
            Row {
                if (onSupprimer != null && !nouvelle) {
                    TextButton(onClick = onSupprimer) { Text("Retirer", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onAnnuler) { Text("Annuler") }
            }
        },
    )
}

fun libelleConversion(c: ConversionAvecUnite): String =
    "${c.uniteSymbole} (${Quantite.formater(c.conversion.facteurMilli)} ${c.uniteProduitSymbole})"

/** Ligne de document affichée dans un formulaire ou une fiche. */
@Composable
fun LigneDocument(
    nom: String,
    photo: File,
    quantite: String,
    detail: String?,
    montant: Long?,
    modifier: Modifier = Modifier,
    complement: String? = null,
) {
    ListItem(
        headlineContent = { Text(nom) },
        supportingContent = {
            Column {
                Text(listOfNotNull(quantite, detail).joinToString(" · "))
                complement?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        leadingContent = { PhotoProduit(photo, Modifier.size(48.dp)) },
        trailingContent = if (montant != null) {
            { Text(Montant.formater(montant), style = MaterialTheme.typography.titleSmall) }
        } else {
            null
        },
        modifier = modifier,
    )
}

/** Libellé + valeur alignés, pour les en-têtes de fiche. */
@Composable
fun LigneInfo(libelle: String, valeur: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(libelle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(valeur, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
    }
}

/** Prix d'une unité d'achat contenant [facteurMilli] millièmes d'unité de stock, arrondi à l'Ariary. */
private fun prixPourUnite(prixUnitaire: Long, facteurMilli: Long): Long = Valorisation.valeur(facteurMilli, prixUnitaire)
