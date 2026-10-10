package mg.tsena.lieux

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.PointVenteEntity
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

/** Dépôts (salles de stockage) et points de vente. */
class LieuRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
) {
    private val dao = db.lieuDao()

    fun observerDepots(): Flow<List<DepotEntity>> = dao.observerDepots()

    fun observerPointsVente(): Flow<List<PointVenteAvecDepot>> = dao.observerPointsVente()

    suspend fun getDepot(id: String): DepotEntity? = dao.getDepot(id)

    suspend fun enregistrerDepot(id: String?, nom: String, adresse: String, actif: Boolean) {
        val auteur = session.exiger(Permission.GERER_REFERENTIEL)
        val nomPropre = nom.trim()
        if (nomPropre.isEmpty()) throw RegleMetierException("Le nom du dépôt est obligatoire.")
        db.withTransaction {
            if (dao.compterDepotsParNom(nomPropre, id ?: "") > 0) throw RegleMetierException("Le dépôt « $nomPropre » existe déjà.")
            if (id == null) {
                dao.insererDepot(DepotEntity(nom = nomPropre, adresse = adresse.trim().ifEmpty { null }, createdBy = auteur.id))
                audit.enregistrer(ActionAudit.CREATION_DEPOT, nomPropre)
            } else {
                val existant = dao.getDepot(id) ?: throw RegleMetierException("Dépôt introuvable.")
                if (!actif && existant.actif && dao.compterPointsVenteActifsUtilisant(id) > 0) {
                    throw RegleMetierException("Ce dépôt alimente un point de vente actif : changez d'abord le dépôt de ce point de vente.")
                }
                dao.modifierDepot(
                    existant.copy(nom = nomPropre, adresse = adresse.trim().ifEmpty { null }, actif = actif, updatedAt = maintenant())
                )
                audit.enregistrer(ActionAudit.MODIFICATION_DEPOT, nomPropre)
            }
        }
    }

    /**
     * Crée ou modifie un point de vente. Si [depotId] est null à la création,
     * un dépôt « Magasin <nom> » rattaché au point de vente est créé.
     */
    suspend fun enregistrerPointVente(id: String?, nom: String, adresse: String, depotId: String?, actif: Boolean) {
        val auteur = session.exiger(Permission.GERER_REFERENTIEL)
        val nomPropre = nom.trim()
        val adressePropre = adresse.trim().ifEmpty { null }
        if (nomPropre.isEmpty()) throw RegleMetierException("Le nom du point de vente est obligatoire.")
        db.withTransaction {
            if (dao.compterPointsVenteParNom(nomPropre, id ?: "") > 0) {
                throw RegleMetierException("Le point de vente « $nomPropre » existe déjà.")
            }
            if (depotId != null) {
                val depot = dao.getDepot(depotId) ?: throw RegleMetierException("Dépôt introuvable.")
                if (!depot.actif) throw RegleMetierException("Le dépôt « ${depot.nom} » est désactivé.")
            }

            if (id == null) {
                val pointVenteId = nouvelId()
                val depotFinal = depotId ?: creerDepotMagasin(nomPropre, adressePropre, auteur.id)
                dao.insererPointVente(
                    PointVenteEntity(id = pointVenteId, nom = nomPropre, adresse = adressePropre, depotId = depotFinal, createdBy = auteur.id)
                )
                if (depotId == null) {
                    // Le dépôt magasin est créé avant le point de vente (clé étrangère), puis rattaché
                    dao.getDepot(depotFinal)?.let { dao.modifierDepot(it.copy(pointVenteId = pointVenteId)) }
                }
                audit.enregistrer(ActionAudit.CREATION_POINT_VENTE, nomPropre)
            } else {
                val existant = dao.getPointVente(id) ?: throw RegleMetierException("Point de vente introuvable.")
                dao.modifierPointVente(
                    existant.copy(
                        nom = nomPropre,
                        adresse = adressePropre,
                        depotId = depotId ?: existant.depotId,
                        actif = actif,
                        updatedAt = maintenant(),
                    )
                )
                audit.enregistrer(ActionAudit.MODIFICATION_POINT_VENTE, nomPropre)
            }
        }
    }

    private suspend fun creerDepotMagasin(nomPointVente: String, adresse: String?, auteurId: String): String {
        var nom = "Magasin $nomPointVente"
        var suffixe = 2
        while (dao.compterDepotsParNom(nom, "") > 0) nom = "Magasin $nomPointVente (${suffixe++})"
        val depot = DepotEntity(nom = nom, adresse = adresse, createdBy = auteurId)
        dao.insererDepot(depot)
        audit.enregistrer(ActionAudit.CREATION_DEPOT, nom)
        return depot.id
    }
}
