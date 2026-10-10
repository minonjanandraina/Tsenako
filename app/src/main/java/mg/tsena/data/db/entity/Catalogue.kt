package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

/** Catégorie de produit (hiérarchique via parent_id). */
@Entity(
    tableName = "type_produit",
    foreignKeys = [ForeignKey(entity = TypeProduitEntity::class, parentColumns = ["id"], childColumns = ["parent_id"])],
    indices = [Index("parent_id")],
)
data class TypeProduitEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(tableName = "unite")
data class UniteEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val symbole: String,
    /** true si la quantité peut être fractionnaire (kg, litre) */
    val decimale: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "produit",
    foreignKeys = [
        ForeignKey(entity = TypeProduitEntity::class, parentColumns = ["id"], childColumns = ["type_produit_id"]),
        ForeignKey(entity = UniteEntity::class, parentColumns = ["id"], childColumns = ["unite_id"]),
    ],
    indices = [
        Index("type_produit_id"),
        Index("unite_id"),
        Index(value = ["code"], unique = true),
        Index(value = ["code_barre"], unique = true),
    ],
)
data class ProduitEntity(
    @PrimaryKey val id: String = nouvelId(),
    val code: String,
    @ColumnInfo(name = "code_barre") val codeBarre: String? = null,
    val nom: String,
    @ColumnInfo(name = "type_produit_id") val typeProduitId: String,
    @ColumnInfo(name = "unite_id") val uniteId: String,
    /** Ariary */
    @ColumnInfo(name = "prix_vente") val prixVente: Long,
    @ColumnInfo(name = "seuil_alerte_milli") val seuilAlerteMilli: Long = 0,
    /** Chemin relatif au dossier photos (ex. produits/<uuid>.jpg), pour rester valide après restauration */
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    val actif: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

data class ProduitAvecDetails(
    @Embedded val produit: ProduitEntity,
    @ColumnInfo(name = "categorie_nom") val categorieNom: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
)
