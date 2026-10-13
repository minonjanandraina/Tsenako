package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

enum class StatutDepense { VALIDEE, ANNULEE }

/** RG-D1 : chaque catégorie est liée à un compte de charge (classe 6), à valider par un comptable avant S6. */
@Entity(tableName = "categorie_depense")
data class CategorieDepenseEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    /** Numéro du compte de charge PCG 2005 (ex. 613 Locations) ; la table des comptes arrivera au sprint S6 */
    @ColumnInfo(name = "compte_numero") val compteNumero: String,
    val actif: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/**
 * Dépense (RG-D1 à RG-D3). Payée en espèces ou mobile money : décaissement dans la caisse ouverte du point de vente
 * (session_caisse_id) ; payée par banque / hors caisse : aucune opération de caisse.
 */
@Entity(
    tableName = "depense",
    foreignKeys = [
        ForeignKey(entity = CategorieDepenseEntity::class, parentColumns = ["id"], childColumns = ["categorie_id"]),
        ForeignKey(entity = PointVenteEntity::class, parentColumns = ["id"], childColumns = ["point_vente_id"]),
        ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_caisse_id"]),
    ],
    indices = [Index("categorie_id"), Index("point_vente_id"), Index("session_caisse_id"), Index("date"), Index(value = ["numero"], unique = true)],
)
data class DepenseEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** DP-000001 */
    val numero: String,
    @ColumnInfo(name = "categorie_id") val categorieId: String,
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String? = null,
    val montant: Long,
    val date: Long = maintenant(),
    @ColumnInfo(name = "mode_paiement") val modePaiement: ModePaiement,
    @ColumnInfo(name = "session_caisse_id") val sessionCaisseId: String? = null,
    /** Caisse ou coffre ; sans effet pour un règlement hors caisse (banque) */
    val source: SourceFonds = SourceFonds.CAISSE,
    /** Photo du justificatif, relative au dossier photos */
    @ColumnInfo(name = "justificatif_chemin") val justificatifChemin: String? = null,
    val description: String,
    val statut: StatutDepense = StatutDepense.VALIDEE,
    @ColumnInfo(name = "date_annulation") val dateAnnulation: Long? = null,
    @ColumnInfo(name = "annulee_par") val annuleePar: String? = null,
    @ColumnInfo(name = "motif_annulation") val motifAnnulation: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** Paiement d'un fournisseur, rattaché à une réception ou en acompte général (RG-A3). */
@Entity(
    tableName = "paiement_fournisseur",
    foreignKeys = [
        ForeignKey(entity = FournisseurEntity::class, parentColumns = ["id"], childColumns = ["fournisseur_id"]),
        ForeignKey(entity = ReceptionEntity::class, parentColumns = ["id"], childColumns = ["reception_id"]),
        ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_caisse_id"]),
    ],
    indices = [Index("fournisseur_id"), Index("reception_id"), Index("session_caisse_id"), Index(value = ["numero"], unique = true)],
)
data class PaiementFournisseurEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** PF-000001 */
    val numero: String,
    @ColumnInfo(name = "fournisseur_id") val fournisseurId: String,
    @ColumnInfo(name = "reception_id") val receptionId: String? = null,
    val montant: Long,
    @ColumnInfo(name = "mode_paiement") val modePaiement: ModePaiement,
    @ColumnInfo(name = "session_caisse_id") val sessionCaisseId: String? = null,
    val reference: String? = null,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** Remboursement par un client de ses achats à crédit. */
@Entity(
    tableName = "encaissement_client",
    foreignKeys = [
        ForeignKey(entity = ClientEntity::class, parentColumns = ["id"], childColumns = ["client_id"]),
        ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_caisse_id"]),
    ],
    indices = [Index("client_id"), Index("session_caisse_id"), Index(value = ["numero"], unique = true)],
)
data class EncaissementClientEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** EC-000001 */
    val numero: String,
    @ColumnInfo(name = "client_id") val clientId: String,
    val montant: Long,
    @ColumnInfo(name = "mode_paiement") val modePaiement: ModePaiement,
    @ColumnInfo(name = "session_caisse_id") val sessionCaisseId: String? = null,
    val reference: String? = null,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** Retour de marchandise au fournisseur (RG-A4) : sortie de stock, la dette diminue du prix d'achat. */
@Entity(
    tableName = "retour_fournisseur",
    foreignKeys = [
        ForeignKey(entity = ReceptionEntity::class, parentColumns = ["id"], childColumns = ["reception_id"]),
        ForeignKey(entity = FournisseurEntity::class, parentColumns = ["id"], childColumns = ["fournisseur_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"]),
    ],
    indices = [Index("reception_id"), Index("fournisseur_id"), Index("depot_id"), Index(value = ["numero"], unique = true)],
)
data class RetourFournisseurEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** RF-000001 */
    val numero: String,
    @ColumnInfo(name = "reception_id") val receptionId: String,
    @ColumnInfo(name = "fournisseur_id") val fournisseurId: String,
    @ColumnInfo(name = "depot_id") val depotId: String,
    val date: Long = maintenant(),
    val motif: String,
    val montant: Long,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

@Entity(
    tableName = "ligne_retour_fournisseur",
    foreignKeys = [
        ForeignKey(entity = RetourFournisseurEntity::class, parentColumns = ["id"], childColumns = ["retour_id"]),
        ForeignKey(entity = LigneReceptionEntity::class, parentColumns = ["id"], childColumns = ["ligne_reception_id"]),
    ],
    indices = [Index("retour_id"), Index("ligne_reception_id")],
)
data class LigneRetourFournisseurEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "retour_id") val retourId: String,
    @ColumnInfo(name = "ligne_reception_id") val ligneReceptionId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    @ColumnInfo(name = "prix_unitaire") val prixUnitaire: Long,
    val montant: Long,
)

// --- Résultats de requêtes ---

data class DepenseAvecDetails(
    @Embedded val depense: DepenseEntity,
    @ColumnInfo(name = "categorie_nom") val categorieNom: String,
    @ColumnInfo(name = "compte_numero") val compteNumero: String,
    @ColumnInfo(name = "point_vente_nom") val pointVenteNom: String?,
    @ColumnInfo(name = "auteur_nom") val auteurNom: String?,
)

data class TotalCategorie(
    @ColumnInfo(name = "categorie_nom") val categorieNom: String,
    val total: Long,
)

/** Réception avec ce qu'il reste à payer : montant − retours − paiements rattachés. */
data class ReceptionAPayer(
    @Embedded val reception: ReceptionEntity,
    @ColumnInfo(name = "total_retours") val totalRetours: Long,
    @ColumnInfo(name = "total_paye") val totalPaye: Long,
) {
    val reste: Long get() = reception.montantTotal - totalRetours - totalPaye
}

data class LigneReceptionRetournable(
    @Embedded val ligne: LigneReceptionEntity,
    val nom: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
)
