package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

enum class StatutLotAppro(val libelle: String) {
    EN_COURS("En préparation"),
    ENVOYE("Envoyé"),
    ANNULE("Annulé"),
}

/**
 * Un appro (ex. « Appro 2026-10-13 ») : une tournée d'achats chez plusieurs fournisseurs, avec un budget en espèces
 * pris au coffre, à étiqueter puis à envoyer au dépôt de vente par un transfert. Chaque achat est une réception
 * (reception.lot_id) payée directement au fournisseur.
 */
@Entity(
    tableName = "lot_appro",
    foreignKeys = [
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_source_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_destination_id"]),
        ForeignKey(entity = TransfertEntity::class, parentColumns = ["id"], childColumns = ["transfert_id"]),
    ],
    indices = [Index(value = ["numero"], unique = true), Index("depot_source_id"), Index("depot_destination_id"), Index("transfert_id")],
)
data class LotApproEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** AP-000001 */
    val numero: String,
    /** Appro 2026-10-13 */
    val nom: String,
    val date: Long = maintenant(),
    /** Montant en espèces dédié à cet appro : les paiements aux fournisseurs ne le dépassent pas */
    val budget: Long,
    /** Dépôt où les achats sont reçus et étiquetés (ex. Antananarivo) */
    @ColumnInfo(name = "depot_source_id") val depotSourceId: String,
    /** Dépôt de vente à qui l'appro est envoyé (ex. Antsirabe) */
    @ColumnInfo(name = "depot_destination_id") val depotDestinationId: String,
    val statut: StatutLotAppro = StatutLotAppro.EN_COURS,
    @ColumnInfo(name = "transfert_id") val transfertId: String? = null,
    @ColumnInfo(name = "date_envoi") val dateEnvoi: Long? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/**
 * Étiquetage d'un produit de l'appro : les codes produit sont posés à la main sur chaque article, puis on compte
 * ce qu'il y a réellement. L'écart avec la quantité achetée est justifié (motif) avant l'envoi.
 */
@Entity(
    tableName = "etiquetage_appro",
    foreignKeys = [
        ForeignKey(entity = LotApproEntity::class, parentColumns = ["id"], childColumns = ["lot_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
    ],
    indices = [Index(value = ["lot_id", "produit_id"], unique = true), Index("produit_id")],
)
data class EtiquetageApproEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "lot_id") val lotId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    /** Nombre d'articles réellement étiquetés / comptés */
    @ColumnInfo(name = "quantite_comptee_milli") val quantiteCompteeMilli: Long,
    @ColumnInfo(name = "motif_ecart") val motifEcart: String? = null,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

// --- Résultats de requêtes ---

data class LotApproAvecTotaux(
    @Embedded val lot: LotApproEntity,
    @ColumnInfo(name = "depot_source_nom") val depotSourceNom: String,
    @ColumnInfo(name = "depot_destination_nom") val depotDestinationNom: String,
    @ColumnInfo(name = "nb_achats") val nbAchats: Int,
    @ColumnInfo(name = "total_achats") val totalAchats: Long,
    @ColumnInfo(name = "total_paye") val totalPaye: Long,
    @ColumnInfo(name = "nb_produits") val nbProduits: Int,
    @ColumnInfo(name = "nb_etiquetes") val nbEtiquetes: Int,
) {
    val budgetRestant: Long get() = lot.budget - totalPaye
}

/** Un produit de l'appro : quantité achetée (retours fournisseur déduits) et quantité comptée après étiquetage. */
data class LigneLotAppro(
    @ColumnInfo(name = "produit_id") val produitId: String,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
    @ColumnInfo(name = "quantite_achetee_milli") val quantiteAcheteeMilli: Long,
    @ColumnInfo(name = "montant_achete") val montantAchete: Long,
    @ColumnInfo(name = "quantite_comptee_milli") val quantiteCompteeMilli: Long?,
    @ColumnInfo(name = "motif_ecart") val motifEcart: String?,
) {
    val etiquete: Boolean get() = quantiteCompteeMilli != null
    /** Compté − acheté : négatif = manque, positif = surplus */
    val ecartMilli: Long? get() = quantiteCompteeMilli?.let { it - quantiteAcheteeMilli }
}

/** Un achat de l'appro (une réception) avec ce qui a été payé. */
data class AchatLotAppro(
    @Embedded val reception: ReceptionEntity,
    @ColumnInfo(name = "fournisseur_nom") val fournisseurNom: String,
    @ColumnInfo(name = "total_paye") val totalPaye: Long,
)
