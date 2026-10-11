package mg.tsena.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

enum class StatutSession { OUVERTE, FERMEE }

enum class TypeOperationCaisse(val libelle: String, val entree: Boolean) {
    ENCAISSEMENT("Encaissement", true),
    DECAISSEMENT("Décaissement", false),
    APPORT("Apport", true),
    RETRAIT("Retrait", false),
}

enum class ModePaiement(val libelle: String) {
    ESPECES("Espèces"),
    MOBILE_MONEY("Mobile money"),
    CARTE("Carte"),
    CREDIT("Crédit"),
    /** Dépenses et paiements fournisseurs réglés hors caisse (banque, poche du propriétaire…) */
    BANQUE("Banque / hors caisse"),
}

enum class TypeVente(val libelle: String) { DETAIL("Détail"), GROS("Gros") }

enum class StatutVente { VALIDEE, ANNULEE }

enum class TypePaiement { COMPTANT, CREDIT, MIXTE }

/** Une caisse par point de vente, créée à la première ouverture. */
@Entity(
    tableName = "caisse",
    foreignKeys = [ForeignKey(entity = PointVenteEntity::class, parentColumns = ["id"], childColumns = ["point_vente_id"])],
    indices = [Index(value = ["point_vente_id"], unique = true)],
)
data class CaisseEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/**
 * Session de caisse (RG-C1 à RG-C4). Les soldes portent sur les espèces, seules comptées à la clôture ;
 * solde_theorique, solde_compte et ecart sont figés à la clôture.
 */
@Entity(
    tableName = "session_caisse",
    foreignKeys = [
        ForeignKey(entity = CaisseEntity::class, parentColumns = ["id"], childColumns = ["caisse_id"]),
        ForeignKey(entity = UtilisateurEntity::class, parentColumns = ["id"], childColumns = ["utilisateur_id"]),
    ],
    indices = [Index(value = ["caisse_id", "statut"]), Index("utilisateur_id")],
)
data class SessionCaisseEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "caisse_id") val caisseId: String,
    /** Utilisateur qui a ouvert la caisse */
    @ColumnInfo(name = "utilisateur_id") val utilisateurId: String,
    val ouverture: Long = maintenant(),
    val fermeture: Long? = null,
    @ColumnInfo(name = "ferme_par") val fermePar: String? = null,
    @ColumnInfo(name = "fond_initial") val fondInitial: Long,
    @ColumnInfo(name = "solde_theorique") val soldeTheorique: Long? = null,
    @ColumnInfo(name = "solde_compte") val soldeCompte: Long? = null,
    val ecart: Long? = null,
    val statut: StatutSession = StatutSession.OUVERTE,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
)

/** Mouvement d'argent dans une session (RG-C5 : un mode par opération). Jamais modifié. */
@Entity(
    tableName = "operation_caisse",
    foreignKeys = [ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_id"])],
    indices = [Index("session_id"), Index("document_id")],
)
data class OperationCaisseEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "session_id") val sessionId: String,
    val type: TypeOperationCaisse,
    val mode: ModePaiement,
    /** Toujours positif ; le sens est donné par le type */
    val montant: Long,
    @ColumnInfo(name = "document_type") val documentType: String? = null,
    @ColumnInfo(name = "document_id") val documentId: String? = null,
    val libelle: String,
    val date: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** RG-V7 / RG-V8 : uniquement nom et téléphone normalisé (+261…), unique. */
