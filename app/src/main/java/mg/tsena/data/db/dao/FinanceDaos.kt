package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.CategorieDepenseEntity
import mg.tsena.data.db.entity.DepenseAvecDetails
import mg.tsena.data.db.entity.DepenseEntity
import mg.tsena.data.db.entity.EncaissementClientEntity
import mg.tsena.data.db.entity.LigneReceptionEntity
import mg.tsena.data.db.entity.LigneReceptionRetournable
import mg.tsena.data.db.entity.LigneRetourFournisseurEntity
import mg.tsena.data.db.entity.PaiementFournisseurEntity
import mg.tsena.data.db.entity.ReceptionAPayer
import mg.tsena.data.db.entity.RetourFournisseurEntity
import mg.tsena.data.db.entity.TotalCategorie
import mg.tsena.credit.TotalClient

@Dao
interface CategorieDepenseDao {
    @Query("SELECT * FROM categorie_depense WHERE deleted_at IS NULL ORDER BY actif DESC, nom COLLATE NOCASE")
    fun observer(): Flow<List<CategorieDepenseEntity>>

    @Query("SELECT * FROM categorie_depense WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): CategorieDepenseEntity?

    @Query("SELECT COUNT(*) FROM categorie_depense WHERE deleted_at IS NULL AND nom = :nom COLLATE NOCASE AND id != :exclureId")
    suspend fun compterParNom(nom: String, exclureId: String): Int

    @Insert
    suspend fun inserer(categorie: CategorieDepenseEntity)

    @Update
    suspend fun modifier(categorie: CategorieDepenseEntity)
}

@Dao
interface DepenseDao {
    @Query(
        """
        SELECT d.*, c.nom AS categorie_nom, c.compte_numero, pv.nom AS point_vente_nom, u.nom AS auteur_nom
        FROM depense d
        JOIN categorie_depense c ON c.id = d.categorie_id
        LEFT JOIN point_vente pv ON pv.id = d.point_vente_id
        LEFT JOIN utilisateur u ON u.id = d.created_by
        WHERE d.date >= :debut AND d.date < :fin
        ORDER BY d.date DESC
        """
    )
    fun observerPeriode(debut: Long, fin: Long): Flow<List<DepenseAvecDetails>>

    @Query(
        """
        SELECT c.nom AS categorie_nom, SUM(d.montant) AS total
        FROM depense d
        JOIN categorie_depense c ON c.id = d.categorie_id
        WHERE d.date >= :debut AND d.date < :fin AND d.statut = 'VALIDEE'
        GROUP BY c.id
        ORDER BY total DESC
        """
    )
    fun observerTotaux(debut: Long, fin: Long): Flow<List<TotalCategorie>>

    @Query("SELECT * FROM depense WHERE id = :id")
    suspend fun get(id: String): DepenseEntity?

    /** Les dépenses ne sont jamais supprimées : le compte donne le prochain numéro. */
    @Query("SELECT COUNT(*) FROM depense")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(depense: DepenseEntity)

    @Update
    suspend fun modifier(depense: DepenseEntity)
}

@Dao
interface PaiementFournisseurDao {
    @Query("SELECT * FROM paiement_fournisseur WHERE fournisseur_id = :fournisseurId ORDER BY date DESC")
    fun observerParFournisseur(fournisseurId: String): Flow<List<PaiementFournisseurEntity>>

    @Query(
        """
        SELECT r.*,
               COALESCE((SELECT SUM(rf.montant) FROM retour_fournisseur rf WHERE rf.reception_id = r.id), 0) AS total_retours,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p WHERE p.reception_id = r.id), 0) AS total_paye
        FROM reception r
        WHERE r.fournisseur_id = :fournisseurId AND r.deleted_at IS NULL
        ORDER BY r.date
        """
    )
    fun observerReceptions(fournisseurId: String): Flow<List<ReceptionAPayer>>

    @Query(
        """
        SELECT r.*,
               COALESCE((SELECT SUM(rf.montant) FROM retour_fournisseur rf WHERE rf.reception_id = r.id), 0) AS total_retours,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p WHERE p.reception_id = r.id), 0) AS total_paye
        FROM reception r
        WHERE r.id = :receptionId
        """
    )
    fun observerReception(receptionId: String): Flow<ReceptionAPayer?>

    @Query(
        """
        SELECT r.*,
               COALESCE((SELECT SUM(rf.montant) FROM retour_fournisseur rf WHERE rf.reception_id = r.id), 0) AS total_retours,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p WHERE p.reception_id = r.id), 0) AS total_paye
        FROM reception r
        WHERE r.id = :receptionId
        """
    )
    suspend fun reception(receptionId: String): ReceptionAPayer?

    @Query(
        """
        SELECT COALESCE((SELECT SUM(montant_total) FROM reception WHERE fournisseur_id = :fournisseurId AND deleted_at IS NULL), 0)
             - COALESCE((SELECT SUM(montant) FROM retour_fournisseur WHERE fournisseur_id = :fournisseurId), 0)
             - COALESCE((SELECT SUM(montant) FROM paiement_fournisseur WHERE fournisseur_id = :fournisseurId), 0)
        """
    )
    suspend fun solde(fournisseurId: String): Long

    @Query("SELECT COUNT(*) FROM paiement_fournisseur")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(paiement: PaiementFournisseurEntity)
}

@Dao
interface RetourFournisseurDao {
    @Query("SELECT * FROM retour_fournisseur WHERE reception_id = :receptionId ORDER BY date DESC")
    fun observerParReception(receptionId: String): Flow<List<RetourFournisseurEntity>>

    @Query(
        """
        SELECT l.*, p.nom, u.symbole AS unite_symbole, u.decimale AS unite_decimale
        FROM ligne_reception l
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE l.reception_id = :receptionId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerLignesRetournables(receptionId: String): Flow<List<LigneReceptionRetournable>>

    @Query("SELECT * FROM ligne_reception WHERE reception_id = :receptionId")
    suspend fun lignesReception(receptionId: String): List<LigneReceptionEntity>

    @Update
    suspend fun modifierLigneReception(ligne: LigneReceptionEntity)

    @Query("SELECT COUNT(*) FROM retour_fournisseur")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(retour: RetourFournisseurEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneRetourFournisseurEntity>)
}

@Dao
interface EncaissementClientDao {
    @Query("SELECT * FROM encaissement_client WHERE client_id = :clientId ORDER BY date DESC")
    fun observerParClient(clientId: String): Flow<List<EncaissementClientEntity>>

    @Query(
        """
        SELECT COALESCE((SELECT SUM(p.montant) FROM paiement_vente p JOIN vente v ON v.id = p.vente_id
                         WHERE v.client_id = :clientId AND v.statut = 'VALIDEE' AND p.mode = 'CREDIT'), 0)
             - COALESCE((SELECT SUM(r.montant) FROM retour_vente r JOIN vente v ON v.id = r.vente_id
                         WHERE v.client_id = :clientId AND r.mode_remboursement = 'CREDIT'), 0)
             - COALESCE((SELECT SUM(montant) FROM encaissement_client WHERE client_id = :clientId), 0)
        """
    )
    suspend fun creditDu(clientId: String): Long

    @Query("SELECT client_id AS clientId, SUM(montant) AS total FROM encaissement_client GROUP BY client_id")
    fun observerTotauxParClient(): Flow<List<TotalClient>>

    @Query("SELECT COUNT(*) FROM encaissement_client")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(encaissement: EncaissementClientEntity)
}
