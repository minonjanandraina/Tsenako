package mg.tsena.compta

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.CompteAvecSolde
import mg.tsena.data.db.entity.CompteEntity
import mg.tsena.data.db.entity.EcritureEntity
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.LigneEcritureAvecCompte
import mg.tsena.data.db.entity.MouvementCompte
import mg.tsena.data.db.entity.StatutExercice
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant
import mg.tsena.finance.Echeances
import java.time.LocalDate
import java.time.YearMonth

/** Consultation de la comptabilité, plan comptable et clôture des exercices. */
class ComptaRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val comptable: Comptable,
) {
    private val compteDao = db.compteDao()
    private val ecritureDao = db.ecritureDao()
    private val exerciceDao = db.exerciceDao()

    fun observerComptes(): Flow<List<CompteAvecSolde>> = compteDao.observerAvecSoldes()

    fun observerCompte(numero: String): Flow<CompteEntity?> = compteDao.observer(numero)

    fun observerExercices(): Flow<List<ExerciceEntity>> = exerciceDao.observerTous()

    fun observerEcritures(mois: YearMonth, journal: Journal?): Flow<List<EcritureEntity>> {
        val (debut, fin) = Echeances.bornesMois(mois)
        return ecritureDao.observerPeriode(debut, fin, journal?.name)
    }

    fun observerLignes(mois: YearMonth, journal: Journal?): Flow<List<LigneEcritureAvecCompte>> {
        val (debut, fin) = Echeances.bornesMois(mois)
        return ecritureDao.observerLignesPeriode(debut, fin, journal?.name)
    }

    fun observerGrandLivre(numero: String): Flow<List<MouvementCompte>> = ecritureDao.observerGrandLivre(numero)

    /**
     * RG-K3 : sous-compte d'un compte existant (ex. 5171 MVola sous 517). Les comptes de base ne se modifient pas.
     */
    suspend fun creerSousCompte(numero: String, libelle: String) {
        val auteur = session.exiger(Permission.GERER_COMPTABILITE)
        val n = numero.trim()
        val texte = libelle.trim()
        if (n.length < 2 || !n.all { it.isDigit() }) throw RegleMetierException("Le numéro ne contient que des chiffres (au moins 2).")
        if (texte.isEmpty()) throw RegleMetierException("Le libellé est obligatoire.")
        db.withTransaction {
            if (compteDao.get(n) != null) throw RegleMetierException("Le compte $n existe déjà.")
            val parent = compteDao.tous().filter { n.startsWith(it.numero) }.maxByOrNull { it.numero.length }
                ?: throw RegleMetierException("Le numéro doit commencer par celui d'un compte existant (ex. 5171 sous 517).")
            compteDao.inserer(CompteEntity(numero = n, libelle = texte, classe = parent.classe, type = parent.type, createdBy = auteur.id))
            audit.enregistrer(ActionAudit.CREATION_COMPTE, "$n $texte (sous ${parent.numero})")
        }
    }

    /** RG-K7 : met le compte 37 à la valeur actuelle du stock. Renvoie l'écart passé. */
    suspend fun mettreAJourStock(): Long {
        session.exiger(Permission.VOIR_COMPTABILITE)
        return db.withTransaction {
            val ecart = comptable.ajusterStock("Variation de stock (mise à jour)")
            audit.enregistrer(ActionAudit.VARIATION_STOCK, Montant.formater(ecart))
            ecart
        }
    }

    /**
     * Clôture d'un exercice terminé : variation de stock à la date de fin, puis solde des comptes de charges et
     * de produits vers le résultat (120 / 129). Plus aucune écriture n'est possible ensuite (RG-K5).
     * Renvoie le résultat (positif = bénéfice).
     */
    suspend fun cloturerExercice(exerciceId: String): Long {
        val auteur = session.exiger(Permission.GERER_COMPTABILITE)
        return db.withTransaction {
            val exercice = exerciceDao.get(exerciceId) ?: throw RegleMetierException("Exercice introuvable.")
            if (exercice.statut == StatutExercice.CLOTURE) throw RegleMetierException("Cet exercice est déjà clôturé.")
            val fin = LocalDate.parse(exercice.dateFin)
            if (!LocalDate.now().isAfter(fin)) throw RegleMetierException("L'exercice se termine le ${exercice.dateFin} : clôture possible après cette date.")
            val dateCloture = Comptable.finDeJournee(fin)
            comptable.ajusterStock("Variation de stock de clôture", date = dateCloture)
            val soldes = ecritureDao.soldesResultat(exercice.id).associate { it.numero to it.solde }
            val lignes = ReglesComptables.clotureResultat(soldes)
            comptable.passer(Journal.OD, "Clôture de l'exercice : résultat", lignes, date = dateCloture)
            val resultat = -soldes.values.sum()
            val date = maintenant()
            exerciceDao.modifier(exercice.copy(statut = StatutExercice.CLOTURE, dateCloture = date, cloturePar = auteur.id, updatedAt = date))
            audit.enregistrer(ActionAudit.CLOTURE_EXERCICE, "${exercice.dateDebut} – ${exercice.dateFin} : résultat ${Montant.formater(resultat)}")
            resultat
        }
    }
}
