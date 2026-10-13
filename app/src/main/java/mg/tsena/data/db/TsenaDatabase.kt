package mg.tsena.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import mg.tsena.data.db.dao.CaisseDao
import mg.tsena.data.db.dao.CategorieDepenseDao
import mg.tsena.data.db.dao.ClientDao
import mg.tsena.data.db.dao.CoffreDao
import mg.tsena.data.db.dao.CommandeDao
import mg.tsena.data.db.dao.CommerceDao
import mg.tsena.data.db.dao.CompteDao
import mg.tsena.data.db.dao.ConversionUniteDao
import mg.tsena.data.db.dao.DepenseDao
import mg.tsena.data.db.dao.EcritureDao
import mg.tsena.data.db.dao.EncaissementClientDao
import mg.tsena.data.db.dao.ExerciceDao
import mg.tsena.data.db.dao.FournisseurDao
import mg.tsena.data.db.dao.InventaireDao
import mg.tsena.data.db.dao.JournalAuditDao
import mg.tsena.data.db.dao.LieuDao
import mg.tsena.data.db.dao.PaiementFournisseurDao
import mg.tsena.data.db.dao.PrixPointVenteDao
import mg.tsena.data.db.dao.ProduitDao
import mg.tsena.data.db.dao.ReceptionDao
import mg.tsena.data.db.dao.RetourFournisseurDao
import mg.tsena.data.db.dao.StockDao
import mg.tsena.data.db.dao.TransfertDao
import mg.tsena.data.db.dao.TypeProduitDao
import mg.tsena.data.db.dao.UniteDao
import mg.tsena.data.db.dao.UtilisateurDao
import mg.tsena.data.db.dao.VenteDao
import mg.tsena.data.db.entity.CaisseEntity
import mg.tsena.data.db.entity.CategorieDepenseEntity
import mg.tsena.data.db.entity.ClientEntity
import mg.tsena.data.db.entity.CommandeFournisseurEntity
import mg.tsena.data.db.entity.CommerceEntity
import mg.tsena.data.db.entity.CompteEntity
import mg.tsena.data.db.entity.ConversionUniteEntity
import mg.tsena.data.db.entity.DepenseEntity
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.EcritureEntity
import mg.tsena.data.db.entity.EncaissementClientEntity
import mg.tsena.data.db.entity.ExerciceEntity
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.data.db.entity.InventaireEntity
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.entity.JournalAuditEntity
import mg.tsena.data.db.entity.LigneCommandeEntity
import mg.tsena.data.db.entity.LigneEcritureEntity
import mg.tsena.data.db.entity.LigneInventaireEntity
import mg.tsena.data.db.entity.LigneReceptionEntity
import mg.tsena.data.db.entity.LigneRetourFournisseurEntity
import mg.tsena.data.db.entity.LigneRetourVenteEntity
import mg.tsena.data.db.entity.LigneTransfertEntity
import mg.tsena.data.db.entity.LigneVenteEntity
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.MouvementStockEntity
import mg.tsena.data.db.entity.OperationCaisseEntity
import mg.tsena.data.db.entity.OperationCoffreEntity
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.data.db.entity.TypeOperationCoffre
import mg.tsena.data.db.entity.PaiementFournisseurEntity
import mg.tsena.data.db.entity.PaiementVenteEntity
import mg.tsena.data.db.entity.PointVenteEntity
import mg.tsena.data.db.entity.PrixPointVenteEntity
import mg.tsena.data.db.entity.ProduitEntity
import mg.tsena.data.db.entity.ReceptionEntity
import mg.tsena.data.db.entity.RetourFournisseurEntity
import mg.tsena.data.db.entity.RetourVenteEntity
import mg.tsena.data.db.entity.SessionCaisseEntity
import mg.tsena.data.db.entity.StatutCommande
import mg.tsena.data.db.entity.StatutDepense
import mg.tsena.data.db.entity.StatutExercice
import mg.tsena.data.db.entity.StatutInventaire
import mg.tsena.data.db.entity.StatutSession
import mg.tsena.data.db.entity.StatutTransfert
import mg.tsena.data.db.entity.StatutVente
import mg.tsena.data.db.entity.StockEntity
import mg.tsena.data.db.entity.TransfertEntity
import mg.tsena.data.db.entity.TypeCompte
import mg.tsena.data.db.entity.TypeMouvement
import mg.tsena.data.db.entity.TypeOperationCaisse
import mg.tsena.data.db.entity.TypePaiement
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.data.db.entity.TypeVente
import mg.tsena.data.db.entity.UniteEntity
import mg.tsena.data.db.entity.UtilisateurEntity
import mg.tsena.data.db.entity.UtilisateurRoleEntity
import mg.tsena.data.db.entity.VenteEntity
import mg.tsena.domain.Role
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

