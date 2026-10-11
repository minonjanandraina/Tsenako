# Tsena — Application mobile de gestion pour petits marchands

## 1. Objectif

Créer une application mobile permettant aux petits marchands de gérer leur activité au quotidien :
stocks (multi-dépôts), fournisseurs, approvisionnements, ventes, caisse, dépenses et comptabilité
conforme aux standards internationaux.

### Demande initiale (conservée)
1. Gestion des stocks, avec plusieurs salles de stockage
2. Gestion des fournisseurs
3. Gestion des approvisionnements et transferts entre stockages
4. Gestion des points de vente avec les produits et types de produits
5. Gestion des charges et dépenses
6. Comptabilité suivant les standards internationaux → PCG 2005 Madagascar
7. Gestion de caisse

---

## 2. Choix techniques

| Élément | Choix |
|---------|-------|
| Langage | **Kotlin** (Android natif) |
| UI | Jetpack Compose |
| Appareil | **Un seul téléphone** par commerce (pas de synchronisation entre appareils) |
| Base de données | **Locale** — Room (SQLite) sur l'appareil, pas de serveur |
| Sauvegarde | Archive `.zip` (base + photos) **partagée par WhatsApp** via le menu de partage Android |
| Photos produits | Fichiers dans le stockage interne de l'app, chemin enregistré en base |
| Comptabilité | **PCG 2005 Madagascar** (aligné IFRS), en Ariary |
| Taxes | **Non gérées** (pas de TVA ni autre taxe : prix saisis = prix payés) |

---

## 3. Modules fonctionnels

| # | Module | Rôle |
|---|--------|------|
| M1 | Référentiel | Commerce, utilisateurs, rôles, produits, types de produits, unités |
| M2 | Stocks | Dépôts, niveaux de stock, mouvements, inventaires |
| M3 | Fournisseurs | Fiches fournisseurs, dettes, historique |
| M4 | Approvisionnement & transferts | Bons de commande, réceptions, transferts inter-dépôts |
| M5 | Points de vente | Ventes, tickets, retours, clients |
| M6 | Caisse | Sessions de caisse, encaissements, décaissements, clôture |
| M7 | Charges & dépenses | Saisie et catégorisation des dépenses |
| M8 | Comptabilité | Écritures en partie double, journaux, états financiers |

---

## 4. Modélisation de la base de données

Conventions : chaque table a `id` (UUID), `created_at`, `updated_at`,
`created_by`, `deleted_at` (suppression logique). Montants en Ariary entiers ; quantités en entiers
exprimés en **millièmes d'unité** (colonnes `_milli`, 1,5 kg = 1500) — jamais de flottant.

### 4.1 Référentiel
- **commerce** : nom, devise (ex. MGA), adresse, NIF/STAT, date début exercice, stock_negatif_autorise (RG-S3)
- **utilisateur** : nom, téléphone, code PIN / mot de passe, actif
- **role** : nom (propriétaire, gérant, vendeur, magasinier), permissions
- **utilisateur_role** : utilisateur_id, role_id, point_vente_id (nullable)
- **type_produit** : nom, parent_id (hiérarchie de catégories)
- **unite** : nom, symbole, decimale (pièce, kg, litre, sac, carton… pré-remplies à la création de la base)
- **produit** : code (unique, auto `P00001`), code_barre (unique, facultatif), nom, type_produit_id (**obligatoire**),
  unite_id, prix_achat (Ar, facultatif), prix_vente (Ar, détail), prix_vente_gros (Ar, facultatif), seuil_alerte_milli, photo_chemin (**obligatoire**, relatif au dossier photos), actif
- **conversion_unite** (unité d'achat) : produit_id, unite_source_id, facteur_milli (millièmes de l'unité du produit
  dans 1 unité source, ex. 1 carton = 24 pièces → 24000) ; l'unité cible est toujours celle du produit

### 4.2 Stocks
- **depot** (salle de stockage) : nom, adresse, point_vente_id (nullable), actif
- **stock** : produit_id, depot_id, quantite, cout_moyen_unitaire *(une ligne par couple produit/dépôt)*
- **mouvement_stock** : produit_id, depot_id, type (`STOCK_INITIAL`, `ENTREE_ACHAT`, `SORTIE_VENTE`, `TRANSFERT_SORTIE`,
  `TRANSFERT_ENTREE`, `AJUSTEMENT`, `RETOUR_CLIENT`, `RETOUR_FOURNISSEUR`, `PERTE`), quantite (signée : + entrée, − sortie),
  cout_unitaire, quantite_apres, document_type, document_id, motif, date
