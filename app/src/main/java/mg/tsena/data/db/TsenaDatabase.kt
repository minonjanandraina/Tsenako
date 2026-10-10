package mg.tsena.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import mg.tsena.data.db.dao.CommerceDao
import mg.tsena.data.db.dao.JournalAuditDao
import mg.tsena.data.db.dao.UtilisateurDao
import mg.tsena.data.db.entity.CommerceEntity
import mg.tsena.data.db.entity.JournalAuditEntity
import mg.tsena.data.db.entity.UtilisateurEntity
import mg.tsena.data.db.entity.UtilisateurRoleEntity
import mg.tsena.domain.Role

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
    ],
    version = TsenaDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TsenaDatabase : RoomDatabase() {
    abstract fun commerceDao(): CommerceDao
    abstract fun utilisateurDao(): UtilisateurDao
    abstract fun journalAuditDao(): JournalAuditDao

    companion object {
        /** À incrémenter à chaque changement de schéma, avec une migration (jamais de migration destructive). */
        const val VERSION = 1
        const val NOM_FICHIER = "tsena.db"

        fun creer(context: Context): TsenaDatabase =
            Room.databaseBuilder(context, TsenaDatabase::class.java, NOM_FICHIER).build()
    }
}
