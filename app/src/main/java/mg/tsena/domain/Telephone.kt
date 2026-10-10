package mg.tsena.domain

/** Numéros de téléphone malgaches (RG-V8), enregistrés au format +261XXXXXXXXX. */
object Telephone {
    /** « 034 12 345 67 », « 0341234567 », « +261 34 12 345 67 » → « +261341234567 » ; null si invalide. */
    fun normaliser(texte: String): String? {
        val t = texte.filter { it.isDigit() || it == '+' }
        val national = when {
            t.startsWith("+261") -> t.removePrefix("+261")
            t.startsWith("00261") -> t.removePrefix("00261")
            t.startsWith("0") && t.length == 10 -> t.substring(1)
            else -> return null
        }
        if (national.length != 9 || !national.all { it.isDigit() }) return null
        return "+261$national"
    }

    /** « +261341234567 » → « 034 12 345 67 » */
    fun formater(normalise: String): String {
        val n = normalise.removePrefix("+261")
        if (n.length != 9) return normalise
        return "0${n.substring(0, 2)} ${n.substring(2, 4)} ${n.substring(4, 7)} ${n.substring(7)}"
    }
}
