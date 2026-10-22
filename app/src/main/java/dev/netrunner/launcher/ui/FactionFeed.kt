package dev.netrunner.launcher.ui

import java.time.LocalTime

/** What the phone reports, for the feed under the clock. */
data class FeedInfo(
    val battery: Int,
    val charging: Boolean,
    val tempC: Int?,
    val ramPct: Int,
    val notifications: Int,
    val time: LocalTime = LocalTime.now(),
)

/**
 * The terminal line under the home clock, in the faction's voice: real readings (battery,
 * temperature, memory, notifications, time of day) told the way that faction would, mixed
 * with a few lines of its lore.
 */
object FactionFeed {
    fun lines(faction: Faction, i: FeedInfo): List<String> {
        val hot = (i.tempC ?: 0) >= 42
        val low = i.battery in 0..19
        val night = i.time.hour >= 22 || i.time.hour < 6
        val temp = i.tempC?.let { "$it°C" } ?: "N/D"
        return when (faction) {
            Faction.Netrunner -> listOf(
                "> CONNEXION AU RÉSEAU......... OK",
                "> CYBERDECK : $temp, ${if (hot) "SURCHAUFFE, DÉBRANCHE" else "TEMPÉRATURE NOMINALE"}",
                "> RAM ALLOUÉE : ${i.ramPct} %",
                if (i.notifications > 0) "> ${i.notifications} PAQUET(S) EN ATTENTE DE DÉCRYPTAGE" else "> AUCUN PAQUET EN ATTENTE",
                "> BATTERIE : ${i.battery} %${if (i.charging) " // EN CHARGE" else ""}",
                "> PROTOCOLE DE BRÈCHE PRÊT",
                "> PUBS XIAOMI : NEUTRALISÉES",
                "> BIENVENUE À NIGHT CITY, NETRUNNER",
            )
            Faction.Arasaka -> listOf(
                "> ARASAKA : SÉCURITÉ, PROSPÉRITÉ, LOYAUTÉ",
                "> RÉSERVE ÉNERGÉTIQUE : ${i.battery} % // ${if (low) "NON CONFORME, RECHARGE EXIGÉE" else "CONFORME"}",
                if (i.notifications > 0) "> ${i.notifications} DOSSIER(S) EN ATTENTE DE VOTRE APPROBATION" else "> BOÎTE DE RÉCEPTION : TRAITÉE. EXCELLENT TRAVAIL",
                "> CHARGE DES SYSTÈMES : ${i.ramPct} % // RENDEMENT SURVEILLÉ",
                if (night) "> HEURES SUPPLÉMENTAIRES ENREGISTRÉES" else "> PRÉSENCE AU POSTE CONFIRMÉE",
                "> LA FAMILLE ARASAKA VOUS REMERCIE",
                "> TOUTE DÉLOYAUTÉ SERA SIGNALÉE",
            )
            Faction.Militech -> listOf(
                "> MILITECH // SITREP",
                "> CELLULE D'ÉNERGIE : ${i.battery} %${if (i.charging) " // RAVITAILLEMENT" else ""}",
                "> TEMPÉRATURE DU CANON : $temp${if (hot) " // REFROIDIR L'ARME" else ""}",
                "> CHARGE TACTIQUE : ${i.ramPct} %",
                if (i.notifications > 0) "> ${i.notifications} ORDRE(S) DE MISSION REÇU(S)" else "> AUCUN ORDRE, TENIR LA POSITION",
                "> HEURE ZOULOU : ${"%02d%02d".format(i.time.hour, i.time.minute)}",
                "> PRÊT AU DÉPLOIEMENT",
            )
            Faction.Netwatch -> listOf(
                "> NETWATCH // SURVEILLANCE ACTIVE",
                if (i.notifications > 0) "> ${i.notifications} SIGNAL(AUX) INTERCEPTÉ(S)" else "> AUCUNE TRANSMISSION SUSPECTE",
                "> CHARGE DU NŒUD : ${i.ramPct} %",
                "> BLACKWALL : INTÈGRE",
                "> IA HOSTILE DANS LE SECTEUR : AUCUNE",
                "> AUTONOMIE DU TERMINAL : ${i.battery} %",
                "> CET APPAREIL EST CONFORME",
            )
            Faction.VoodooBoys -> listOf(
                "> PACIFICA REÇOIT, CHOOM",
                if (i.notifications > 0) "> LES LOAS ONT ENTENDU ${i.notifications} MURMURE(S)" else "> LES LOAS SONT SILENCIEUX",
                "> ÉNERGIE DU CORPS : ${i.battery} %${if (low) " // TU T'ÉTEINS, RECHARGE-TOI" else ""}",
                "> ESPRIT OCCUPÉ À ${i.ramPct} %",
                if (night) "> LA NUIT, LE NET EST PLUS PROCHE" else "> LE JOUR, ON SE CACHE DE NETWATCH",
                "> AU-DELÀ DU BLACKWALL, QUELQUE CHOSE RÉPOND",
                "> PLACIDE NE DONNE PAS SA CONFIANCE",
            )
            Faction.Barghest -> listOf(
                "> BARGHEST // DOGTOWN EST SOUS CONTRÔLE",
                if (i.notifications > 0) "> RAPPORT DE PATROUILLE : ${i.notifications} TRANSMISSION(S)" else "> RAPPORT DE PATROUILLE : SECTEUR CALME",
                "> CHECKPOINT : AUTONOMIE ${i.battery} %${if (low) " // RETOUR À LA BASE" else ""}",
                "> TEMPÉRATURE DU MATÉRIEL : $temp",
                if (night) "> COUVRE-FEU EN VIGUEUR, ÉVITE LES PATROUILLES" else "> MARCHÉ DU STADE : OUVERT",
                "> CHARGE DU RÉSEAU LOCAL : ${i.ramPct} %",
                "> ICI, LA LOI C'EST HANSEN",
                "> PAS DE NUSA À DOGTOWN",
            )
            Faction.Ghost -> listOf(
                "> SILENCE RADIO",
                "> BATTERIE ${i.battery} %",
                "> PERSONNE NE TE VOIT",
            )
        }
    }
}
