package dev.netrunner.launcher.kit

import android.content.Context
import dev.netrunner.launcher.ui.Faction

/**
 * Reputation earned on the job, worked out from the logbook each time (nothing extra is stored):
 * points for every closed intervention, more when it is well documented, ranks named in the
 * faction's style, and achievements for milestones.
 */
object Reputation {
    /** Name of the feature in the kit and on the home widget. */
    const val TITLE = "DOSSIER TECH"

    data class Rank(val level: Int, val title: String, val from: Int, val to: Int?)
    data class Achievement(val title: String, val detail: String, val progress: Int, val target: Int) {
        val unlocked get() = progress >= target
    }
    data class Status(val points: Int, val closed: Int, val rank: Rank, val next: Rank?, val achievements: List<Achievement>)

    private val Thresholds = listOf(0, 500, 1500, 3500, 7000, 12000, 20000)

    private fun titles(faction: Faction): List<String> = when (faction) {
        Faction.Arasaka -> listOf("Stagiaire", "Employé", "Agent qualifié", "Superviseur", "Chef de département", "Directeur", "Conseiller de la famille")
        Faction.Militech -> listOf("Recrue", "Soldat", "Caporal", "Sergent", "Lieutenant", "Capitaine", "Commandant")
        Faction.Barghest -> listOf("Bleu", "Milicien", "Éclaireur", "Chef de patrouille", "Sergent de la milice", "Officier de Hansen", "Bras droit du colonel")
        Faction.VoodooBoys -> listOf("Novice", "Initié", "Passeur", "Houngan du net", "Gardien des loas", "Mambo", "Voix du Blackwall")
        Faction.Netwatch -> listOf("Analyste", "Agent", "Agent spécial", "Traqueur", "Superviseur", "Directeur de section", "Gardien du Blackwall")
        else -> listOf("Recrue", "Technicien", "Technicien confirmé", "Spécialiste", "Expert réseau", "Chef de chantier", "Légende de Night City")
    }

    /** Points for one closed intervention; open ones count for nothing yet. */
    fun points(i: Intervention): Int {
        if (i.open) return 0
        val runs = Logbook.runsOf(i)
        val checklistsDone = runs.isNotEmpty() && runs.all { it.done.size >= it.steps.size }
        return 100 +
            minOf(i.photos.size, 5) * 15 +
            (if (checklistsDone) 60 else 0) +
            (if (i.journal.size >= 3) 30 else 0) +
            (if (i.consumed.isNotEmpty()) 20 else 0) +
            (if (i.client.isNotBlank()) 10 else 0)
    }

    fun status(context: Context, faction: Faction): Status {
        val closed = Logbook.items.filter { !it.open }
        val points = closed.sumOf { points(it) }
        val names = titles(faction)
        val k = Thresholds.indexOfLast { points >= it }.coerceAtLeast(0)
        fun rank(n: Int) = Rank(n + 1, names[n], Thresholds[n], Thresholds.getOrNull(n + 1))
        val docs = Clients.items.sumOf { ClientDocs.list(context, it.id).size }
        val fullChecklists = closed.count { i -> Logbook.runsOf(i).let { r -> r.isNotEmpty() && r.all { it.done.size >= it.steps.size } } }
        val clients = closed.map { it.client.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().size
        val longest = closed.maxOfOrNull { it.ended - it.started } ?: 0L
        val quick = closed.count { it.ended - it.started in 1 until 30 * 60_000L && it.journal.isNotEmpty() }
        val achievements = listOf(
            Achievement("Premier contrat", "Clôturer une intervention", closed.size, 1),
            Achievement("Habitué du terrain", "10 interventions clôturées", closed.size, 10),
            Achievement("Vétéran", "50 interventions clôturées", closed.size, 50),
            Achievement("Centurion", "100 interventions clôturées", closed.size, 100),
            Achievement("Méthodique", "10 interventions avec toutes les checklists terminées", fullChecklists, 10),
            Achievement("Œil du photographe", "50 photos prises en intervention", closed.sumOf { it.photos.size }, 50),
            Achievement("Carnet de bord", "100 entrées de journal", closed.sumOf { it.journal.size }, 100),
            Achievement("Carnet d'adresses", "Travailler pour 5 clients différents", clients, 5),
            Achievement("Prestataire de confiance", "Travailler pour 15 clients différents", clients, 15),
            Achievement("Câbleur", "50 liaisons relevées dans CÂBLES", KitStore.cables.size, 50),
            Achievement("Maître câbleur", "250 liaisons relevées", KitStore.cables.size, 250),
            Achievement("Logisticien", "Noter 20 consommations de stock", closed.sumOf { it.consumed.size }, 20),
            Achievement("Cartographe Wi-Fi", "3 relevés de couverture", Coverage.items.size, 3),
            Achievement("Documentaliste", "Classer 20 documents dans les fiches clients", docs, 20),
            Achievement("Marathon", "Une intervention de plus de 8 h", if (longest >= 8 * 3_600_000L) 1 else 0, 1),
            Achievement("Éclair", "5 interventions bouclées en moins de 30 min", quick, 5),
        )
        return Status(points, closed.size, rank(k), if (k + 1 < Thresholds.size) rank(k + 1) else null, achievements)
    }
}
