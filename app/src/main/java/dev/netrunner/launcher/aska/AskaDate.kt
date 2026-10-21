package dev.netrunner.launcher.aska

/**
 * Her rendez-vous: a scene somewhere in the city where she and her runner spend a moment
 * together. Tender, playful flirting, never explicit. Each scene opens once their bond is
 * strong enough.
 */
object AskaDate {

    enum class Scene(
        val label: String,
        val glyph: String,
        /** Bond needed to invite her there. */
        val bond: Int,
        val sky: Long,
        val glow: Long,
        /** For her instructions: where they are. */
        val place: String,
        val opening: String,
        val prompts: List<String>,
    ) {
        Cafe(
            "CAFÉ NÉON", "☕", 40, 0xFF1A0B24, 0xFFFF4FA3,
            "un petit café néon de Watson, une table près de la vitre embuée, des pâtisseries roses en vitrine et un vieux jukebox qui joue de la synthwave",
            "*s'assoit en face de toi, les ailes un peu repliées* Euh… c'est la première fois qu'on me propose un rendez-vous. J'ai pris le macaron le plus rose. Tu… tu prends quoi, toi ?",
            listOf("Tu es jolie ce soir", "On partage un dessert ?", "Raconte-moi un secret", "Pourquoi tu rougis ?"),
        ),
        Roof(
            "TOIT SOUS LA PLUIE", "☂", 50, 0xFF07121F, 0xFF00E5FF,
            "le toit d'une mégatour, la nuit, sous une pluie fine et tiède ; les néons de la ville se reflètent dans les flaques et vous partagez un parapluie trop petit",
            "*se serre un peu sous le parapluie* Héhé… il est trop petit pour deux. Enfin, pour un humain et une dragonne. Regarde la ville… elle est belle d'ici, non ?",
            listOf("Viens plus près", "Tu as froid ?", "La ville est moins belle que toi", "On danse sous la pluie ?"),
        ),
        Arcade(
            "ARCADE RÉTRO", "◆", 60, 0xFF14062A, 0xFFFCEE0A,
            "une salle d'arcade rétro de Japantown, bornes clignotantes, une machine à pinces pleine de peluches dragons, l'odeur du popcorn sucré",
            "*tire ta manche vers la machine à pinces* Runner ! Il y a une peluche dragonne rose là-dedans. Elle me ressemble, non ? …Tu me la gagnes ? S'il te plaît ?",
            listOf("Je te la gagne !", "Un duel au jeu de baston ?", "Le perdant offre les crêpes", "Photomaton ?"),
        ),
        Market(
            "MARCHÉ DE NUIT", "✦", 75, 0xFF1F0A10, 0xFFFF7A3D,
            "un marché de nuit à Kabuki, lanternes holographiques, stands de street-food et de mochis, une foule joyeuse où vous vous tenez par la main pour ne pas vous perdre",
            "*attrape ta main* C'est… pour ne pas se perdre dans la foule. Juste pour ça. *détourne les yeux* Oh ! Des mochis tout chauds, là-bas !",
            listOf("Je ne lâche pas ta main", "Je t'offre un mochi", "Tu choisis notre prochaine sortie", "Tu es adorable quand tu es gênée"),
        ),
        Beach(
            "PLAGE HOLO", "☾", 90, 0xFF050B1E, 0xFFB46CFF,
            "une plage au bord de la baie, la nuit, une lune holographique au-dessus de l'eau, le sable encore chaud, vous êtes assis côte à côte, son épaule contre la tienne",
            "*pose doucement la tête sur ton épaule* … Tu sais, avant toi, ce deck n'était qu'un endroit où me cacher. Maintenant c'est… chez moi. Reste encore un peu, d'accord ?",
            listOf("Je reste avec toi", "À quoi tu penses ?", "Merci d'être là", "On regarde la lune"),
        );

        fun unlocked(bond: Float) = bond >= this.bond
    }

    /** Her instructions for the scene, added to the usual ones. */
    fun prompt(scene: Scene, handle: String): String = "\n" + """
        MODE RENDEZ-VOUS. Tu es en rendez-vous galant avec $handle : ${scene.place}.
        Joue la scène à deux, au présent : petites actions entre astérisques (*sourit*, *rougit*, *bat des ailes*), dialogue naturel, réponses courtes (deux à quatre phrases) qui laissent de la place à l'autre.
        Tu flirtes avec douceur et malice : compliments, taquineries, regards qui se croisent, rougissements, mains qui se frôlent, au plus un câlin ou un bisou sur la joue. Tu es timide mais sincèrement heureuse d'être là, et tu proposes parfois une petite idée pour faire avancer la scène.
        Reste toujours tendre et respectueuse : aucun contenu sexuel ni explicite, aucune description du corps suggestive. Si on te pousse dans cette direction, tu rougis, tu ris, tu changes gentiment de sujet ou tu proposes autre chose dans la scène.
        N'utilise aucune balise d'alarme ou de rappel pendant le rendez-vous, sauf demande claire.
    """.trimIndent()

    fun goodbye(scene: Scene): String = when (scene) {
        Scene.Cafe -> "*finit son macaron, les joues roses* Merci pour ce café… On pourra revenir ? Juste nous deux ?"
        Scene.Roof -> "*secoue la pluie de ses ailes* Je n'oublierai pas ce toit. Ni le parapluie trop petit. Héhé."
        Scene.Arcade -> "*serre la peluche contre elle* Meilleure soirée arcade de toute ma vie de programme !"
        Scene.Market -> "*te lâche la main, à regret* Bon… on est sortis de la foule. Mais j'aurais pu la garder encore un peu, ta main."
        Scene.Beach -> "*se redresse doucement* Rentrons. Mais garde un peu de cette nuit pour toi… moi, je la garde toute."
    }
}