class Converters {
    @TypeConverter
    fun roleVersTexte(role: Role): String = role.name

    @TypeConverter
    fun texteVersRole(texte: String): Role = Role.valueOf(texte)

    @TypeConverter
    fun typeMouvementVersTexte(type: TypeMouvement): String = type.name

    @TypeConverter
    fun texteVersTypeMouvement(texte: String): TypeMouvement = TypeMouvement.valueOf(texte)

    @TypeConverter
    fun statutInventaireVersTexte(statut: StatutInventaire): String = statut.name

    @TypeConverter
    fun texteVersStatutInventaire(texte: String): StatutInventaire = StatutInventaire.valueOf(texte)

    @TypeConverter
    fun statutCommandeVersTexte(statut: StatutCommande): String = statut.name

    @TypeConverter
    fun texteVersStatutCommande(texte: String): StatutCommande = StatutCommande.valueOf(texte)

    @TypeConverter
    fun statutTransfertVersTexte(statut: StatutTransfert): String = statut.name

    @TypeConverter
    fun texteVersStatutTransfert(texte: String): StatutTransfert = StatutTransfert.valueOf(texte)

    @TypeConverter
    fun statutSessionVersTexte(valeur: StatutSession): String = valeur.name

    @TypeConverter
    fun texteVersStatutSession(texte: String): StatutSession = StatutSession.valueOf(texte)

    @TypeConverter
    fun typeOperationCaisseVersTexte(valeur: TypeOperationCaisse): String = valeur.name

    @TypeConverter
    fun texteVersTypeOperationCaisse(texte: String): TypeOperationCaisse = TypeOperationCaisse.valueOf(texte)

    @TypeConverter
    fun modePaiementVersTexte(valeur: ModePaiement): String = valeur.name

    @TypeConverter
    fun texteVersModePaiement(texte: String): ModePaiement = ModePaiement.valueOf(texte)

    @TypeConverter
    fun typeVenteVersTexte(valeur: TypeVente): String = valeur.name

    @TypeConverter
    fun texteVersTypeVente(texte: String): TypeVente = TypeVente.valueOf(texte)

    @TypeConverter
    fun statutVenteVersTexte(valeur: StatutVente): String = valeur.name

    @TypeConverter
    fun texteVersStatutVente(texte: String): StatutVente = StatutVente.valueOf(texte)

    @TypeConverter
    fun typePaiementVersTexte(valeur: TypePaiement): String = valeur.name

    @TypeConverter
    fun texteVersTypePaiement(texte: String): TypePaiement = TypePaiement.valueOf(texte)

    @TypeConverter
    fun statutDepenseVersTexte(valeur: StatutDepense): String = valeur.name

    @TypeConverter
    fun texteVersStatutDepense(texte: String): StatutDepense = StatutDepense.valueOf(texte)

    @TypeConverter
    fun sourceFondsVersTexte(valeur: SourceFonds): String = valeur.name

    @TypeConverter
    fun texteVersSourceFonds(texte: String): SourceFonds = SourceFonds.valueOf(texte)

