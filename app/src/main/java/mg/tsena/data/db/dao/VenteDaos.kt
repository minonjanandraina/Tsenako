package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.CaisseEntity
import mg.tsena.data.db.entity.ClientAvecCredit
import mg.tsena.data.db.entity.ClientEntity
import mg.tsena.data.db.entity.LigneRetourVenteEntity
import mg.tsena.data.db.entity.LigneVenteAvecProduit
import mg.tsena.data.db.entity.LigneVenteEntity
import mg.tsena.data.db.entity.OperationCaisseEntity
import mg.tsena.data.db.entity.PaiementVenteEntity
import mg.tsena.data.db.entity.PrixPointVenteEntity
import mg.tsena.data.db.entity.PrixProduitPointVente
import mg.tsena.data.db.entity.ProduitVente
import mg.tsena.data.db.entity.ResumeVentes
import mg.tsena.data.db.entity.RetourVenteEntity
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.SessionCaisseEntity
import mg.tsena.data.db.entity.VenteAvecDetails
import mg.tsena.data.db.entity.VenteEntity

@Dao
interface CaisseDao {
    @Query("SELECT * FROM caisse WHERE point_vente_id = :pointVenteId AND deleted_at IS NULL")
    suspend fun getParPointVente(pointVenteId: String): CaisseEntity?

    @Insert
    suspend fun inserer(caisse: CaisseEntity)

    @Query(
        """
        SELECT s.*, c.nom AS caisse_nom, c.point_vente_id, u.nom AS ouvert_par_nom
        FROM session_caisse s
        JOIN caisse c ON c.id = s.caisse_id
        JOIN utilisateur u ON u.id = s.utilisateur_id
        WHERE c.point_vente_id = :pointVenteId AND s.statut = 'OUVERTE'
        LIMIT 1
        """
    )
    fun observerSessionOuverte(pointVenteId: String): Flow<SessionAvecDetails?>

    @Query(
        """
        SELECT s.*, c.nom AS caisse_nom, c.point_vente_id, u.nom AS ouvert_par_nom
        FROM session_caisse s
        JOIN caisse c ON c.id = s.caisse_id
        JOIN utilisateur u ON u.id = s.utilisateur_id
        WHERE c.point_vente_id = :pointVenteId AND s.statut = 'FERMEE'
        ORDER BY s.ouverture DESC
        LIMIT :limite
        """
    )
    fun observerSessionsFermees(pointVenteId: String, limite: Int): Flow<List<SessionAvecDetails>>

    @Query("SELECT * FROM session_caisse WHERE caisse_id = :caisseId AND statut = 'OUVERTE' LIMIT 1")
    suspend fun getSessionOuverte(caisseId: String): SessionCaisseEntity?

    @Query("SELECT * FROM session_caisse WHERE id = :id")
    suspend fun getSession(id: String): SessionCaisseEntity?

    @Insert
    suspend fun insererSession(session: SessionCaisseEntity)

    @Update
    suspend fun modifierSession(session: SessionCaisseEntity)

    @Insert
    suspend fun insererOperation(operation: OperationCaisseEntity)

    @Query("SELECT * FROM operation_caisse WHERE session_id = :sessionId ORDER BY date DESC")
    fun observerOperations(sessionId: String): Flow<List<OperationCaisseEntity>>

    @Query("SELECT * FROM operation_caisse WHERE session_id = :sessionId")
    suspend fun operations(sessionId: String): List<OperationCaisseEntity>
}

@Dao
interface ClientDao {
    /** Crédit dû = ventes à crédit non annulées − retours déduits du crédit. */
    @Query(
        """
        SELECT c.*,
               COALESCE((SELECT SUM(p.montant) FROM paiement_vente p JOIN vente v ON v.id = p.vente_id
                         WHERE v.client_id = c.id AND v.statut = 'VALIDEE' AND p.mode = 'CREDIT'), 0)
             - COALESCE((SELECT SUM(r.montant) FROM retour_vente r JOIN vente v ON v.id = r.vente_id
                         WHERE v.client_id = c.id AND r.mode_remboursement = 'CREDIT'), 0) AS credit_du
        FROM client c
        WHERE c.deleted_at IS NULL
        ORDER BY c.nom COLLATE NOCASE
        """
    )
    fun observerAvecCredit(): Flow<List<ClientAvecCredit>>

    @Query("SELECT * FROM client WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): ClientEntity?

    @Query("SELECT * FROM client WHERE telephone = :telephone AND id != :exclureId")
    suspend fun getParTelephone(telephone: String, exclureId: String): ClientEntity?

    @Insert
    suspend fun inserer(client: ClientEntity)

    @Update
    suspend fun modifier(client: ClientEntity)
}

@Dao
interface PrixPointVenteDao {
    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.nom, p.photo_chemin, p.prix_vente, p.prix_vente_gros,
               pp.prix_detail, pp.prix_gros
        FROM produit p
        LEFT JOIN prix_point_vente pp ON pp.produit_id = p.id AND pp.point_vente_id = :pointVenteId
        WHERE p.deleted_at IS NULL AND p.actif = 1
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observer(pointVenteId: String): Flow<List<PrixProduitPointVente>>

    @Query("SELECT * FROM prix_point_vente WHERE point_vente_id = :pointVenteId AND produit_id = :produitId")
    suspend fun get(pointVenteId: String, produitId: String): PrixPointVenteEntity?

