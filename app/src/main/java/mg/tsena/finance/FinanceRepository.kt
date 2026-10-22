package mg.tsena.finance

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Comptable
import mg.tsena.compta.PieceComptable
import mg.tsena.compta.ReglesComptables
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.CategorieDepenseEntity
import mg.tsena.data.db.entity.DepenseAvecDetails
import mg.tsena.data.db.entity.DepenseEntity
import mg.tsena.data.db.entity.EncaissementClientEntity
import mg.tsena.data.db.entity.FinancementAvecSolde
import mg.tsena.data.db.entity.FinancementEntity
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationCaisseEntity
import mg.tsena.data.db.entity.PaiementFournisseurEntity
import mg.tsena.data.db.entity.ReceptionAPayer
import mg.tsena.data.db.entity.RemboursementEmpruntEntity
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.SourceFonds
import mg.tsena.data.db.entity.StatutDepense
import mg.tsena.data.db.entity.StatutFinancement
import mg.tsena.data.db.entity.StatutSession
import mg.tsena.data.db.entity.TotalCategorie
import mg.tsena.data.db.entity.TypeFinancement
import mg.tsena.data.db.entity.TypeOperationCaisse
import mg.tsena.data.db.entity.TypeOperationCoffre
import mg.tsena.data.db.entity.VenteAvecDetails
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.maintenant
import mg.tsena.vente.CalculsVente
import java.time.YearMonth

/** Modes de règlement des sorties et rentrées d'argent hors vente. */
val MODES_REGLEMENT = listOf(ModePaiement.ESPECES, ModePaiement.MOBILE_MONEY, ModePaiement.BANQUE)

/**
 * Charges et dépenses (RG-D1 à RG-D3), paiements fournisseurs (RG-A3), remboursements des crédits clients,
 * injections de capital et emprunts (RG-F1 à RG-F5).
 */
class FinanceRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val comptable: Comptable,
    private val coffre: CoffreRepository,
) {
    private val categorieDao = db.categorieDepenseDao()
    private val depenseDao = db.depenseDao()
    private val paiementDao = db.paiementFournisseurDao()
    private val encaissementDao = db.encaissementClientDao()
    private val caisseDao = db.caisseDao()
    private val financementDao = db.financementDao()

    /** Caisses ouvertes des points de vente visibles par l'utilisateur (RG-X2). */
    fun observerCaissesOuvertes(): Flow<List<SessionAvecDetails>> =
        caisseDao.observerSessionsOuvertes().map { liste ->
            val u = session.utilisateur.value
            liste.filter { u != null && u.voitPointVente(it.pointVenteId) }
        }

    // --- Catégories de dépenses (RG-D1) ---

    fun observerCategories(): Flow<List<CategorieDepenseEntity>> = categorieDao.observer()

    suspend fun enregistrerCategorie(id: String?, nom: String, compteNumero: String, actif: Boolean) {
        val auteur = session.exiger(Permission.GERER_DEPENSES)
        val nomPropre = nom.trim()
        val compte = compteNumero.trim()
        if (nomPropre.isEmpty()) throw RegleMetierException("Le nom de la catégorie est obligatoire.")
        if (!compte.startsWith("6") || compte.length < 2 || !compte.all { it.isDigit() }) {
            throw RegleMetierException("Le compte doit être un compte de charge de la classe 6 (ex. 613).")
        }
        db.withTransaction {
            if (db.compteDao().get(compte) == null) {
                throw RegleMetierException("Le compte $compte n'existe pas : créez-le d'abord dans le plan comptable.")
            }
            if (categorieDao.compterParNom(nomPropre, id ?: "") > 0) throw RegleMetierException("La catégorie « $nomPropre » existe déjà.")
            if (id == null) {
                categorieDao.inserer(CategorieDepenseEntity(nom = nomPropre, compteNumero = compte, createdBy = auteur.id))
            } else {
                val existante = categorieDao.get(id) ?: throw RegleMetierException("Catégorie introuvable.")
                categorieDao.modifier(existante.copy(nom = nomPropre, compteNumero = compte, actif = actif, updatedAt = maintenant()))
            }
            audit.enregistrer(ActionAudit.CATEGORIE_DEPENSE, "$nomPropre ($compte)")
        }
    }

    // --- Dépenses (RG-D2, RG-D3) ---

    fun observerDepenses(mois: YearMonth): Flow<List<DepenseAvecDetails>> {
        val (debut, fin) = Echeances.bornesMois(mois)
        return depenseDao.observerPeriode(debut, fin)
    }

    fun observerTotauxDepenses(mois: YearMonth): Flow<List<TotalCategorie>> {
        val (debut, fin) = Echeances.bornesMois(mois)
        return depenseDao.observerTotaux(debut, fin)
    }

    suspend fun seuilJustificatif(): Long = db.commerceDao().get()?.seuilJustificatif ?: 0

    /** RG-D3 : propriétaire uniquement. */
    suspend fun definirSeuilJustificatif(seuil: Long) {
        session.exiger(Permission.PARAMETRER_COMMERCE)
        if (seuil < 0) throw RegleMetierException("Le seuil ne peut pas être négatif.")
        db.commerceDao().modifierSeuilJustificatif(seuil, maintenant())
        audit.enregistrer(ActionAudit.PARAMETRE_JUSTIFICATIF, Montant.formater(seuil))
    }

    /**
     * Enregistre une dépense. Espèces / mobile money : décaissement dans la caisse ouverte de [pointVenteId] (RG-D2) ;
     * banque / hors caisse : aucune opération de caisse. Justificatif obligatoire au-delà du seuil (RG-D3).
     * Écriture : compte de charge de la catégorie / trésorerie.
     */
    suspend fun enregistrerDepense(
        categorieId: String,
        montant: Long,
        description: String,
        mode: ModePaiement,
        pointVenteId: String?,
        justificatif: String?,
        source: SourceFonds = SourceFonds.CAISSE,
    ): String {
        val auteur = session.exiger(Permission.GERER_DEPENSES)
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        val texte = description.trim().ifEmpty { throw RegleMetierException("Décrivez la dépense.") }
        return db.withTransaction {
            val categorie = categorieDao.get(categorieId) ?: throw RegleMetierException("Choisissez une catégorie.")
            if (!categorie.actif) throw RegleMetierException("La catégorie « ${categorie.nom} » est désactivée.")
            val seuil = db.commerceDao().get()?.seuilJustificatif ?: 0
            if (justificatif == null && montant > seuil) {
                throw RegleMetierException("Au-delà de ${Montant.formater(seuil)}, la photo du justificatif est obligatoire.")
            }
            val depense = DepenseEntity(
                numero = numero(PREFIXE_DEPENSE, depenseDao.compter() + 1),
                categorieId = categorie.id,
                pointVenteId = pointVenteId,
                montant = montant,
                modePaiement = mode,
                source = sourceEffective(mode, source),
                justificatifChemin = justificatif,
                description = texte,
                createdBy = auteur.id,
            )
            val sessionId = sortirFonds(
                depense.source, pointVenteId, mode, montant,
                DOCUMENT_DEPENSE, depense.id, "Dépense ${depense.numero} : ${categorie.nom}", auteur.id,
            )
            depenseDao.inserer(depense.copy(sessionCaisseId = sessionId))
            comptable.passer(
                ReglesComptables.journalReglement(mode), "Dépense ${depense.numero} : ${categorie.nom}",
                ReglesComptables.depense(montant, categorie.compteNumero, mode, depense.source), PieceComptable(DOCUMENT_DEPENSE, depense.id),
            )
            audit.enregistrer(ActionAudit.DEPENSE, "${depense.numero} : ${categorie.nom}, ${Montant.formater(montant)}")
            depense.id
        }
    }

    /** Annulation : l'argent revient dans la même caisse si elle est encore ouverte ; hors caisse, toujours possible. */
    suspend fun annulerDepense(id: String, motif: String) {
        val auteur = session.exiger(Permission.GERER_DEPENSES)
        val texte = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif de l'annulation.") }
        db.withTransaction {
            val depense = depenseDao.get(id) ?: throw RegleMetierException("Dépense introuvable.")
            if (depense.statut == StatutDepense.ANNULEE) throw RegleMetierException("Cette dépense est déjà annulée.")
            if (depense.source == SourceFonds.COFFRE) {
                coffre.entrer(
                    depense.modePaiement, depense.montant, TypeOperationCoffre.REMBOURSEMENT,
                    "Annulation dépense ${depense.numero}", auteur.id, null, DOCUMENT_DEPENSE, depense.id,
                )
            }
            depense.sessionCaisseId?.let { sessionId ->
                val s = caisseDao.getSession(sessionId)
                if (s?.statut != StatutSession.OUVERTE) {
                    throw RegleMetierException("La caisse de cette dépense est clôturée : elle ne peut plus être annulée.")
                }
                caisseDao.insererOperation(
                    OperationCaisseEntity(
                        sessionId = sessionId,
                        type = TypeOperationCaisse.ENCAISSEMENT,
                        mode = depense.modePaiement,
                        montant = depense.montant,
                        documentType = DOCUMENT_DEPENSE,
                        documentId = depense.id,
                        libelle = "Annulation dépense ${depense.numero}",
                        createdBy = auteur.id,
                    )
                )
            }
            val date = maintenant()
            depenseDao.modifier(
                depense.copy(statut = StatutDepense.ANNULEE, dateAnnulation = date, annuleePar = auteur.id, motifAnnulation = texte, updatedAt = date)
            )
            comptable.contrePasser(PieceComptable(DOCUMENT_DEPENSE, depense.id), "Annulation dépense ${depense.numero}")
            audit.enregistrer(ActionAudit.ANNULATION_DEPENSE, "${depense.numero} : $texte")
        }
    }

    // --- Paiements fournisseurs (RG-A3) ---

    fun observerPaiementsFournisseur(fournisseurId: String): Flow<List<PaiementFournisseurEntity>> =
        paiementDao.observerParFournisseur(fournisseurId)

    fun observerReceptionsAPayer(fournisseurId: String): Flow<List<ReceptionAPayer>> = paiementDao.observerReceptions(fournisseurId)

    fun observerReceptionAPayer(receptionId: String): Flow<ReceptionAPayer?> = paiementDao.observerReception(receptionId)

    /**
     * Paiement d'un fournisseur : rattaché à [receptionId] (au plus son reste à payer) ou en acompte général
     * (au plus le solde dû). Espèces / mobile money : décaissement dans la caisse ouverte de [pointVenteId].
     * Renvoie le paiement enregistré.
     */
    suspend fun payerFournisseur(
        fournisseurId: String,
        receptionId: String?,
        montant: Long,
        mode: ModePaiement,
        pointVenteId: String?,
        reference: String,
        source: SourceFonds = SourceFonds.CAISSE,
    ): PaiementFournisseurEntity {
        val auteur = session.exiger(Permission.GERER_DEPENSES)
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        return db.withTransaction {
            val fournisseur = db.fournisseurDao().get(fournisseurId) ?: throw RegleMetierException("Fournisseur introuvable.")
            val solde = paiementDao.solde(fournisseurId)
            if (montant > solde) throw RegleMetierException("Le solde dû à ${fournisseur.nom} n'est que de ${Montant.formater(solde)}.")
            if (receptionId != null) {
                val r = paiementDao.reception(receptionId) ?: throw RegleMetierException("Réception introuvable.")
                if (r.reception.fournisseurId != fournisseurId) throw RegleMetierException("Cette réception est d'un autre fournisseur.")
                if (montant > r.reste) {
                    throw RegleMetierException("Il ne reste que ${Montant.formater(r.reste)} à payer sur ${r.reception.numero}.")
                }
            }
            val paiement = PaiementFournisseurEntity(
                numero = numero(PREFIXE_PAIEMENT, paiementDao.compter() + 1),
                fournisseurId = fournisseurId,
                receptionId = receptionId,
                montant = montant,
                modePaiement = mode,
                reference = reference.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            val sourceReelle = sourceEffective(mode, source)
            val sessionId = sortirFonds(
                sourceReelle, pointVenteId, mode, montant,
                DOCUMENT_PAIEMENT_FOURNISSEUR, paiement.id, "Paiement ${fournisseur.nom} (${paiement.numero})", auteur.id,
            )
            val enregistre = paiement.copy(sessionCaisseId = sessionId)
            paiementDao.inserer(enregistre)
            comptable.passer(
                ReglesComptables.journalReglement(mode), "Paiement ${fournisseur.nom} (${paiement.numero})",
                ReglesComptables.paiementFournisseur(montant, mode, fournisseurId, sourceReelle), PieceComptable(DOCUMENT_PAIEMENT_FOURNISSEUR, paiement.id),
            )
            audit.enregistrer(ActionAudit.PAIEMENT_FOURNISSEUR, "${paiement.numero} : ${fournisseur.nom}, ${Montant.formater(montant)}")
            enregistre
        }
    }

    // --- Remboursements des crédits clients ---

    fun observerEncaissementsClient(clientId: String): Flow<List<EncaissementClientEntity>> = encaissementDao.observerParClient(clientId)

    fun observerVentesClient(clientId: String): Flow<List<VenteAvecDetails>> = db.venteDao().observerVentesClient(clientId)

    /** Le client rembourse tout ou partie de son crédit (au plus le crédit dû). */
    suspend fun encaisserClient(clientId: String, montant: Long, mode: ModePaiement, pointVenteId: String?, reference: String) {
        val auteur = session.exiger(Permission.GERER_CAISSE)
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        db.withTransaction {
            val client = db.clientDao().get(clientId) ?: throw RegleMetierException("Client introuvable.")
            val du = encaissementDao.creditDu(clientId)
            if (montant > du) throw RegleMetierException("${client.nom} ne doit que ${Montant.formater(du)}.")
            val encaissement = EncaissementClientEntity(
                numero = numero(PREFIXE_ENCAISSEMENT, encaissementDao.compter() + 1),
                clientId = clientId,
                montant = montant,
                modePaiement = mode,
                reference = reference.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            val sessionId = mouvementCaisse(
                pointVenteId, mode, TypeOperationCaisse.ENCAISSEMENT, montant,
                DOCUMENT_ENCAISSEMENT, encaissement.id, "Remboursement ${client.nom} (${encaissement.numero})", auteur.id,
            )
            encaissementDao.inserer(encaissement.copy(sessionCaisseId = sessionId))
            comptable.passer(
                ReglesComptables.journalReglement(mode), "Remboursement ${client.nom} (${encaissement.numero})",
                ReglesComptables.reglementClient(montant, mode, clientId), PieceComptable(DOCUMENT_ENCAISSEMENT, encaissement.id),
            )
            audit.enregistrer(ActionAudit.ENCAISSEMENT_CLIENT, "${encaissement.numero} : ${client.nom}, ${Montant.formater(montant)}")
        }
    }

    /** Le coffre ne concerne que les espèces et le mobile money ; un règlement hors caisse n'a pas de source. */
    private fun sourceEffective(mode: ModePaiement, source: SourceFonds) =
        if (mode == ModePaiement.BANQUE) SourceFonds.CAISSE else source

    /**
     * Sortie d'argent d'une dépense ou d'un paiement : du coffre (refusée si son solde est insuffisant) ou de la
     * caisse ouverte. Renvoie l'id de la session de caisse utilisée, null pour le coffre et le hors caisse.
     */
    private suspend fun sortirFonds(
        source: SourceFonds,
        pointVenteId: String?,
        mode: ModePaiement,
        montant: Long,
        documentType: String,
        documentId: String,
        libelle: String,
        auteurId: String,
    ): String? {
        if (source == SourceFonds.COFFRE) {
            coffre.sortir(mode, montant, TypeOperationCoffre.DECAISSEMENT, libelle, auteurId, documentType, documentId)
            return null
        }
        return mouvementCaisse(pointVenteId, mode, TypeOperationCaisse.DECAISSEMENT, montant, documentType, documentId, libelle, auteurId)
    }

    // --- Injections de capital et emprunts (RG-F1 à RG-F5) ---

    fun observerFinancements(): Flow<List<FinancementAvecSolde>> = financementDao.observerAvecSoldes()

    fun observerRemboursements(financementId: String): Flow<List<RemboursementEmpruntEntity>> =
        financementDao.observerRemboursements(financementId)

    /**
     * Argent apporté au commerce. Capital : trésorerie / 101. Emprunt : trésorerie / 164 (banque, microfinance si
     * [etablissementCredit]) ou 168 (famille, ami, autre). Espèces / mobile money : entrée au coffre ou dans la caisse
     * ouverte de [pointVenteId] selon [source] ; banque : ni l'un ni l'autre.
     */
    suspend fun enregistrerFinancement(
        type: TypeFinancement,
        tiers: String,
        etablissementCredit: Boolean,
        montant: Long,
        mode: ModePaiement,
        pointVenteId: String?,
        echeance: Long?,
        description: String,
        source: SourceFonds = SourceFonds.COFFRE,
    ): String {
        val auteur = session.exiger(Permission.GERER_FINANCEMENT)
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        val nom = tiers.trim().ifEmpty {
            if (type == TypeFinancement.EMPRUNT) throw RegleMetierException("Indiquez le prêteur.") else "Propriétaire"
        }
        if (type == TypeFinancement.EMPRUNT && echeance != null && echeance < maintenant()) {
            throw RegleMetierException("L'échéance de remboursement doit être dans le futur.")
        }
        val compte = when {
            type == TypeFinancement.CAPITAL -> ReglesComptables.CAPITAL
            etablissementCredit -> ReglesComptables.EMPRUNTS_ETABLISSEMENTS
            else -> ReglesComptables.EMPRUNTS_AUTRES
        }
        return db.withTransaction {
            val financement = FinancementEntity(
                numero = numero(PREFIXE_FINANCEMENT, financementDao.compter() + 1),
                type = type,
                tiers = nom,
                compteNumero = compte,
                montant = montant,
                modePaiement = mode,
                source = sourceEffective(mode, source),
                echeance = if (type == TypeFinancement.EMPRUNT) echeance else null,
                description = description.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            val libelle = libelleFinancement(financement)
            val sessionId = if (financement.source == SourceFonds.COFFRE && mode != ModePaiement.BANQUE) {
                coffre.entrer(mode, montant, TypeOperationCoffre.APPORT, libelle, auteur.id, null, DOCUMENT_FINANCEMENT, financement.id)
                null
            } else {
                mouvementCaisse(pointVenteId, mode, TypeOperationCaisse.APPORT, montant, DOCUMENT_FINANCEMENT, financement.id, libelle, auteur.id)
            }
            financementDao.inserer(financement.copy(sessionCaisseId = sessionId))
            comptable.passer(
                ReglesComptables.journalReglement(mode), libelle,
                ReglesComptables.financement(montant, compte, mode, financement.source), PieceComptable(DOCUMENT_FINANCEMENT, financement.id),
            )
            audit.enregistrer(
                if (type == TypeFinancement.CAPITAL) ActionAudit.INJECTION_CAPITAL else ActionAudit.EMPRUNT,
                "${financement.numero} : $nom, ${Montant.formater(montant)}",
            )
            financement.id
        }
    }

    /**
     * RG-F3 : remboursement d'un emprunt. [capital] au plus le reste dû ; [interets] en charge 661, sans limite.
     * Deux écritures : le capital (flux de financement) et les intérêts (flux opérationnel).
     */
    suspend fun rembourserEmprunt(
        financementId: String,
        capital: Long,
        interets: Long,
        mode: ModePaiement,
        pointVenteId: String?,
        reference: String,
        source: SourceFonds = SourceFonds.COFFRE,
    ) {
        val auteur = session.exiger(Permission.GERER_FINANCEMENT)
        if (capital < 0 || interets < 0) throw RegleMetierException("Les montants ne peuvent pas être négatifs.")
        if (capital + interets <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        db.withTransaction {
            val f = financementDao.getAvecSolde(financementId) ?: throw RegleMetierException("Emprunt introuvable.")
            val emprunt = f.financement
            if (emprunt.type != TypeFinancement.EMPRUNT) throw RegleMetierException("Une injection de capital ne se rembourse pas.")
            if (emprunt.statut != StatutFinancement.VALIDE) throw RegleMetierException("Cet emprunt est annulé.")
            if (capital > f.reste) throw RegleMetierException("Il ne reste que ${Montant.formater(f.reste)} à rembourser à ${emprunt.tiers}.")
            val remboursement = RemboursementEmpruntEntity(
                numero = numero(PREFIXE_REMBOURSEMENT, financementDao.compterTousRemboursements() + 1),
                financementId = emprunt.id,
                capital = capital,
                interets = interets,
                modePaiement = mode,
                source = sourceEffective(mode, source),
                reference = reference.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            val libelle = "Remboursement ${emprunt.tiers} (${remboursement.numero})"
            val sessionId = sortirFonds(
                remboursement.source, pointVenteId, mode, capital + interets,
                DOCUMENT_REMBOURSEMENT_EMPRUNT, remboursement.id, libelle, auteur.id,
            )
            financementDao.insererRemboursement(remboursement.copy(sessionCaisseId = sessionId))
            val piece = PieceComptable(DOCUMENT_REMBOURSEMENT_EMPRUNT, remboursement.id)
            val journal = ReglesComptables.journalReglement(mode)
            val source = remboursement.source
            comptable.passer(journal, libelle, ReglesComptables.remboursementEmprunt(capital, emprunt.compteNumero, mode, source), piece)
            comptable.passer(
                journal, "Intérêts ${emprunt.tiers} (${remboursement.numero})", ReglesComptables.interetsEmprunt(interets, mode, source), piece,
            )
            audit.enregistrer(
                ActionAudit.REMBOURSEMENT_EMPRUNT,
                "${remboursement.numero} : ${emprunt.tiers}, capital ${Montant.formater(capital)}, intérêts ${Montant.formater(interets)}",
            )
        }
    }

    /**
     * RG-F4 : annulation d'une saisie erronée, sans remboursement enregistré. L'argent ressort du coffre (s'il en
     * contient assez) ou de la même caisse si elle est encore ouverte ; hors caisse, toujours possible.
     */
    suspend fun annulerFinancement(id: String, motif: String) {
        val auteur = session.exiger(Permission.GERER_FINANCEMENT)
        val texte = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif de l'annulation.") }
        db.withTransaction {
            val f = financementDao.getAvecSolde(id)?.financement ?: throw RegleMetierException("Financement introuvable.")
            if (f.statut == StatutFinancement.ANNULE) throw RegleMetierException("${f.numero} est déjà annulé.")
            if (financementDao.compterRemboursements(f.id) > 0) {
                throw RegleMetierException("Des remboursements sont déjà enregistrés sur cet emprunt : il ne peut plus être annulé.")
            }
            if (f.source == SourceFonds.COFFRE && f.modePaiement != ModePaiement.BANQUE) {
                coffre.sortir(f.modePaiement, f.montant, TypeOperationCoffre.RETRAIT, "Annulation ${f.numero}", auteur.id, DOCUMENT_FINANCEMENT, f.id)
            }
            f.sessionCaisseId?.let { sessionId ->
                val s = caisseDao.getSession(sessionId)?.takeIf { it.statut == StatutSession.OUVERTE }
                    ?: throw RegleMetierException("La caisse de ${f.numero} est clôturée : il ne peut plus être annulé.")
                val disponible = CalculsVente.soldeTheorique(
                    if (f.modePaiement == ModePaiement.ESPECES) s.fondInitial else 0,
                    caisseDao.operations(s.id).filter { it.mode == f.modePaiement }.map { it.type to it.montant },
                )
                if (f.montant > disponible) {
                    throw RegleMetierException("Il n'y a que ${Montant.formater(disponible)} (${f.modePaiement.libelle.lowercase()}) dans la caisse.")
                }
                caisseDao.insererOperation(
                    OperationCaisseEntity(
                        sessionId = sessionId,
                        type = TypeOperationCaisse.RETRAIT,
                        mode = f.modePaiement,
                        montant = f.montant,
                        documentType = DOCUMENT_FINANCEMENT,
                        documentId = f.id,
                        libelle = "Annulation ${f.numero}",
                        createdBy = auteur.id,
                    )
                )
            }
            val date = maintenant()
            financementDao.modifier(
                f.copy(statut = StatutFinancement.ANNULE, dateAnnulation = date, annulePar = auteur.id, motifAnnulation = texte, updatedAt = date)
            )
            comptable.contrePasser(PieceComptable(DOCUMENT_FINANCEMENT, f.id), "Annulation ${f.numero}")
            audit.enregistrer(ActionAudit.ANNULATION_FINANCEMENT, "${f.numero} : $texte")
        }
    }

    private fun libelleFinancement(f: FinancementEntity) = when (f.type) {
        TypeFinancement.CAPITAL -> "Injection de capital ${f.tiers} (${f.numero})"
        TypeFinancement.EMPRUNT -> "Emprunt ${f.tiers} (${f.numero})"
    }

    /**
     * Opération dans la caisse ouverte de [pointVenteId] pour un règlement en espèces ou mobile money ;
     * aucune pour la banque / hors caisse. Une sortie d'espèces ne peut pas dépasser les espèces en caisse.
     * Renvoie l'id de la session utilisée.
     */
    private suspend fun mouvementCaisse(
        pointVenteId: String?,
        mode: ModePaiement,
        type: TypeOperationCaisse,
        montant: Long,
        documentType: String,
        documentId: String,
        libelle: String,
        auteurId: String,
    ): String? {
        if (mode !in MODES_REGLEMENT) throw RegleMetierException("Mode de règlement non accepté : ${mode.libelle}.")
        if (mode == ModePaiement.BANQUE) return null
        val pv = pointVenteId?.let { db.lieuDao().getPointVente(it) } ?: throw RegleMetierException("Choisissez la caisse utilisée.")
        val u = session.utilisateur.value ?: throw RegleMetierException("Aucun utilisateur connecté.")
        if (!u.voitPointVente(pv.id)) throw RegleMetierException("Vous n'êtes pas rattaché au point de vente « ${pv.nom} ».")
        val sessionCaisse = caisseDao.getParPointVente(pv.id)?.let { caisseDao.getSessionOuverte(it.id) }
            ?: throw RegleMetierException("La caisse de « ${pv.nom} » est fermée.")
        if (!type.entree) {
            // Le fond de caisse ne compte que pour les espèces ; le mobile money part de ce qui a été encaissé
            val fond = if (mode == ModePaiement.ESPECES) sessionCaisse.fondInitial else 0
            val disponible = CalculsVente.soldeTheorique(
                fond,
                caisseDao.operations(sessionCaisse.id).filter { it.mode == mode }.map { it.type to it.montant },
            )
            if (montant > disponible) {
                throw RegleMetierException("Il n'y a que ${Montant.formater(disponible)} (${mode.libelle.lowercase()}) dans la caisse.")
            }
        }
        caisseDao.insererOperation(
            OperationCaisseEntity(
                sessionId = sessionCaisse.id,
                type = type,
                mode = mode,
                montant = montant,
                documentType = documentType,
                documentId = documentId,
                libelle = libelle,
                createdBy = auteurId,
            )
        )
        return sessionCaisse.id
    }

    private fun numero(prefixe: String, rang: Int) = "$prefixe-" + rang.toString().padStart(6, '0')

    companion object {
        const val DOCUMENT_DEPENSE = "DEPENSE"
        const val DOCUMENT_PAIEMENT_FOURNISSEUR = "PAIEMENT_FOURNISSEUR"
        const val DOCUMENT_ENCAISSEMENT = "ENCAISSEMENT_CLIENT"
        private const val PREFIXE_DEPENSE = "DP"
        private const val PREFIXE_PAIEMENT = "PF"
        private const val PREFIXE_ENCAISSEMENT = "EC"
        const val DOCUMENT_FINANCEMENT = "FINANCEMENT"
        const val DOCUMENT_REMBOURSEMENT_EMPRUNT = "REMBOURSEMENT_EMPRUNT"
        private const val PREFIXE_FINANCEMENT = "FI"
        private const val PREFIXE_REMBOURSEMENT = "RE"
    }
}