@Entity(tableName = "client", indices = [Index(value = ["telephone"], unique = true)])
data class ClientEntity(
    @PrimaryKey val id: String = nouvelId(),
    val nom: String,
    val telephone: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/** Surcharge des prix d'un produit dans un point de vente (RG-V1) ; null = prix du produit. */
@Entity(
    tableName = "prix_point_vente",
    foreignKeys = [
        ForeignKey(entity = PointVenteEntity::class, parentColumns = ["id"], childColumns = ["point_vente_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
    ],
    indices = [Index(value = ["point_vente_id", "produit_id"], unique = true), Index("produit_id")],
)
data class PrixPointVenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "prix_detail") val prixDetail: Long? = null,
    @ColumnInfo(name = "prix_gros") val prixGros: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** Vente validée (RG-X4 : numéro sans trou). Jamais supprimée : annulée (RG-V6) ou retournée en partie. */
@Entity(
    tableName = "vente",
    foreignKeys = [
        ForeignKey(entity = PointVenteEntity::class, parentColumns = ["id"], childColumns = ["point_vente_id"]),
        ForeignKey(entity = DepotEntity::class, parentColumns = ["id"], childColumns = ["depot_id"]),
        ForeignKey(entity = ClientEntity::class, parentColumns = ["id"], childColumns = ["client_id"]),
        ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_caisse_id"]),
    ],
    indices = [
        Index(value = ["numero"], unique = true),
        Index(value = ["point_vente_id", "date"]),
        Index("depot_id"),
        Index("client_id"),
        Index("session_caisse_id"),
    ],
)
data class VenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** V-000001 */
    val numero: String,
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String,
    /** Dépôt d'où le stock est sorti (celui du point de vente au moment de la vente) */
    @ColumnInfo(name = "depot_id") val depotId: String,
    @ColumnInfo(name = "type_vente") val typeVente: TypeVente,
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    @ColumnInfo(name = "session_caisse_id") val sessionCaisseId: String,
    val date: Long = maintenant(),
    @ColumnInfo(name = "total_brut") val totalBrut: Long,
    val remise: Long = 0,
    @ColumnInfo(name = "total_net") val totalNet: Long,
    /** Utilisateur ayant autorisé une remise au-delà du seuil (RG-V5) */
    @ColumnInfo(name = "remise_autorisee_par") val remiseAutoriseePar: String? = null,
    val statut: StatutVente = StatutVente.VALIDEE,
    @ColumnInfo(name = "type_paiement") val typePaiement: TypePaiement,
    @ColumnInfo(name = "date_annulation") val dateAnnulation: Long? = null,
    @ColumnInfo(name = "annulee_par") val annuleePar: String? = null,
    @ColumnInfo(name = "motif_annulation") val motifAnnulation: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = createdAt,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

/** cout_unitaire : CMUP figé au moment de la vente (RG-V3). */
@Entity(
    tableName = "ligne_vente",
    foreignKeys = [
        ForeignKey(entity = VenteEntity::class, parentColumns = ["id"], childColumns = ["vente_id"]),
        ForeignKey(entity = ProduitEntity::class, parentColumns = ["id"], childColumns = ["produit_id"]),
    ],
    indices = [Index("vente_id"), Index("produit_id")],
)
data class LigneVenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "vente_id") val venteId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    @ColumnInfo(name = "prix_unitaire") val prixUnitaire: Long,
    val montant: Long,
    @ColumnInfo(name = "cout_unitaire") val coutUnitaire: Long,
    @ColumnInfo(name = "quantite_retournee_milli") val quantiteRetourneeMilli: Long = 0,
)

@Entity(
    tableName = "paiement_vente",
    foreignKeys = [ForeignKey(entity = VenteEntity::class, parentColumns = ["id"], childColumns = ["vente_id"])],
    indices = [Index("vente_id")],
)
data class PaiementVenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "vente_id") val venteId: String,
    val mode: ModePaiement,
    val montant: Long,
    /** Référence de transaction mobile money, etc. */
    val reference: String? = null,
)

/** Retour client : remboursement en espèces / mobile money, ou déduit du crédit du client. */
@Entity(
    tableName = "retour_vente",
    foreignKeys = [
        ForeignKey(entity = VenteEntity::class, parentColumns = ["id"], childColumns = ["vente_id"]),
        ForeignKey(entity = SessionCaisseEntity::class, parentColumns = ["id"], childColumns = ["session_caisse_id"]),
    ],
    indices = [Index("vente_id"), Index("session_caisse_id"), Index(value = ["numero"], unique = true)],
)
data class RetourVenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    /** RV-000001 */
    val numero: String,
    @ColumnInfo(name = "vente_id") val venteId: String,
    val date: Long = maintenant(),
    val motif: String,
    val montant: Long,
    @ColumnInfo(name = "mode_remboursement") val modeRemboursement: ModePaiement,
    @ColumnInfo(name = "session_caisse_id") val sessionCaisseId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = maintenant(),
    @ColumnInfo(name = "created_by") val createdBy: String? = null,
)

