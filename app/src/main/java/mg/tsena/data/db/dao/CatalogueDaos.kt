package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.PointVenteEntity
import mg.tsena.data.db.entity.ProduitAvecDetails
import mg.tsena.data.db.entity.ProduitEntity
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.data.db.entity.UniteEntity

@Dao
interface TypeProduitDao {
    @Query("SELECT * FROM type_produit WHERE deleted_at IS NULL ORDER BY nom COLLATE NOCASE")
    fun observer(): Flow<List<TypeProduitEntity>>

    @Query("SELECT * FROM type_produit WHERE deleted_at IS NULL")
    suspend fun tous(): List<TypeProduitEntity>

    @Query("SELECT * FROM type_produit WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): TypeProduitEntity?

    @Query("SELECT COUNT(*) FROM type_produit WHERE parent_id = :id AND deleted_at IS NULL")
    suspend fun compterEnfants(id: String): Int

    @Insert
    suspend fun inserer(categorie: TypeProduitEntity)

    @Update
    suspend fun modifier(categorie: TypeProduitEntity)
}

@Dao
interface UniteDao {
    @Query("SELECT * FROM unite WHERE deleted_at IS NULL ORDER BY nom COLLATE NOCASE")
    fun observer(): Flow<List<UniteEntity>>

    @Query("SELECT * FROM unite WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): UniteEntity?

    @Query("SELECT COUNT(*) FROM unite WHERE deleted_at IS NULL AND nom = :nom COLLATE NOCASE")
    suspend fun compterParNom(nom: String): Int

    @Insert
    suspend fun inserer(unite: UniteEntity)
}

@Dao
interface ProduitDao {
    @Query(
        """
        SELECT p.*, t.nom AS categorie_nom, u.symbole AS unite_symbole
        FROM produit p
        JOIN type_produit t ON t.id = p.type_produit_id
        JOIN unite u ON u.id = p.unite_id
        WHERE p.deleted_at IS NULL
        ORDER BY p.actif DESC, p.nom COLLATE NOCASE
        """
    )
    fun observerAvecDetails(): Flow<List<ProduitAvecDetails>>

    @Query("SELECT * FROM produit WHERE id = :id AND deleted_at IS NULL")
    suspend fun get(id: String): ProduitEntity?

    @Query("SELECT COUNT(*) FROM produit WHERE type_produit_id = :categorieId AND deleted_at IS NULL")
    suspend fun compterParCategorie(categorieId: String): Int

    @Query("SELECT COUNT(*) FROM produit WHERE code = :code AND id != :exclureId")
    suspend fun compterParCode(code: String, exclureId: String): Int

    @Query("SELECT COUNT(*) FROM produit WHERE code_barre = :codeBarre AND id != :exclureId")
    suspend fun compterParCodeBarre(codeBarre: String, exclureId: String): Int

    @Query("SELECT code FROM produit")
    suspend fun tousLesCodes(): List<String>

    @Insert
    suspend fun inserer(produit: ProduitEntity)

    @Update
    suspend fun modifier(produit: ProduitEntity)
}

@Dao
interface LieuDao {
    @Query("SELECT * FROM depot WHERE deleted_at IS NULL ORDER BY actif DESC, nom COLLATE NOCASE")
    fun observerDepots(): Flow<List<DepotEntity>>

    @Query(
        """
        SELECT pv.*, d.nom AS depot_nom FROM point_vente pv
        JOIN depot d ON d.id = pv.depot_id
        WHERE pv.deleted_at IS NULL
        ORDER BY pv.actif DESC, pv.nom COLLATE NOCASE
        """
    )
    fun observerPointsVente(): Flow<List<PointVenteAvecDepot>>

    @Query("SELECT * FROM depot WHERE id = :id AND deleted_at IS NULL")
    suspend fun getDepot(id: String): DepotEntity?

    @Query("SELECT * FROM point_vente WHERE id = :id AND deleted_at IS NULL")
    suspend fun getPointVente(id: String): PointVenteEntity?

    @Query("SELECT COUNT(*) FROM depot WHERE deleted_at IS NULL AND nom = :nom COLLATE NOCASE AND id != :exclureId")
    suspend fun compterDepotsParNom(nom: String, exclureId: String): Int

    @Query("SELECT COUNT(*) FROM point_vente WHERE deleted_at IS NULL AND nom = :nom COLLATE NOCASE AND id != :exclureId")
    suspend fun compterPointsVenteParNom(nom: String, exclureId: String): Int

    @Query("SELECT COUNT(*) FROM point_vente WHERE deleted_at IS NULL AND actif = 1 AND depot_id = :depotId")
    suspend fun compterPointsVenteActifsUtilisant(depotId: String): Int

    @Insert
    suspend fun insererDepot(depot: DepotEntity)

    @Update
    suspend fun modifierDepot(depot: DepotEntity)

    @Insert
    suspend fun insererPointVente(pointVente: PointVenteEntity)

    @Update
    suspend fun modifierPointVente(pointVente: PointVenteEntity)
}
