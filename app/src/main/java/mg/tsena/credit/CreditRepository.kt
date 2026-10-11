package mg.tsena.credit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import mg.tsena.data.db.TsenaDatabase
import mg.tsena.data.db.entity.ClientAvecCredit
import mg.tsena.domain.maintenant

/** Client et suivi de ses ventes à crédit. */
data class CreditClient(val client: ClientAvecCredit, val ventes: List<EtatCreditVente>) {
    val du: Long get() = client.creditDu
    val impayees: List<EtatCreditVente> get() = ventes.filter { it.reste > 0 }
    /** Ancienneté de la plus vieille vente non soldée, en jours */
    val plusAncienJours: Int? get() = impayees.maxOfOrNull { it.ageJours }
    val enRetard: Boolean get() = (plusAncienJours ?: 0) > Credits.DELAI_JOURS
}

/** Suivi des ventes à crédit : reste dû par client et par vente, ancienneté des impayés. */
class CreditRepository(private val db: TsenaDatabase) {
    fun observerCredits(): Flow<List<CreditClient>> =
        combine(
            db.clientDao().observerAvecCredit(),
            db.venteDao().observerVentesCredit(),
            db.encaissementClientDao().observerTotauxParClient(),
        ) { clients, ventes, rembourses ->
            val maintenant = maintenant()
            val ventesParClient = ventes.groupBy { it.clientId }
            val rembourseParClient = rembourses.associate { it.clientId to it.total }
            clients.map { c ->
                CreditClient(
                    client = c,
                    ventes = Credits.suivre(ventesParClient[c.client.id].orEmpty(), rembourseParClient[c.client.id] ?: 0, maintenant),
                )
            }
        }

    fun observerCreditClient(clientId: String): Flow<CreditClient?> =
        observerCredits().map { liste -> liste.firstOrNull { it.client.client.id == clientId } }
}
