package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.CommandeAvecDetails
import mg.tsena.data.db.entity.CommandeFournisseurEntity
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.ConversionUniteEntity
import mg.tsena.data.db.entity.FournisseurAvecSolde
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.data.db.entity.LigneCommandeAvecProduit
import mg.tsena.data.db.entity.LigneCommandeEntity
import mg.tsena.data.db.entity.LigneReceptionAvecProduit
import mg.tsena.data.db.entity.LigneReceptionEntity
import mg.tsena.data.db.entity.LigneTransfertAvecProduit
import mg.tsena.data.db.entity.LigneTransfertEntity
import mg.tsena.data.db.entity.ReceptionAvecDetails
import mg.tsena.data.db.entity.ReceptionEntity
import mg.tsena.data.db.entity.TransfertAvecDepots
import mg.tsena.data.db.entity.TransfertEntity

@Dao
interface FournisseurDao {
    @Query(
        """
        SELECT f.*,
               COALESCE((SELECT SUM(r.montant_total) FROM reception r WHERE r.fournisseur_id = f.id AND r.deleted_at IS NULL), 0) AS total_achats,
               (SELECT COUNT(*) FROM reception r WHERE r.fournisseur_id = f.id AND r.deleted_at IS NULL) AS nb_receptions,
               COALESCE((SELECT SUM(rf.montant) FROM retour_fournisseur rf WHERE rf.fournisseur_id = f.id), 0) AS total_retours,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p WHERE p.fournisseur_id = f.id), 0) AS total_paye
        FROM fournisseur f
        WHERE f.deleted_at IS NULL
        ORDER BY f.actif DESC, f.nom COLLATE NOCASE
        """
    )
    fun observerAvecSolde(): Flow<List<FournisseurAvecSolde>>

    @Query("SELECT * FROM fournisseur WHERE deleted_at IS NULL AND actif = 1 ORDER BY nom COLLATE NOCASE")
    fun observerActifs(): Flow<List<FournisseurEntity>>

    @Query("SELECT * FROM fournisseur WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): FournisseurEntity?

    @Query("SELECT COUNT(*) FROM fournisseur WHERE deleted_at IS NULL AND nom = :nom COLLATE NOCASE AND id != :exclureId")
    suspend fun compterParNom(nom: String, exclureId: String): Int

    @Insert
    suspend fun inserer(fournisseur: FournisseurEntity)

    @Update
    suspend fun modifier(fournisseur: FournisseurEntity)
}

@Dao
interface ConversionUniteDao {
    @Query(
        """
        SELECT c.*, p.code AS produit_code, p.nom AS produit_nom, u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               up.symbole AS unite_produit_symbole
        FROM conversion_unite c
        JOIN produit p ON p.id = c.produit_id
        JOIN unite u ON u.id = c.unite_source_id
        JOIN unite up ON up.id = p.unite_id
        WHERE c.deleted_at IS NULL AND p.deleted_at IS NULL
        ORDER BY p.nom COLLATE NOCASE, u.nom COLLATE NOCASE
        """
    )
    fun observerToutes(): Flow<List<ConversionAvecUnite>>

    @Query("SELECT * FROM conversion_unite WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): ConversionUniteEntity?

    @Query("SELECT COUNT(*) FROM conversion_unite WHERE produit_id = :produitId AND unite_source_id = :uniteId AND deleted_at IS NULL")
    suspend fun compter(produitId: String, uniteId: String): Int

    @Insert
    suspend fun inserer(conversion: ConversionUniteEntity)

    @Update
    suspend fun modifier(conversion: ConversionUniteEntity)
}

@Dao
interface CommandeDao {
    @Query(
        """
        SELECT c.*, f.nom AS fournisseur_nom, f.telephone AS fournisseur_telephone, d.nom AS depot_nom,
               COALESCE((SELECT SUM(l.montant) FROM ligne_commande l WHERE l.commande_id = c.id), 0) AS montant_total,
               (SELECT COUNT(*) FROM ligne_commande l WHERE l.commande_id = c.id) AS nb_lignes
        FROM commande_fournisseur c
        JOIN fournisseur f ON f.id = c.fournisseur_id
        JOIN depot d ON d.id = c.depot_id
        WHERE c.deleted_at IS NULL
        ORDER BY c.statut IN ('RECUE', 'ANNULEE'), c.date DESC
        """
    )
    fun observerToutes(): Flow<List<CommandeAvecDetails>>

    @Query(
        """
        SELECT c.*, f.nom AS fournisseur_nom, f.telephone AS fournisseur_telephone, d.nom AS depot_nom,
               COALESCE((SELECT SUM(l.montant) FROM ligne_commande l WHERE l.commande_id = c.id), 0) AS montant_total,
               (SELECT COUNT(*) FROM ligne_commande l WHERE l.commande_id = c.id) AS nb_lignes
        FROM commande_fournisseur c
        JOIN fournisseur f ON f.id = c.fournisseur_id
        JOIN depot d ON d.id = c.depot_id
        WHERE c.id = :id
        """
    )
    fun observer(id: String): Flow<CommandeAvecDetails?>

