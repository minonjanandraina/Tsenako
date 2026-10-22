package mg.tsena.banque

/**
 * Id Tsenako : numéro de compte Tsenako d'un tiers (ex. un fournisseur), destinataire de nos virements (RG-B6).
 * Saisi librement (espaces, minuscules) puis enregistré en majuscules, sans espaces : `LILO-0123456789`.
 */
object IdTsenako {
    private val FORMAT = Regex("[A-Z0-9]+(-[A-Z0-9]+)*")
    private const val LONGUEUR_MIN = 6
    private const val LONGUEUR_MAX = 34

    /** Forme normalisée, ou null si [texte] n'est pas un id valide. */
    fun normaliser(texte: String): String? {
        val id = texte.filterNot { it.isWhitespace() }.uppercase()
        return id.takeIf { it.length in LONGUEUR_MIN..LONGUEUR_MAX && FORMAT.matches(it) }
    }
}