    @Insert
    suspend fun inserer(prix: PrixPointVenteEntity)

    @Update
    suspend fun modifier(prix: PrixPointVenteEntity)
}

@Dao
interface VenteDao {
    /** RG-V1 : prix du point de vente s'il existe, sinon prix du produit. Stock du dépôt de vente. */
    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.code_barre, p.nom, p.photo_chemin,
               u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               COALESCE(pp.prix_detail, p.prix_vente) AS prix_detail,
               COALESCE(pp.prix_gros, p.prix_vente_gros) AS prix_gros,
               COALESCE(s.quantite_milli, 0) AS stock_milli
        FROM produit p
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN prix_point_vente pp ON pp.produit_id = p.id AND pp.point_vente_id = :pointVenteId
        LEFT JOIN stock s ON s.produit_id = p.id AND s.depot_id = :depotId
        WHERE p.deleted_at IS NULL AND p.actif = 1
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerCatalogue(pointVenteId: String, depotId: String): Flow<List<ProduitVente>>

    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.code_barre, p.nom, p.photo_chemin,
               u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               COALESCE(pp.prix_detail, p.prix_vente) AS prix_detail,
               COALESCE(pp.prix_gros, p.prix_vente_gros) AS prix_gros,
               COALESCE(s.quantite_milli, 0) AS stock_milli
        FROM produit p
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN prix_point_vente pp ON pp.produit_id = p.id AND pp.point_vente_id = :pointVenteId
        LEFT JOIN stock s ON s.produit_id = p.id AND s.depot_id = :depotId
        WHERE p.id = :produitId AND p.deleted_at IS NULL AND p.actif = 1
        """
    )
    suspend fun produitVendable(pointVenteId: String, depotId: String, produitId: String): ProduitVente?

    @Query(
        """
        SELECT v.*, pv.nom AS point_vente_nom, c.nom AS client_nom, c.telephone AS client_telephone,
               u.nom AS vendeur_nom, s.statut AS session_statut
        FROM vente v
        JOIN point_vente pv ON pv.id = v.point_vente_id
        JOIN session_caisse s ON s.id = v.session_caisse_id
        LEFT JOIN client c ON c.id = v.client_id
        LEFT JOIN utilisateur u ON u.id = v.created_by
        WHERE (:pointVenteId IS NULL OR v.point_vente_id = :pointVenteId)
        ORDER BY v.date DESC
        LIMIT :limite
        """
    )
    fun observerVentes(pointVenteId: String?, limite: Int): Flow<List<VenteAvecDetails>>

    @Query(
        """
        SELECT v.*, pv.nom AS point_vente_nom, c.nom AS client_nom, c.telephone AS client_telephone,
               u.nom AS vendeur_nom, s.statut AS session_statut
        FROM vente v
        JOIN point_vente pv ON pv.id = v.point_vente_id
        JOIN session_caisse s ON s.id = v.session_caisse_id
        LEFT JOIN client c ON c.id = v.client_id
        LEFT JOIN utilisateur u ON u.id = v.created_by
        WHERE v.id = :id
        """
    )
    fun observer(id: String): Flow<VenteAvecDetails?>

    @Query(
        """
        SELECT l.*, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale
        FROM ligne_vente l
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE l.vente_id = :venteId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerLignes(venteId: String): Flow<List<LigneVenteAvecProduit>>

    @Query("SELECT * FROM paiement_vente WHERE vente_id = :venteId")
    fun observerPaiements(venteId: String): Flow<List<PaiementVenteEntity>>

    @Query("SELECT * FROM retour_vente WHERE vente_id = :venteId ORDER BY date DESC")
    fun observerRetours(venteId: String): Flow<List<RetourVenteEntity>>

    @Query("SELECT COUNT(*) AS nb, COALESCE(SUM(total_net), 0) AS total FROM vente WHERE session_caisse_id = :sessionId AND statut = 'VALIDEE'")
    fun observerResume(sessionId: String): Flow<ResumeVentes>

    @Query("SELECT * FROM vente WHERE id = :id")
    suspend fun get(id: String): VenteEntity?

    @Query("SELECT * FROM ligne_vente WHERE vente_id = :venteId")
    suspend fun lignes(venteId: String): List<LigneVenteEntity>

    @Query("SELECT * FROM paiement_vente WHERE vente_id = :venteId")
    suspend fun paiements(venteId: String): List<PaiementVenteEntity>

    @Query("SELECT COUNT(*) FROM retour_vente WHERE vente_id = :venteId")
    suspend fun compterRetoursVente(venteId: String): Int

    /** Ventes et retours ne sont jamais supprimés : les comptes donnent les prochains numéros (RG-X4). */
    @Query("SELECT COUNT(*) FROM vente")
    suspend fun compter(): Int

    @Query("SELECT COUNT(*) FROM retour_vente")
    suspend fun compterRetours(): Int

    @Insert
    suspend fun inserer(vente: VenteEntity)

    @Update
    suspend fun modifier(vente: VenteEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneVenteEntity>)

    @Update
    suspend fun modifierLigne(ligne: LigneVenteEntity)

    @Insert
    suspend fun insererPaiements(paiements: List<PaiementVenteEntity>)

    @Insert
    suspend fun insererRetour(retour: RetourVenteEntity)

    @Insert
    suspend fun insererLignesRetour(lignes: List<LigneRetourVenteEntity>)
}
