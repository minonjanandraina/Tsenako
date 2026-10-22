package mg.tsena.banque

import mg.tsena.domain.Montant
import mg.tsena.domain.RegleMetierException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Ordre envoyé à la banque : entrée ([entree] = true) ou sortie d'argent du compte [compte]. */
data class OrdreBancaire(
    val compte: String,
    val montant: Long,
    val entree: Boolean,
    val libelle: String,
    /** Nom du bénéficiaire d'un virement sortant */
    val beneficiaire: String? = null,
)

/**
 * Point d'entrée unique vers une banque partenaire (banking as a platform) : l'app ne connaît que cette interface.
 * Aujourd'hui [LiloSimulee] ; demain l'API réelle de Lilo ou d'une autre banque, sans toucher au reste de l'app.
 * Une banque réelle sera appelée par le réseau : l'ordre est envoyé après toutes les vérifications locales, et une
 * réponse négative lève [RegleMetierException] pour annuler le document.
 */
interface PasserelleBancaire {
    /** Nom de la banque affiché à l'utilisateur */
    val banque: String

    /** Simulation : aucun argent réel ne circule. */
    val simulation: Boolean

    /** Ouvre un compte et renvoie son numéro. */
    suspend fun ouvrirCompte(titulaire: String): String

    /** Exécute l'ordre et renvoie la référence de la transaction ; lève [RegleMetierException] si la banque refuse. */
    suspend fun executer(ordre: OrdreBancaire): String
}

/** Banque Lilo simulée : numéros et références générés sur le téléphone, aucun appel réseau. */
class LiloSimulee(private val aleatoire: Random = Random.Default, private val aujourdhui: () -> LocalDate = LocalDate::now) :
    PasserelleBancaire {
    override val banque = "Lilo"
    override val simulation = true

    override suspend fun ouvrirCompte(titulaire: String): String =
        PREFIXE_COMPTE + (1..NB_CHIFFRES_COMPTE).joinToString("") { aleatoire.nextInt(10).toString() }

    override suspend fun executer(ordre: OrdreBancaire): String {
        if (ordre.montant <= 0) throw RegleMetierException("Le montant doit être supérieur à zéro.")
        if (ordre.montant > PLAFOND_OPERATION) {
            throw RegleMetierException("Lilo refuse les opérations de plus de ${Montant.formater(PLAFOND_OPERATION)}.")
        }
        return "LL" + aujourdhui().format(FORMAT_DATE) + aleatoire.nextInt(1_000_000).toString().padStart(6, '0')
    }

    companion object {
        const val PREFIXE_COMPTE = "LILO-"
        const val NB_CHIFFRES_COMPTE = 10
        /** Plafond par opération de la simulation, comme une banque réelle en impose un */
        const val PLAFOND_OPERATION = 50_000_000L
        private val FORMAT_DATE = DateTimeFormatter.ofPattern("yyMMdd")
    }
}
