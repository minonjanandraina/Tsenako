package mg.tsena.compta

import mg.tsena.auth.SessionManager
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.EcritureEntity
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.LigneEcritureEntity
import mg.tsena.data.db.entity.StatutExercice
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant
import mg.tsena.stock.Valorisation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pièce à l'origine d'une écriture. */
data class PieceComptable(val type: String, val id: String)

/**
 * Passe les écritures générées par les documents (RG-K2). Toutes les méthodes s'appellent
 * dans la transaction du document : si l'écriture est refusée, le document l'est aussi.
 */
class Comptable(private val db: TsenaDatabase, private val session: SessionManager) {
    private val ecritureDao = db.ecritureDao()
    private val exerciceDao = db.exerciceDao()

    /**
     * Enregistre une écriture équilibrée (RG-K1) dans l'exercice de [date] (créé au besoin, refusé s'il est
     * clôturé : RG-K5). Une liste vide ne produit aucune écriture (renvoie null).
     */
    suspend fun passer(
        journal: Journal,
        libelle: String,
        imputations: List<Imputation>,
        piece: PieceComptable? = null,
        date: Long = maintenant(),
        contrepassationDe: String? = null,
    ): String? {
        if (imputations.isEmpty()) return null
        if (!ReglesComptables.equilibree(imputations)) throw RegleMetierException("Écriture déséquilibrée : $libelle.")
        imputations.map { it.compte }.toSet().forEach { numero ->
            if (db.compteDao().get(numero) == null) throw RegleMetierException("Le compte $numero n'existe pas dans le plan comptable.")
        }
        val exercice = exercicePour(date)
        if (exercice.statut == StatutExercice.CLOTURE) {
            throw RegleMetierException("L'exercice ${exercice.dateDebut} – ${exercice.dateFin} est clôturé.")
        }
        val ecriture = EcritureEntity(
            numero = ecritureDao.dernierNumero() + 1,
            journal = journal,
            exerciceId = exercice.id,
            date = date,
            libelle = libelle,
            documentType = piece?.type,
            documentId = piece?.id,
            contrepassationDe = contrepassationDe,
            createdBy = session.utilisateur.value?.id,
        )
        ecritureDao.inserer(ecriture)
        ecritureDao.insererLignes(
            imputations.map {
                LigneEcritureEntity(
                    ecritureId = ecriture.id,
                    compteNumero = it.compte,
                    debit = it.debit,
                    credit = it.credit,
                    tiersType = it.tiersType,
                    tiersId = it.tiersId,
                )
            }
        )
        return ecriture.id
    }

    /** RG-K4 : contre-passe les écritures d'une pièce (annulation d'une vente, d'une dépense…). */
    suspend fun contrePasser(piece: PieceComptable, libelle: String) {
        ecritureDao.parDocument(piece.type, piece.id)
            .filter { it.contrepassationDe == null && ecritureDao.compterContrepassations(it.id) == 0 }
            .forEach { e ->
                val lignes = ecritureDao.lignes(e.id).map { Imputation(it.compteNumero, it.debit, it.credit, it.tiersType, it.tiersId) }
                passer(e.journal, libelle, ReglesComptables.inverse(lignes), piece, contrepassationDe = e.id)
            }
    }

    /**
     * RG-K7 : ajuste le compte 37 à la valeur du stock de tous les dépôts (quantités × CMUP),
     * contrepartie 6037. Renvoie l'écart passé (0 si le compte était à jour).
     */
    suspend fun ajusterStock(libelle: String, piece: PieceComptable? = null, date: Long = maintenant()): Long {
        val valeur = db.stockDao().tous().sumOf { Valorisation.valeur(it.quantiteMilli, it.coutMoyenUnitaire) }
        val solde = ecritureDao.solde(ReglesComptables.STOCK)
        passer(Journal.OD, libelle, ReglesComptables.variationStock(valeur, solde), piece, date)
        return valeur - solde
    }

    /** Exercice contenant [date], créé s'il n'existe pas encore. */
    suspend fun exercicePour(date: Long): ExerciceEntity {
        val jour = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate()
        exerciceDao.contenant(jour.toString())?.let { return it }
        val moisDebut = db.commerceDao().get()?.debutExerciceMois ?: 1
        val (debut, fin) = ReglesComptables.exercice(jour, moisDebut)
        val exercice = ExerciceEntity(dateDebut = debut.toString(), dateFin = fin.toString())
        exerciceDao.inserer(exercice)
        return exercice
    }

    companion object {
        /** Dernier instant d'un jour, dans le fuseau du téléphone. */
        fun finDeJournee(jour: LocalDate): Long =
            jour.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    }
}