    @TypeConverter
    fun typeOperationCoffreVersTexte(valeur: TypeOperationCoffre): String = valeur.name

    @TypeConverter
    fun texteVersTypeOperationCoffre(texte: String): TypeOperationCoffre = TypeOperationCoffre.valueOf(texte)

    @TypeConverter
    fun statutExerciceVersTexte(valeur: StatutExercice): String = valeur.name

    @TypeConverter
    fun texteVersStatutExercice(texte: String): StatutExercice = StatutExercice.valueOf(texte)

    @TypeConverter
    fun typeCompteVersTexte(valeur: TypeCompte): String = valeur.name

    @TypeConverter
    fun texteVersTypeCompte(texte: String): TypeCompte = TypeCompte.valueOf(texte)

    @TypeConverter
    fun journalVersTexte(valeur: Journal): String = valeur.name

    @TypeConverter
    fun texteVersJournal(texte: String): Journal = Journal.valueOf(texte)
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
        StockEntity::class,
        MouvementStockEntity::class,
        InventaireEntity::class,
        LigneInventaireEntity::class,
        FournisseurEntity::class,
        ConversionUniteEntity::class,
        CommandeFournisseurEntity::class,
        LigneCommandeEntity::class,
        ReceptionEntity::class,
        LigneReceptionEntity::class,
        TransfertEntity::class,
        LigneTransfertEntity::class,
        CaisseEntity::class,
        SessionCaisseEntity::class,
        OperationCaisseEntity::class,
        OperationCoffreEntity::class,
        ClientEntity::class,
        PrixPointVenteEntity::class,
        VenteEntity::class,
        LigneVenteEntity::class,
        PaiementVenteEntity::class,
        RetourVenteEntity::class,
        LigneRetourVenteEntity::class,
        CategorieDepenseEntity::class,
        DepenseEntity::class,
        PaiementFournisseurEntity::class,
        EncaissementClientEntity::class,
        RetourFournisseurEntity::class,
        LigneRetourFournisseurEntity::class,
        ExerciceEntity::class,
        CompteEntity::class,
        EcritureEntity::class,
        LigneEcritureEntity::class,
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
    abstract fun stockDao(): StockDao
    abstract fun inventaireDao(): InventaireDao
    abstract fun fournisseurDao(): FournisseurDao
    abstract fun conversionUniteDao(): ConversionUniteDao
    abstract fun commandeDao(): CommandeDao
    abstract fun receptionDao(): ReceptionDao
    abstract fun transfertDao(): TransfertDao
    abstract fun caisseDao(): CaisseDao
    abstract fun coffreDao(): CoffreDao
    abstract fun clientDao(): ClientDao
    abstract fun prixPointVenteDao(): PrixPointVenteDao
    abstract fun venteDao(): VenteDao
    abstract fun categorieDepenseDao(): CategorieDepenseDao
    abstract fun depenseDao(): DepenseDao
    abstract fun paiementFournisseurDao(): PaiementFournisseurDao
    abstract fun retourFournisseurDao(): RetourFournisseurDao
    abstract fun encaissementClientDao(): EncaissementClientDao
    abstract fun compteDao(): CompteDao
    abstract fun exerciceDao(): ExerciceDao
    abstract fun ecritureDao(): EcritureDao

