package mg.tsena.catalogue

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.ProduitAvecDetails
import mg.tsena.data.db.entity.ProduitEntity
import mg.tsena.data.db.entity.TypeProduitEntity
import mg.tsena.data.db.entity.UniteEntity
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant

/** Saisie d'un produit, déjà convertie (montant en Ariary, seuil en millièmes). */
data class SaisieProduit(
    val id: String?,
    val nom: String,
    val code: String,
    val codeBarre: String,
    val categorieId: String?,
    val uniteId: String?,
    val prixVente: Long,
    val seuilAlerteMilli: Long,
    val photoChemin: String?,
    val actif: Boolean,
)

class CatalogueRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val photos: PhotoManager,
) {
    private val categorieDao = db.typeProduitDao()
    private val uniteDao = db.uniteDao()
    private val produitDao = db.produitDao()

    fun observerCategories(): Flow<List<TypeProduitEntity>> = categorieDao.observer()

    fun observerUnites(): Flow<List<UniteEntity>> = uniteDao.observer()

    fun observerProduits(): Flow<List<ProduitAvecDetails>> = produitDao.observerAvecDetails()

    suspend fun getProduit(id: String): ProduitEntity? = produitDao.get(id)

    suspend fun getUnite(id: String): UniteEntity? = uniteDao.get(id)

    // --- Catégories ---

    /** Crée (id null) ou renomme/déplace une catégorie. Renvoie son id. */
    suspend fun enregistrerCategorie(id: String?, nom: String, parentId: String?): String {
        val auteur = session.exiger(Permission.GERER_PRODUITS)
        val nomPropre = nom.trim()
        if (nomPropre.isEmpty()) throw RegleMetierException("Le nom de la catégorie est obligatoire.")

        return db.withTransaction {
            val toutes = categorieDao.tous().associateBy { it.id }
            if (parentId != null) {
                if (parentId !in toutes) throw RegleMetierException("Catégorie parente introuvable.")
                if (id != null && Categories.creeraitUnCycle(id, parentId) { toutes[it]?.parentId }) {
                    throw RegleMetierException("Une catégorie ne peut pas être placée dans elle-même ou dans une de ses sous-catégories.")
                }
            }
            val doublon = toutes.values.any { it.id != id && it.parentId == parentId && it.nom.equals(nomPropre, ignoreCase = true) }
            if (doublon) throw RegleMetierException("Une catégorie « $nomPropre » existe déjà à cet endroit.")

            if (id == null) {
                val nouvelle = TypeProduitEntity(nom = nomPropre, parentId = parentId, createdBy = auteur.id)
                categorieDao.inserer(nouvelle)
                audit.enregistrer(ActionAudit.CREATION_CATEGORIE, nomPropre)
                nouvelle.id
            } else {
                val existante = toutes[id] ?: throw RegleMetierException("Catégorie introuvable.")
                categorieDao.modifier(existante.copy(nom = nomPropre, parentId = parentId, updatedAt = maintenant()))
                audit.enregistrer(ActionAudit.MODIFICATION_CATEGORIE, nomPropre)
                id
            }
        }
    }

    /** RG-P3 : suppression (logique) uniquement si la catégorie est vide. */
    suspend fun supprimerCategorie(id: String) {
        session.exiger(Permission.GERER_PRODUITS)
        db.withTransaction {
            val categorie = categorieDao.get(id) ?: throw RegleMetierException("Catégorie introuvable.")
            if (produitDao.compterParCategorie(id) > 0) {
                throw RegleMetierException("Impossible de supprimer « ${categorie.nom} » : elle contient des produits.")
            }
            if (categorieDao.compterEnfants(id) > 0) {
                throw RegleMetierException("Impossible de supprimer « ${categorie.nom} » : elle contient des sous-catégories.")
            }
            val date = maintenant()
            categorieDao.modifier(categorie.copy(deletedAt = date, updatedAt = date))
            audit.enregistrer(ActionAudit.SUPPRESSION_CATEGORIE, categorie.nom)
        }
    }

    // --- Unités ---

    suspend fun creerUnite(nom: String, symbole: String, decimale: Boolean) {
        val auteur = session.exiger(Permission.GERER_PRODUITS)
        val nomPropre = nom.trim()
        val symbolePropre = symbole.trim()
        if (nomPropre.isEmpty() || symbolePropre.isEmpty()) throw RegleMetierException("Le nom et le symbole sont obligatoires.")
        if (uniteDao.compterParNom(nomPropre) > 0) throw RegleMetierException("L'unité « $nomPropre » existe déjà.")
        uniteDao.inserer(UniteEntity(nom = nomPropre, symbole = symbolePropre, decimale = decimale, createdBy = auteur.id))
        audit.enregistrer(ActionAudit.CREATION_UNITE, nomPropre)
    }

    // --- Produits ---

    /** RG-P1, RG-P2 : catégorie et photo obligatoires. Renvoie l'id du produit. */
    suspend fun enregistrerProduit(saisie: SaisieProduit): String {
        val auteur = session.exiger(Permission.GERER_PRODUITS)
        val nom = saisie.nom.trim()
        val codeBarre = saisie.codeBarre.trim().ifEmpty { null }
        if (nom.isEmpty()) throw RegleMetierException("Le nom du produit est obligatoire.")
        val photo = saisie.photoChemin?.takeIf { it.isNotBlank() && photos.fichier(it).exists() }
            ?: throw RegleMetierException("La photo du produit est obligatoire.")
        if (saisie.prixVente < 0) throw RegleMetierException("Le prix de vente ne peut pas être négatif.")
        if (saisie.seuilAlerteMilli < 0) throw RegleMetierException("Le seuil d'alerte ne peut pas être négatif.")

        var ancienneSiRemplacee: String? = null
        val id = db.withTransaction {
            val categorieId = saisie.categorieId?.takeIf { categorieDao.get(it) != null }
                ?: throw RegleMetierException("Choisissez une catégorie.")
            val uniteId = saisie.uniteId?.takeIf { uniteDao.get(it) != null }
                ?: throw RegleMetierException("Choisissez une unité.")
            val idCible = saisie.id ?: ""
            val code = saisie.code.trim().uppercase().ifEmpty { Categories.prochainCode(produitDao.tousLesCodes()) }
            if (produitDao.compterParCode(code, idCible) > 0) throw RegleMetierException("Le code « $code » est déjà utilisé.")
            if (codeBarre != null && produitDao.compterParCodeBarre(codeBarre, idCible) > 0) {
                throw RegleMetierException("Ce code-barre est déjà utilisé par un autre produit.")
            }

            if (saisie.id == null) {
                val produit = ProduitEntity(
                    code = code,
                    codeBarre = codeBarre,
                    nom = nom,
                    typeProduitId = categorieId,
                    uniteId = uniteId,
                    prixVente = saisie.prixVente,
                    seuilAlerteMilli = saisie.seuilAlerteMilli,
                    photoChemin = photo,
                    createdBy = auteur.id,
                )
                produitDao.inserer(produit)
                audit.enregistrer(ActionAudit.CREATION_PRODUIT, "$code $nom")
                produit.id
            } else {
                val existant = produitDao.get(saisie.id) ?: throw RegleMetierException("Produit introuvable.")
                produitDao.modifier(
                    existant.copy(
                        code = code,
                        codeBarre = codeBarre,
                        nom = nom,
                        typeProduitId = categorieId,
                        uniteId = uniteId,
                        prixVente = saisie.prixVente,
                        seuilAlerteMilli = saisie.seuilAlerteMilli,
                        photoChemin = photo,
                        actif = saisie.actif,
                        updatedAt = maintenant(),
                    )
                )
                if (existant.photoChemin != photo) ancienneSiRemplacee = existant.photoChemin
                audit.enregistrer(ActionAudit.MODIFICATION_PRODUIT, "$code $nom")
                existant.id
            }
        }
        // Après validation seulement : l'ancienne photo n'est plus référencée
        photos.supprimer(ancienneSiRemplacee)
        return id
    }
}
