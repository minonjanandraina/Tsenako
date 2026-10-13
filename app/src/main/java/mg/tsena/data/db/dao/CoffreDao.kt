package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationCoffreEntity
import mg.tsena.data.db.entity.SoldeCoffre

@Dao
interface CoffreDao {
    @Insert
    suspend fun inserer(operation: OperationCoffreEntity)

    @Query("SELECT * FROM operation_coffre ORDER BY date DESC LIMIT :limite")
    fun observerOperations(limite: Int): Flow<List<OperationCoffreEntity>>

    /** Entrées − sorties d'un mode (types sortants : FOND_CAISSE, RETRAIT, DECAISSEMENT). */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN type IN ('FOND_CAISSE', 'RETRAIT', 'DECAISSEMENT') THEN -montant ELSE montant END), 0)
        FROM operation_coffre WHERE mode = :mode
        """
    )
    suspend fun solde(mode: ModePaiement): Long

    @Query(
        """
        SELECT mode, COALESCE(SUM(CASE WHEN type IN ('FOND_CAISSE', 'RETRAIT', 'DECAISSEMENT') THEN -montant ELSE montant END), 0) AS solde
        FROM operation_coffre GROUP BY mode
        """
    )
    fun observerSoldes(): Flow<List<SoldeCoffre>>
}
