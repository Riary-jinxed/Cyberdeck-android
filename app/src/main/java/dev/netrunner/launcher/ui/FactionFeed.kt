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
    enum class Power { Plugged, Unplugged, Low }

    /** Title and line for a power event banner, in the faction's voice. */
    fun power(faction: Faction, event: Power, level: Int): Pair<String, String> = when (event) {
        Power.Plugged -> when (faction) {
            Faction.Arasaka -> "Recharge autorisée" to "Réserve à $level %. La société apprécie votre prévoyance."
            Faction.Militech -> "Ravitaillement" to "Cellule d'énergie à $level %, recharge en cours."
            Faction.Netwatch -> "Alimentation détectée" to "Terminal sur secteur, $level %. Connexion journalisée."
            Faction.VoodooBoys -> "Le courant passe" to "$level %. Les loas boivent à la prise."
            Faction.Barghest -> "Ravitaillement au checkpoint" to "Autonomie $level %, on recharge avant la patrouille."
            else -> "Jack-in secteur" to "Batterie à $level %, recharge en cours."
        }
        Power.Unplugged -> when (faction) {
            Faction.Arasaka -> "Fin de recharge" to "Réserve à $level %. Retour au poste."
            Faction.Militech -> "Déploiement" to "Sur batterie, $level %. Bonne chasse."
            Faction.Netwatch -> "Alimentation coupée" to "Terminal sur batterie, $level %."
            Faction.VoodooBoys -> "Hors du flux" to "$level %. Garde ton énergie pour le net."
            Faction.Barghest -> "En patrouille" to "Débranché à $level %. Dogtown t'attend."
            else -> "Jack-out" to "Sur batterie, $level %."
        }
        Power.Low -> when (faction) {
            Faction.Arasaka -> "Réserve non conforme" to "$level % restants. Rechargez sans délai."
            Faction.Militech -> "Munitions faibles" to "Cellule à $level %. Retour à la base conseillé."
            Faction.Netwatch -> "Terminal en danger" to "$level % restants. Perte de liaison imminente."
            Faction.VoodooBoys -> "Ton esprit s'éteint" to "$level %. Trouve une prise avant que le net ne t'oublie."
            Faction.Barghest -> "Autonomie critique" to "$level %. Rentre au checkpoint, choom."
            else -> "Batterie faible" to "$level % restants. Le Ripperdoc passe en mode éco."
        }
    }

    /** How the faction names an incoming notification, on the banner over other apps. */
    fun incoming(faction: Faction): String = when (faction) {
        Faction.Netrunner -> "PAQUET ENTRANT"
        Faction.Arasaka -> "DOSSIER TRANSMIS"
        Faction.Militech -> "ORDRE REÇU"
        Faction.Netwatch -> "SIGNAL INTERCEPTÉ"
        Faction.VoodooBoys -> "UN LOA MURMURE"
        Faction.Barghest -> "TRANSMISSION RADIO"
        Faction.Ghost -> "SIGNAL"
    }

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