- **inventaire** : depot_id, date, statut (`BROUILLON`, `VALIDE`), date_validation, valide_par
- **ligne_inventaire** : inventaire_id, produit_id, quantite_theorique, quantite_comptee (null = non compté), ecart

### 4.3 Fournisseurs
- **fournisseur** : nom, téléphone (facultatif, format +261), adresse, NIF, delai_paiement_jours, actif
- **paiement_fournisseur** : fournisseur_id, reception_id (nullable), montant, mode_paiement, caisse_session_id, date

### 4.4 Approvisionnement & transferts
- **commande_fournisseur** : numero (`CF-000001`), fournisseur_id, depot_id (livraison), date, note,
  statut (`BROUILLON`, `ENVOYEE`, `PARTIELLE`, `RECUE`, `ANNULEE`)
- **ligne_commande** : commande_id, produit_id, quantite, prix_unitaire, montant, quantite_recue (cumul des réceptions)
- **reception** : numero (`BR-000001`), commande_id (nullable), fournisseur_id, depot_id, date, montant_total,
  reference (n° facture / BL du fournisseur) — reste à payer = montant − retours − paiements rattachés
- **ligne_reception** : reception_id, produit_id, ligne_commande_id (nullable), quantite, prix_unitaire, montant
- **transfert** : numero (`TR-000001`), depot_source_id, depot_destination_id, date, statut (`EN_TRANSIT`, `RECU`, `ANNULE`),
  date_reception, recu_par, motif_ecart, note — créé directement à l'envoi (pas de brouillon enregistré)
