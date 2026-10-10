package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

/** Salle de stockage. point_vente_id : point de vente auquel le dépôt est rattaché (magasin), sinon null. */
@Entity(
    tableName = "depot",
    foreignKeys = [ForeignKey(entity = PointVenteEntity::class, parentColumns = ["id"], childColumns = ["point_vente_id"])],
    indices = [Index("point_vente_id")],
)
data class DepotEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val adresse: String? = null,
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String? = null,
    val actif: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/** depot_id : dépôt dans lequel les ventes de ce point de vente prélèvent le stock. */
@Entity(
    tableName = "point_vente",
    foreignKeys = [ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"])],
    indices = [Index("depot_id")],
)
data class PointVenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val adresse: String? = null,
    @ColumnInfo(name = "depot_id") val depotId: String,
    val actif: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

data class PointVenteAvecDepot(
    @Embedded val pointVente: PointVenteEntity,
    @ColumnInfo(name = "depot_nom") val depotNom: String,
)
