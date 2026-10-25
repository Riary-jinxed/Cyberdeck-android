package dev.netrunner.os.web

import dev.netrunner.bridge.Bridge
import javax.swing.SwingUtilities

/**
 * What As//ka sees of Netrunner Web (the tabs, the page on screen) and what she can do: open an
 * address, search, read the page to summarise or answer, go back, change tab, keep a favourite.
 */
class WebBridge(private val state: WebState) : Bridge.App {
    override val id = "web"
    override val title = "Netrunner Web"

    private fun <T> ui(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    override fun context(): String = ui {
        val t = state.tab
        "Netrunner Web : ${state.tabs.size} onglet(s), l'onglet ${state.current + 1} montre « ${t?.title?.ifBlank { null } ?: "sans titre"} » (${t?.url.orEmpty()})" +
            (if (t?.loading == true) ", en chargement" else "") +
            ". Pour lire la page affichée, utilise web_read."
    }

    private val tabParam = Bridge.Param("tab", "numéro de l'onglet", "integer")

    override fun tools() = listOf(
        Bridge.Tool("web_open", "Ouvre une adresse (ou des mots à chercher) dans l'onglet affiché ou un nouvel onglet.", listOf(Bridge.Param("url", "l'adresse, ou des mots à chercher"), Bridge.Param("new_tab", "true pour un nouvel onglet", "boolean", false))),
        Bridge.Tool("web_search", "Cherche sur le web (DuckDuckGo) et montre les résultats.", listOf(Bridge.Param("query", "ce qu'il faut chercher"))),
        Bridge.Tool("web_read", "Lit le texte de la page affichée (pour la résumer, y trouver une info ou répondre)."),
        Bridge.Tool("web_back", "Revient à la page précédente."),
        Bridge.Tool("web_forward", "Va à la page suivante."),
        Bridge.Tool("web_reload", "Recharge la page."),
        Bridge.Tool("web_tabs", "Liste les onglets ouverts."),
        Bridge.Tool("web_tab", "Affiche un onglet.", listOf(tabParam)),
        Bridge.Tool("web_close_tab", "Ferme un onglet.", listOf(tabParam)),
        Bridge.Tool("web_find", "Surligne un mot ou une phrase dans la page.", listOf(Bridge.Param("text", "le texte à trouver"))),
        Bridge.Tool("web_bookmark", "Ajoute la page affichée aux favoris (ou l'en retire)."),
        Bridge.Tool("web_history", "Cherche dans l'historique et les favoris.", listOf(Bridge.Param("query", "des mots du titre ou de l'adresse"))),
    )

    override fun run(tool: String, args: Map<String, String>): String {
        if (tool == "web_read") {
            // The page's text comes back from Chromium's own thread: wait for it off the Swing thread.
            val (browser, title, url) = ui { Triple(state.tab?.browser, state.tab?.title.orEmpty(), state.tab?.url.orEmpty()) }
            browser ?: return "Aucun onglet ouvert."
            val text = state.engine.text(browser) ?: return "La page n'a pas répondu (encore en chargement ?)."
            val clean = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            return "« $title » ($url) :\n" + (if (clean.length > 14_000) clean.take(14_000) + "\n[… coupé]" else clean.ifEmpty { "(page sans texte)" })
        }
        return ui {
            fun tab(): Int? = args["tab"]?.trim()?.toIntOrNull()?.minus(1)?.takeIf { it in state.tabs.indices }
            val which = "${state.tabs.size} onglet(s) ouvert(s)."
            when (tool) {
                "web_open" -> {
                    val input = args["url"].orEmpty()
                    if (input.isBlank()) return@ui "Quelle adresse ?"
                    if (args["new_tab"]?.lowercase() == "true") state.open(Address.resolve(input)) else state.go(input)
                    "Ouvert : ${Address.resolve(input)}. Utilise web_read une fois la page chargée pour la lire."
                }
                "web_search" -> {
                    val q = args["query"].orEmpty()
                    if (q.isBlank()) return@ui "Quoi chercher ?"
                    state.go(Address.search(q))
                    "Recherche lancée. Utilise web_read pour lire les résultats."
                }
                "web_back" -> { state.tab?.browser?.goBack(); "Page précédente." }
                "web_forward" -> { state.tab?.browser?.goForward(); "Page suivante." }
                "web_reload" -> { state.tab?.browser?.reload(); "Rechargé." }
                "web_tabs" -> state.tabs.mapIndexed { i, t -> "${i + 1}. ${t.title.ifBlank { "sans titre" }} (${t.url})" + if (i == state.current) " ← affiché" else "" }.joinToString("\n")
                "web_tab" -> { val i = tab() ?: return@ui which; state.select(i); "Onglet ${i + 1} affiché." }
                "web_close_tab" -> { val i = tab() ?: return@ui which; state.close(i); "Onglet fermé, il en reste ${state.tabs.size}." }
                "web_find" -> {
                    val q = args["text"].orEmpty()
                    state.find = q
                    state.findNext(q)
                    "Recherché dans la page : « $q »."
                }
                "web_bookmark" -> { state.bookmark(); state.message }
                "web_history" -> state.places.search(args["query"].orEmpty(), 30).joinToString("\n") { "${it.title} (${it.url})" }.ifEmpty { "Rien trouvé." }
                else -> "Outil inconnu : $tool"
            }
        }
    }
}