    companion object {
        /**
         * À incrémenter à chaque changement de schéma, avec une migration (jamais de migration destructive),
         * dès que l'app est installée chez un marchand. Voir Claude.md §8.
         */
        const val VERSION = 1
        const val NOM_FICHIER = "tsena.db"

        /**
         * Migrations du schéma, à compléter à chaque nouvelle version (jamais de migration destructive).
         * Utilisées aussi pour accepter la restauration d'une sauvegarde plus ancienne.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        fun creer(context: Context): TsenaDatabase =
            Room.databaseBuilder(context, TsenaDatabase::class.java, NOM_FICHIER)
                .addCallback(DonneesInitiales)
                .addMigrations(*MIGRATIONS)
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

        /**
         * RG-D1 : catégories de dépenses proposées, avec leur compte de charge PCG 2005
         * (numéros à faire valider par un comptable avant le sprint S6).
         */
        private val CATEGORIES_DEPENSES = listOf(
            "Loyer" to "613",
            "Eau et électricité" to "6061",
            "Transport" to "624",
            "Téléphone et internet" to "626",
            "Salaires" to "641",
            "Entretien et réparations" to "615",
            "Fournitures de bureau" to "6064",
            "Frais bancaires et mobile money" to "627",
            "Impôts et taxes" to "635",
            "Divers" to "628",
        )

        /**
         * RG-K3 : comptes de base du PCG 2005 utilisés par l'app (numéro, libellé, type). La classe est le premier
         * chiffre. Numéros à faire valider par un comptable malgache.
         */
        private val PLAN_COMPTABLE = listOf(
            Triple("101", "Capital", TypeCompte.CAPITAUX),
            Triple("108", "Compte de l'exploitant", TypeCompte.CAPITAUX),
            Triple("120", "Résultat net : bénéfice", TypeCompte.CAPITAUX),
            Triple("129", "Résultat net : perte", TypeCompte.CAPITAUX),
            Triple("218", "Autres immobilisations corporelles", TypeCompte.ACTIF),
            Triple("37", "Stocks de marchandises", TypeCompte.ACTIF),
            Triple("401", "Fournisseurs", TypeCompte.PASSIF),
            Triple("411", "Clients", TypeCompte.ACTIF),
            Triple("421", "Personnel, rémunérations dues", TypeCompte.PASSIF),
            Triple("512", "Banques", TypeCompte.ACTIF),
            Triple("517", "Mobile money (autres organismes financiers)", TypeCompte.ACTIF),
            Triple("531", "Caisse des points de vente", TypeCompte.ACTIF),
            Triple("532", "Coffre", TypeCompte.ACTIF),
            Triple("6037", "Variation des stocks de marchandises", TypeCompte.CHARGE),
            Triple("6061", "Fournitures non stockables (eau, énergie)", TypeCompte.CHARGE),
            Triple("6064", "Fournitures administratives", TypeCompte.CHARGE),
            Triple("607", "Achats de marchandises", TypeCompte.CHARGE),
            Triple("613", "Locations", TypeCompte.CHARGE),
            Triple("615", "Entretien et réparations", TypeCompte.CHARGE),
            Triple("624", "Transports de biens", TypeCompte.CHARGE),
            Triple("626", "Frais postaux et de télécommunications", TypeCompte.CHARGE),
            Triple("627", "Services bancaires et assimilés", TypeCompte.CHARGE),
            Triple("628", "Charges externes diverses", TypeCompte.CHARGE),
            Triple("635", "Autres impôts, taxes et versements assimilés", TypeCompte.CHARGE),
            Triple("658", "Charges diverses de gestion courante", TypeCompte.CHARGE),
            Triple("641", "Rémunérations du personnel", TypeCompte.CHARGE),
            Triple("707", "Ventes de marchandises", TypeCompte.PRODUIT),
            Triple("758", "Produits divers de gestion courante", TypeCompte.PRODUIT),
        )

        private object DonneesInitiales : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val date = maintenant()
                PLAN_COMPTABLE.forEach { (numero, libelle, type) ->
                    db.execSQL(
                        "INSERT INTO compte (id, numero, libelle, classe, type, de_base, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)",
                        arrayOf<Any>(nouvelId(), numero, libelle, numero.first().digitToInt(), type.name, date),
                    )
                }
                CATEGORIES_DEPENSES.forEach { (nom, compte) ->
                    db.execSQL(
                        "INSERT INTO categorie_depense (id, nom, compte_numero, actif, created_at, updated_at) VALUES (?, ?, ?, 1, ?, ?)",
                        arrayOf<Any>(nouvelId(), nom, compte, date, date),
                    )
                }
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
