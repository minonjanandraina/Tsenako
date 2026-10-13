package mg.tsena.vente

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.AuthRepository
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Comptable
import mg.tsena.compta.PieceComptable
import mg.tsena.compta.ReglesComptables
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.CaisseEntity
import mg.tsena.data.db.entity.ClientAvecCredit
import mg.tsena.data.db.entity.ClientEntity
import mg.tsena.data.db.entity.LigneRetourVenteEntity
import mg.tsena.data.db.entity.LigneVenteAvecProduit
import mg.tsena.data.db.entity.LigneVenteEntity
import mg.tsena.data.db.entity.ModePaiement
import mg.tsena.data.db.entity.OperationCaisseEntity
import mg.tsena.data.db.entity.PaiementVenteEntity
import mg.tsena.data.db.entity.PointVenteAvecDepot
import mg.tsena.data.db.entity.PointVenteEntity
import mg.tsena.data.db.entity.PrixPointVenteEntity
import mg.tsena.data.db.entity.PrixProduitPointVente
import mg.tsena.data.db.entity.ProduitVente
import mg.tsena.data.db.entity.ResumeVentes
import mg.tsena.data.db.entity.RetourVenteEntity
import mg.tsena.data.db.entity.SessionAvecDetails
import mg.tsena.data.db.entity.SessionCaisseEntity
import mg.tsena.data.db.entity.StatutSession
import mg.tsena.data.db.entity.StatutVente
import mg.tsena.data.db.entity.TypeMouvement
import mg.tsena.data.db.entity.TypeOperationCaisse
import mg.tsena.data.db.entity.TypeOperationCoffre
import mg.tsena.data.db.entity.TypeVente
import mg.tsena.data.db.entity.VenteAvecDetails
import mg.tsena.data.db.entity.VenteEntity
import mg.tsena.domain.AutorisationRequiseException
import mg.tsena.finance.CoffreRepository
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Telephone
import mg.tsena.domain.maintenant
import mg.tsena.stock.DocumentStock
import mg.tsena.stock.StockRepository
import mg.tsena.stock.Valorisation

data class LigneVenteSaisie(val produitId: String, val quantiteMilli: Long)

data class PaiementSaisi(val mode: ModePaiement, val montant: Long, val reference: String? = null)

data class SaisieVente(
    val pointVenteId: String,
    val type: TypeVente,
    val clientId: String?,
    val lignes: List<LigneVenteSaisie>,
    val remise: Long,
    val paiements: List<PaiementSaisi>,
    /** Code PIN d'un gérant / propriétaire pour une remise au-delà du seuil (RG-V5) */
    val pinAutorisation: String? = null,
)

