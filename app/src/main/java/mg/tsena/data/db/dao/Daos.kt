package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.CommerceEntity
import mg.tsena.data.db.entity.JournalAuditEntity
import mg.tsena.data.db.entity.UtilisateurAvecRoles
import mg.tsena.data.db.entity.UtilisateurEntity
import mg.tsena.data.db.entity.UtilisateurRoleEntity
import mg.tsena.domain.Role

@Dao
interface CommerceDao {
    @Query("SELECT * FROM commerce WHERE deleted_at IS NULL LIMIT 1")
    suspend fun get(): CommerceEntity?

    @Query("SELECT * FROM commerce WHERE deleted_at IS NULL LIMIT 1")
    fun observer(): Flow<CommerceEntity?>

    @Insert
    suspend fun inserer(commerce: CommerceEntity)

    @Query("UPDATE commerce SET stock_negatif_autorise = :autorise, updated_at = :date WHERE deleted_at IS NULL")
    suspend fun modifierStockNegatif(autorise: Boolean, date: Long)

    @Query("UPDATE commerce SET remise_max_pourcent = :pourcent, updated_at = :date WHERE deleted_at IS NULL")
    suspend fun modifierRemiseMax(pourcent: Int, date: Long)
}

@Dao
interface UtilisateurDao {
    @Transaction
    @Query("SELECT * FROM utilisateur WHERE deleted_at IS NULL AND actif = 1 ORDER BY nom")
    fun observerActifs(): Flow<List<UtilisateurAvecRoles>>

    @Transaction
    @Query("SELECT * FROM utilisateur WHERE deleted_at IS NULL ORDER BY actif DESC, nom")
    fun observerTous(): Flow<List<UtilisateurAvecRoles>>

    @Transaction
    @Query("SELECT * FROM utilisateur WHERE deleted_at IS NULL AND actif = 1")
    suspend fun tousActifs(): List<UtilisateurAvecRoles>

    @Transaction
    @Query("SELECT * FROM utilisateur WHERE id = :id AND deleted_at IS NULL")
    suspend fun getAvecRoles(id: String): UtilisateurAvecRoles?

    @Insert
    suspend fun inserer(utilisateur: UtilisateurEntity)

    @Insert
    suspend fun insererRole(role: UtilisateurRoleEntity)

    /** Point de vente d'un vendeur (RG-X2), porté par ses rôles actifs */
    @Query("UPDATE utilisateur_role SET point_vente_id = :pointVenteId, updated_at = :date WHERE utilisateur_id = :utilisateurId AND deleted_at IS NULL")
    suspend fun modifierPointVente(utilisateurId: String, pointVenteId: String?, date: Long)

    @Query("UPDATE utilisateur SET actif = 0, updated_at = :date WHERE id = :id")
    suspend fun desactiver(id: String, date: Long)

    @Query(
        """
        SELECT COUNT(DISTINCT u.id) FROM utilisateur u
        JOIN utilisateur_role r ON r.utilisateur_id = u.id
        WHERE u.actif = 1 AND u.deleted_at IS NULL AND r.deleted_at IS NULL AND r.role = :role
        """
    )
    suspend fun compterActifsAvecRole(role: Role): Int
}

@Dao
interface JournalAuditDao {
    @Insert
    suspend fun inserer(entree: JournalAuditEntity)
}
