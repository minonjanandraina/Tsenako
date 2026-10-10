package mg.tsena.domain

enum class Permission {
    GERER_UTILISATEURS,
    SAUVEGARDER,
    RESTAURER,
    GERER_PRODUITS,
    GERER_STOCK,
    GERER_APPRO,
    VENDRE,
    GERER_CAISSE,
    GERER_DEPENSES,
    VOIR_COMPTABILITE,
    VOIR_TOUS_POINTS_VENTE,
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
            Permission.VOIR_COMPTABILITE,
            Permission.VOIR_TOUS_POINTS_VENTE,
        ),
    ),
    VENDEUR("Vendeur", setOf(Permission.VENDRE, Permission.GERER_CAISSE)),
    MAGASINIER("Magasinier", setOf(Permission.GERER_STOCK, Permission.GERER_APPRO)),
}
