# Tsena 1.0 — Cahier de tests terrain

À dérouler sur le téléphone du commerce (ou un téléphone équivalent), avec l'APK de la dernière version.
Cocher chaque ligne ; noter tout écart (capture d'écran + rapport d'erreur si l'app s'arrête).

> Si l'application s'arrête : au redémarrage, l'accueil propose **« Envoyer »** le rapport d'erreur.
> L'envoyer par WhatsApp au développeur avec une phrase sur ce qui était en cours.

## 0. Préparation
- [ ] Désinstaller toute version de test précédente, installer l'APK 1.0.
- [ ] Premier lancement : créer le commerce et le propriétaire (code PIN).
- [ ] Créer un gérant, un vendeur (rattaché à un point de vente), un magasinier.

## 1. Catalogue
- [ ] Créer 3 catégories (dont une sous-catégorie) et 10 produits avec photo (appareil photo et galerie).
- [ ] Un produit au kg (quantités décimales), un produit avec prix de gros, un sans.
- [ ] Prix d'achat renseigné : la marge indicative s'affiche dans la fiche.
- [ ] Un dépôt « Réserve » et un point de vente « Boutique » (dépôt magasin créé automatiquement).

## 2. Stock
- [ ] Stock initial sur 5 produits (le prix d'achat est proposé comme coût).
- [ ] Déclarer une perte avec motif.
- [ ] Seuil d'alerte : l'icône Stocks de l'accueil affiche l'alerte.
- [ ] Inventaire d'un dépôt : compter 3 produits, valider, vérifier les ajustements.

## 3. Approvisionnement
- [ ] Fournisseur avec téléphone ; unité d'achat « carton = 24 pièces ».
- [ ] Commande en cartons (prix converti), envoi au fournisseur par WhatsApp.
- [ ] Réception partielle puis réception du reste ; la commande passe à « Reçue ».
- [ ] Achat sans commande ; retour d'un article au fournisseur.
- [ ] Transfert Réserve → Boutique, réception avec un manque (motif obligatoire).

## 4. Ventes et caisse
- [ ] Connexion vendeur : il ne voit que sa boutique.
- [ ] Ouvrir la caisse avec un fond ; vente détail en espèces (monnaie à rendre correcte).
- [ ] Vente en gros : client obligatoire, prix de gros appliqué.
- [ ] Vente mixte espèces + crédit ; ticket envoyé par WhatsApp.
- [ ] Remise au-delà du seuil : code PIN du gérant demandé.
- [ ] Retour d'un article ; annulation d'une vente (gérant).
- [ ] Apport et retrait d'espèces ; clôture avec un écart volontaire.

## 5. Dépenses et règlements
- [ ] Dépense en espèces avec photo du justificatif ; dépense « hors caisse ».
- [ ] Dépense au-delà du seuil sans photo : refusée.
- [ ] Paiement partiel d'un fournisseur ; le solde et les retards se mettent à jour.
- [ ] Remboursement partiel d'un client à crédit.

## 6. Comptabilité et rapports
- [ ] Journaux du mois : chaque opération ci-dessus a son écriture, totaux débit = crédit.
- [ ] « Mettre à jour la valeur du stock » : compte 37 = valeur de l'état des stocks.
- [ ] Tableau de bord, ventes et marges, état des stocks : chiffres cohérents avec les écrans de vente et de stock.
- [ ] États financiers : bilan équilibré (aucun message d'écart), partage du compte de résultat.
- [ ] Faire relire le plan comptable et les états par un comptable (numéros de comptes PCG 2005).

## 7. Sauvegarde et restauration (essentiel)
- [ ] Sauvegarder et envoyer par WhatsApp à son propre numéro ; noter la taille du fichier.
- [ ] Sur un **second téléphone** (ou après désinstallation) : premier lancement → « Restaurer », ouvrir le fichier
      reçu dans WhatsApp avec Tsena.
- [ ] Après redémarrage : se connecter, vérifier produits et photos, stocks, ventes, caisse, écritures.
- [ ] Essayer de restaurer un fichier qui n'est pas une sauvegarde : message clair, données intactes.
- [ ] Essayer de restaurer une sauvegarde d'une ancienne version de test : refusée avec un message, données intactes.

## 8. Usage réel
- [ ] Une semaine d'utilisation en boutique ; noter les lenteurs (écran, action, nombre de produits / ventes).
- [ ] Vérifier la place occupée par l'app (Paramètres Android) et la taille des sauvegardes.
