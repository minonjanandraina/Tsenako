package mg.tsena.auth

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.CommerceEntity
import mg.tsena.data.db.entity.UtilisateurAvecRoles
import mg.tsena.data.db.entity.UtilisateurEntity
import mg.tsena.data.db.entity.UtilisateurRoleEntity
import mg.tsena.data.db.entity.rolesActifs
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Role
import mg.tsena.domain.maintenant
import mg.tsena.domain.nouvelId

sealed interface ResultatConnexion {
    data object Succes : ResultatConnexion
    data class PinIncorrect(val essaisRestants: Int) : ResultatConnexion
    data class Bloque(val secondes: Int) : ResultatConnexion
    data object Introuvable : ResultatConnexion
}

class AuthRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
) {
    private val commerceDao = db.commerceDao()
    private val utilisateurDao = db.utilisateurDao()

    private data class Echecs(val nombre: Int = 0, val bloqueJusqua: Long = 0)

    private val echecs = mutableMapOf<String, Echecs>()

    fun observerCommerce(): Flow<CommerceEntity?> = commerceDao.observer()

    fun observerUtilisateursActifs(): Flow<List<UtilisateurAvecRoles>> = utilisateurDao.observerActifs()

    fun observerTousUtilisateurs(): Flow<List<UtilisateurAvecRoles>> = utilisateurDao.observerTous()

    /** Premier lancement : crée le commerce et son propriétaire, puis le connecte. */
    suspend fun initialiser(nomCommerce: String, nomProprietaire: String, telephone: String?, pin: String) {
        val proprietaire = nouvelUtilisateur(nomProprietaire, telephone, pin, creePar = null)
        db.withTransaction {
            if (commerceDao.get() != null) throw RegleMetierException("Le commerce est déjà configuré.")
            commerceDao.inserer(CommerceEntity(nom = nomCommerce.trim(), createdBy = proprietaire.id))
            utilisateurDao.inserer(proprietaire)
            utilisateurDao.insererRole(
                UtilisateurRoleEntity(utilisateurId = proprietaire.id, role = Role.PROPRIETAIRE, createdBy = proprietaire.id)
            )
        }
        session.ouvrir(UtilisateurConnecte(proprietaire.id, proprietaire.nom, setOf(Role.PROPRIETAIRE)))
        audit.enregistrer(ActionAudit.CREATION_COMMERCE, nomCommerce.trim())
    }

    suspend fun connecter(utilisateurId: String, pin: String): ResultatConnexion {
        val debut = maintenant()
        echecs[utilisateurId]?.let {
            if (it.bloqueJusqua > debut) return ResultatConnexion.Bloque(((it.bloqueJusqua - debut) / 1000 + 1).toInt())
        }
        val trouve = utilisateurDao.getAvecRoles(utilisateurId)
        if (trouve == null || !trouve.utilisateur.actif) return ResultatConnexion.Introuvable
        val u = trouve.utilisateur

        val pinCorrect = withContext(Dispatchers.Default) { PinHasher.verifier(pin, u.pinSel, u.pinHash) }
        if (!pinCorrect) {
            audit.enregistrer(ActionAudit.ECHEC_CONNEXION, utilisateurId = u.id)
            val nombre = (echecs[u.id]?.nombre ?: 0) + 1
            return if (nombre >= ESSAIS_MAX) {
                echecs[u.id] = Echecs(bloqueJusqua = maintenant() + DUREE_BLOCAGE_MS)
                ResultatConnexion.Bloque((DUREE_BLOCAGE_MS / 1000).toInt())
            } else {
                echecs[u.id] = Echecs(nombre = nombre)
                ResultatConnexion.PinIncorrect(ESSAIS_MAX - nombre)
            }
        }

        echecs.remove(u.id)
        session.ouvrir(UtilisateurConnecte(u.id, u.nom, trouve.rolesActifs))
        audit.enregistrer(ActionAudit.CONNEXION)
        return ResultatConnexion.Succes
    }

    suspend fun deconnecter() {
        audit.enregistrer(ActionAudit.DECONNEXION)
        session.fermer()
    }

    suspend fun creerUtilisateur(nom: String, telephone: String?, role: Role, pin: String) {
        val auteur = session.exiger(Permission.GERER_UTILISATEURS)
        val utilisateur = nouvelUtilisateur(nom, telephone, pin, creePar = auteur.id)
        db.withTransaction {
            utilisateurDao.inserer(utilisateur)
            utilisateurDao.insererRole(
                UtilisateurRoleEntity(utilisateurId = utilisateur.id, role = role, createdBy = auteur.id)
            )
        }
        audit.enregistrer(ActionAudit.CREATION_UTILISATEUR, "${utilisateur.nom} (${role.libelle})")
    }

    suspend fun desactiverUtilisateur(utilisateurId: String) {
        val auteur = session.exiger(Permission.GERER_UTILISATEURS)
        if (utilisateurId == auteur.id) throw RegleMetierException("Vous ne pouvez pas désactiver votre propre compte.")
        db.withTransaction {
            val cible = utilisateurDao.getAvecRoles(utilisateurId)
                ?: throw RegleMetierException("Utilisateur introuvable.")
            if (Role.PROPRIETAIRE in cible.rolesActifs && utilisateurDao.compterActifsAvecRole(Role.PROPRIETAIRE) <= 1) {
                throw RegleMetierException("Il doit rester au moins un propriétaire actif.")
            }
            utilisateurDao.desactiver(utilisateurId, maintenant())
        }
        audit.enregistrer(ActionAudit.DESACTIVATION_UTILISATEUR, utilisateurId)
    }

    private suspend fun nouvelUtilisateur(nom: String, telephone: String?, pin: String, creePar: String?): UtilisateurEntity {
        if (nom.isBlank()) throw RegleMetierException("Le nom est obligatoire.")
        if (!PinHasher.formatValide(pin)) throw RegleMetierException("Le code PIN doit contenir 4 à 6 chiffres.")
        return withContext(Dispatchers.Default) {
            val id = nouvelId()
            val sel = PinHasher.nouveauSel()
            UtilisateurEntity(
                id = id,
                nom = nom.trim(),
                telephone = telephone?.trim()?.ifBlank { null },
                pinHash = PinHasher.hacher(pin, sel),
                pinSel = sel,
                createdBy = creePar ?: id,
            )
        }
    }

    private companion object {
        const val ESSAIS_MAX = 5
        const val DUREE_BLOCAGE_MS = 30_000L
    }
}
