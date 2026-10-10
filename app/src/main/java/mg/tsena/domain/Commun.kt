package mg.tsena.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Violation d'une règle métier ; le message est affiché tel quel à l'utilisateur. */
open class RegleMetierException(message: String) : Exception(message)

/** Action possible seulement avec le code PIN d'un utilisateur ayant la permission (ex. remise au-delà du seuil). */
class AutorisationRequiseException(message: String) : RegleMetierException(message)

fun nouvelId(): String = UUID.randomUUID().toString()

fun maintenant(): Long = System.currentTimeMillis()

private val formatDateHeure = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

fun formaterDateHeure(millis: Long): String = formatDateHeure.format(Instant.ofEpochMilli(millis))
