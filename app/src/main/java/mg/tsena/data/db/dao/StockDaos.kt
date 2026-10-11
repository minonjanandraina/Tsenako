package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.AlerteStock
import mg.tsena.data.db.entity.InventaireAvecDepot
import mg.tsena.data.db.entity.InventaireEntity
import mg.tsena.data.db.entity.LigneInventaireAvecProduit
import mg.tsena.data.db.entity.LigneInventaireEntity
import mg.tsena.data.db.entity.MouvementAvecAuteur
import mg.tsena.data.db.entity.MouvementStockEntity
import mg.tsena.data.db.entity.StockEntity
import mg.tsena.data.db.entity.StockProduit

@Dao
interface StockDao {
    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.nom, p.photo_chemin, p.seuil_alerte_milli,
               u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               COALESCE(s.quantite_milli, 0) AS quantite_milli,
               COALESCE(s.cout_moyen_unitaire, 0) AS cout_moyen_unitaire
        FROM produit p
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN stock s ON s.produit_id = p.id AND s.depot_id = :depotId
        WHERE p.deleted_at IS NULL AND p.actif = 1
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerParDepot(depotId: String): Flow<List<StockProduit>>

    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.nom, p.photo_chemin, p.seuil_alerte_milli,
               u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               COALESCE(s.quantite_milli, 0) AS quantite_milli,
               COALESCE(s.cout_moyen_unitaire, 0) AS cout_moyen_unitaire
        FROM produit p
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN stock s ON s.produit_id = p.id AND s.depot_id = :depotId
        WHERE p.id = :produitId
        """
    )
    fun observerProduit(produitId: String, depotId: String): Flow<StockProduit?>

    /**
     * RG-S4 : produits actifs sous leur seuil, dans les dépôts actifs où ils ont déjà eu du stock.
     * La règle doit rester identique à Valorisation.enAlerte.
     */
    @Query(
        """
        SELECT p.id AS produit_id, p.nom AS produit_nom, d.id AS depot_id, d.nom AS depot_nom,
               u.symbole AS unite_symbole, s.quantite_milli, p.seuil_alerte_milli
        FROM stock s
        JOIN produit p ON p.id = s.produit_id
        JOIN depot d ON d.id = s.depot_id
        JOIN unite u ON u.id = p.unite_id
        WHERE p.deleted_at IS NULL AND p.actif = 1 AND d.deleted_at IS NULL AND d.actif = 1
          AND p.seuil_alerte_milli > 0 AND s.quantite_milli <= p.seuil_alerte_milli
        ORDER BY d.nom COLLATE NOCASE, p.nom COLLATE NOCASE
        """
    )
    fun observerAlertes(): Flow<List<AlerteStock>>

    /** Toutes les lignes de stock, pour la valeur totale (RG-K7). */
    @Query("SELECT * FROM stock")
    suspend fun tous(): List<StockEntity>

    @Query("SELECT * FROM stock WHERE produit_id = :produitId AND depot_id = :depotId")
    suspend fun get(produitId: String, depotId: String): StockEntity?

    @Insert
    suspend fun inserer(stock: StockEntity)

    @Update
    suspend fun modifier(stock: StockEntity)

    @Insert
    suspend fun insererMouvement(mouvement: MouvementStockEntity)

    @Query(
        """
        SELECT m.*, u.nom AS auteur_nom FROM mouvement_stock m
        LEFT JOIN utilisateur u ON u.id = m.created_by
        WHERE m.produit_id = :produitId AND m.depot_id = :depotId
        ORDER BY m.date DESC
        LIMIT :limite
        """
    )
    fun observerMouvements(produitId: String, depotId: String, limite: Int): Flow<List<MouvementAvecAuteur>>
}

@Dao
interface InventaireDao {
    @Query(
        """
        SELECT i.*, d.nom AS depot_nom,
               (SELECT COUNT(*) FROM ligne_inventaire l WHERE l.inventaire_id = i.id) AS nb_lignes,
               (SELECT COUNT(*) FROM ligne_inventaire l WHERE l.inventaire_id = i.id AND l.quantite_comptee_milli IS NOT NULL) AS nb_comptees
        FROM inventaire i
        JOIN depot d ON d.id = i.depot_id
        WHERE i.deleted_at IS NULL
        ORDER BY i.statut = 'VALIDE', i.date DESC
        """
    )
    fun observerTous(): Flow<List<InventaireAvecDepot>>

    @Query(
        """
        SELECT i.*, d.nom AS depot_nom,
               (SELECT COUNT(*) FROM ligne_inventaire l WHERE l.inventaire_id = i.id) AS nb_lignes,
               (SELECT COUNT(*) FROM ligne_inventaire l WHERE l.inventaire_id = i.id AND l.quantite_comptee_milli IS NOT NULL) AS nb_comptees
        FROM inventaire i
        JOIN depot d ON d.id = i.depot_id
        WHERE i.id = :id
        """
    )
    fun observer(id: String): Flow<InventaireAvecDepot?>

    @Query(
        """
        SELECT l.*, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               COALESCE(s.quantite_milli, 0) AS stock_actuel_milli
        FROM ligne_inventaire l
        JOIN inventaire i ON i.id = l.inventaire_id
        JOIN produit p ON p.id = l.produit_id
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN stock s ON s.produit_id = l.produit_id AND s.depot_id = i.depot_id
        WHERE l.inventaire_id = :inventaireId
        ORDER BY p.nom COLLATE NOCASE
        """
    )
    fun observerLignes(inventaireId: String): Flow<List<LigneInventaireAvecProduit>>

    @Query("SELECT * FROM inventaire WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): InventaireEntity?

    @Query("SELECT COUNT(*) FROM inventaire WHERE depot_id = :depotId AND statut = 'BROUILLON' AND deleted_at IS NULL")
    suspend fun compterBrouillons(depotId: String): Int

    @Query("SELECT * FROM ligne_inventaire WHERE inventaire_id = :inventaireId")
    suspend fun lignes(inventaireId: String): List<LigneInventaireEntity>

    @Query("SELECT * FROM ligne_inventaire WHERE id = :id")
    suspend fun getLigne(id: String): LigneInventaireEntity?

    @Insert
    suspend fun inserer(inventaire: InventaireEntity)

    @Update
    suspend fun modifier(inventaire: InventaireEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneInventaireEntity>)

    @Update
    suspend fun modifierLigne(ligne: LigneInventaireEntity)
}
