package mg.tsena.appro

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import mg.tsena.auth.ActionAudit
import mg.tsena.auth.AuditLogger
import mg.tsena.auth.SessionManager
import mg.tsena.compta.Comptable
import mg.tsena.compta.PieceComptable
import mg.tsena.compta.ReglesComptables
import mg.tsena.data.db.entity.Journal
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.CommandeAvecDetails
import mg.tsena.data.db.entity.CommandeFournisseurEntity
import mg.tsena.data.db.entity.ConversionAvecUnite
import mg.tsena.data.db.entity.ConversionUniteEntity
import mg.tsena.data.db.entity.DepotEntity
import mg.tsena.data.db.entity.FournisseurAvecSolde
import mg.tsena.data.db.entity.FournisseurEntity
import mg.tsena.data.db.entity.LigneCommandeAvecProduit
import mg.tsena.data.db.entity.LigneCommandeEntity
import mg.tsena.data.db.entity.LigneReceptionAvecProduit
import mg.tsena.data.db.entity.LigneReceptionEntity
import mg.tsena.data.db.entity.LigneReceptionRetournable
import mg.tsena.data.db.entity.LigneRetourFournisseurEntity
import mg.tsena.data.db.entity.RetourFournisseurEntity
import mg.tsena.data.db.entity.LigneTransfertAvecProduit
import mg.tsena.data.db.entity.LigneTransfertEntity
import mg.tsena.data.db.entity.ProduitEntity
import mg.tsena.data.db.entity.ReceptionAvecDetails
import mg.tsena.data.db.entity.ReceptionEntity
import mg.tsena.data.db.entity.StatutCommande
import mg.tsena.data.db.entity.StatutTransfert
import mg.tsena.data.db.entity.TransfertAvecDepots
import mg.tsena.data.db.entity.TransfertEntity
import mg.tsena.data.db.entity.TypeMouvement
import mg.tsena.domain.Montant
import mg.tsena.domain.Permission
import mg.tsena.domain.Quantite
import mg.tsena.domain.RegleMetierException
import mg.tsena.domain.Telephone
import mg.tsena.domain.maintenant
import mg.tsena.stock.DocumentStock
import mg.tsena.stock.StockRepository

/** Ligne d'achat saisie (commande ou réception), dans l'unité du produit. */
data class LigneAchat(val produitId: String, val quantiteMilli: Long, val montant: Long)

/** Ligne de transfert saisie, dans l'unité du produit. */
data class LigneEnvoi(val produitId: String, val quantiteMilli: Long)

