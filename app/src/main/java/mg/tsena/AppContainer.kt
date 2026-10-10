package mg.tsena

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.SessionManager
import mg.tsena.backup.BackupManager
import mg.tsena.catalogue.CatalogueRepository
import mg.tsena.catalogue.PhotoManager
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.lieux.LieuRepository
import mg.tsena.stock.StockRepository

/** Injection de dépendances manuelle : une seule instance de chaque service pour toute l'app. */
class AppContainer(context: Context) {
    val database = TsenaDatabase.creer(context)
    val session = SessionManager()
    val audit = AuditLogger(database.journalAuditDao(), session)
    val authRepository = AuthRepository(database, session, audit)
    val backupManager = BackupManager(context, database, audit)
    val photoManager = PhotoManager(context)
    val catalogueRepository = CatalogueRepository(database, session, audit, photoManager)
    val lieuRepository = LieuRepository(database, session, audit)
    val stockRepository = StockRepository(database, session, audit)
}

class TsenaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Accès au conteneur depuis les fabriques de ViewModel. */
val CreationExtras.container: AppContainer
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TsenaApp).container
