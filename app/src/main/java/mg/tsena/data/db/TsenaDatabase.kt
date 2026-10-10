package mg.tsena.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import mg.tsena.data.db.dao.CommerceDao
import mg.tsena.data.db.dao.JournalAuditDao
import mg.tsena.data.db.dao.LieuDao
import mg.tsena.data.db.dao.ProduitDao
import mg.tsena.data.db.dao.TypeProduitDao
import mg.tsena.data.db.dao.UniteDao
import mg.tsena.data.db.dao.UtilisateurDao
import mg.tsena.data.db.entity.CommerceEntity
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.JournalAuditEntity
import mg.tsena.data.db.entity.PointVenteEntity
import mg.tsena.data.db.entity.ProduitEntity
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.data.db.entity.UniteEntity
import mg.tsena.data.db.entity.UtilisateurEntity
import mg.tsena.data.db.entity.UtilisateurRoleEntity
import mg.tsena.domain.Role
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

class Converters {
    @TypeConverter
    fun roleVersTexte(role: Role): String = role.name

    @TypeConverter
    fun texteVersRole(texte: String): Role = Role.valueOf(texte)
}

@Database(
    entities = [
        CommerceEntity::class,
        UtilisateurEntity::class,
        UtilisateurRoleEntity::class,
        JournalAuditEntity::class,
        TypeProduitEntity::class,
        UniteEntity::class,
        ProduitEntity::class,
        DepotEntity::class,
        PointVenteEntity::class,
    ],
    version = TsenaDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TsenaDatabase : RoomDatabase() {
    abstract fun commerceDao(): CommerceDao
    abstract fun utilisateurDao(): UtilisateurDao
    abstract fun journalAuditDao(): JournalAuditDao
    abstract fun typeProduitDao(): TypeProduitDao
    abstract fun uniteDao(): UniteDao
    abstract fun produitDao(): ProduitDao
    abstract fun lieuDao(): LieuDao

    companion object {
        /**
         * À incrémenter à chaque changement de schéma, avec une migration (jamais de migration destructive),
         * dès que l'app est installée chez un marchand. Voir Claude.md §8.
         */
        const val VERSION = 1
        const val NOM_FICHIER = "tsena.db"

        fun creer(context: Context): TsenaDatabase =
            Room.databaseBuilder(context, TsenaDatabase::class.java, NOM_FICHIER)
                .addCallback(DonneesInitiales)
                .build()

        /** Unités proposées à la création de la base ; le marchand peut en ajouter. */
        private val UNITES_PAR_DEFAUT = listOf(
            Triple("Pièce", "pce", false),
            Triple("Kilogramme", "kg", true),
            Triple("Litre", "L", true),
            Triple("Mètre", "m", true),
            Triple("Sac", "sac", false),
            Triple("Carton", "ctn", false),
            Triple("Paquet", "pqt", false),
            Triple("Boîte", "bte", false),
            Triple("Bouteille", "btl", false),
        )

        private object DonneesInitiales : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val date = maintenant()
                UNITES_PAR_DEFAUT.forEach { (nom, symbole, decimale) ->
                    db.execSQL(
                        "INSERT INTO unite (id, nom, symbole, decimale, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(nouvelId(), nom, symbole, if (decimale) 1 else 0, date, date),
                    )
                }
            }
        }
    }
}