- **ligne_transfert** : transfert_id, produit_id, quantite_envoyee, quantite_recue, cout_unitaire (CMUP source à l'envoi)

### 4.5 Points de vente
- **point_vente** : nom, adresse, depot_id (dépôt de sortie par défaut), actif
- **prix_point_vente** : point_vente_id, produit_id, prix_detail, prix_gros (surcharges optionnelles des prix produit)
- **client** : nom, telephone (normalisé, unique) — **uniquement ces deux informations**
- **vente** : numero, point_vente_id, type_vente (`DETAIL`, `GROS`), client_id (nullable, **obligatoire en gros**),
  caisse_session_id, date, total_brut, remise, total_net, statut (`VALIDEE`, `ANNULEE`), type_paiement (`COMPTANT`, `CREDIT`, `MIXTE`)
- **ligne_vente** : vente_id, produit_id, quantite, prix_unitaire (détail ou gros selon la vente), remise, cout_unitaire (figé à la vente)
- **paiement_vente** : vente_id, mode (`ESPECES`, `MOBILE_MONEY`, `CARTE`, `CREDIT`), montant, reference
- **retour_vente** : vente_id, date, motif + lignes

### 4.6 Caisse
- **caisse** : nom, point_vente_id
- **session_caisse** : caisse_id, utilisateur_id, ouverture, fermeture, fond_initial, solde_theorique,
  solde_compte, ecart, statut (`OUVERTE`, `FERMEE`)
- **operation_caisse** : session_id, type (`ENCAISSEMENT`, `DECAISSEMENT`, `APPORT`, `RETRAIT`), mode,
  montant, document_type, document_id, libelle

### 4.7 Charges & dépenses
- **categorie_depense** : nom, compte_comptable_id (ex. loyer, électricité, transport, salaires)
- **depense** : categorie_id, point_vente_id (nullable), montant, date, mode_paiement, session_caisse_id
  (nullable), justificatif (photo), description

### 4.8 Comptabilité
- **exercice** : date_debut, date_fin, statut (`OUVERT`, `CLOTURE`)
- **compte** : numero, libelle, classe, type (`ACTIF`, `PASSIF`, `CHARGE`, `PRODUIT`, `CAPITAUX`)
- **journal** : code (`VT` ventes, `AC` achats, `CA` caisse, `BQ` banque, `OD` opérations diverses), libelle —
  liste fixe (enum `Journal`), pas de table
- **ecriture** : numero (séquentiel sans trou), journal, exercice_id, date, libelle, document_type, document_id,
  contrepassation_de — toujours validée (générée automatiquement)
- **ligne_ecriture** : ecriture_id, compte_numero, debit, credit, tiers_type (`CLIENT`, `FOURNISSEUR`), tiers_id

### 4.9 Relations clés (résumé)
```
point_vente 1─n depot 1─n stock n─1 produit n─1 type_produit
fournisseur 1─n commande_fournisseur 1─n reception ─> mouvement_stock (+)
transfert ─> mouvement_stock (- source, + destination)
vente 1─n ligne_vente ─> mouvement_stock (-) ; vente 1─n paiement_vente ─> operation_caisse
depense ─> operation_caisse ; tout document validé ─> ecriture (partie double)
```

---

## 5. Règles métier

### 5.1 Produits
- RG-P1 : Chaque produit appartient **obligatoirement** à une catégorie (`type_produit`).
- RG-P2 : Chaque produit a **obligatoirement** une photo (appareil photo ou galerie), compressée et
  redimensionnée avant enregistrement.
- RG-P3 : Une catégorie contenant des produits ne peut pas être supprimée.
- RG-P4 : La photo est affichée dans le catalogue, la caisse (vente) et les écrans de stock.

- RG-P5 : Code produit unique, généré automatiquement (`P00001`…) s'il est laissé vide ; code-barre unique s'il est saisi.
- RG-P6 : Un produit n'est jamais supprimé (il sera référencé par les mouvements) : on le désactive.
- RG-P7 : Catégories hiérarchiques : nom unique au même niveau ; une catégorie ne peut pas être placée
  dans elle-même ou dans une de ses sous-catégories.
- RG-P8 : Une quantité décimale n'est acceptée que si l'unité l'autorise (kg, litre…).
- RG-P10 : Le **prix d'achat** est facultatif : c'est un prix habituel de référence, proposé à la saisie des commandes,
  réceptions (converti si l'on saisit en carton, sac…) et du stock initial, et affiché avec la marge indicative.
  La valorisation du stock reste au CMUP (RG-S2).
- RG-P9 : Le prix de vente au détail est obligatoire ; le **prix de vente en gros** est facultatif.
  Un produit sans prix de gros ne peut pas être ajouté à une vente en gros.

### 5.2 Lieux
- RG-L1 : Un point de vente vend depuis un seul dépôt (`point_vente.depot_id`). À sa création, si aucun
  dépôt n'est choisi, un dépôt « Magasin <nom> » rattaché au point de vente est créé automatiquement.
- RG-L2 : Noms de dépôts et de points de vente uniques. Désactivation plutôt que suppression.
- RG-L3 : Un dépôt qui alimente un point de vente actif ne peut pas être désactivé.

### 5.3 Stocks
- RG-S1 : Le stock n'est **jamais modifié directement** ; toute variation passe par un `mouvement_stock`.
  `stock.quantite` = somme des mouvements (valeur dénormalisée, recalculable).
- RG-S2 : Valorisation au **coût moyen unitaire pondéré (CMUP)**, recalculé à chaque entrée
  (IAS 2 autorise CMUP ou FIFO ; LIFO interdit).
  Une entrée sur un stock nul ou négatif prend directement son coût comme CMUP ; une sortie est valorisée au CMUP.
- RG-S3 : Stock négatif interdit par défaut (paramétrable par commerce, propriétaire uniquement).
- RG-S4 : Alerte quand `quantite <= seuil_alerte`, si le seuil est > 0, pour les dépôts actifs où le produit
  a déjà eu du stock.
- RG-S5 : Un inventaire validé génère des mouvements `AJUSTEMENT` pour chaque écart, et une écriture
  comptable (perte ou gain sur stock, à partir de S6). Un seul inventaire en cours par dépôt ; la quantité théorique
  est relue au moment de la validation (les ventes faites pendant le comptage sont prises en compte) ;
  les produits non comptés ne sont pas modifiés.
- RG-S6 : **Stock initial** : saisie manuelle de la quantité de départ avec son coût d'achat unitaire
  (mouvement `STOCK_INITIAL`). **Perte** (casse, péremption, vol) : sortie manuelle avec motif obligatoire.

### 5.4 Fournisseurs & approvisionnement
- RG-A1 : Une réception validée crée les mouvements d'entrée et met à jour le CMUP du dépôt concerné.
- RG-A2 : Une réception peut être partielle ; la commande passe en `PARTIELLE` jusqu'à réception complète.
- RG-A3 : Réception à crédit ⇒ dette fournisseur (compte 401) ; le solde fournisseur = réceptions − paiements.
- RG-A4 : Un document validé ne se modifie pas ; on l'annule par un document inverse (avoir, retour).
- RG-A5 : Seule une commande en brouillon est modifiable. Annulation possible tant que rien n'est reçu ; une
  commande reçue en partie peut être **soldée** (le reste n'est plus attendu, statut `RECUE`). Une commande peut être
  reçue sans avoir été marquée envoyée. Le bon est partagé au fournisseur par WhatsApp (son numéro s'il est connu).
- RG-A6 : Une réception sur commande reprend le fournisseur et le dépôt de la commande ; le reste à recevoir est
  proposé au prix convenu, modifiable ; un produit hors commande est accepté. Sans commande : achat direct.
- RG-A7 : **Unités d'achat** : une quantité peut être saisie en carton, sac… ; elle est convertie dans l'unité du
  produit (refusée si le résultat n'est pas entier pour une unité entière). Les documents sont toujours enregistrés dans
  l'unité du produit. Le coût d'entrée en stock = montant de la ligne / quantité, arrondi à l'Ariary.
- RG-A8 : Numéros séquentiels sans trou par type de document (`CF`, `BR`, `TR`) ; aucun document n'est supprimé.

### 5.5 Transferts
- RG-T1 : Dépôt source ≠ dépôt destination.
- RG-T2 : À l'envoi : sortie du dépôt source (statut `EN_TRANSIT`) ; à la réception : entrée au dépôt destination.
- RG-T3 : Écart entre quantité envoyée et reçue ⇒ mouvement `PERTE` justifié : la quantité envoyée entre au dépôt
  de destination, puis le manque en sort en `PERTE` avec le motif saisi (obligatoire). Quantité reçue ≤ envoyée.
- RG-T5 : Un transfert en transit peut être annulé : la marchandise revient au dépôt source, à son coût d'envoi.
- RG-T4 : Le transfert se fait au CMUP du dépôt source ; il n'a **pas d'impact sur le résultat**.

### 5.6 Ventes
- RG-V0 : Au début de chaque vente, le vendeur choisit le type : **Détail** (par défaut) ou **Gros**.
  Le type ne change plus une fois la première ligne ajoutée.
- RG-V1 : Prix appliqué selon le type de vente : détail = `prix_point_vente.prix_detail` sinon `produit.prix_vente` ;
  gros = `prix_point_vente.prix_gros` sinon `produit.prix_vente_gros`.
- RG-V2 : Une vente nécessite une session de caisse **ouverte** sur le point de vente.
- RG-V3 : Le coût unitaire (CMUP) est **figé** sur la ligne au moment de la vente (calcul de marge).
- RG-V4 : Vente à crédit uniquement pour un client enregistré.
- RG-V5 : Remise au-delà d'un seuil ⇒ validation par un rôle gérant/propriétaire.
- RG-V6 : Annulation d'une vente = mouvements de stock inverses + écritures inverses (jamais de suppression).
- RG-V7 : **Vente en gros réservée aux clients préenregistrés.** Le client est créé au préalable depuis
  l'écran **Clients** (nom + numéro de téléphone, rien d'autre). À la vente en gros, le vendeur choisit un
  client existant (recherche par nom ou téléphone) ; aucune création de client depuis l'écran de vente.
  Sans client sélectionné, la vente en gros ne peut pas être validée.
- RG-V9 : **Retour** : quantités ≤ vendu − déjà retourné ; motif obligatoire ; articles remis en stock au coût figé
  de la vente ; remboursement au prorata de la remise, en espèces / mobile money (caisse ouverte du point de vente)
  ou déduit du crédit du client. L'annulation (RG-V6) n'est possible que tant que la caisse de la vente est ouverte
  et sans retour (permission gérant / propriétaire) ; ensuite, on passe par un retour.
- RG-V10 : Remise globale sur le ticket. Au-delà de `commerce.remise_max_pourcent` (10 % par défaut, réglé par le
  propriétaire), un vendeur doit faire saisir le code PIN d'un gérant ou du propriétaire (RG-V5), tracé dans l'audit.
- RG-V11 : Numéros sans trou `V-000001` (ventes) et `RV-000001` (retours). Le ticket est partagé par WhatsApp.
- RG-V8 : Téléphone client : numéro malgache valide (10 chiffres commençant par 0, ou +261 suivi de 9 chiffres),
  enregistré au format normalisé `+261XXXXXXXXX`, unique par client.

### 5.7 Caisse
- RG-C1 : Une seule session ouverte par caisse à la fois.
- RG-C2 : Solde théorique = fond initial + encaissements − décaissements.
- RG-C3 : À la clôture, l'utilisateur saisit le montant compté ; l'écart est enregistré et comptabilisé.
- RG-C4 : Aucune opération possible sur une session fermée.
- RG-C5 : Suivi séparé par mode de paiement (espèces, mobile money…).
- RG-C6 : Une caisse par point de vente, créée à sa première ouverture. Seules les espèces sont comptées à la
  clôture (écart = compté − théorique) ; mobile money et carte sont totalisés. Apports et retraits d'espèces avec motif ;
  un retrait ne peut pas dépasser les espèces en caisse.

### 5.8 Charges & dépenses
- RG-D1 : Toute dépense a une catégorie, elle-même liée à un compte de charge (classe 6).
- RG-D2 : Une dépense payée en espèces génère un décaissement dans la session de caisse ouverte.
- RG-D3 : Justificatif (photo) recommandé, obligatoire au-delà d'un montant paramétrable
  (`commerce.seuil_justificatif`, 50 000 Ar par défaut, réglé par le propriétaire).
- RG-D4 : Modes de règlement des dépenses, paiements fournisseurs et remboursements clients : espèces ou mobile money
  (opération dans la caisse **ouverte** choisie ; une sortie d'espèces ne dépasse pas les espèces en caisse) ou
  « hors caisse » (banque, argent du propriétaire : aucune opération de caisse). Numéros `DP`, `PF`, `EC`.
- RG-D5 : Une dépense s'annule (motif) tant que sa caisse est ouverte, l'argent y revient ; hors caisse, toujours.
- RG-D6 : Catégories pré-remplies à la création de la base, chacune avec son compte de charge (classe 6).
- RG-A9 : Paiement fournisseur rattaché à une réception (au plus son reste à payer) ou en acompte (au plus le solde).
  Une réception est en retard si elle n'est pas soldée après date + délai de paiement du fournisseur.
- RG-A10 : Retour au fournisseur depuis une réception (`RF`) : quantités ≤ reçu − déjà retourné, sortie de stock
  `RETOUR_FOURNISSEUR` au CMUP, la dette diminue du prix d'achat (exact pour une ligne entière).
- RG-V12 : Remboursement d'un crédit client (`EC`), au plus le crédit dû. Crédit dû = ventes à crédit − retours
  déduits du crédit − remboursements.

### 5.9 Comptabilité
- RG-K1 : **Partie double** : pour chaque écriture, Σ débit = Σ crédit.
- RG-K2 : Les écritures sont **générées automatiquement** à partir des documents validés
  (vente, réception, paiement, dépense, inventaire, clôture de caisse).
- RG-K3 : Plan comptable : **PCG 2005 Madagascar** (Plan Comptable Général, aligné sur les IFRS).
  Classes : 1 Capitaux, 2 Immobilisations, 3 Stocks, 4 Tiers, 5 Trésorerie, 6 Charges, 7 Produits.
  Le plan est pré-chargé ; le marchand peut ajouter des sous-comptes, pas modifier les comptes de base.
- RG-K4 : Aucune écriture validée n'est modifiable ou supprimable ; correction par contre-passation.
- RG-K5 : Aucune écriture possible sur un exercice clôturé.
- RG-K6 : États produits (format PCG 2005) : journal, grand livre, balance, **bilan**, **compte de résultat
  par nature**, **tableau des flux de trésorerie**, état des stocks.
- RG-K7 : Achats comptabilisés en charge (607) ; à chaque inventaire / clôture, la variation de stock
  (6037 ↔ 37) est passée à partir de la valeur CMUP calculée par l'app.
- RG-K8 : Monnaie de tenue des comptes : Ariary (MGA), sans décimales.

Schémas d'écritures types (PCG 2005) :

| Opération | Débit | Crédit |
|-----------|-------|--------|
| Vente comptant (espèces) | 53 Caisse | 707 Ventes de marchandises |
| Vente payée par mobile money | 51x Mobile money | 707 Ventes de marchandises |
| Vente à crédit | 411 Clients | 707 Ventes de marchandises |
| Encaissement client | 53 Caisse | 411 Clients |
| Retour client | 707 Ventes de marchandises | 53 Caisse / 411 Clients |
| Réception fournisseur | 607 Achats de marchandises | 401 Fournisseurs |
| Paiement fournisseur | 401 Fournisseurs | 53 Caisse / 512 Banque |
| Variation de stock (hausse) | 37 Stocks de marchandises | 6037 Variation des stocks |
| Variation de stock (baisse) | 6037 Variation des stocks | 37 Stocks de marchandises |
| Dépense (loyer, électricité, transport…) | 6x Charge correspondante | 53 Caisse |
| Écart de caisse négatif | 658 Charges diverses | 53 Caisse |
| Écart de caisse positif | 53 Caisse | 758 Produits divers |
| Apport du propriétaire | 53 Caisse | 108 Compte de l'exploitant |
| Retrait du propriétaire | 108 Compte de l'exploitant | 53 Caisse |

> Numéros de comptes à faire valider par un comptable malgache. Comptes utilisés par l'app : 517 mobile money,
> 512 banque et carte, 101 capital (stock initial apporté), 120 / 129 résultat.

- RG-K9 : Écritures passées dans la transaction du document (refus de l'écriture = refus du document).
  Annulations de vente et de dépense : contre-passation. Pertes, ventes et retours ne mouvementent pas le stock
  comptable : le compte 37 est ajusté (contrepartie 6037) à la valeur CMUP de tous les dépôts à chaque inventaire
  validé, à la clôture d'exercice, ou à la demande (« Mettre à jour la valeur du stock »).
- RG-K11 : **États** (S7), recalculés depuis les écritures, sans l'écriture de clôture de l'exercice :
  compte de résultat par nature (CA 70-72 ; consommations 60-62 ; personnel 64 ; impôts 63 ; autres produits /
  charges 75, 78 / 65, 68 ; financier 76 / 66), bilan (classes 2 à 5 selon le sens du solde, résultats antérieurs
  = 12 + charges et produits cumulés hors exercice), flux de trésorerie par méthode directe (chaque écriture
  touchant la classe 5 classée selon sa contrepartie principale), balance cumulée. Partage en texte (WhatsApp).
- RG-K10 : Exercices créés automatiquement selon le mois de début du commerce. Clôture (propriétaire, après la date
  de fin) : variation de stock puis solde des classes 6 et 7 vers 120 / 129. Les comptes de bilan sont cumulés
  depuis l'origine (pas d'écritures d'à-nouveaux).

### 5.10 Transverses
- RG-X1 : Toutes les données sont dans une **base locale** (Room) ; l'app fonctionne entièrement sans internet.
- RG-X1b : **Sauvegarde par WhatsApp** :
  - L'app crée une archive unique `tsena_sauvegarde_AAAAMMJJ_HHMM.zip` (base Room + dossier photos + fichier
    de version du schéma), puis ouvre le menu de partage Android avec WhatsApp proposé en premier.
  - Le marchand l'envoie à lui-même ou à un contact de confiance (ex. son propre numéro, un groupe privé).
  - Rappel de sauvegarde paramétrable (par défaut : chaque jour à la fermeture de caisse).
  - Photos compressées pour garder l'archive légère (WhatsApp limite la taille des documents).
- RG-X1c : **Restauration** : l'utilisateur ouvre le fichier `.zip` reçu dans WhatsApp (« Ouvrir avec Tsena »)
  ou le choisit dans les fichiers du téléphone. L'app vérifie l'archive et la version du schéma, demande
  confirmation (les données actuelles sont remplacées), puis redémarre. Réservé au rôle propriétaire.
- RG-X1d : **Vérification avant restauration** : intégrité SQLite, présence d'un commerce, version et empreinte
  du schéma Room. Refus (données actuelles intactes) si la sauvegarde vient d'une version plus récente, d'une
  version de test au même numéro mais de structure différente, ou d'une version trop ancienne sans migration.
- RG-X5b : **Rapport d'erreur** : un arrêt inattendu est enregistré ; au démarrage suivant, l'accueil propose de
  l'envoyer (texte technique, choix de l'utilisateur) ou de l'ignorer.
- RG-X2 : Accès par rôle : le vendeur ne voit que son point de vente ; le propriétaire voit tout.
  Plusieurs utilisateurs peuvent partager le téléphone : chacun se connecte avec son code PIN.
- RG-X3 : Journal d'audit (qui a fait quoi, quand) sur toutes les opérations sensibles.
- RG-X4 : Numérotation séquentielle sans trou pour ventes et documents comptables.
- RG-X5 : **Aucune taxe** n'est calculée ni comptabilisée ; les prix d'achat et de vente sont des montants nets.

---

## 6. Découpage en sprints (proposition — 2 semaines par sprint)

| Sprint | Contenu | Livrable |
|--------|---------|----------|
| **S0** | Projet Kotlin/Compose, architecture, base Room, sauvegarde .zip partagée par WhatsApp + restauration, authentification, rôles | Squelette de l'app, connexion, sauvegarde |
| **S1** | Référentiel : catégories, produits avec photo (caméra/galerie), unités, dépôts, points de vente | Catalogue produits illustré |
| **S2** | Stocks : mouvements, CMUP, consultation par dépôt, alertes, inventaire | Suivi de stock multi-dépôts |
| **S3** | Fournisseurs, commandes, réceptions, transferts inter-dépôts | Cycle d'approvisionnement complet |
| **S4** | Caisse (sessions, ouverture/clôture) + Ventes **détail / gros** (POS, paiements, retours) + fiches clients (nom + téléphone ; gros réservé aux clients préenregistrés) | Vente en boutique fonctionnelle |
| **S5** | Charges & dépenses, paiements fournisseurs, crédits et encaissements clients | Suivi des sorties d'argent |
| **S6** | Comptabilité : plan comptable PCG 2005, génération automatique des écritures, journaux | Écritures en partie double |
| **S7** | États financiers PCG 2005 (bilan, compte de résultat, flux de trésorerie), tableaux de bord, rapports (marges, ventes, stock) | Reporting |
| **S8** | Stabilisation, tests terrain, performance, test de restauration, déploiement | Version 1.0 |

Le MVP utilisable par un marchand est atteint à la fin de **S4**.

---

## 7. Questions ouvertes (à confirmer avant de démarrer)

Décidé : Kotlin (Android natif), base locale avec sauvegarde, photo et catégorie obligatoires par produit,
aucune gestion des taxes, comptabilité selon le PCG 2005 Madagascar,
**un seul téléphone** par commerce, sauvegarde partagée par WhatsApp.

1. **Ordre des sprints** : le découpage ci-dessus convient-il ?

---

## 8. Développement

### Structure du projet
```
app/src/main/java/mg/tsena/
  AppContainer.kt      injection de dépendances manuelle (TsenaApp.container)
  MainActivity.kt      point d'entrée + réception des sauvegardes (WhatsApp)
  domain/              rôles, permissions, utilitaires communs
  data/db/             base Room : entités, DAO, TsenaDatabase
  auth/                PIN, session, audit, AuthRepository
  backup/              BackupArchive (format zip, JVM pur) + BackupManager (Android)
  ui/                  écrans Compose + ViewModels (un dossier par écran)
```

### Conventions
- Code, noms et textes de l'interface en **français**.
- Package `mg.tsena`, minSdk 26, Kotlin + Jetpack Compose + Room (KSP), pas de framework d'injection.
- Chaque ViewModel expose un `Factory` qui récupère ses dépendances via `container`.
- Les violations de règles métier lèvent `RegleMetierException` avec un message affichable.
- **Schéma de base** : tant qu'aucune version n'est installée chez un marchand, le schéma version 1 est
  modifié directement (désinstaller l'app de test après un changement). Ensuite, tout changement incrémente `TsenaDatabase.VERSION` et ajoute une `Migration`
  (jamais de migration destructive : les données du marchand et les anciennes sauvegardes doivent rester restaurables).
  Les schémas exportés dans `app/schemas/` sont versionnés.
- Tests unitaires JVM dans `app/src/test` pour la logique sans Android.

### Publication (version signée)
1. Créer une fois la clé (à conserver précieusement hors du dépôt : sans elle, plus de mise à jour possible) :
   `keytool -genkey -v -keystore tsena.jks -alias tsena -keyalg RSA -keysize 2048 -validity 10000`
2. Secrets du dépôt GitHub : `TSENA_KEYSTORE_BASE64` (`base64 -w0 tsena.jks`), `TSENA_KEYSTORE_PASSWORD`,
   `TSENA_KEY_ALIAS`, `TSENA_KEY_PASSWORD`.
3. `git tag v1.0.0 && git push origin v1.0.0` : le workflow `release.yml` teste, signe et publie l'APK dans une
   version GitHub (avec les schémas Room en artefact).
4. **Après la première installation chez un marchand**, le schéma est figé : committer `app/schemas/.../1.json`
   (artefact de la CI) ; tout changement incrémente `TsenaDatabase.VERSION` et ajoute une migration dans
   `TsenaDatabase.MIGRATIONS` (aussi utilisée pour accepter les anciennes sauvegardes).

### Commandes
- `./gradlew assembleDebug` — compiler l'APK
- `./gradlew testDebugUnitTest` — tests unitaires
- Le wrapper Gradle (`gradlew`, `gradle-wrapper.jar`) est généré à la première ouverture dans Android Studio
  ou avec `gradle wrapper`.

### Avancement
- **S0** (code écrit, non compilé dans l'environnement de génération) : projet, base Room (commerce,
  utilisateur, utilisateur_role, journal_audit), premier lancement, connexion par PIN (5 essais puis
  blocage 30 s), rôles et permissions, gestion des utilisateurs, sauvegarde WhatsApp et restauration.
- **S1** (code écrit, non compilé) : catégories hiérarchiques, unités, produits avec photo (appareil photo
  ou galerie, redimensionnée 1024 px, JPEG 80 %), recherche et filtre par catégorie, dépôts et points de vente.
  Prix de vente en gros (facultatif) sur le produit.
  Reporté : conversion_unite (S3), prix_point_vente et rattachement vendeur ↔ point de vente (S4).
- **S2** (code écrit, non compilé) : stock par dépôt avec CMUP (`StockRepository.enregistrerMouvement`, seul point
  d'entrée pour les achats/ventes/transferts à venir), historique des mouvements, stock initial, pertes,
  alertes de stock (accueil + écran dédié), inventaires (comptage, validation, abandon), paramètre stock négatif.
  Reporté : écriture comptable des écarts d'inventaire (S6).
- **S3** (code écrit, non compilé localement) : fournisseurs (total des achats), unités d'achat (conversion_unite),
  commandes fournisseurs (brouillon, envoi par WhatsApp, annulation, solde), réceptions avec ou sans commande
  (entrée en stock au coût d'achat, CMUP, réceptions partielles), transferts inter-dépôts (envoi au CMUP source,
  réception avec manque en perte, annulation). Numérotation CF / BR / TR. `domain/Telephone` (format RG-V8).
  Reporté : paiements fournisseurs et solde dû (S5), retour fournisseur / annulation de réception (S5),
  écritures comptables des achats (S6).
- **S4** (code écrit, non compilé localement) : caisses (ouverture avec fond, apports / retraits, clôture avec écart,
  historique), point de vente détail / gros (prix du point de vente sinon du produit, panier, remise avec autorisation,
  paiements espèces / mobile money / carte / crédit / mixte, monnaie à rendre), ticket partagé par WhatsApp,
  historique des ventes, annulation et retours, clients (nom + téléphone, crédit dû), prix par point de vente,
  rattachement vendeur ↔ point de vente (RG-X2, pris en compte à la connexion).
  Reporté : encaissement des crédits clients et dépenses en caisse (S5), écritures comptables des ventes et écarts
  de caisse (S6), lecture de codes-barres par la caméra.
- **S5** (code écrit, non compilé localement) : dépenses par mois avec totaux par catégorie, justificatif photo,
  annulation, catégories (compte de charge) ; fiche fournisseur (solde, échéances, retards, paiements, acomptes) ;
  retours au fournisseur ; fiche client (crédit dû, achats, remboursements). Mode `BANQUE` (hors caisse).
  Reporté : écritures comptables (S6), annulation d'un paiement fournisseur ou d'un remboursement client.
- **S6** (code écrit, non compilé localement) : plan comptable PCG 2005 pré-chargé et sous-comptes, exercices
  automatiques et clôture, écritures générées par tous les documents (ventes, retours, annulations, réceptions,
  retours fournisseurs, paiements, dépenses, remboursements, apports / retraits, écarts de caisse, stock initial,
  inventaires), journaux par mois, grand livre par compte. Permission `GERER_COMPTABILITE` (propriétaire).
  Reporté : balance, bilan, compte de résultat, flux de trésorerie (S7).
- **S7** (code écrit, non compilé localement) : tableau de bord (ventes et marge du jour et du mois, dépenses,
  valeur du stock, crédits clients, dettes fournisseurs, alertes), rapport des ventes et marges par mois (par
  produit et point de vente, retours et remises déduits), état des stocks valorisé par dépôt, états financiers
  par exercice (compte de résultat, bilan, flux de trésorerie, balance) partageables. Correction : salaires au
  compte 641 (et non 661, charges financières).
- **Accueil** : grandes icônes par module (Catalogue, Stocks, Ventes, Approvisionnement, Dépenses, Comptabilité,
  Utilisateurs), visibles selon les permissions ; un appui ouvre la liste des écrans du module (`ui/home/Modules.kt`).
  Prix d'achat facultatif par produit (RG-P10).
- **S8** (code écrit, non compilé localement) : vérification complète des sauvegardes avant restauration
  (`CompatibiliteSchema`, testé), liste des migrations, rapport d'erreur à envoyer après un plantage, version 1.0.0,
  signature et publication par étiquette (`release.yml`), cahier de tests terrain (`docs/TESTS_TERRAIN.md`).
  Reste à faire sur le terrain : tests du cahier, mesure des performances, relecture comptable, première publication.
