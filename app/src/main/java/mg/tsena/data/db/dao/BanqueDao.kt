package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.CompteBancaireEntity
import mg.tsena.data.db.entity.OperationBancaireEntity

@Dao
interface BanqueDao {
    @Insert
    suspend fun insererCompte(compte: CompteBancaireEntity)

    @Query("SELECT * FROM compte_bancaire LIMIT 1")
    suspend fun compte(): CompteBancaireEntity?

    @Query("SELECT * FROM compte_bancaire LIMIT 1")
    fun observerCompte(): Flow<CompteBancaireEntity?>

    @Insert
    suspend fun insererOperation(operation: OperationBancaireEntity)

    @Query("SELECT * FROM operation_bancaire WHERE compte_id = :compteId ORDER BY date DESC LIMIT :limite")
    fun observerOperations(compteId: String, limite: Int): Flow<List<OperationBancaireEntity>>

    @Query("SELECT COUNT(*) FROM operation_bancaire")
    suspend fun compterOperations(): Int

    /** Entrées − sorties (type sortant : PAIEMENT_FOURNISSEUR). */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'PAIEMENT_FOURNISSEUR' THEN -montant ELSE montant END), 0)
        FROM operation_bancaire WHERE compte_id = :compteId
        """
    )
    suspend fun solde(compteId: String): Long

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type = 'PAIEMENT_FOURNISSEUR' THEN -montant ELSE montant END), 0)
        FROM operation_bancaire WHERE compte_id = :compteId
        """
    )
    fun observerSolde(compteId: String): Flow<Long>
}
