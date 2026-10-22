package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

enum class StatutCommande(val libelle: String) {
    BROUILLON("Brouillon"),
    ENVOYEE("Envoyée"),
    PARTIELLE("Reçue en partie"),
    RECUE("Reçue"),
    ANNULEE("Annulée"),
}

enum class StatutTransfert(val libelle: String) {
    EN_TRANSIT("En transit"),
    RECU("Reçu"),
    ANNULE("Annulé"),
}

@Entity(tableName = "fournisseur")
data class FournisseurEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val telephone: String? = null,
    val adresse: String? = null,
    val nif: String? = null,
    /** Conditions de paiement, en jours (0 = comptant) */
    @ColumnInfo(name = "delai_paiement_jours") val delaiPaiementJours: Int = 0,
    /** Numéro de compte Tsenako du fournisseur, destinataire de nos virements (RG-B6) */
    @ColumnInfo(name = "id_tsenako") val idTsenako: String? = null,
    val actif: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/**
 * Unité d'achat d'un produit : 1 unité source = facteur_milli millièmes de l'unité du produit
 * (ex. 1 carton = 24 pièces → 24000). Utilisée à la saisie des commandes et réceptions ;
 * les lignes sont toujours enregistrées dans l'unité du produit.
 */
@Entity(
    tableName = "conversion_unite",
    foreignKeys = [
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
        ForeignKey(entity = UniteEntity::class, parentColumns = ["id"], childColumns = ["unite_source_id"]),
    ],
    indices = [Index("produit_id"), Index("unite_source_id")],
)
data class ConversionUniteEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "unite_source_id") val uniteSourceId: String,
    @ColumnInfo(name = "facteur_milli") val facteurMilli: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "commande_fournisseur",
    foreignKeys = [
        ForeignKey(entity = FournisseurEntity::class, parentColumns = ["id"], childColumns = ["fournisseur_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"]),
    ],
    indices = [Index("fournisseur_id"), Index("depot_id"), Index(value = ["numero"], unique = true)],
)
data class CommandeFournisseurEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** CF-000001 */
    val numero: String,
    @ColumnInfo(name = "fournisseur_id") val fournisseurId: String,
    /** Dépôt de livraison */
    @ColumnInfo(name = "depot_id") val depotId: String,
    val date: Long = maintenant(),
    val statut: StatutCommande = StatutCommande.BROUILLON,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/** Quantités dans l'unité du produit ; montant = prix convenu de la ligne, prix_unitaire en dérive. */
@Entity(
    tableName = "ligne_commande",
    foreignKeys = [
        ForeignKey(entity = CommandeFournisseurEntity::class, parentColumns = ["id"], childColumns = ["commande_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
    ],
    indices = [Index(value = ["commande_id", "produit_id"], unique = true), Index("produit_id")],
)
data class LigneCommandeEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "commande_id") val commandeId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    @ColumnInfo(name = "prix_unitaire") val prixUnitaire: Long,
    val montant: Long,
    /** Cumul des réceptions (RG-A2) */
    @ColumnInfo(name = "quantite_recue_milli") val quantiteRecueMilli: Long = 0,
)

/** Réception validée (RG-A4 : jamais modifiée). Payée plus tard (S5) : montant_total = dette fournisseur (RG-A3). */
@Entity(
    tableName = "reception",
    foreignKeys = [
        ForeignKey(entity = CommandeFournisseurEntity::class, parentColumns = ["id"], childColumns = ["commande_id"]),
        ForeignKey(entity = FournisseurEntity::class, parentColumns = ["id"], childColumns = ["fournisseur_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"]),
    ],
    indices = [Index("commande_id"), Index("fournisseur_id"), Index("depot_id"), Index("lot_id"), Index(value = ["numero"], unique = true)],
)
data class ReceptionEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** BR-000001 */
    val numero: String,
    @ColumnInfo(name = "commande_id") val commandeId: String? = null,
    @ColumnInfo(name = "fournisseur_id") val fournisseurId: String,
    @ColumnInfo(name = "depot_id") val depotId: String,
    val date: Long = maintenant(),
    @ColumnInfo(name = "montant_total") val montantTotal: Long,
    /** Appro dont cet achat fait partie (lot_appro) */
    @ColumnInfo(name = "lot_id") val lotId: String? = null,
    /** Référence de la facture ou du bon de livraison du fournisseur */
    val reference: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "ligne_reception",
    foreignKeys = [
        ForeignKey(entity = ReceptionEntity::class, parentColumns = ["id"], childColumns = ["reception_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
        ForeignKey(entity = LigneCommandeEntity::class, parentColumns = ["id"], childColumns = ["ligne_commande_id"]),
    ],
    indices = [Index("reception_id"), Index("produit_id"), Index("ligne_commande_id")],
)
data class LigneReceptionEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "reception_id") val receptionId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "ligne_commande_id") val ligneCommandeId: String? = null,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    /** Coût d'entrée en stock (montant / quantité, arrondi) */
    @ColumnInfo(name = "prix_unitaire") val prixUnitaire: Long,
    val montant: Long,
    /** Cumul des retours au fournisseur */
    @ColumnInfo(name = "quantite_retournee_milli") val quantiteRetourneeMilli: Long = 0,
)

