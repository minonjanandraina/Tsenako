package mg.tsena.catalogue

/** Calculs sur l'arbre des catégories (JVM pur, testable). */
object Categories {
    const val SEPARATEUR = " › "

    /** « Boissons › Sodas » ; protégé contre les cycles. */
    fun chemin(id: String, nomDe: (String) -> String?, parentDe: (String) -> String?): String {
        val noms = ArrayDeque<String>()
        val vus = mutableSetOf<String>()
        var courant: String? = id
        while (courant != null && vus.add(courant)) {
            noms.addFirst(nomDe(courant) ?: break)
            courant = parentDe(courant)
        }
        return noms.joinToString(SEPARATEUR)
    }

    /** Vrai si [candidat] est [ancetre] lui-même ou l'une de ses sous-catégories (à toute profondeur). */
    fun estDans(candidat: String, ancetre: String, parentDe: (String) -> String?): Boolean {
        val vus = mutableSetOf<String>()
        var courant: String? = candidat
        while (courant != null && vus.add(courant)) {
            if (courant == ancetre) return true
            courant = parentDe(courant)
        }
        return false
    }

    /** Placer [id] sous [candidatParent] créerait un cycle si le parent est [id] ou l'un de ses descendants. */
    fun creeraitUnCycle(id: String, candidatParent: String, parentDe: (String) -> String?): Boolean =
        estDans(candidatParent, id, parentDe)

    /** Prochain code produit libre : P00001, P00002… */
    fun prochainCode(codesExistants: Collection<String>): String {
        val max = codesExistants
            .mapNotNull { code -> code.removePrefix("P").takeIf { code.startsWith("P") }?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "P%05d".format(max + 1)
    }
}
