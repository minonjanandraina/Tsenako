package mg.tsena.rapports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Bilan
import mg.tsena.compta.Comptable
import mg.tsena.compta.CompteResultat
import mg.tsena.compta.EtatsFinanciers
import mg.tsena.compta.FluxTresorerie
import mg.tsena.compta.LigneBalance
import mg.tsena.credit.Anciennete
import mg.tsena.credit.CreditRepository
import mg.tsena.credit.Credits
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.finance.Echeances
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** États financiers d'un exercice (RG-K6). */
data class EtatsExercice(
    val exercice: ExerciceEntity,
    val balance: List<LigneBalance>,
    val compteResultat: CompteResultat,
    val bilan: Bilan,
    val flux: FluxTresorerie,
)

/** Données du tableau de performance d'un mois. */
data class DonneesPerformance(
    val mois: YearMonth,
    val ventes: RapportVentes,
    /** Chiffre d'affaires de chaque jour du mois (jours sans vente à 0) */
    val parJour: List<Pair<LocalDate, Long>>,
    /** Comptant / crédit des six derniers mois, le mois choisi en dernier */
    val sixMois: List<Pair<YearMonth, PartsVentes>>,
    val impayesParAnciennete: Map<Anciennete, Long>,
    val creances: Long,
    val clientsEnRetard: Int,
)

/** Indicateurs de l'accueil du tableau de bord. */
data class TableauDeBord(
    val jour: RapportVentes,
    val mois: RapportVentes,
    val valeurStock: Long,
    val creancesClients: Long,
    val dettesFournisseurs: Long,
    val depensesMois: Long,
    val nbAlertes: Int,
)

/** Tableaux de bord, rapports de ventes, état des stocks et états financiers (S7). */
class RapportRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val credits: CreditRepository,
) {
    fun observerVentes(mois: YearMonth): Flow<RapportVentes> {
        val (debut, fin) = Echeances.bornesMois(mois)
        return observerVentesPeriode(debut, fin)
    }

    private fun observerVentesPeriode(debut: Long, fin: Long): Flow<RapportVentes> =
        db.venteDao().observerLignesRapport(debut, fin).map { CalculRapports.ventes(it) }

    fun observerEtatStocks(): Flow<List<EtatStockLigne>> = db.stockDao().observerEtat()

    fun observerPerformance(mois: YearMonth): Flow<DonneesPerformance> {
        val zone = ZoneId.systemDefault()
        val premierMois = mois.minusMonths(5)
        val debut = premierMois.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val (debutMois, fin) = Echeances.bornesMois(mois, zone)
        return combine(db.venteDao().observerLignesRapport(debut, fin), credits.observerCredits()) { lignes, clients ->
            val lignesMois = lignes.filter { it.date >= debutMois }
            val parJour = Performance.caPar(lignesMois) { Echeances.jourDe(it, zone) }
            val parMois = Performance.partsPar(lignes) { Echeances.moisDe(it, zone) }
            val etats = clients.flatMap { it.ventes }
            DonneesPerformance(
                mois = mois,
                ventes = CalculRapports.ventes(lignesMois),
                parJour = (1..mois.lengthOfMonth()).map { j -> mois.atDay(j).let { it to (parJour[it] ?: 0L) } },
                sixMois = (0L..5L).map { premierMois.plusMonths(it) }.map { m -> m to (parMois[m] ?: PartsVentes(0, 0)) },
                impayesParAnciennete = Credits.parAnciennete(etats),
                creances = clients.sumOf { maxOf(0, it.du) },
                clientsEnRetard = clients.count { it.enRetard },
            )
        }
    }

    fun observerTableauDeBord(): Flow<TableauDeBord> {
        val zone = ZoneId.systemDefault()
        val aujourdhui = LocalDate.now(zone)
        val debutJour = aujourdhui.atStartOfDay(zone).toInstant().toEpochMilli()
        val finJour = aujourdhui.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val mois = YearMonth.from(aujourdhui)
        val (debutMois, finMois) = Echeances.bornesMois(mois, zone)

        val ventes = combine(observerVentesPeriode(debutJour, finJour), observerVentesPeriode(debutMois, finMois)) { j, m -> j to m }
        val tiers = combine(db.clientDao().observerAvecCredit(), db.fournisseurDao().observerAvecSolde()) { clients, fournisseurs ->
            clients.sumOf { maxOf(0, it.creditDu) } to fournisseurs.sumOf { maxOf(0, it.solde) }
        }
        return combine(
            ventes,
            tiers,
            db.stockDao().observerEtat(),
            db.depenseDao().observerTotaux(debutMois, finMois),
            db.stockDao().observerAlertes(),
        ) { (jour, moisVentes), (creances, dettes), stocks, depenses, alertes ->
            TableauDeBord(
                jour = jour,
                mois = moisVentes,
                valeurStock = stocks.sumOf { it.valeur },
                creancesClients = creances,
                dettesFournisseurs = dettes,
                depensesMois = depenses.sumOf { it.total },
                nbAlertes = alertes.size,
            )
        }
    }

    fun observerExercices(): Flow<List<ExerciceEntity>> = db.exerciceDao().observerTous()

    /** Calcule les états d'un exercice à partir des écritures (sans son écriture de clôture). */
    suspend fun etats(exerciceId: String): EtatsExercice {
        session.exiger(Permission.VOIR_COMPTABILITE)
        val exercice = db.exerciceDao().get(exerciceId) ?: throw RegleMetierException("Exercice introuvable.")
        val zone = ZoneId.systemDefault()
        val debut = LocalDate.parse(exercice.dateDebut).atStartOfDay(zone).toInstant().toEpochMilli()
        val fin = Comptable.finDeJournee(LocalDate.parse(exercice.dateFin)) + 1
        val cumul = db.ecritureDao().lignesJusqua(fin, exercice.id)
        val exerciceSeul = cumul.filter { it.date >= debut }
        val libelles = db.compteDao().tous().associate { it.numero to it.libelle }
        val resultat = EtatsFinanciers.compteResultat(exerciceSeul)
        val tresorerieDebut = cumul.filter { it.date < debut && it.compte.startsWith("5") }.sumOf { it.debit - it.credit }
        return EtatsExercice(
            exercice = exercice,
            balance = EtatsFinanciers.balance(cumul, libelles),
            compteResultat = resultat,
            bilan = EtatsFinanciers.bilan(cumul, resultat.resultatNet),
            flux = EtatsFinanciers.fluxTresorerie(exerciceSeul, tresorerieDebut),
        )
    }
}
