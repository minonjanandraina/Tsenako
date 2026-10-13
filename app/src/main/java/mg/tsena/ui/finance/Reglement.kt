package mg.tsena.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.domain.Montant
import mg.tsena.finance.MODES_REGLEMENT
import mg.tsena.ui.common.Selecteur

/**
 * Choix du mode de règlement puis, pour les espèces ou le mobile money, de la source de l'argent : la caisse ouverte
 * d'un point de vente ou le coffre. Sans [onSource], seule la caisse est proposée.
 * [pointVenteId] : point de vente de la caisse choisie ; [soldesCoffre] : solde du coffre par mode.
 */
@Composable
fun ChoixReglement(
    mode: ModePaiement,
    onMode: (ModePaiement) -> Unit,
    caisses: List<SessionAvecDetails>,
    pointVenteId: String?,
    onPointVente: (String) -> Unit,
    source: SourceFonds = SourceFonds.CAISSE,
    onSource: ((SourceFonds) -> Unit)? = null,
    soldesCoffre: Map<ModePaiement, Long> = emptyMap(),
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Réglé par", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODES_REGLEMENT.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onMode(m) },
                    label = { Text(if (m == ModePaiement.BANQUE) "Hors caisse" else m.libelle) },
                )
            }
        }
        if (mode == ModePaiement.BANQUE) {
            Text("Banque, virement ou argent du propriétaire : ni la caisse ni le coffre ne sont touchés.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (onSource != null) {
            Text("Argent pris dans", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceFonds.entries.forEach { s ->
                    FilterChip(selected = source == s, onClick = { onSource(s) }, label = { Text(s.libelle) })
                }
            }
        }
        if (onSource != null && source == SourceFonds.COFFRE) {
            Text("Dans le coffre : ${Montant.formater(soldesCoffre[mode] ?: 0)} (${mode.libelle.lowercase()}).", style = MaterialTheme.typography.bodySmall)
        } else if (caisses.isEmpty()) {
            Text("Aucune caisse ouverte : ouvrez une caisse, choisissez le coffre ou « Hors caisse ».", color = MaterialTheme.colorScheme.error)
        } else {
            Selecteur(
                libelle = "Caisse",
                valeurAffichee = caisses.firstOrNull { it.pointVenteId == pointVenteId }?.caisseNom.orEmpty(),
                options = caisses,
                texte = { it.caisseNom },
                onSelection = { onPointVente(it.pointVenteId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
