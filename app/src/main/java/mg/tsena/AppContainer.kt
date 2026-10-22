package mg.tsena

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import mg.tsena.appro.ApproRepository
import mg.tsena.appro.LotApproRepository
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.SessionManager
import mg.tsena.backup.BackupManager
import mg.tsena.banque.BanqueRepository
import mg.tsena.banque.LiloSimulee
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.catalogue.PhotoManager
import mg.tsena.compta.ComptaRepository
import mg.tsena.compta.Comptable
import mg.tsena.credit.CreditRepository
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.erreurs.JournalErreurs
import mg.tsena.finance.CoffreRepository
import mg.tsena.finance.FinanceRepository
import mg.tsena.lieux.LieuRepository
import mg.tsena.rapports.RapportRepository
import mg.tsena.stock.StockRepository
import mg.tsena.vente.VenteRepository

/** Injection de dépendances manuelle : une seule instance de chaque service pour toute l'app. */
class AppContainer(context: Context) {
    val journalErreurs = JournalErreurs(context)
    val database = TsenaDatabase.creer(context)
    val session = SessionManager()
    val audit = AuditLogger(database.journalAuditDao(), session)
    val authRepository = AuthRepository(database, session, audit)
    val backupManager = BackupManager(context, database, audit)
    val photoManager = PhotoManager(context)
    val catalogueRepository = CatalogueRepository(database, session, audit, photoManager)
    val lieuRepository = LieuRepository(database, session, audit)
    val comptable = Comptable(database, session)
    val stockRepository = StockRepository(database, session, audit, comptable)
    val approRepository = ApproRepository(database, session, audit, stockRepository, comptable)
    val coffreRepository = CoffreRepository(database, session, audit, comptable)
    val venteRepository = VenteRepository(database, session, audit, authRepository, stockRepository, comptable, coffreRepository)
    val financeRepository = FinanceRepository(database, session, audit, comptable, coffreRepository)
    /** Banque partenaire : Lilo simulée tant qu'aucune API bancaire réelle n'est branchée */
    val banqueRepository = BanqueRepository(database, session, audit, comptable, financeRepository, LiloSimulee())
    val lotApproRepository = LotApproRepository(database, session, audit, approRepository, financeRepository, stockRepository)
    val comptaRepository = ComptaRepository(database, session, audit, comptable)
    val creditRepository = CreditRepository(database)
    val rapportRepository = RapportRepository(database, session, creditRepository)
}

class TsenaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.journalErreurs.installer()
    }
}

/** Accès au conteneur depuis les fabriques de ViewModel. */
val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TsenaApp).container
