package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.AchatLotAppro
import mg.tsena.data.db.entity.EtiquetageApproEntity
import mg.tsena.data.db.entity.LigneLotAppro
import mg.tsena.data.db.entity.LotApproAvecTotaux
import mg.tsena.data.db.entity.LotApproEntity

@Dao
interface LotApproDao {
    @Query(
        """
        SELECT l.*, ds.nom AS depot_source_nom, dd.nom AS depot_destination_nom,
               (SELECT COUNT(*) FROM reception r WHERE r.lot_id = l.id) AS nb_achats,
               COALESCE((SELECT SUM(r.montant_total) FROM reception r WHERE r.lot_id = l.id), 0) AS total_achats,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p JOIN reception r ON r.id = p.reception_id
                         WHERE r.lot_id = l.id), 0) AS total_paye,
               (SELECT COUNT(DISTINCT lr.produit_id) FROM ligne_reception lr JOIN reception r ON r.id = lr.reception_id
                WHERE r.lot_id = l.id) AS nb_produits,
               (SELECT COUNT(*) FROM etiquetage_appro e WHERE e.lot_id = l.id) AS nb_etiquetes
        FROM lot_appro l
        JOIN depot ds ON ds.id = l.depot_source_id
        JOIN depot dd ON dd.id = l.depot_destination_id
        ORDER BY l.date DESC
        """
    )
    fun observerTous(): Flow<List<LotApproAvecTotaux>>

    @Query(
        """
        SELECT l.*, ds.nom AS depot_source_nom, dd.nom AS depot_destination_nom,
               (SELECT COUNT(*) FROM reception r WHERE r.lot_id = l.id) AS nb_achats,
               COALESCE((SELECT SUM(r.montant_total) FROM reception r WHERE r.lot_id = l.id), 0) AS total_achats,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p JOIN reception r ON r.id = p.reception_id
                         WHERE r.lot_id = l.id), 0) AS total_paye,
               (SELECT COUNT(DISTINCT lr.produit_id) FROM ligne_reception lr JOIN reception r ON r.id = lr.reception_id
                WHERE r.lot_id = l.id) AS nb_produits,
               (SELECT COUNT(*) FROM etiquetage_appro e WHERE e.lot_id = l.id) AS nb_etiquetes
        FROM lot_appro l
        JOIN depot ds ON ds.id = l.depot_source_id
        JOIN depot dd ON dd.id = l.depot_destination_id
        WHERE l.id = :id
        """
    )
    fun observer(id: String): Flow<LotApproAvecTotaux?>

    /** Lecture ponctuelle (dans une transaction) des totaux d'un appro. */
    @Query(
        """
        SELECT l.*, ds.nom AS depot_source_nom, dd.nom AS depot_destination_nom,
               (SELECT COUNT(*) FROM reception r WHERE r.lot_id = l.id) AS nb_achats,
               COALESCE((SELECT SUM(r.montant_total) FROM reception r WHERE r.lot_id = l.id), 0) AS total_achats,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p JOIN reception r ON r.id = p.reception_id
                         WHERE r.lot_id = l.id), 0) AS total_paye,
               (SELECT COUNT(DISTINCT lr.produit_id) FROM ligne_reception lr JOIN reception r ON r.id = lr.reception_id
                WHERE r.lot_id = l.id) AS nb_produits,
               (SELECT COUNT(*) FROM etiquetage_appro e WHERE e.lot_id = l.id) AS nb_etiquetes
        FROM lot_appro l
        JOIN depot ds ON ds.id = l.depot_source_id
        JOIN depot dd ON dd.id = l.depot_destination_id
        WHERE l.id = :id
        """
    )
    suspend fun avecTotaux(id: String): LotApproAvecTotaux?

    @Query("SELECT * FROM lot_appro WHERE id = :id")
    suspend fun get(id: String): LotApproEntity?

    /** Les appros ne sont jamais supprimés : le compte donne le prochain numéro. */
    @Query("SELECT COUNT(*) FROM lot_appro")
    suspend fun compter(): Int

    @Insert
    suspend fun inserer(lot: LotApproEntity)

    @Update
    suspend fun modifier(lot: LotApproEntity)

    /** Achats (réceptions) de l'appro, avec ce qui a été payé. */
    @Query(
        """
        SELECT r.*, f.nom AS fournisseur_nom,
               COALESCE((SELECT SUM(p.montant) FROM paiement_fournisseur p WHERE p.reception_id = r.id), 0) AS total_paye
        FROM reception r
        JOIN fournisseur f ON f.id = r.fournisseur_id
        WHERE r.lot_id = :lotId
        ORDER BY r.date
        """
    )
    fun observerAchats(lotId: String): Flow<List<AchatLotAppro>>

    /** Produits de l'appro : quantités achetées (retours fournisseur déduits) et étiquetage. */
    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               SUM(lr.quantite_milli)
                 - SUM(COALESCE((SELECT SUM(x.quantite_milli) FROM ligne_retour_fournisseur x WHERE x.ligne_reception_id = lr.id), 0))
                 AS quantite_achetee_milli,
               SUM(lr.montant) AS montant_achete,
               e.quantite_comptee_milli AS quantite_comptee_milli, e.motif_ecart AS motif_ecart
        FROM ligne_reception lr
        JOIN reception r ON r.id = lr.reception_id
        JOIN produit p ON p.id = lr.produit_id
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN etiquetage_appro e ON e.lot_id = r.lot_id AND e.produit_id = lr.produit_id
        WHERE r.lot_id = :lotId
        GROUP BY p.id
        ORDER BY (e.quantite_comptee_milli IS NOT NULL), p.nom COLLATE NOCASE
        """
    )
    fun observerLignes(lotId: String): Flow<List<LigneLotAppro>>

    @Query(
        """
        SELECT p.id AS produit_id, p.code, p.nom, p.photo_chemin, u.symbole AS unite_symbole, u.decimale AS unite_decimale,
               SUM(lr.quantite_milli)
                 - SUM(COALESCE((SELECT SUM(x.quantite_milli) FROM ligne_retour_fournisseur x WHERE x.ligne_reception_id = lr.id), 0))
                 AS quantite_achetee_milli,
               SUM(lr.montant) AS montant_achete,
               e.quantite_comptee_milli AS quantite_comptee_milli, e.motif_ecart AS motif_ecart
        FROM ligne_reception lr
        JOIN reception r ON r.id = lr.reception_id
        JOIN produit p ON p.id = lr.produit_id
        JOIN unite u ON u.id = p.unite_id
        LEFT JOIN etiquetage_appro e ON e.lot_id = r.lot_id AND e.produit_id = lr.produit_id
        WHERE r.lot_id = :lotId
        GROUP BY p.id
        """
    )
    suspend fun lignes(lotId: String): List<LigneLotAppro>

    @Query("SELECT * FROM etiquetage_appro WHERE lot_id = :lotId AND produit_id = :produitId")
    suspend fun etiquetage(lotId: String, produitId: String): EtiquetageApproEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enregistrerEtiquetage(etiquetage: EtiquetageApproEntity)
}