@Entity(
    tableName = "ligne_retour_vente",
    foreignKeys = [
        ForeignKey(entity = RetourVenteEntity::class, parentColumns = ["id"], childColumns = ["retour_id"]),
        ForeignKey(entity = LigneVenteEntity::class, parentColumns = ["id"], childColumns = ["ligne_vente_id"]),
    ],
    indices = [Index("retour_id"), Index("ligne_vente_id")],
)
data class LigneRetourVenteEntity(
    @PrimaryKey val id: String = nouvelId(),
    @ColumnInfo(name = "retour_id") val retourId: String,
    @ColumnInfo(name = "ligne_vente_id") val ligneVenteId: String,
    @ColumnInfo(name = "produit_id") val produitId: String,
    @ColumnInfo(name = "quantite_milli") val quantiteMilli: Long,
    val montant: Long,
)

// --- Résultats de requêtes ---

data class SessionAvecDetails(
    @Embedded val session: SessionCaisseEntity,
    @ColumnInfo(name = "caisse_nom") val caisseNom: String,
    @ColumnInfo(name = "point_vente_id") val pointVenteId: String,
    @ColumnInfo(name = "ouvert_par_nom") val ouvertParNom: String,
)

data class ResumeVentes(
    val nb: Int,
    val total: Long,
    /** Part de ces ventes payée à crédit */
    val credit: Long = 0,
)

data class ClientAvecCredit(
    @Embedded val client: ClientEntity,
    /** Ventes à crédit − retours déduits du crédit − remboursements du client */
    @ColumnInfo(name = "credit_du") val creditDu: Long,
)

/** Produit vendable dans un point de vente, avec ses prix (RG-V1) et le stock du dépôt de vente. */
data class ProduitVente(
    @ColumnInfo(name = "produit_id") val produitId: String,
    val code: String,
    @ColumnInfo(name = "code_barre") val codeBarre: String?,
    val nom: String,
    @ColumnInfo(name = "type_produit_id") val typeProduitId: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
    @ColumnInfo(name = "prix_detail") val prixDetail: Long,
    @ColumnInfo(name = "prix_gros") val prixGros: Long?,
    @ColumnInfo(name = "stock_milli") val stockMilli: Long,
)

/** Prix d'un produit et surcharges éventuelles dans un point de vente. */
data class PrixProduitPointVente(
    @ColumnInfo(name = "produit_id") val produitId: String,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "prix_vente") val prixVente: Long,
    @ColumnInfo(name = "prix_vente_gros") val prixVenteGros: Long?,
    @ColumnInfo(name = "prix_detail") val prixDetail: Long?,
    @ColumnInfo(name = "prix_gros") val prixGros: Long?,
)

data class VenteAvecDetails(
    @Embedded val vente: VenteEntity,
    @ColumnInfo(name = "point_vente_nom") val pointVenteNom: String,
    @ColumnInfo(name = "client_nom") val clientNom: String?,
    @ColumnInfo(name = "client_telephone") val clientTelephone: String?,
    @ColumnInfo(name = "vendeur_nom") val vendeurNom: String?,
    @ColumnInfo(name = "session_statut") val sessionStatut: StatutSession,
)

data class LigneVenteAvecProduit(
    @Embedded val ligne: LigneVenteEntity,
    val code: String,
    val nom: String,
    @ColumnInfo(name = "photo_chemin") val photoChemin: String,
    @ColumnInfo(name = "unite_symbole") val uniteSymbole: String,
    @ColumnInfo(name = "unite_decimale") val uniteDecimale: Boolean,
)
