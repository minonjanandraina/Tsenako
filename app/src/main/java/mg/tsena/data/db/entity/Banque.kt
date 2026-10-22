package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

/**
 * Compte Tsenako : compte bancaire du commerce, tenu par une banque partenaire (Lilo, simulée pour l'instant).
 * Un seul compte par commerce (RG-B1).
 */
@Entity(tableName = "compte_bancaire", indices = [Index(value = ["numero"], unique = true)])
data class CompteBancaireEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** Nom de la banque partenaire (ex. « Lilo ») */
    val banque: String,
    /** Numéro attribué par la banque, communiqué pour les WTB */
    val numero: String,
    val intitule: String,
    val titulaire: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

enum class TypeOperationBancaire(val libelle: String, val entree: Boolean) {
    /** Argent arrivé sur le compte (simulation d'un WTB depuis le mobile money) */
    APPROVISIONNEMENT("Approvisionnement", true),
    /** Virement à un fournisseur depuis l'app */
    PAIEMENT_FOURNISSEUR("Paiement fournisseur", false),
}

/**
 * Mouvement du compte Tsenako, jamais modifié. Solde = entrées − sorties (RG-B2).
 * [reference] : référence de la transaction renvoyée par la banque.
 */
@Entity(
    tableName = "operation_bancaire",
    foreignKeys = [ForeignKey(entity = CompteBancaireEntity::class, parentColumns = ["id"], childColumns = ["compte_id"])],
    indices = [Index("compte_id"), Index("document_id"), Index("date"), Index(value = ["numero"], unique = true)],
)
data class OperationBancaireEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** `OB-000001` */
    val numero: String,
    @ColumnInfo(name = "compte_id") val compteId: String,
    val type: TypeOperationBancaire,
    /** Toujours positif ; le sens est donné par le type */
    val montant: Long,
    val reference: String,
    val libelle: String,
    @ColumnInfo(name = "document_type") val documentType: String? = null,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)
