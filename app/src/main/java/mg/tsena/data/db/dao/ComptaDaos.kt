package mg.tsena.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import mg.tsena.data.db.entity.CompteAvecSolde
import mg.tsena.data.db.entity.CompteEntity
import mg.tsena.data.db.entity.EcritureEntity
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.data.db.entity.LigneEcritureAvecCompte
import mg.tsena.data.db.entity.LigneEcritureEntity
import mg.tsena.data.db.entity.MouvementCompte
import mg.tsena.compta.LigneComptable

@Dao
interface CompteDao {
    /** Soldes cumulés depuis l'origine (les comptes de bilan n'ont pas d'à-nouveaux). */
    @Query(
        """
        SELECT c.*,
               COALESCE((SELECT SUM(l.debit) FROM ligne_ecriture l WHERE l.compte_numero = c.numero), 0) AS total_debit,
               COALESCE((SELECT SUM(l.credit) FROM ligne_ecriture l WHERE l.compte_numero = c.numero), 0) AS total_credit
        FROM compte c
        ORDER BY c.numero
        """
    )
    fun observerAvecSoldes(): Flow<List<CompteAvecSolde>>

    @Query("SELECT * FROM compte ORDER BY numero")
    suspend fun tous(): List<CompteEntity>

    @Query("SELECT * FROM compte WHERE numero = :numero")
    suspend fun get(numero: String): CompteEntity?

    @Query("SELECT * FROM compte WHERE numero = :numero")
    fun observer(numero: String): Flow<CompteEntity?>

    @Insert
    suspend fun inserer(compte: CompteEntity)
}

@Dao
interface ExerciceDao {
    @Query("SELECT * FROM exercice ORDER BY date_debut DESC")
    fun observerTous(): Flow<List<ExerciceEntity>>

    @Query("SELECT * FROM exercice WHERE date_debut <= :jour AND date_fin >= :jour")
    suspend fun contenant(jour: String): ExerciceEntity?

    @Query("SELECT * FROM exercice WHERE id = :id")
    suspend fun get(id: String): ExerciceEntity?

    @Insert
    suspend fun inserer(exercice: ExerciceEntity)

    @Update
    suspend fun modifier(exercice: ExerciceEntity)
}

@Dao
interface EcritureDao {
    @Query(
        """
        SELECT * FROM ecriture
        WHERE date >= :debut AND date < :fin AND (:journal IS NULL OR journal = :journal)
        ORDER BY numero DESC
        """
    )
    fun observerPeriode(debut: Long, fin: Long, journal: String?): Flow<List<EcritureEntity>>

    @Query(
        """
        SELECT l.*, c.libelle AS compte_libelle FROM ligne_ecriture l
        JOIN compte c ON c.numero = l.compte_numero
        JOIN ecriture e ON e.id = l.ecriture_id
        WHERE e.date >= :debut AND e.date < :fin AND (:journal IS NULL OR e.journal = :journal)
        ORDER BY l.debit = 0, l.compte_numero
        """
    )
    fun observerLignesPeriode(debut: Long, fin: Long, journal: String?): Flow<List<LigneEcritureAvecCompte>>

    /** Grand livre d'un compte et de ses sous-comptes. */
    @Query(
        """
        SELECT e.id AS ecriture_id, e.numero, e.journal, e.date, e.libelle,
               SUM(l.debit) AS debit, SUM(l.credit) AS credit
        FROM ligne_ecriture l
        JOIN ecriture e ON e.id = l.ecriture_id
        WHERE l.compte_numero = :numero OR l.compte_numero LIKE :numero || '%'
        GROUP BY e.id
        ORDER BY e.date, e.numero
        """
    )
    fun observerGrandLivre(numero: String): Flow<List<MouvementCompte>>

    /** Lignes jusqu'à [fin] (exclue), sans l'écriture de clôture de l'exercice [exerciceId], pour les états. */
    @Query(
        """
        SELECT e.id AS ecritureId, e.date, e.document_type AS documentType, l.compte_numero AS compte, l.debit, l.credit
        FROM ligne_ecriture l
        JOIN ecriture e ON e.id = l.ecriture_id
        WHERE e.date < :fin
          AND (e.document_id IS NULL OR e.document_id != :exerciceId OR e.document_type != 'CLOTURE_EXERCICE')
        """
    )
    suspend fun lignesJusqua(fin: Long, exerciceId: String): List<LigneComptable>

    @Query("SELECT COALESCE(SUM(debit), 0) - COALESCE(SUM(credit), 0) FROM ligne_ecriture WHERE compte_numero = :numero")
    suspend fun solde(numero: String): Long

    /** Soldes (débit − crédit) des comptes de charges et produits d'un exercice, pour la clôture. */
    @Query(
        """
        SELECT l.compte_numero AS numero, SUM(l.debit) - SUM(l.credit) AS solde
        FROM ligne_ecriture l
        JOIN ecriture e ON e.id = l.ecriture_id
        JOIN compte c ON c.numero = l.compte_numero
        WHERE e.exercice_id = :exerciceId AND c.classe IN (6, 7)
        GROUP BY l.compte_numero
        """
    )
    suspend fun soldesResultat(exerciceId: String): List<SoldeCompte>

    @Query("SELECT * FROM ecriture WHERE document_type = :documentType AND document_id = :documentId")
    suspend fun parDocument(documentType: String, documentId: String): List<EcritureEntity>

    @Query("SELECT * FROM ligne_ecriture WHERE ecriture_id = :ecritureId")
    suspend fun lignes(ecritureId: String): List<LigneEcritureEntity>

    @Query("SELECT COUNT(*) FROM ecriture WHERE contrepassation_de = :ecritureId")
    suspend fun compterContrepassations(ecritureId: String): Int

    @Query("SELECT COALESCE(MAX(numero), 0) FROM ecriture")
    suspend fun dernierNumero(): Int

    @Insert
    suspend fun inserer(ecriture: EcritureEntity)

    @Insert
    suspend fun insererLignes(lignes: List<LigneEcritureEntity>)
}

data class SoldeCompte(val numero: String, val solde: Long)
