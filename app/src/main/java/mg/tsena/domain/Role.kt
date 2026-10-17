package mg.tsena.domain

enum class Permission {
    GERER_UTILISATEURS,
    /** Dépôts et points de vente */
    GERER_REFERENTIEL,
    SAUVEGARDER,
    RESTAURER,
    GERER_PRODUITS,
    GERER_STOCK,
    GERER_APPRO,
    VENDRE,
    GERER_CAISSE,
    GERER_DEPENSES,
    /** Apports et retraits du coffre */
    GERER_COFFRE,
    VOIR_COMPTABILITE,
    VOIR_TOUS_POINTS_VENTE,
    /** RG-V5 : remise au-delà du seuil du commerce */
    ACCORDER_REMISE,
    /** RG-V6 : annulation d'une vente */
    ANNULER_VENTE,
    /** Plan comptable (sous-comptes) et clôture des exercices : propriétaire uniquement */
    GERER_COMPTABILITE,
    /** Injections de capital, emprunts et remboursements (RG-F5) : propriétaire uniquement */
    GERER_FINANCEMENT,
    /** Paramètres du commerce (stock négatif…) : propriétaire uniquement */
    PARAMETRER_COMMERCE,
}

enum class Role(val libelle: String, val permissions: Set<Permission>) {
    PROPRIETAIRE("Propriétaire", Permission.entries.toSet()),
    GERANT(
        "Gérant",
        setOf(
            Permission.SAUVEGARDER,
            Permission.GERER_PRODUITS,
            Permission.GERER_STOCK,
            Permission.GERER_APPRO,
            Permission.VENDRE,
            Permission.GERER_CAISSE,
            Permission.GERER_DEPENSES,
            Permission.GERER_COFFRE,
            Permission.VOIR_COMPTABILITE,
            Permission.VOIR_TOUS_POINTS_VENTE,
            Permission.ACCORDER_REMISE,
            Permission.ANNULER_VENTE,
        ),
    ),
    VENDEUR("Vendeur", setOf(Permission.VENDRE, Permission.GERER_CAISSE)),
    MAGASINIER("Magasinier", setOf(Permission.GERER_STOCK, Permission.GERER_APPRO)),
}
