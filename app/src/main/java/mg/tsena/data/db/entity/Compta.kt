package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

enum class StatutExercice { OUVERT, CLOTURE }

enum class TypeCompte(val libelle: String) {
    ACTIF("Actif"),
    PASSIF("Passif"),
    CAPITAUX("Capitaux"),
    CHARGE("Charge"),
    PRODUIT("Produit"),
}

/** Journaux comptables (liste fixe). */
enum class Journal(val libelle: String) {
    VT("Ventes"),
    AC("Achats"),
    CA("Caisse"),
    BQ("Banque"),
    OD("Opérations diverses"),
}

/** Exercice comptable (RG-K5 : aucune écriture sur un exercice clôturé). Dates au format AAAA-MM-JJ. */
@Entity(tableName = "exercice", indices = [Index(value = ["date_debut"], unique = true)])
data class ExerciceEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "date_debut") val dateDebut: String,
    @ColumnInfo(name = "date_fin") val dateFin: String,
    val statut: StatutExercice = StatutExercice.OUVERT,
    @ColumnInfo(name = "date_cloture") val dateCloture: Long? = null,
    @ColumnInfo(name = "cloture_par") val cloturePar: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
)

/**
 * Compte du plan comptable PCG 2005 (RG-K3). Les comptes de base (de_base) sont pré-chargés et non modifiables ;
 * le marchand ajoute des sous-comptes dont le numéro commence par celui d'un compte existant.
 */
@Entity(tableName = "compte", indices = [Index(value = ["numero"], unique = true)])
data class CompteEntity(
    @PrimaryKey val id: String = nouvelId(),
    val numero: String,
    val libelle: String,
    val classe: Int,
    val type: TypeCompte,
    @ColumnInfo(name = "de_base") val deBase: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/**
 * Écriture en partie double (RG-K1), générée automatiquement depuis un document validé (RG-K2) et jamais modifiée
 * (RG-K4) : une erreur se corrige par contre-passation (contrepassation_de).
 */
@Entity(
    tableName = "ecriture",
    foreignKeys = [ForeignKey(entity = ExerciceEntity::class, parentColumns = ["id"], childColumns = ["exercice_id"])],
    indices = [
        Index(value = ["numero"], unique = true),
        Index("exercice_id"),
        Index(value = ["journal", "date"]),
        Index("date"),
        Index(value = ["document_type", "document_id"]),
    ],
)
data class EcritureEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** Numéro séquentiel sans trou (RG-X4) */
    val numero: Int,
    val journal: Journal,
    @ColumnInfo(name = "exercice_id") val exerciceId: String,
    val date: Long = maintenant(),
    val libelle: String,
    @ColumnInfo(name = "document_type") val documentType: String? = null,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    /** Écriture annulée par celle-ci */
    @ColumnInfo(name = "contrepassation_de") val contrepassationDe: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** Montants en Ariary (RG-K8) ; une ligne porte soit un débit, soit un crédit. */
@Entity(
    tableName = "ligne_ecriture",
    foreignKeys = [
        ForeignKey(entity = EcritureEntity::class, parentColumns = ["id"], childColumns = ["ecriture_id"]),
        ForeignKey(entity = CompteEntity::class, parentColumns = ["numero"], childColumns = ["compte_numero"]),
    ],
    indices = [Index("ecriture_id"), Index("compte_numero"), Index(value = ["tiers_type", "tiers_id"])],
)
data class LigneEcritureEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "ecriture_id") val ecritureId: String,
    @ColumnInfo(name = "compte_numero") val compteNumero: String,
    val debit: Long = 0,
    val credit: Long = 0,
    /** CLIENT ou FOURNISSEUR, pour les comptes 411 / 401 */
    @ColumnInfo(name = "tiers_type") val tiersType: String? = null,
    @ColumnInfo(name = "tiers_id") val tiersId: String? = null,
)

// --- Résultats de requêtes ---

data class LigneEcritureAvecCompte(
    @Embedded val ligne: LigneEcritureEntity,
    @ColumnInfo(name = "compte_libelle") val compteLibelle: String,
)

/** Ligne du grand livre d'un compte. */
data class MouvementCompte(
    @ColumnInfo(name = "ecriture_id") val ecritureId: String,
    val numero: Int,
    val journal: Journal,
    val date: Long,
    val libelle: String,
    val debit: Long,
    val credit: Long,
)

data class CompteAvecSolde(
    @Embedded val compte: CompteEntity,
    @ColumnInfo(name = "total_debit") val totalDebit: Long,
    @ColumnInfo(name = "total_credit") val totalCredit: Long,
) {
    /** Débit − crédit */
    val solde: Long get() = totalDebit - totalCredit
}
