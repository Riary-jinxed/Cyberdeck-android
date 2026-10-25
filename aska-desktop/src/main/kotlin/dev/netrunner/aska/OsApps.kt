package dev.netrunner.aska

import dev.netrunner.bridge.Bridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Netrunner OS's own apps, through their bridge (os-bridge/): what is on their screen goes into
 * her prompt, their actions next to her own tools. Nothing open (or not on Netrunner OS): nothing.
 */
object OsApps {
    /** One action of an open app, and the socket to send it to. */
    class Action(val app: Bridge.Description, val tool: Bridge.Tool)

    /** An action that needs « oui » first. */
    class Pending(val socket: File, val tool: String, val args: Map<String, String>, val summary: String)

    fun open(): List<Bridge.Description> = runCatching { Bridge.discover() }.getOrDefault(emptyList())

    /** "Apps Netrunner OS ouvertes : …", for her system prompt; null when none is open. */
    fun prompt(apps: List<Bridge.Description>): String? = if (apps.isEmpty()) null else
        "Applis Netrunner OS ouvertes sur ce PC (tu peux y agir avec leurs outils) :\n" + apps.joinToString("\n") { "- ${it.title} : ${it.context}" }

    fun find(apps: List<Bridge.Description>, name: String): Action? =
        apps.firstNotNullOfOrNull { a -> a.tools.firstOrNull { it.name == name }?.let { Action(a, it) } }

    /** Their actions, in the same shape as [Tools.definitions]. */
    fun definitions(apps: List<Bridge.Description>): JsonArray = buildJsonArray {
        apps.flatMap { it.tools }.distinctBy { it.name }.forEach { t ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description + if (t.confirm) " Rien n'est fait avant que l'utilisateur dise oui." else "")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            t.params.forEach { p -> putJsonObject(p.name) { put("type", p.type); put("description", p.description) } }
                        }
                        putJsonArray("required") { t.params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
                    }
                }
            }
        }
    }

    /** Runs [action], or holds it for « oui » through [hold] when the app asks for a confirmation. */
    fun run(action: Action, args: Map<String, String>, hold: (Pending) -> Unit): String {
        if (!action.tool.confirm) return Bridge.run(action.app.socket, action.tool.name, args)
        val summary = action.tool.description.substringBefore(". ").trimEnd('.') + " (${action.app.title})"
        hold(Pending(action.app.socket, action.tool.name, args, summary))
        return "Prêt, mais PAS encore fait : $summary. Demande à l'utilisateur de confirmer par « oui » (ou « non » pour annuler)."
    }
}