/** RG-T2 : créé à l'envoi (sortie du dépôt source), reçu ou annulé ensuite. */
@Entity(
    tableName = "transfert",
    foreignKeys = [
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_source_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_destination_id"]),
    ],
    indices = [Index("depot_source_id"), Index("depot_destination_id"), Index(value = ["numero"], unique = true)],
)
data class TransfertEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** TR-000001 */
    val numero: String,
    @ColumnInfo(name = "depot_source_id") val depotSourceId: String,
    @ColumnInfo(name = "depot_destination_id") val depotDestinationId: String,
    val date: Long = maintenant(),
    val statut: StatutTransfert = StatutTransfert.EN_TRANSIT,
    @ColumnInfo(name = "date_reception") val dateReception: Long? = null,
    @ColumnInfo(name = "recu_par") val recuPar: String? = null,
    /** Justification des écarts à la réception (RG-T3) */
    @ColumnInfo(name = "motif_ecart") val motifEcart: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/** cout_unitaire : CMUP du dépôt source à l'envoi (RG-T4). */
@Entity(
    tableName = "ligne_transfert",
    foreignKeys = [
        ForeignKey(entity = TransfertEntity::class, parentColumns = ["id"], childColumns = ["transfert_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
    ],
    indices = [Index(value = ["transfert_id", "produit_id"], unique = true), Index("produit_id")],
)
data class LigneTransfertEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "transfert_id") val transfertId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "quantite_envoyee_milli") val quantiteEnvoyeeMilli: Long,
    @ColumnInfo(name = "quantite_recue_milli") val quantiteRecueMilli: Long? = null,
    @ColumnInfo(name = "cout_unitaire") val coutUnitaire: Long,
)

// --- Résultats de requêtes ---

/** RG-A3 : solde dû = réceptions − retours au fournisseur − paiements. */
data class FournisseurAvecSolde(
    @Embedded val fournisseur: FournisseurEntity,
    @ColumnInfo(name = "total_achats") val totalAchats: Long,
    @ColumnInfo(name = "nb_receptions") val nbReceptions: Int,
    @ColumnInfo(name = "total_retours") val totalRetours: Long,
    @ColumnInfo(name = "total_paye") val totalPaye: Long,
) {
    val solde: Long get() = totalAchats - totalRetours - totalPaye
}

data class ConversionAvecUnite(
    @Embedded val conversion: ConversionUniteEntity,
    @ColumnInfo(name = "produit_code") val produitCode: String,
    @ColumnInfo(name = "produit_nom") val produitNom: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
    /** Unité de stock du produit */
    @ColumnInfo(name = "unite_produit_symbole") val uniteProduitSymbole: String,
)

data class CommandeAvecDetails(
    @Embedded val commande: CommandeFournisseurEntity,
    @ColumnInfo(name = "fournisseur_nom") val fournisseurNom: String,
    @ColumnInfo(name = "fournisseur_telephone") val fournisseurTelephone: String?,
    @ColumnInfo(name = "depot_nom") val depotNom: String,
    @ColumnInfo(name = "montant_total") val montantTotal: Long,
    @ColumnInfo(name = "nb_lignes") val nbLignes: Int,
)

/** Ligne de document (commande, réception, transfert) avec les informations du produit. */
data class LigneCommandeAvecProduit(
    @Embedded val ligne: LigneCommandeEntity,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
)

data class ReceptionAvecDetails(
    @Embedded val reception: ReceptionEntity,
    @ColumnInfo(name = "fournisseur_nom") val fournisseurNom: String,
    @ColumnInfo(name = "depot_nom") val depotNom: String,
    @ColumnInfo(name = "commande_numero") val commandeNumero: String?,
    @ColumnInfo(name = "auteur_nom") val auteurNom: String?,
)

data class LigneReceptionAvecProduit(
    @Embedded val ligne: LigneReceptionEntity,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
)

data class TransfertAvecDepots(
    @Embedded val transfert: TransfertEntity,
    @ColumnInfo(name = "source_nom") val sourceNom: String,
    @ColumnInfo(name = "destination_nom") val destinationNom: String,
    @ColumnInfo(name = "nb_lignes") val nbLignes: Int,
)

data class LigneTransfertAvecProduit(
    @Embedded val ligne: LigneTransfertEntity,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
)
