package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import mg.tsena.domain.Role
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

// Conventions (voir Claude.md §4) : id UUID, created_at, updated_at, created_by, deleted_at.

@Entity(tableName = "commerce")
data class CommerceEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val devise: String = "MGA",
    val adresse: String? = null,
    val nif: String? = null,
    val stat: String? = null,
    @ColumnInfo(name = "debut_exercice_mois") val debutExerciceMois: Int = 1,
    /** RG-S3 : autorise les sorties qui rendent le stock négatif */
    @ColumnInfo(name = "stock_negatif_autorise") val stockNegatifAutorise: Boolean = false,
    /** RG-V5 : remise maximale (en % du total) qu'un vendeur peut accorder sans autorisation d'un gérant */
    @ColumnInfo(name = "remise_max_pourcent") val remiseMaxPourcent: Int = 10,
    /** RG-D3 : montant de dépense au-delà duquel le justificatif (photo) est obligatoire */
    @ColumnInfo(name = "seuil_justificatif") val seuilJustificatif: Long = 50_000,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(tableName = "utilisateur")
data class UtilisateurEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val telephone: String? = null,
    @ColumnInfo(name = "pin_hash") val pinHash: String,
    @ColumnInfo(name = "pin_sel") val pinSel: String,
    val actif: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "utilisateur_role",
    foreignKeys = [
        ForeignKey(entity = UtilisateurEntity::class, parentColumns = ["id"], childColumns = ["utilisateur_id"]),
        ForeignKey(entity = PointVenteEntity::class, parentColumns = ["id"], childColumns = ["point_vente_id"]),
    ],
    indices = [Index("utilisateur_id"), Index("point_vente_id")],
)
data class UtilisateurRoleEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "utilisateur_id") val utilisateurId: String,
    val role: Role,
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/** RG-X3 : journal d'audit des opérations sensibles. */
@Entity(tableName = "journal_audit", indices = [Index("date")])
data class JournalAuditEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "utilisateur_id") val utilisateurId: String?,
    val action: String,
    val details: String? = null,
    val date: Long = maintenant(),
)

data class UtilisateurAvecRoles(
    @Embedded val utilisateur: UtilisateurEntity,
    @Relation(parentColumn = "id", entityColumn = "utilisateur_id")
    val roles: List<UtilisateurRoleEntity>,
)

val UtilisateurAvecRoles.rolesActifs: Set<Role>
    get() = roles.filter { it.deletedAt == null }.map { it.role }.toSet()
