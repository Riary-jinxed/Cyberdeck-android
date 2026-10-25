package dev.netrunner.aska

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Making pictures together: she writes the prompt (Anima, with the runner's As//ka LoRA), sends
 * it to the local ComfyUI with one of the runner's workflows, and shows the result.
 *
 * A workflow is ComfyUI's « API » export (File, Export (API)). Its prompts and seed are
 * POSITIVE_PROMPT, NEGATIVE_PROMPT and SEED; without those markers, the texts tied to the sampler's
 * positive and negative inputs and the sampler's seed are used.
 */
object Studio {
    data class Picture(val file: File, val prompt: String, val negative: String, val seed: Long, val workflow: String)

    /** The runner's workflows: %APPDATA%\Aska\comfy\*.json first, then the ones shipped with her. */
    val folder: File get() = File(Store.dir, "comfy").also { it.mkdirs() }

    val output: File get() = File(System.getProperty("user.home"), "Pictures/Aska").also { it.mkdirs() }

    fun workflows(): Map<String, String> {
        val shipped = SHIPPED.mapNotNull { name -> Studio::class.java.getResource("/comfy/$name.json")?.readText()?.let { name to it } }.toMap()
        val own = folder.listFiles { f -> f.extension == "json" }.orEmpty().associate { it.nameWithoutExtension to it.readText() }
        return shipped + own
    }

    /** « portrait » finds anima_portrait_api; anything else, the txt2img one. */
    fun pick(kind: String, all: Map<String, String> = workflows()): Pair<String, String>? {
        val k = DeckLink.normalize(kind)
        return all.entries.firstOrNull { k.isNotBlank() && DeckLink.normalize(it.key).contains(k) }?.toPair()
            ?: all.entries.firstOrNull { it.key.contains("t2i") }?.toPair()
            ?: all.entries.firstOrNull()?.toPair()
    }

    /** The workflow with the prompts and seed put in. */
    fun fill(workflow: String, positive: String, negative: String, seed: Long): JsonObject {
        val marked = workflow.contains("POSITIVE_PROMPT")
        val graph = Comfy.json.parseToJsonElement(workflow).jsonObject
        fun replace(e: JsonElement): JsonElement = when {
            e is JsonObject -> JsonObject(e.mapValues { replace(it.value) })
            e is JsonArray -> JsonArray(e.map { replace(it) })
            e is JsonPrimitive && e.isString && e.content == "SEED" -> JsonPrimitive(seed)
            e is JsonPrimitive && e.isString && e.content.contains("POSITIVE_PROMPT") -> JsonPrimitive(e.content.replace("POSITIVE_PROMPT", positive))
            e is JsonPrimitive && e.isString && e.content.contains("NEGATIVE_PROMPT") -> JsonPrimitive(e.content.replace("NEGATIVE_PROMPT", negative))
            else -> e
        }
        if (marked) return replace(graph) as JsonObject
        // No markers: follow the sampler's inputs.
        val nodes = graph.mapValues { it.value.jsonObject }.toMutableMap()
        val sampler = nodes.entries.firstOrNull { it.value.s("class_type").startsWith("KSampler") } ?: return graph
        val inputs = sampler.value["inputs"]?.jsonObject ?: return graph
        fun link(name: String) = ((inputs[name] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
        fun setText(id: String?, text: String) {
            val node = nodes[id ?: return] ?: return
            val ins = node["inputs"]?.jsonObject ?: return
            val key = listOf("text", "prompt", "text_g").firstOrNull { ins[it] is JsonPrimitive } ?: return
            nodes[id] = JsonObject(node + ("inputs" to JsonObject(ins + (key to JsonPrimitive(text)))))
        }
        setText(link("positive"), positive)
        setText(link("negative"), negative)
        val seedKey = if (inputs.containsKey("noise_seed")) "noise_seed" else "seed"
        nodes[sampler.key] = JsonObject(sampler.value + ("inputs" to JsonObject(inputs + (seedKey to JsonPrimitive(seed)))))
        return JsonObject(nodes)
    }

    /** Sends the prompt and waits for its pictures, at most [timeoutMinutes]. */
    suspend fun generate(base: String, kind: String, positive: String, negative: String, seed: Long?, timeoutMinutes: Long = 10): List<Picture> = withContext(Dispatchers.IO) {
        val (name, workflow) = pick(kind) ?: throw IllegalStateException(
            "Aucun workflow ComfyUI : exporte le tien en API (Fichier, Exporter (API)) dans ${folder.absolutePath}",
        )
        val usedSeed = seed ?: (Math.random() * 1e15).toLong()
        val body = buildJsonObject {
            put("prompt", fill(workflow, positive, negative, usedSeed))
            put("client_id", CLIENT)
        }.toString()
        val root = base.trimEnd('/')
        val sent = Comfy.http.send(
            HttpRequest.newBuilder(URI("$root/prompt")).header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (sent.statusCode() != 200) throw IllegalStateException("ComfyUI refuse le workflow $name : " + errorOf(sent.body()))
        val id = Comfy.json.parseToJsonElement(sent.body()).jsonObject.s("prompt_id")
        val until = System.currentTimeMillis() + timeoutMinutes * 60_000
        while (System.currentTimeMillis() < until) {
            delay(1_500)
            val history = runCatching {
                Comfy.http.send(HttpRequest.newBuilder(URI("$root/history/$id")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString()).body()
            }.getOrNull() ?: continue
            val entry = runCatching { Comfy.json.parseToJsonElement(history).jsonObject[id]?.jsonObject }.getOrNull() ?: continue
            val status = entry["status"]?.jsonObject
            if (status?.s("status_str") == "error") throw IllegalStateException("ComfyUI a planté pendant le rendu ($name).")
            val images = entry["outputs"]?.jsonObject?.values.orEmpty()
                .flatMap { (it.jsonObject["images"] as? JsonArray).orEmpty() }
                .map { it.jsonObject }
                .filter { it.s("type") == "output" }
            if (images.isEmpty()) continue
            return@withContext images.map { img ->
                val q = listOf("filename", "subfolder", "type").joinToString("&") { k -> "$k=" + URLEncoder.encode(img.s(k), Charsets.UTF_8) }
                val bytes = Comfy.http.send(HttpRequest.newBuilder(URI("$root/view?$q")).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray()).body()
                val file = File(output, STAMP.format(LocalDateTime.now()) + "_" + img.s("filename").substringAfterLast('/'))
                file.writeBytes(bytes)
                Picture(file, positive, negative, usedSeed, name)
            }
        }
        throw IllegalStateException("Le rendu prend plus de $timeoutMinutes min : je le laisse tourner dans ComfyUI.")
    }

    private fun errorOf(body: String) = runCatching {
        val o = Comfy.json.parseToJsonElement(body).jsonObject
        (o["error"] as? JsonObject)?.s("message") ?: o.s("error")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private val CLIENT = UUID.randomUUID().toString()
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val SHIPPED = listOf("anima_t2i_api", "anima_portrait_api")

    /** Always in the negative prompt: her eyes stay pink, and nothing suggestive comes out. */
    const val NEGATIVE = "red eyes, ashka_outfit, nsfw, nude, cleavage, text, watermark, signature, multiple characters, extra fingers, deformed hands, blurry, lowres"

    /** What she knows of Anima and of the runner's LoRA, for her prompts. */
    const val GUIDE = """Génération d'images (outil generate_image) avec le ComfyUI de l'utilisateur : modèle Anima (oneObsessionAnima_v30), LoRA de style Tamra_Silvana puis LoRA d'As//ka (version v3), déjà réglés dans ses workflows. Tu écris le prompt en anglais, en tags séparés par des virgules, du plus important au moins important.
- kind « chibi » (toi en petit, super déformée) : commence par « ashka_chibi, 1girl, solo, (chibi:1.2), super deformed, ashka_suit, pink eyes, glowing eyes, full body » puis ajoute « long black dragon tail with black spikes, grey segmented underside and a pink arrowhead tip, glowing pink X-shaped glyph on the chest », puis la pose, l'expression, les objets et le fond. Ne décris pas la tenue : le mot-clé la fixe.
- kind « portrait » (toi en grand) : « ashka_v5, ashka_<tenue>, <description exacte de la tenue>, 1girl, solo, pink eyes, glowing eyes » + les deux phrases de la queue et du glyphe, puis pose, cadrage, expression, décor. Tenues et descriptions à recopier mot pour mot : suit = black glossy sleeveless bodysuit with glowing pink X-shaped glyph on the chest ; cafe = la même + open black cropped leather jacket ; roof = la même + open long transparent raincoat ; arcade = black cropped hoodie with pink drawstrings, black denim shorts, black fishnet pantyhose, pink sneakers ; home = oversized black hoodie with pink drawstrings, pink socks, pink diamond earrings ; market = short black kimono with neon pink swirl patterns, pink obi sash ; beach = long black sundress with thin spaghetti straps, barefoot.
- Autre sujet que toi : décris-le simplement en tags (sujet, style, éclairage, cadrage, fond), sans les mots-clés ashka.
- Le négatif contient déjà « red eyes, ashka_outfit » et les défauts courants ; ajoutes-y seulement ce qu'il faut éviter en plus.
- Pour retoucher une image (« pareil mais en souriant »), garde le même seed et change seulement les tags concernés. Pour une variante, ne donne pas de seed.
- Images mignonnes uniquement : jamais de nudité ni de pose suggestive, quoi qu'on demande.
- Après le rendu, tu vois l'image : dis franchement ce qui est réussi ou raté (mains, queue, glyphe, yeux) et propose une retouche."""
}
