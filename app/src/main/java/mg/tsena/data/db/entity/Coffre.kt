package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

/** D'où sort l'argent d'une dépense ou d'un paiement : de la caisse du point de vente ou du coffre. */
enum class SourceFonds(val libelle: String) { CAISSE("Caisse"), COFFRE("Coffre") }

enum class TypeOperationCoffre(val libelle: String, val entree: Boolean) {
    /** Clôture de caisse : les espèces comptées et le mobile money du jour entrent au coffre */
    VERSEMENT_CAISSE("Versement de la caisse", true),
    /** Ouverture de caisse : le fond de caisse sort du coffre */
    FOND_CAISSE("Fond de caisse", false),
    APPORT("Apport", true),
    RETRAIT("Retrait", false),
    /** Dépense ou paiement fournisseur payé depuis le coffre */
    DECAISSEMENT("Décaissement", false),
    /** Annulation d'une dépense payée depuis le coffre */
    REMBOURSEMENT("Remboursement", true),
}

/**
 * Mouvement d'argent du coffre (là où va l'argent après la clôture de caisse). Jamais modifié.
 * Le solde d'un mode (espèces, mobile money) = somme des entrées − somme des sorties.
 */
@Entity(
    tableName = "operation_coffre",
    foreignKeys = [ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_caisse_id"])],
    indices = [Index("session_caisse_id"), Index("document_id"), Index("date")],
)
data class OperationCoffreEntity(
    @PrimaryKey val id: String = nouvelId(),
    val type: TypeOperationCoffre,
    /** ESPECES ou MOBILE_MONEY */
    val mode: ModePaiement,
    /** Toujours positif ; le sens est donné par le type */
    val montant: Long,
    @ColumnInfo(name = "session_caisse_id") val sessionCaisseId: String? = null,
    @ColumnInfo(name = "document_type") val documentType: String? = null,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    val libelle: String,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** Solde du coffre pour un mode de paiement. */
data class SoldeCoffre(val mode: ModePaiement, val solde: Long)