    @Query(
        """
        SELECT l.*, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale
        FROM ligne_commande l
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE l.commande_id = :commandeId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerLignes(commandeId: String): Flow<List<LigneCommandeAvecProduit>>

    @Query(
        """
        SELECT l.*, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale
        FROM ligne_commande l
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE l.commande_id = :commandeId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    suspend fun lignesAvecProduit(commandeId: String): List<LigneCommandeAvecProduit>

    @Query("SELECT * FROM commande_fournisseur WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): CommandeFournisseurEntity?

    @Query("SELECT * FROM ligne_commande WHERE commande_id = :commandeId")
    suspend fun lignes(commandeId: String): List<LigneCommandeEntity>

    /** Les commandes ne sont jamais supprimées physiquement : le compte donne le prochain numéro sans trou. */
    @Query("SELECT COUNT(*) FROM commande_fournisseur")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(commande: CommandeFournisseurEntity)

    @Update
    suspend fun modifier(commande: CommandeFournisseurEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneCommandeEntity>)

    @Update
    suspend fun modifierLigne(ligne: LigneCommandeEntity)

    @Query("DELETE FROM ligne_commande WHERE commande_id = :commandeId")
    suspend fun supprimerLignes(commandeId: String)
}

@Dao
interface ReceptionDao {
    @Query(
        """
        SELECT r.*, f.nom AS fournisseur_nom, d.nom AS depot_nom, c.numero AS commande_numero, u.nom AS auteur_nom
        FROM reception r
        JOIN fournisseur f ON f.id = r.fournisseur_id
        JOIN depot d ON d.id = r.depot_id
        LEFT JOIN commande_fournisseur c ON c.id = r.commande_id
        LEFT JOIN utilisateur u ON u.id = r.created_by
        WHERE r.deleted_at IS NULL
        ORDER BY r.date DESC
        """
    )
    fun observerToutes(): Flow<List<ReceptionAvecDetails>>

    @Query(
        """
        SELECT r.*, f.nom AS fournisseur_nom, d.nom AS depot_nom, c.numero AS commande_numero, u.nom AS auteur_nom
        FROM reception r
        JOIN fournisseur f ON f.id = r.fournisseur_id
        JOIN depot d ON d.id = r.depot_id
        LEFT JOIN commande_fournisseur c ON c.id = r.commande_id
        LEFT JOIN utilisateur u ON u.id = r.created_by
        WHERE r.commande_id = :commandeId AND r.deleted_at IS NULL
        ORDER BY r.date DESC
        """
    )
    fun observerParCommande(commandeId: String): Flow<List<ReceptionAvecDetails>>

    @Query(
        """
        SELECT r.*, f.nom AS fournisseur_nom, d.nom AS depot_nom, c.numero AS commande_numero, u.nom AS auteur_nom
        FROM reception r
        JOIN fournisseur f ON f.id = r.fournisseur_id
        JOIN depot d ON d.id = r.depot_id
        LEFT JOIN commande_fournisseur c ON c.id = r.commande_id
        LEFT JOIN utilisateur u ON u.id = r.created_by
        WHERE r.id = :id
        """
    )
    fun observer(id: String): Flow<ReceptionAvecDetails?>

    @Query(
        """
        SELECT l.*, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole
        FROM ligne_reception l
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE l.reception_id = :receptionId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerLignes(receptionId: String): Flow<List<LigneReceptionAvecProduit>>

    @Query("SELECT * FROM reception WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): ReceptionEntity?

    @Query("SELECT COUNT(*) FROM reception WHERE commande_id = :commandeId AND deleted_at IS NULL")
    suspend fun compterParCommande(commandeId: String): Int

    @Query("SELECT COUNT(*) FROM reception")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(reception: ReceptionEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneReceptionEntity>)
}

@Dao
interface TransfertDao {
    @Query(
        """
        SELECT t.*, s.nom AS source_nom, d.nom AS destination_nom,
               (SELECT COUNT(*) FROM ligne_transfert l WHERE l.transfert_id = t.id) AS nb_lignes
        FROM transfert t
        JOIN depot s ON s.id = t.depot_source_id
        JOIN depot d ON d.id = t.depot_destination_id
        WHERE t.deleted_at IS NULL
        ORDER BY t.statut != 'EN_TRANSIT', t.date DESC
        """
    )
    fun observerTous(): Flow<List<TransfertAvecDepots>>

    @Query(
        """
        SELECT t.*, s.nom AS source_nom, d.nom AS destination_nom,
               (SELECT COUNT(*) FROM ligne_transfert l WHERE l.transfert_id = t.id) AS nb_lignes
        FROM transfert t
        JOIN depot s ON s.id = t.depot_source_id
        JOIN depot d ON d.id = t.depot_destination_id
        WHERE t.id = :id
        """
    )
    fun observer(id: String): Flow<TransfertAvecDepots?>

    @Query(
        """
        SELECT l.*, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale
        FROM ligne_transfert l
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE l.transfert_id = :transfertId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerLignes(transfertId: String): Flow<List<LigneTransfertAvecProduit>>

    @Query("SELECT COUNT(*) FROM transfert WHERE statut = 'EN_TRANSIT' AND deleted_at IS NULL")
    fun observerNbEnTransit(): Flow<Int>

    @Query("SELECT * FROM transfert WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): TransfertEntity?

    @Query("SELECT * FROM ligne_transfert WHERE transfert_id = :transfertId")
    suspend fun lignes(transfertId: String): List<LigneTransfertEntity>

    @Query("SELECT COUNT(*) FROM transfert")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(transfert: TransfertEntity)

    @Update
    suspend fun modifier(transfert: TransfertEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneTransfertEntity>)

    @Update
    suspend fun modifierLigne(ligne: LigneTransfertEntity)
}