/** Caisses (sessions, opérations), clients, prix par point de vente, ventes et retours. */
class VenteRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val auth: AuthRepository,
    private val stock: StockRepository,
    private val comptable: Comptable,
    private val coffre: CoffreRepository,
) {
    private val caisseDao = db.caisseDao()
    private val clientDao = db.clientDao()
    private val prixDao = db.prixPointVenteDao()
    private val venteDao = db.venteDao()

    /** RG-X2 : points de vente actifs visibles par l'utilisateur connecté. */
    fun observerPointsVente(): Flow<List<PointVenteAvecDepot>> =
        db.lieuDao().observerPointsVente().map { liste ->
            val u = session.utilisateur.value
            liste.filter { it.pointVente.actif && u != null && u.voitPointVente(it.pointVente.id) }
        }

    // --- Caisse (RG-C1 à RG-C5) ---

    fun observerSessionOuverte(pointVenteId: String): Flow<SessionAvecDetails?> = caisseDao.observerSessionOuverte(pointVenteId)

    fun observerSessionsFermees(pointVenteId: String): Flow<List<SessionAvecDetails>> =
        caisseDao.observerSessionsFermees(pointVenteId, LIMITE_HISTORIQUE)

    fun observerOperations(sessionId: String): Flow<List<OperationCaisseEntity>> = caisseDao.observerOperations(sessionId)

    fun observerResumeVentes(sessionId: String): Flow<ResumeVentes> = venteDao.observerResume(sessionId)

    suspend fun ouvrirCaisse(pointVenteId: String, fondInitial: Long) {
        val auteur = session.exiger(Permission.GERER_CAISSE)
        if (fondInitial < 0) throw RegleMetierException("Le fond de caisse ne peut pas être négatif.")
        db.withTransaction {
            val pv = exigerPointVente(pointVenteId)
            val caisse = caisseDao.getParPointVente(pv.id)
                ?: CaisseEntity(nom = "Caisse ${pv.nom}", pointVenteId = pv.id, createdBy = auteur.id).also { caisseDao.inserer(it) }
            if (caisseDao.getSessionOuverte(caisse.id) != null) throw RegleMetierException("La caisse de « ${pv.nom} » est déjà ouverte.")
            val nouvelle = SessionCaisseEntity(caisseId = caisse.id, utilisateurId = auteur.id, fondInitial = fondInitial)
            caisseDao.insererSession(nouvelle)
            prendreFondAuCoffre(nouvelle, pv.nom, fondInitial, auteur.id)
            audit.enregistrer(ActionAudit.OUVERTURE_CAISSE, "${pv.nom} : fond ${Montant.formater(fondInitial)}")
        }
    }

    /**
     * Le fond de caisse vient du coffre (532 / 531). Si le coffre n'a pas assez d'espèces, la différence est un
     * apport du propriétaire (531 / 108), pour que la caisse et le coffre restent justes.
     */
    private suspend fun prendreFondAuCoffre(s: SessionCaisseEntity, pointVenteNom: String, fond: Long, auteurId: String) {
        if (fond <= 0) return
        val prise = minOf(fond, coffre.solde(ModePaiement.ESPECES))
        if (prise > 0) {
            coffre.sortir(
                ModePaiement.ESPECES, prise, TypeOperationCoffre.FOND_CAISSE, "Fond de caisse $pointVenteNom", auteurId,
                DOCUMENT_SESSION_CAISSE, s.id,
            )
            comptable.passer(
                Journal.CA, "Fond de caisse $pointVenteNom", ReglesComptables.fondCaisse(prise),
                PieceComptable(DOCUMENT_SESSION_CAISSE, s.id),
            )
            audit.enregistrer(ActionAudit.FOND_CAISSE, "$pointVenteNom : ${Montant.formater(prise)} pris au coffre")
        }
        val apport = fond - prise
        if (apport > 0) {
            comptable.passer(
                Journal.CA, "Fond de caisse $pointVenteNom : apport du propriétaire", ReglesComptables.apport(apport),
                PieceComptable(DOCUMENT_SESSION_CAISSE, s.id),
            )
        }
    }

    /** Apport (+) ou retrait (−) d'espèces par le propriétaire ou le gérant. */
    suspend fun mouvementManuel(sessionId: String, type: TypeOperationCaisse, montant: Long, libelle: String) {
        val auteur = session.exiger(Permission.GERER_CAISSE)
        if (type != TypeOperationCaisse.APPORT && type != TypeOperationCaisse.RETRAIT) {
            throw RegleMetierException("Opération non autorisée.")
        }
        if (montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        val motif = libelle.trim().ifEmpty { throw RegleMetierException("Indiquez le motif.") }
        db.withTransaction {
            val s = exigerSessionOuverte(sessionId)
            if (type == TypeOperationCaisse.RETRAIT && montant > soldeEspeces(s)) {
                throw RegleMetierException("Il n'y a que ${Montant.formater(soldeEspeces(s))} en espèces dans la caisse.")
            }
            val operation = OperationCaisseEntity(
                sessionId = s.id, type = type, mode = ModePaiement.ESPECES, montant = montant, libelle = motif, createdBy = auteur.id,
            )
            caisseDao.insererOperation(operation)
            // Apport : 53 / 108 ; retrait : 108 / 53
            comptable.passer(
                Journal.CA, "${type.libelle} : $motif",
                if (type == TypeOperationCaisse.APPORT) ReglesComptables.apport(montant) else ReglesComptables.retrait(montant),
                PieceComptable(DOCUMENT_OPERATION_CAISSE, operation.id),
            )
            audit.enregistrer(
                if (type == TypeOperationCaisse.APPORT) ActionAudit.APPORT_CAISSE else ActionAudit.RETRAIT_CAISSE,
                "${Montant.formater(montant)} : $motif",
            )
        }
    }

    /** RG-C3 : clôture avec les espèces comptées ; l'écart est enregistré et comptabilisé (658 / 758). Renvoie l'écart. */
    suspend fun cloturerCaisse(sessionId: String, especesComptees: Long): Long {
        val auteur = session.exiger(Permission.GERER_CAISSE)
        if (especesComptees < 0) throw RegleMetierException("Le montant compté ne peut pas être négatif.")
        return db.withTransaction {
            val s = exigerSessionOuverte(sessionId)
            val theorique = soldeEspeces(s)
            val ecart = especesComptees - theorique
            val date = maintenant()
            caisseDao.modifierSession(
                s.copy(
                    statut = StatutSession.FERMEE,
                    fermeture = date,
                    fermePar = auteur.id,
                    soldeTheorique = theorique,
                    soldeCompte = especesComptees,
                    ecart = ecart,
                    updatedAt = date,
                )
            )
            comptable.passer(
                Journal.CA, "Écart de caisse à la clôture", ReglesComptables.ecartCaisse(ecart),
                PieceComptable(DOCUMENT_SESSION_CAISSE, s.id), date,
            )
            versementAuCoffre(s, especesComptees, auteur.id)
            audit.enregistrer(
                ActionAudit.CLOTURE_CAISSE,
                "Théorique ${Montant.formater(theorique)}, compté ${Montant.formater(especesComptees)}, écart ${Montant.formater(ecart)}",
            )
            ecart
        }
    }

    /**
     * Clôture de journée : les espèces comptées (532 / 531) et le mobile money encaissé pendant la session
     * (déjà au compte 517) entrent au coffre. La caisse est alors vide ; le prochain fond viendra du coffre.
     */
    private suspend fun versementAuCoffre(s: SessionCaisseEntity, especesComptees: Long, auteurId: String) {
        val mobileMoney = CalculsVente.soldeTheorique(
            0, caisseDao.operations(s.id).filter { it.mode == ModePaiement.MOBILE_MONEY }.map { it.type to it.montant },
        )
        if (especesComptees > 0) {
            coffre.entrer(
                ModePaiement.ESPECES, especesComptees, TypeOperationCoffre.VERSEMENT_CAISSE, "Clôture de caisse", auteurId,
                s.id, DOCUMENT_SESSION_CAISSE, s.id,
            )
            comptable.passer(
                Journal.CA, "Versement de la caisse au coffre", ReglesComptables.versementCoffre(especesComptees),
                PieceComptable(DOCUMENT_SESSION_CAISSE, s.id),
            )
        }
        if (mobileMoney > 0) {
            coffre.entrer(
                ModePaiement.MOBILE_MONEY, mobileMoney, TypeOperationCoffre.VERSEMENT_CAISSE, "Clôture de caisse", auteurId,
                s.id, DOCUMENT_SESSION_CAISSE, s.id,
            )
        }
        audit.enregistrer(
            ActionAudit.VERSEMENT_COFFRE,
            "Espèces ${Montant.formater(especesComptees)}, mobile money ${Montant.formater(maxOf(mobileMoney, 0))}",
        )
    }

    private suspend fun soldeEspeces(s: SessionCaisseEntity): Long =
        CalculsVente.soldeTheorique(
            s.fondInitial,
            caisseDao.operations(s.id).filter { it.mode == ModePaiement.ESPECES }.map { it.type to it.montant },
        )

    // --- Clients (RG-V7, RG-V8) ---

    fun observerClients(): Flow<List<ClientAvecCredit>> = clientDao.observerAvecCredit()

    /** Renvoie l'id du client. */
    suspend fun enregistrerClient(id: String?, nom: String, telephone: String): String {
        val auteur = session.exiger(Permission.VENDRE)
        val nomPropre = nom.trim()
        if (nomPropre.isEmpty()) throw RegleMetierException("Le nom du client est obligatoire.")
        val tel = Telephone.normaliser(telephone)
            ?: throw RegleMetierException("Numéro de téléphone invalide (10 chiffres commençant par 0, ou +261).")
        return db.withTransaction {
            clientDao.getParTelephone(tel, id ?: "")?.let {
                throw RegleMetierException("Ce numéro est déjà celui du client « ${it.nom} ».")
            }
            if (id == null) {
                val client = ClientEntity(nom = nomPropre, telephone = tel, createdBy = auteur.id)
                clientDao.inserer(client)
                audit.enregistrer(ActionAudit.CREATION_CLIENT, nomPropre)
                client.id
            } else {
                val existant = clientDao.get(id) ?: throw RegleMetierException("Client introuvable.")
                clientDao.modifier(existant.copy(nom = nomPropre, telephone = tel, updatedAt = maintenant()))
                audit.enregistrer(ActionAudit.MODIFICATION_CLIENT, nomPropre)
                id
            }
        }
    }

    // --- Prix par point de vente (RG-V1) ---

    fun observerPrixPointVente(pointVenteId: String): Flow<List<PrixProduitPointVente>> = prixDao.observer(pointVenteId)

    /** null = prix du produit. */
    suspend fun definirPrixPointVente(pointVenteId: String, produitId: String, prixDetail: Long?, prixGros: Long?) {
        val auteur = session.exiger(Permission.GERER_PRODUITS)
        if ((prixDetail ?: 0) < 0 || (prixGros ?: 0) < 0) throw RegleMetierException("Un prix ne peut pas être négatif.")
        db.withTransaction {
            val pv = exigerPointVente(pointVenteId)
            val produit = db.produitDao().get(produitId) ?: throw RegleMetierException("Produit introuvable.")
            val existant = prixDao.get(pv.id, produitId)
            if (existant == null) {
                prixDao.inserer(
                    PrixPointVenteEntity(pointVenteId = pv.id, produitId = produitId, prixDetail = prixDetail, prixGros = prixGros, createdBy = auteur.id)
                )
            } else {
                prixDao.modifier(existant.copy(prixDetail = prixDetail, prixGros = prixGros, updatedAt = maintenant()))
            }
            audit.enregistrer(
                ActionAudit.PRIX_POINT_VENTE,
                "${produit.nom} (${pv.nom}) : détail ${prixDetail?.let(Montant::formater) ?: "par défaut"}, " +
                    "gros ${prixGros?.let(Montant::formater) ?: "par défaut"}",
            )
        }
    }

    // --- Ventes ---

    fun observerCatalogue(pointVenteId: String, depotId: String): Flow<List<ProduitVente>> =
        venteDao.observerCatalogue(pointVenteId, depotId)

    /** Ventes d'un point de vente, ou de tous ([pointVenteId] null) si l'utilisateur les voit tous. */
    fun observerVentes(pointVenteId: String?): Flow<List<VenteAvecDetails>> = venteDao.observerVentes(pointVenteId, LIMITE_HISTORIQUE)

    fun observerVente(id: String): Flow<VenteAvecDetails?> = venteDao.observer(id)

    fun observerLignesVente(venteId: String): Flow<List<LigneVenteAvecProduit>> = venteDao.observerLignes(venteId)

    fun observerPaiements(venteId: String): Flow<List<PaiementVenteEntity>> = venteDao.observerPaiements(venteId)

    fun observerRetours(venteId: String): Flow<List<RetourVenteEntity>> = venteDao.observerRetours(venteId)

    suspend fun remiseMaxPourcent(): Int = db.commerceDao().get()?.remiseMaxPourcent ?: 0

    /** RG-V5 : propriétaire uniquement. */
    suspend fun definirRemiseMax(pourcent: Int) {
        session.exiger(Permission.PARAMETRER_COMMERCE)
        if (pourcent !in 0..100) throw RegleMetierException("Le pourcentage doit être entre 0 et 100.")
        db.commerceDao().modifierRemiseMax(pourcent, maintenant())
        audit.enregistrer(ActionAudit.PARAMETRE_REMISE, "$pourcent %")
    }

    /**
     * Valide une vente : prix recalculés (RG-V1), session ouverte (RG-V2), coût figé (RG-V3), sorties de stock,
     * encaissements par mode (RG-C5). Lève [AutorisationRequiseException] si la remise dépasse le seuil
     * sans code PIN valide d'un gérant (RG-V5). Renvoie l'id de la vente.
     * Écriture 53 / 517 / 512 / 411 – 707 au journal des ventes.
     */
    suspend fun validerVente(saisie: SaisieVente): String {
        val vendeur = session.exiger(Permission.VENDRE)
        return db.withTransaction {
            val pv = exigerPointVente(saisie.pointVenteId)
            val caisse = caisseDao.getParPointVente(pv.id)
            val sessionCaisse = caisse?.let { caisseDao.getSessionOuverte(it.id) }
                ?: throw RegleMetierException("Ouvrez d'abord la caisse de « ${pv.nom} ».")

            val client = saisie.clientId?.let { clientDao.get(it) ?: throw RegleMetierException("Client introuvable.") }
            if (saisie.type == TypeVente.GROS && client == null) {
                throw RegleMetierException("Une vente en gros est réservée à un client enregistré : choisissez le client.")
            }
            if (saisie.lignes.isEmpty()) throw RegleMetierException("Le panier est vide.")
            if (saisie.lignes.map { it.produitId }.toSet().size != saisie.lignes.size) {
                throw RegleMetierException("Un produit apparaît deux fois dans le panier.")
            }

            val produits = saisie.lignes.map { l ->
                val p = venteDao.produitVendable(pv.id, pv.depotId, l.produitId) ?: throw RegleMetierException("Produit introuvable ou désactivé.")
                if (l.quantiteMilli <= 0) throw RegleMetierException("La quantité de « ${p.nom} » doit être supérieure à zéro.")
                if (!p.uniteDecimale && l.quantiteMilli % 1000 != 0L) {
                    throw RegleMetierException("La quantité de « ${p.nom} » doit être un nombre entier.")
                }
                val prix = CalculsVente.prixApplique(saisie.type, p.prixDetail, p.prixGros)
                    ?: throw RegleMetierException("« ${p.nom} » n'a pas de prix de gros : il ne peut pas être vendu en gros.")
                Triple(l, p, prix)
            }
            val totalBrut = produits.sumOf { (l, _, prix) -> Valorisation.valeur(l.quantiteMilli, prix) }
            if (saisie.remise < 0) throw RegleMetierException("La remise ne peut pas être négative.")
            if (saisie.remise > totalBrut) throw RegleMetierException("La remise dépasse le total.")
            val totalNet = totalBrut - saisie.remise

            var autorisePar: String? = null
            val seuil = db.commerceDao().get()?.remiseMaxPourcent ?: 0
            if (saisie.remise > 0 && !CalculsVente.remiseDansLimite(totalBrut, saisie.remise, seuil) && !vendeur.peut(Permission.ACCORDER_REMISE)) {
                val pin = saisie.pinAutorisation
                    ?: throw AutorisationRequiseException("Remise supérieure à $seuil % : code PIN d'un gérant ou du propriétaire nécessaire.")
                val validateur = auth.verifierAutorisation(pin, Permission.ACCORDER_REMISE)
                    ?: throw AutorisationRequiseException("Code PIN incorrect ou sans droit d'accorder cette remise.")
                autorisePar = validateur.id
            }

            val paiements = saisie.paiements.filter { it.montant != 0L }
            if (paiements.any { it.montant < 0 }) throw RegleMetierException("Un montant payé ne peut pas être négatif.")
            if (paiements.map { it.mode }.toSet().size != paiements.size) throw RegleMetierException("Un mode de paiement apparaît deux fois.")
            if (paiements.sumOf { it.montant } != totalNet) {
                throw RegleMetierException("Les paiements (${Montant.formater(paiements.sumOf { it.montant })}) ne correspondent pas au total ${Montant.formater(totalNet)}.")
            }
            if (paiements.any { it.mode == ModePaiement.CREDIT } && client == null) {
                throw RegleMetierException("La vente à crédit est réservée à un client enregistré.")
            }

            val vente = VenteEntity(
                numero = numero(PREFIXE_VENTE, venteDao.compter() + 1),
                pointVenteId = pv.id,
                depotId = pv.depotId,
                typeVente = saisie.type,
                clientId = client?.id,
                sessionCaisseId = sessionCaisse.id,
                totalBrut = totalBrut,
                remise = saisie.remise,
                totalNet = totalNet,
                remiseAutoriseePar = autorisePar,
                typePaiement = CalculsVente.typePaiement(paiements.map { it.mode }.ifEmpty { listOf(ModePaiement.ESPECES) }),
                createdBy = vendeur.id,
            )
            venteDao.inserer(vente)

            val document = DocumentStock(DOCUMENT_VENTE, vente.id)
            venteDao.insererLignes(
                produits.map { (l, p, prix) ->
                    // RG-V3 : coût figé avant la sortie (la sortie se fait au CMUP)
                    val cout = db.stockDao().get(p.produitId, pv.depotId)?.coutMoyenUnitaire ?: 0L
                    stock.enregistrerMouvement(
                        p.produitId, pv.depotId, TypeMouvement.SORTIE_VENTE, -l.quantiteMilli,
                        document = document, motif = vente.numero, auteurId = vendeur.id,
                    )
                    LigneVenteEntity(
                        venteId = vente.id,
                        produitId = p.produitId,
                        quantiteMilli = l.quantiteMilli,
                        prixUnitaire = prix,
                        montant = Valorisation.valeur(l.quantiteMilli, prix),
                        coutUnitaire = cout,
                    )
                }
            )
            venteDao.insererPaiements(
                paiements.map { PaiementVenteEntity(venteId = vente.id, mode = it.mode, montant = it.montant, reference = it.reference?.trim()?.ifEmpty { null }) }
            )
            paiements.filter { it.mode != ModePaiement.CREDIT }.forEach { p ->
                caisseDao.insererOperation(
                    OperationCaisseEntity(
                        sessionId = sessionCaisse.id,
                        type = TypeOperationCaisse.ENCAISSEMENT,
                        mode = p.mode,
                        montant = p.montant,
                        documentType = DOCUMENT_VENTE,
                        documentId = vente.id,
                        libelle = "Vente ${vente.numero}",
                        createdBy = vendeur.id,
                    )
                )
            }
            comptable.passer(
                Journal.VT, "Vente ${vente.numero}" + (client?.let { " : ${it.nom}" } ?: ""),
                ReglesComptables.vente(totalNet, paiements.map { it.mode to it.montant }, client?.id),
                PieceComptable(DOCUMENT_VENTE, vente.id), vente.date,
            )
            audit.enregistrer(ActionAudit.VENTE, "${vente.numero} : ${Montant.formater(totalNet)} (${pv.nom})")
            if (autorisePar != null) {
                audit.enregistrer(ActionAudit.REMISE_AUTORISEE, "${vente.numero} : remise ${Montant.formater(saisie.remise)}", utilisateurId = autorisePar)
            }
            vente.id
        }
    }

    /**
     * RG-V6 : annulation tant que la caisse de la vente est ouverte et sans retour. Stock remis au coût figé,
     * encaissements reversés ; la vente reste, au statut annulé. Ensuite, passer par un retour.
     */
    suspend fun annulerVente(venteId: String, motif: String) {
        val auteur = session.exiger(Permission.ANNULER_VENTE)
        val motifPropre = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif de l'annulation.") }
        db.withTransaction {
            val vente = exigerVenteValidee(venteId)
            val sessionVente = caisseDao.getSession(vente.sessionCaisseId)?.takeIf { it.statut == StatutSession.OUVERTE }
                ?: throw RegleMetierException("La caisse de cette vente est clôturée : faites un retour.")
            if (venteDao.compterRetoursVente(vente.id) > 0) {
                throw RegleMetierException("Des articles ont déjà été retournés : faites un retour pour le reste.")
            }
            val document = DocumentStock(DOCUMENT_VENTE, vente.id)
            venteDao.lignes(vente.id).forEach { l ->
                stock.enregistrerMouvement(
                    l.produitId, vente.depotId, TypeMouvement.RETOUR_CLIENT, l.quantiteMilli, l.coutUnitaire,
                    document = document, motif = "Annulation ${vente.numero}", auteurId = auteur.id,
                )
            }
            venteDao.paiements(vente.id).filter { it.mode != ModePaiement.CREDIT }.forEach { p ->
                caisseDao.insererOperation(
                    OperationCaisseEntity(
                        sessionId = sessionVente.id,
                        type = TypeOperationCaisse.DECAISSEMENT,
                        mode = p.mode,
                        montant = p.montant,
                        documentType = DOCUMENT_VENTE,
                        documentId = vente.id,
                        libelle = "Annulation ${vente.numero}",
                        createdBy = auteur.id,
                    )
                )
            }
            val date = maintenant()
            venteDao.modifier(
                vente.copy(statut = StatutVente.ANNULEE, dateAnnulation = date, annuleePar = auteur.id, motifAnnulation = motifPropre, updatedAt = date)
            )
            comptable.contrePasser(PieceComptable(DOCUMENT_VENTE, vente.id), "Annulation vente ${vente.numero}")
            audit.enregistrer(ActionAudit.ANNULATION_VENTE, "${vente.numero} : $motifPropre")
        }
    }

    /**
     * Retour client : [quantites] par id de ligne de vente (≤ quantité vendue non encore retournée).
     * Stock remis au coût figé de la vente ; remboursement au prorata de la remise, en espèces / mobile money
     * (caisse ouverte du point de vente) ou déduit du crédit du client. Renvoie le montant remboursé.
     */
    suspend fun retourner(venteId: String, quantites: Map<String, Long>, motif: String, mode: ModePaiement): Long {
        val auteur = session.exiger(Permission.VENDRE)
        val motifPropre = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif du retour.") }
        return db.withTransaction {
            val vente = exigerVenteValidee(venteId)
            if (mode == ModePaiement.CREDIT && vente.clientId == null) {
                throw RegleMetierException("Remboursement sur crédit impossible : la vente n'a pas de client.")
            }
            if (mode == ModePaiement.CARTE) throw RegleMetierException("Remboursez en espèces, par mobile money ou sur le crédit du client.")
            val sessionCaisse = if (mode == ModePaiement.CREDIT) {
                null
            } else {
                caisseDao.getParPointVente(vente.pointVenteId)?.let { caisseDao.getSessionOuverte(it.id) }
                    ?: throw RegleMetierException("Ouvrez la caisse du point de vente pour rembourser le client.")
            }

            val lignes = venteDao.lignes(vente.id).associateBy { it.id }
            val retournees = quantites.filterValues { it != 0L }
            if (retournees.isEmpty()) throw RegleMetierException("Indiquez les quantités retournées.")
            val produitsNoms = mutableMapOf<String, String>()
            retournees.forEach { (ligneId, q) ->
                val l = lignes[ligneId] ?: throw RegleMetierException("Ligne de vente introuvable.")
                val nom = db.produitDao().get(l.produitId)?.nom.orEmpty().also { produitsNoms[ligneId] = it }
                val reste = l.quantiteMilli - l.quantiteRetourneeMilli
                if (q < 0 || q > reste) {
                    throw RegleMetierException("Quantité retournée invalide pour « $nom » (au plus ${Quantite.formater(reste)}).")
                }
            }

            val valeur = retournees.entries.sumOf { (ligneId, q) -> Valorisation.valeur(q, lignes.getValue(ligneId).prixUnitaire) }
            val montant = CalculsVente.montantRetour(valeur, vente.totalBrut, vente.totalNet)
            val retour = RetourVenteEntity(
                numero = numero(PREFIXE_RETOUR, venteDao.compterRetours() + 1),
                venteId = vente.id,
                motif = motifPropre,
                montant = montant,
                modeRemboursement = mode,
                sessionCaisseId = sessionCaisse?.id,
                createdBy = auteur.id,
            )
            venteDao.insererRetour(retour)
            val document = DocumentStock(DOCUMENT_RETOUR, retour.id)
            venteDao.insererLignesRetour(
                retournees.map { (ligneId, q) ->
                    val l = lignes.getValue(ligneId)
                    stock.enregistrerMouvement(
                        l.produitId, vente.depotId, TypeMouvement.RETOUR_CLIENT, q, l.coutUnitaire,
                        document = document, motif = "${retour.numero} (${vente.numero}) : $motifPropre", auteurId = auteur.id,
                    )
                    venteDao.modifierLigne(l.copy(quantiteRetourneeMilli = l.quantiteRetourneeMilli + q))
                    LigneRetourVenteEntity(
                        retourId = retour.id,
                        ligneVenteId = l.id,
                        produitId = l.produitId,
                        quantiteMilli = q,
                        montant = CalculsVente.montantRetour(Valorisation.valeur(q, l.prixUnitaire), vente.totalBrut, vente.totalNet),
                    )
                }
            )
            if (sessionCaisse != null && montant > 0) {
                caisseDao.insererOperation(
                    OperationCaisseEntity(
                        sessionId = sessionCaisse.id,
                        type = TypeOperationCaisse.DECAISSEMENT,
                        mode = mode,
                        montant = montant,
                        documentType = DOCUMENT_RETOUR,
                        documentId = retour.id,
                        libelle = "Retour ${retour.numero} (${vente.numero})",
                        createdBy = auteur.id,
                    )
                )
            }
            comptable.passer(
                Journal.VT, "Retour ${retour.numero} (${vente.numero})",
                ReglesComptables.retourVente(montant, mode, vente.clientId), PieceComptable(DOCUMENT_RETOUR, retour.id),
            )
            audit.enregistrer(ActionAudit.RETOUR_VENTE, "${retour.numero} (${vente.numero}) : ${Montant.formater(montant)}, ${mode.libelle}")
            montant
        }
    }

    // --- Vérifications ---

    private suspend fun exigerPointVente(id: String): PointVenteEntity {
        val pv = db.lieuDao().getPointVente(id) ?: throw RegleMetierException("Point de vente introuvable.")
        if (!pv.actif) throw RegleMetierException("Le point de vente « ${pv.nom} » est désactivé.")
        val u = session.utilisateur.value ?: throw RegleMetierException("Aucun utilisateur connecté.")
        if (!u.voitPointVente(pv.id)) throw RegleMetierException("Vous n'êtes pas rattaché au point de vente « ${pv.nom} ».")
        return pv
    }

    /** RG-C4 : aucune opération sur une session fermée. */
    private suspend fun exigerSessionOuverte(id: String): SessionCaisseEntity {
        val s = caisseDao.getSession(id) ?: throw RegleMetierException("Session de caisse introuvable.")
        if (s.statut != StatutSession.OUVERTE) throw RegleMetierException("Cette caisse est clôturée.")
        return s
    }

    private suspend fun exigerVenteValidee(id: String): VenteEntity {
        val vente = venteDao.get(id) ?: throw RegleMetierException("Vente introuvable.")
        if (vente.statut != StatutVente.VALIDEE) throw RegleMetierException("Cette vente est annulée.")
        val u = session.utilisateur.value ?: throw RegleMetierException("Aucun utilisateur connecté.")
        if (!u.voitPointVente(vente.pointVenteId)) throw RegleMetierException("Cette vente appartient à un autre point de vente.")
        return vente
    }

    private fun numero(prefixe: String, rang: Int) = "$prefixe-" + rang.toString().padStart(6, '0')

    companion object {
        const val DOCUMENT_VENTE = "VENTE"
        const val DOCUMENT_RETOUR = "RETOUR_VENTE"
        const val DOCUMENT_SESSION_CAISSE = "SESSION_CAISSE"
        const val DOCUMENT_OPERATION_CAISSE = "OPERATION_CAISSE"
        private const val PREFIXE_VENTE = "V"
        private const val PREFIXE_RETOUR = "RV"
        private const val LIMITE_HISTORIQUE = 200
    }
}
