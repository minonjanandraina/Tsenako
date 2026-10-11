package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

enum class TypeMouvement(val libelle: String) {
    STOCK_INITIAL("Stock initial"),
    ENTREE_ACHAT("Achat"),
    SORTIE_VENTE("Vente"),
    TRANSFERT_SORTIE("Transfert sortant"),
    TRANSFERT_ENTREE("Transfert entrant"),
    AJUSTEMENT("Ajustement d'inventaire"),
    RETOUR_CLIENT("Retour client"),
    RETOUR_FOURNISSEUR("Retour fournisseur"),
    PERTE("Perte"),
}

enum class StatutInventaire { BROUILLON, VALIDE }

/**
 * Quantité et CMUP d'un produit dans un dépôt (une ligne par couple).
 * RG-S1 : valeur dénormalisée, modifiée uniquement avec un mouvement_stock (StockRepository).
 */
@Entity(
    tableName = "stock",
    foreignKeys = [
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"]),
    ],
    indices = [Index(value = ["produit_id", "depot_id"], unique = true), Index("depot_id")],
)
data class StockEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "depot_id") val depotId: String,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long = 0,
    /** CMUP en Ariary (RG-S2) */
    @ColumnInfo(name = "cout_moyen_unitaire") val coutMoyenUnitaire: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
)

/**
 * Mouvement de stock, jamais modifié ni supprimé.
 * quantite_milli est signée : positive pour une entrée, négative pour une sortie.
 * document_type / document_id : pièce d'origine (INVENTAIRE, ACHAT, VENTE…), null pour une saisie manuelle.
 */
@Entity(
    tableName = "mouvement_stock",
    foreignKeys = [
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"]),
    ],
    indices = [Index(value = ["depot_id", "date"]), Index(value = ["produit_id", "date"]), Index("document_id")],
)
data class MouvementStockEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "depot_id") val depotId: String,
    val type: TypeMouvement,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    /** Coût unitaire du mouvement, Ariary (coût d'entrée, ou CMUP pour une sortie) */
    @ColumnInfo(name = "cout_unitaire") val coutUnitaire: Long,
    /** Stock du dépôt après le mouvement, pour l'historique */
    @ColumnInfo(name = "quantite_apres_milli") val quantiteApresMilli: Long,
    @ColumnInfo(name = "document_type") val documentType: String? = null,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    val motif: String? = null,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

@Entity(
    tableName = "inventaire",
    foreignKeys = [ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"])],
    indices = [Index("depot_id")],
)
data class InventaireEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "depot_id") val depotId: String,
    val date: Long = maintenant(),
    val statut: StatutInventaire = StatutInventaire.BROUILLON,
    @ColumnInfo(name = "date_validation") val dateValidation: Long? = null,
    @ColumnInfo(name = "valide_par") val validePar: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/**
 * quantite_comptee null = produit pas encore compté (aucun ajustement à la validation).
 * quantite_theorique et ecart sont figés à la validation, à partir du stock à cet instant.
 */
@Entity(
    tableName = "ligne_inventaire",
    foreignKeys = [
        ForeignKey(entity = InventaireEntity::class, parentColumns = ["id"], childColumns = ["inventaire_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
    ],
    indices = [Index(value = ["inventaire_id", "produit_id"], unique = true), Index("produit_id")],
)
data class LigneInventaireEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "inventaire_id") val inventaireId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "quantite_theorique_milli") val quantiteTheoriqueMilli: Long,
    @ColumnInfo(name = "quantite_comptee_milli") val quantiteCompteeMilli: Long? = null,
    @ColumnInfo(name = "ecart_milli") val ecartMilli: Long? = null,
)

/** Produit actif avec son stock dans un dépôt (0 s'il n'y a jamais eu de mouvement). */
data class StockProduit(
    @ColumnInfo(name = "produit_id") val produitId: String,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "seuil_alerte_milli") val seuilAlerteMilli: Long,
    /** Prix d'achat habituel de la fiche produit, s'il est renseigné */
    @ColumnInfo(name = "prix_achat") val prixAchat: Long?,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    @ColumnInfo(name = "cout_moyen_unitaire") val coutMoyenUnitaire: Long,
)

/** Produit sous son seuil d'alerte dans un dépôt (RG-S4). */
data class AlerteStock(
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "produit_nom") val produitNom: String,
    @ColumnInfo(name = "depot_id") val depotId: String,
    @ColumnInfo(name = "depot_nom") val depotNom: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    @ColumnInfo(name = "seuil_alerte_milli") val seuilAlerteMilli: Long,
)

data class MouvementAvecAuteur(
    @Embedded val mouvement: MouvementStockEntity,
    @ColumnInfo(name = "auteur_nom") val auteurNom: String?,
)

data class InventaireAvecDepot(
    @Embedded val inventaire: InventaireEntity,
    @ColumnInfo(name = "depot_nom") val depotNom: String,
    @ColumnInfo(name = "nb_lignes") val nbLignes: Int,
    @ColumnInfo(name = "nb_comptees") val nbComptees: Int,
)

data class LigneInventaireAvecProduit(
    @Embedded val ligne: LigneInventaireEntity,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
    /** Stock actuel du dépôt (la théorique n'est figée qu'à la validation) */
    @ColumnInfo(name = "stock_actuel_milli") val stockActuelMilli: Long,
)
