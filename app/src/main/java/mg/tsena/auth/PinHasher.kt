package mg.tsena.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Hachage des codes PIN (PBKDF2 + sel aléatoire) ; le PIN n'est jamais stocké en clair. */
object PinHasher {
    private const val ALGORITHME = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 50_000
    private const val LONGUEUR_CLE = 256
    private const val LONGUEUR_SEL = 16

    private val aleatoire = SecureRandom()

    fun formatValide(pin: String): Boolean = pin.length in 4..6 && pin.all { it in '0'..'9' }

    fun nouveauSel(): String = ByteArray(LONGUEUR_SEL).also(aleatoire::nextBytes).versHex()

    fun hacher(pin: String, sel: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), sel.depuisHex(), ITERATIONS, LONGUEUR_CLE)
        return try {
            SecretKeyFactory.getInstance(ALGORITHME).generateSecret(spec).encoded.versHex()
        } finally {
            spec.clearPassword()
        }
    }

    fun verifier(pin: String, sel: String, hashAttendu: String): Boolean =
        MessageDigest.isEqual(hacher(pin, sel).toByteArray(), hashAttendu.toByteArray())

    private fun ByteArray.versHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.depuisHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
