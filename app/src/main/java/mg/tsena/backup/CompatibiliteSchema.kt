package mg.tsena.backup

/** Peut-on ouvrir une base sauvegardée avec la version actuelle du schéma ? Sans Android (testé sur JVM). */
object CompatibiliteSchema {
    sealed interface Verdict {
        data object Compatible : Verdict
        data class Refusee(val raison: String) : Verdict
    }

    /**
     * [versionBase] : PRAGMA user_version de la base sauvegardée ; [hashBase] / [hashAttendu] : empreintes Room
     * (room_master_table) de la base sauvegardée et de la version installée ; [migrations] : étapes (de, vers)
     * disponibles dans TsenaDatabase.
     */
    fun verifier(
        versionBase: Int,
        hashBase: String?,
        versionActuelle: Int,
        hashAttendu: String?,
        migrations: List<Pair<Int, Int>>,
    ): Verdict = when {
        versionBase > versionActuelle ->
            Verdict.Refusee("Cette sauvegarde vient d'une version plus récente de Tsena. Mettez l'application à jour.")
        versionBase < 1 || hashBase == null ->
            Verdict.Refusee("Ce fichier ne contient pas de données Tsena.")
        versionBase == versionActuelle && hashAttendu != null && hashBase != hashAttendu ->
            // Même numéro de version mais structure différente : sauvegarde d'une version de test
            Verdict.Refusee("Cette sauvegarde vient d'une version de test de Tsena, incompatible avec celle-ci.")
        versionBase < versionActuelle && !migrationPossible(versionBase, versionActuelle, migrations) ->
            Verdict.Refusee("Cette sauvegarde est trop ancienne pour cette version de Tsena.")
        else -> Verdict.Compatible
    }

    /** Une migration existe pour chaque étape de [depuis] à [vers]. */
    fun migrationPossible(depuis: Int, vers: Int, migrations: List<Pair<Int, Int>>): Boolean =
        (depuis until vers).all { v -> (v to v + 1) in migrations }
}