/** Fournisseurs, unités d'achat, commandes, réceptions et transferts inter-dépôts. */
class ApproRepository(
    private val db: TsenaDatabase,
    private val session: SessionManager,
    private val audit: AuditLogger,
    private val stock: StockRepository,
    private val comptable: Comptable,
) {
    private val fournisseurDao = db.fournisseurDao()
    private val conversionDao = db.conversionUniteDao()
    private val commandeDao = db.commandeDao()
    private val receptionDao = db.receptionDao()
    private val transfertDao = db.transfertDao()

    // --- Fournisseurs ---

    fun observerFournisseurs(): Flow<List<FournisseurAvecSolde>> = fournisseurDao.observerAvecSolde()

    fun observerFournisseursActifs(): Flow<List<FournisseurEntity>> = fournisseurDao.observerActifs()

    suspend fun enregistrerFournisseur(
        id: String?,
        nom: String,
        telephone: String,
        adresse: String,
        nif: String,
        delaiPaiementJours: Int,
        actif: Boolean,
    ) {
        val auteur = session.exiger(Permission.GERER_APPRO)
        val nomPropre = nom.trim()
        if (nomPropre.isEmpty()) throw RegleMetierException("Le nom du fournisseur est obligatoire.")
        val tel = telephone.trim().ifEmpty { null }?.let {
            Telephone.normaliser(it) ?: throw RegleMetierException("Numéro de téléphone invalide (ex. 034 12 345 67).")
        }
        if (delaiPaiementJours < 0) throw RegleMetierException("Le délai de paiement ne peut pas être négatif.")
        db.withTransaction {
            if (fournisseurDao.compterParNom(nomPropre, id ?: "") > 0) {
                throw RegleMetierException("Le fournisseur « $nomPropre » existe déjà.")
            }
            if (id == null) {
                fournisseurDao.inserer(
                    FournisseurEntity(
                        nom = nomPropre,
                        telephone = tel,
                        adresse = adresse.trim().ifEmpty { null },
                        nif = nif.trim().ifEmpty { null },
                        delaiPaiementJours = delaiPaiementJours,
                        createdBy = auteur.id,
                    )
                )
                audit.enregistrer(ActionAudit.CREATION_FOURNISSEUR, nomPropre)
            } else {
                val existant = fournisseurDao.get(id) ?: throw RegleMetierException("Fournisseur introuvable.")
                fournisseurDao.modifier(
                    existant.copy(
                        nom = nomPropre,
                        telephone = tel,
                        adresse = adresse.trim().ifEmpty { null },
                        nif = nif.trim().ifEmpty { null },
                        delaiPaiementJours = delaiPaiementJours,
                        actif = actif,
                        updatedAt = maintenant(),
                    )
                )
                audit.enregistrer(ActionAudit.MODIFICATION_FOURNISSEUR, nomPropre)
            }
        }
    }

    // --- Unités d'achat (conversion_unite) ---

    fun observerConversions(): Flow<List<ConversionAvecUnite>> = conversionDao.observerToutes()

    /** 1 [uniteSourceId] = [facteurMilli] millièmes de l'unité du produit. */
    suspend fun ajouterConversion(produitId: String, uniteSourceId: String, facteurMilli: Long) {
        val auteur = session.exiger(Permission.GERER_APPRO)
        db.withTransaction {
            val produit = db.produitDao().get(produitId) ?: throw RegleMetierException("Produit introuvable.")
            val unite = db.uniteDao().get(uniteSourceId) ?: throw RegleMetierException("Unité introuvable.")
            val uniteProduit = db.uniteDao().get(produit.uniteId) ?: throw RegleMetierException("Unité introuvable.")
            if (unite.id == produit.uniteId) throw RegleMetierException("Choisissez une unité différente de celle du produit.")
            if (facteurMilli <= 0) throw RegleMetierException("Le nombre doit être supérieur à zéro.")
            if (!uniteProduit.decimale && facteurMilli % 1000 != 0L) {
                throw RegleMetierException("Le nombre de ${uniteProduit.symbole} doit être entier.")
            }
            if (conversionDao.compter(produitId, uniteSourceId) > 0) {
                throw RegleMetierException("Cette unité est déjà définie pour « ${produit.nom} ».")
            }
            conversionDao.inserer(
                ConversionUniteEntity(produitId = produitId, uniteSourceId = uniteSourceId, facteurMilli = facteurMilli, createdBy = auteur.id)
            )
            audit.enregistrer(
                ActionAudit.CREATION_CONVERSION,
                "${produit.nom} : 1 ${unite.symbole} = ${Quantite.formater(facteurMilli)} ${uniteProduit.symbole}",
            )
        }
    }

    /** Suppression logique : les documents déjà saisis sont enregistrés dans l'unité du produit. */
    suspend fun supprimerConversion(id: String) {
        session.exiger(Permission.GERER_APPRO)
        db.withTransaction {
            val conversion = conversionDao.get(id) ?: throw RegleMetierException("Unité d'achat introuvable.")
            val date = maintenant()
            conversionDao.modifier(conversion.copy(deletedAt = date, updatedAt = date))
            val produit = db.produitDao().get(conversion.produitId)?.nom.orEmpty()
            audit.enregistrer(ActionAudit.SUPPRESSION_CONVERSION, produit)
        }
    }

    // --- Commandes fournisseur ---

    fun observerCommandes(): Flow<List<CommandeAvecDetails>> = commandeDao.observerToutes()

    fun observerCommande(id: String): Flow<CommandeAvecDetails?> = commandeDao.observer(id)

    fun observerLignesCommande(commandeId: String): Flow<List<LigneCommandeAvecProduit>> = commandeDao.observerLignes(commandeId)

    suspend fun getCommande(id: String): CommandeFournisseurEntity? = commandeDao.get(id)

    suspend fun lignesCommande(commandeId: String): List<LigneCommandeAvecProduit> = commandeDao.lignesAvecProduit(commandeId)

    /** Crée ([id] null) ou modifie une commande en brouillon ; renvoie son id. */
    suspend fun enregistrerCommande(id: String?, fournisseurId: String, depotId: String, note: String, lignes: List<LigneAchat>): String {
        val auteur = session.exiger(Permission.GERER_APPRO)
        return db.withTransaction {
            val fournisseur = exigerFournisseurActif(fournisseurId)
            exigerDepotActif(depotId)
            verifierLignesAchat(lignes)
            val commande = if (id == null) {
                CommandeFournisseurEntity(
                    numero = Approvisionnement.numero(PREFIXE_COMMANDE, commandeDao.compter() + 1),
                    fournisseurId = fournisseurId,
                    depotId = depotId,
                    note = note.trim().ifEmpty { null },
                    createdBy = auteur.id,
                ).also { commandeDao.inserer(it) }
            } else {
                val existante = commandeDao.get(id) ?: throw RegleMetierException("Commande introuvable.")
                if (existante.statut != StatutCommande.BROUILLON) {
                    throw RegleMetierException("Seule une commande en brouillon peut être modifiée.")
                }
                existante.copy(fournisseurId = fournisseurId, depotId = depotId, note = note.trim().ifEmpty { null }, updatedAt = maintenant())
                    .also {
                        commandeDao.modifier(it)
                        commandeDao.supprimerLignes(it.id)
                    }
            }
            commandeDao.insererLignes(
                lignes.map { l ->
                    LigneCommandeEntity(
                        commandeId = commande.id,
                        produitId = l.produitId,
                        quantiteMilli = l.quantiteMilli,
                        prixUnitaire = Approvisionnement.prixUnitaire(l.montant, l.quantiteMilli),
                        montant = l.montant,
                    )
                }
            )
            audit.enregistrer(
                if (id == null) ActionAudit.CREATION_COMMANDE else ActionAudit.MODIFICATION_COMMANDE,
                "${commande.numero} (${fournisseur.nom})",
            )
            commande.id
        }
    }

    /** Brouillon → envoyée (le bon peut être partagé au fournisseur). */
    suspend fun marquerCommandeEnvoyee(id: String) {
        changerStatutCommande(id, ActionAudit.ENVOI_COMMANDE) { commande ->
            if (commande.statut != StatutCommande.BROUILLON) throw RegleMetierException("Cette commande est déjà envoyée.")
            StatutCommande.ENVOYEE
        }
    }

    /** Annulation possible tant que rien n'a été reçu (sinon : solder). */
    suspend fun annulerCommande(id: String) {
        changerStatutCommande(id, ActionAudit.ANNULATION_COMMANDE) { commande ->
            if (commande.statut !in setOf(StatutCommande.BROUILLON, StatutCommande.ENVOYEE)) {
                throw RegleMetierException("Une commande déjà reçue (même en partie) ne peut pas être annulée.")
            }
            if (receptionDao.compterParCommande(commande.id) > 0) {
                throw RegleMetierException("Une commande déjà reçue (même en partie) ne peut pas être annulée.")
            }
            StatutCommande.ANNULEE
        }
    }

    /** Commande reçue en partie dont le reste ne sera pas livré : elle passe à « Reçue ». */
    suspend fun solderCommande(id: String) {
        changerStatutCommande(id, ActionAudit.SOLDE_COMMANDE) { commande ->
            if (commande.statut != StatutCommande.PARTIELLE) {
                throw RegleMetierException("Seule une commande reçue en partie peut être soldée.")
            }
            StatutCommande.RECUE
        }
    }

    private suspend fun changerStatutCommande(
        id: String,
        action: ActionAudit,
        nouveauStatut: suspend (CommandeFournisseurEntity) -> StatutCommande,
    ) {
        session.exiger(Permission.GERER_APPRO)
        db.withTransaction {
            val commande = commandeDao.get(id) ?: throw RegleMetierException("Commande introuvable.")
            commandeDao.modifier(commande.copy(statut = nouveauStatut(commande), updatedAt = maintenant()))
            audit.enregistrer(action, commande.numero)
        }
    }

    // --- Réceptions (RG-A1 à RG-A4) ---

    fun observerReceptions(): Flow<List<ReceptionAvecDetails>> = receptionDao.observerToutes()

    fun observerReceptionsCommande(commandeId: String): Flow<List<ReceptionAvecDetails>> = receptionDao.observerParCommande(commandeId)

    fun observerReception(id: String): Flow<ReceptionAvecDetails?> = receptionDao.observer(id)

    fun observerLignesReception(receptionId: String): Flow<List<LigneReceptionAvecProduit>> = receptionDao.observerLignes(receptionId)

    /**
     * Valide une réception : entrée en stock au coût d'achat (CMUP recalculé), mise à jour des quantités
     * reçues et du statut de la commande. Avec une commande, le fournisseur et le dépôt sont ceux de la commande ;
     * un produit hors commande est accepté. Le montant total devient une dette fournisseur (RG-A3).
     * Renvoie l'id de la réception. Écriture 607 / 401 au journal des achats.
     */
    suspend fun validerReception(
        commandeId: String?,
        fournisseurId: String,
        depotId: String,
        reference: String,
        lignes: List<LigneAchat>,
    ): String {
        val auteur = session.exiger(Permission.GERER_APPRO)
        return db.withTransaction {
            val commande = commandeId?.let { commandeDao.get(it) ?: throw RegleMetierException("Commande introuvable.") }
            if (commande != null && commande.statut in setOf(StatutCommande.RECUE, StatutCommande.ANNULEE)) {
                throw RegleMetierException("La commande ${commande.numero} est ${commande.statut.libelle.lowercase()}.")
            }
            val fournisseur = commande?.let { fournisseurDao.get(it.fournisseurId) } ?: exigerFournisseurActif(fournisseurId)
            val depot = exigerDepotActif(commande?.depotId ?: depotId)
            verifierLignesAchat(lignes)

            val reception = ReceptionEntity(
                numero = Approvisionnement.numero(PREFIXE_RECEPTION, receptionDao.compter() + 1),
                commandeId = commande?.id,
                fournisseurId = fournisseur.id,
                depotId = depot.id,
                montantTotal = lignes.sumOf { it.montant },
                reference = reference.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            receptionDao.inserer(reception)

            val lignesCommande = commande?.let { c -> commandeDao.lignes(c.id).associateBy { it.produitId } }.orEmpty()
            val document = DocumentStock(DOCUMENT_RECEPTION, reception.id)
            receptionDao.insererLignes(
                lignes.map { l ->
                    val prix = Approvisionnement.prixUnitaire(l.montant, l.quantiteMilli)
                    stock.enregistrerMouvement(
                        l.produitId, depot.id, TypeMouvement.ENTREE_ACHAT, l.quantiteMilli, prix,
                        document = document, motif = "Réception ${reception.numero}", auteurId = auteur.id,
                    )
                    val ligneCommande = lignesCommande[l.produitId]
                    ligneCommande?.let { commandeDao.modifierLigne(it.copy(quantiteRecueMilli = it.quantiteRecueMilli + l.quantiteMilli)) }
                    LigneReceptionEntity(
                        receptionId = reception.id,
                        produitId = l.produitId,
                        ligneCommandeId = ligneCommande?.id,
                        quantiteMilli = l.quantiteMilli,
                        prixUnitaire = prix,
                        montant = l.montant,
                    )
                }
            )

            comptable.passer(
                Journal.AC, "Réception ${reception.numero} : ${fournisseur.nom}",
                ReglesComptables.achat(reception.montantTotal, fournisseur.id), PieceComptable(DOCUMENT_RECEPTION, reception.id),
            )
            if (commande != null) {
                val statut = Approvisionnement.statutApresReception(
                    commandeDao.lignes(commande.id).map { it.quantiteMilli to it.quantiteRecueMilli }
                )
                commandeDao.modifier(commande.copy(statut = statut, updatedAt = maintenant()))
            }
            audit.enregistrer(
                ActionAudit.RECEPTION,
                "${reception.numero} (${fournisseur.nom}, ${depot.nom})" + (commande?.let { " · ${it.numero}" } ?: ""),
            )
            reception.id
        }
    }

    // --- Retours au fournisseur (RG-A4) ---

    fun observerRetoursFournisseur(receptionId: String): Flow<List<RetourFournisseurEntity>> =
        db.retourFournisseurDao().observerParReception(receptionId)

    fun observerLignesRetournables(receptionId: String): Flow<List<LigneReceptionRetournable>> =
        db.retourFournisseurDao().observerLignesRetournables(receptionId)

    /**
     * Retour de marchandise reçue : [quantites] par id de ligne de réception (≤ reçu − déjà retourné).
     * Sortie de stock (RETOUR_FOURNISSEUR, au CMUP) ; la dette fournisseur diminue du prix d'achat des quantités
     * (exact pour une ligne entière). Renvoie le montant déduit.
     */
    suspend fun retournerAuFournisseur(receptionId: String, quantites: Map<String, Long>, motif: String): Long {
        val auteur = session.exiger(Permission.GERER_APPRO)
        val texte = motif.trim().ifEmpty { throw RegleMetierException("Indiquez le motif du retour.") }
        val retourDao = db.retourFournisseurDao()
        return db.withTransaction {
            val reception = receptionDao.get(receptionId) ?: throw RegleMetierException("Réception introuvable.")
            val lignes = retourDao.lignesReception(receptionId).associateBy { it.id }
            val retournees = quantites.filterValues { it != 0L }
            if (retournees.isEmpty()) throw RegleMetierException("Indiquez les quantités retournées.")
            retournees.forEach { (ligneId, q) ->
                val l = lignes[ligneId] ?: throw RegleMetierException("Ligne de réception introuvable.")
                val reste = l.quantiteMilli - l.quantiteRetourneeMilli
                if (q < 0 || q > reste) {
                    val nom = db.produitDao().get(l.produitId)?.nom.orEmpty()
                    throw RegleMetierException("Quantité retournée invalide pour « $nom » (au plus ${Quantite.formater(reste)}).")
                }
            }
            val montants = retournees.mapValues { (ligneId, q) ->
                val l = lignes.getValue(ligneId)
                Approvisionnement.montantAuProrata(l.montant, l.quantiteMilli, q)
            }
            val retour = RetourFournisseurEntity(
                numero = Approvisionnement.numero(PREFIXE_RETOUR, retourDao.compter() + 1),
                receptionId = reception.id,
                fournisseurId = reception.fournisseurId,
                depotId = reception.depotId,
                motif = texte,
                montant = montants.values.sum(),
                createdBy = auteur.id,
            )
            retourDao.inserer(retour)
            val document = DocumentStock(DOCUMENT_RETOUR_FOURNISSEUR, retour.id)
            retourDao.insererLignes(
                retournees.map { (ligneId, q) ->
                    val l = lignes.getValue(ligneId)
                    stock.enregistrerMouvement(
                        l.produitId, reception.depotId, TypeMouvement.RETOUR_FOURNISSEUR, -q,
                        document = document, motif = "${retour.numero} (${reception.numero}) : $texte", auteurId = auteur.id,
                    )
                    retourDao.modifierLigneReception(l.copy(quantiteRetourneeMilli = l.quantiteRetourneeMilli + q))
                    LigneRetourFournisseurEntity(
                        retourId = retour.id,
                        ligneReceptionId = l.id,
                        produitId = l.produitId,
                        quantiteMilli = q,
                        prixUnitaire = l.prixUnitaire,
                        montant = montants.getValue(ligneId),
                    )
                }
            )
            val fournisseurNom = db.fournisseurDao().get(reception.fournisseurId)?.nom.orEmpty()
            comptable.passer(
                Journal.AC, "Retour ${retour.numero} à $fournisseurNom",
                ReglesComptables.retourAchat(retour.montant, reception.fournisseurId), PieceComptable(DOCUMENT_RETOUR_FOURNISSEUR, retour.id),
            )
            audit.enregistrer(ActionAudit.RETOUR_FOURNISSEUR, "${retour.numero} (${reception.numero}) : ${Montant.formater(retour.montant)}")
            retour.montant
        }
    }

    // --- Transferts inter-dépôts (RG-T1 à RG-T4) ---

    fun observerTransferts(): Flow<List<TransfertAvecDepots>> = transfertDao.observerTous()

    fun observerTransfert(id: String): Flow<TransfertAvecDepots?> = transfertDao.observer(id)

    fun observerLignesTransfert(transfertId: String): Flow<List<LigneTransfertAvecProduit>> = transfertDao.observerLignes(transfertId)

    fun observerNbTransfertsEnTransit(): Flow<Int> = transfertDao.observerNbEnTransit()

    /** Envoi : sortie du dépôt source au CMUP, le transfert passe « en transit ». Renvoie son id. */
    suspend fun envoyerTransfert(sourceId: String, destinationId: String, note: String, lignes: List<LigneEnvoi>): String {
        val auteur = session.exiger(Permission.GERER_APPRO)
        if (sourceId == destinationId) throw RegleMetierException("Le dépôt de destination doit être différent du dépôt source.")
        return db.withTransaction {
            val source = exigerDepotActif(sourceId)
            val destination = exigerDepotActif(destinationId)
            if (lignes.isEmpty()) throw RegleMetierException("Ajoutez au moins un produit.")
            if (lignes.map { it.produitId }.toSet().size != lignes.size) throw RegleMetierException("Un produit apparaît deux fois.")
            lignes.forEach { exigerQuantite(exigerProduitActif(it.produitId), it.quantiteMilli) }

            val transfert = TransfertEntity(
                numero = Approvisionnement.numero(PREFIXE_TRANSFERT, transfertDao.compter() + 1),
                depotSourceId = source.id,
                depotDestinationId = destination.id,
                note = note.trim().ifEmpty { null },
                createdBy = auteur.id,
            )
            transfertDao.inserer(transfert)
            val document = DocumentStock(DOCUMENT_TRANSFERT, transfert.id)
            transfertDao.insererLignes(
                lignes.map { l ->
                    // RG-T4 : la valeur sortie au CMUP source est celle qui entrera à destination
                    val cout = db.stockDao().get(l.produitId, source.id)?.coutMoyenUnitaire ?: 0L
                    stock.enregistrerMouvement(
                        l.produitId, source.id, TypeMouvement.TRANSFERT_SORTIE, -l.quantiteMilli,
                        document = document, motif = "${transfert.numero} vers ${destination.nom}", auteurId = auteur.id,
                    )
                    LigneTransfertEntity(transfertId = transfert.id, produitId = l.produitId, quantiteEnvoyeeMilli = l.quantiteMilli, coutUnitaire = cout)
                }
            )
            audit.enregistrer(ActionAudit.ENVOI_TRANSFERT, "${transfert.numero} : ${source.nom} → ${destination.nom}")
            transfert.id
        }
    }

    /**
     * Réception au dépôt de destination. [quantitesRecues] : par id de ligne (absente = tout reçu).
     * La quantité envoyée entre au coût du transfert ; un manque (RG-T3) sort aussitôt en PERTE avec [motifEcart].
     */
    suspend fun recevoirTransfert(id: String, quantitesRecues: Map<String, Long>, motifEcart: String) {
        val auteur = session.exiger(Permission.GERER_APPRO)
        val motif = motifEcart.trim()
        db.withTransaction {
            val transfert = exigerEnTransit(id)
            val destination = exigerDepotActif(transfert.depotDestinationId)
            val lignes = transfertDao.lignes(id)
            val recues = lignes.associate { l -> l.id to (quantitesRecues[l.id] ?: l.quantiteEnvoyeeMilli) }
            lignes.forEach { l ->
                val recue = recues.getValue(l.id)
                if (recue < 0 || recue > l.quantiteEnvoyeeMilli) {
                    val nom = db.produitDao().get(l.produitId)?.nom.orEmpty()
                    throw RegleMetierException("Quantité reçue invalide pour « $nom » (0 à ${Quantite.formater(l.quantiteEnvoyeeMilli)}).")
                }
            }
            val avecEcart = lignes.any { recues.getValue(it.id) < it.quantiteEnvoyeeMilli }
            if (avecEcart && motif.isEmpty()) throw RegleMetierException("Indiquez la raison des quantités manquantes.")

            val document = DocumentStock(DOCUMENT_TRANSFERT, id)
            lignes.forEach { l ->
                val recue = recues.getValue(l.id)
                stock.enregistrerMouvement(
                    l.produitId, destination.id, TypeMouvement.TRANSFERT_ENTREE, l.quantiteEnvoyeeMilli, l.coutUnitaire,
                    document = document, motif = transfert.numero, auteurId = auteur.id,
                )
                val manque = l.quantiteEnvoyeeMilli - recue
                if (manque > 0) {
                    stock.enregistrerMouvement(
                        l.produitId, destination.id, TypeMouvement.PERTE, -manque,
                        document = document, motif = "${transfert.numero} : $motif", auteurId = auteur.id,
                    )
                }
                transfertDao.modifierLigne(l.copy(quantiteRecueMilli = recue))
            }
            val date = maintenant()
            transfertDao.modifier(
                transfert.copy(
                    statut = StatutTransfert.RECU,
                    dateReception = date,
                    recuPar = auteur.id,
                    motifEcart = motif.ifEmpty { null },
                    updatedAt = date,
                )
            )
            audit.enregistrer(ActionAudit.RECEPTION_TRANSFERT, transfert.numero + if (avecEcart) " avec écart ($motif)" else "")
        }
    }

    /** Annulation d'un transfert en transit : la marchandise revient au dépôt source, à son coût d'envoi. */
    suspend fun annulerTransfert(id: String) {
        val auteur = session.exiger(Permission.GERER_APPRO)
        db.withTransaction {
            val transfert = exigerEnTransit(id)
            val document = DocumentStock(DOCUMENT_TRANSFERT, id)
            transfertDao.lignes(id).forEach { l ->
                stock.enregistrerMouvement(
                    l.produitId, transfert.depotSourceId, TypeMouvement.TRANSFERT_ENTREE, l.quantiteEnvoyeeMilli, l.coutUnitaire,
                    document = document, motif = "Annulation ${transfert.numero}", auteurId = auteur.id,
                )
            }
            transfertDao.modifier(transfert.copy(statut = StatutTransfert.ANNULE, updatedAt = maintenant()))
            audit.enregistrer(ActionAudit.ANNULATION_TRANSFERT, transfert.numero)
        }
    }

    // --- Vérifications ---

    private suspend fun exigerEnTransit(id: String): TransfertEntity {
        val transfert = transfertDao.get(id) ?: throw RegleMetierException("Transfert introuvable.")
        if (transfert.statut != StatutTransfert.EN_TRANSIT) {
            throw RegleMetierException("Ce transfert est déjà ${transfert.statut.libelle.lowercase()}.")
        }
        return transfert
    }

    private suspend fun exigerFournisseurActif(id: String): FournisseurEntity {
        val fournisseur = fournisseurDao.get(id) ?: throw RegleMetierException("Choisissez un fournisseur.")
        if (!fournisseur.actif) throw RegleMetierException("Le fournisseur « ${fournisseur.nom} » est désactivé.")
        return fournisseur
    }

    private suspend fun exigerDepotActif(id: String): DepotEntity {
        val depot = db.lieuDao().getDepot(id) ?: throw RegleMetierException("Choisissez un dépôt.")
        if (!depot.actif) throw RegleMetierException("Le dépôt « ${depot.nom} » est désactivé.")
        return depot
    }

    private suspend fun exigerProduitActif(id: String): ProduitEntity {
        val produit = db.produitDao().get(id) ?: throw RegleMetierException("Produit introuvable.")
        if (!produit.actif) throw RegleMetierException("Le produit « ${produit.nom} » est désactivé.")
        return produit
    }

    /** RG-P8 : quantité positive, entière si l'unité du produit n'accepte pas de décimales. */
    private suspend fun exigerQuantite(produit: ProduitEntity, quantiteMilli: Long) {
        if (quantiteMilli <= 0) throw RegleMetierException("La quantité de « ${produit.nom} » doit être supérieure à zéro.")
        val decimale = db.uniteDao().get(produit.uniteId)?.decimale ?: false
        if (!decimale && quantiteMilli % 1000 != 0L) {
            throw RegleMetierException("La quantité de « ${produit.nom} » doit être un nombre entier.")
        }
    }

    private suspend fun verifierLignesAchat(lignes: List<LigneAchat>) {
        if (lignes.isEmpty()) throw RegleMetierException("Ajoutez au moins un produit.")
        if (lignes.map { it.produitId }.toSet().size != lignes.size) throw RegleMetierException("Un produit apparaît deux fois.")
        lignes.forEach { l ->
            exigerQuantite(exigerProduitActif(l.produitId), l.quantiteMilli)
            if (l.montant < 0) throw RegleMetierException("Le montant ne peut pas être négatif.")
        }
    }

    companion object {
        const val DOCUMENT_RECEPTION = "RECEPTION"
        const val DOCUMENT_TRANSFERT = "TRANSFERT"
        const val DOCUMENT_RETOUR_FOURNISSEUR = "RETOUR_FOURNISSEUR"
        private const val PREFIXE_RETOUR = "RF"
        private const val PREFIXE_COMMANDE = "CF"
        private const val PREFIXE_RECEPTION = "BR"
        private const val PREFIXE_TRANSFERT = "TR"
    }
}
