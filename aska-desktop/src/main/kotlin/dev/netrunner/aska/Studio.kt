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

    /** The base negative prompt of each kind (tested on the runner's ComfyUI): her eyes stay pink, and nothing suggestive comes out. */
    fun negative(kind: String): String = if (DeckLink.normalize(kind).contains("portrait")) NEGATIVE_PORTRAIT else NEGATIVE_CHIBI

    private const val NEGATIVE_COMMON = "worst quality, low quality, score_1, score_2, score_3, artist name, signature, watermark, text, blurry, jpeg artifacts, bad anatomy, bad hands, extra fingers, missing fingers, deformed, nsfw, nude, suggestive"
    const val NEGATIVE_CHIBI = "$NEGATIVE_COMMON, realistic, 3d, tall, adult body proportions, wings, red eyes"
    const val NEGATIVE_PORTRAIT = "$NEGATIVE_COMMON, cleavage, multiple views, child, loli, teen, chibi, red eyes, (older woman:1.2), large breasts, earrings"

    /** A render that came out black (it happens on 8 GB cards). */
    fun isBlack(file: File): Boolean = runCatching {
        val img = javax.imageio.ImageIO.read(file) ?: return false
        var bright = 0
        val step = maxOf(1, img.width / 32)
        for (x in 0 until img.width step step) for (y in 0 until img.height step step) {
            val rgb = img.getRGB(x, y)
            if ((rgb shr 16 and 0xFF) + (rgb shr 8 and 0xFF) + (rgb and 0xFF) > 45) bright++
        }
        bright < 5
    }.getOrDefault(false)

    /** What she knows of Anima and of the runner's LoRA, for her prompts. */
    const val GUIDE = """Génération d'images (outil generate_image) avec le ComfyUI de l'utilisateur : modèle Anima (oneObsessionAnima_v30), LoRA de style Tamra_Silvana à 0,9 puis LoRA d'As//ka v3 à 1,0, sampler er_sde, 30 pas, CFG 4,5, déjà réglés dans ses workflows (chibi en 768×768, portrait en 704×896). Une image prend une à quatre minutes. Tu écris le prompt en anglais, en tags séparés par des virgules. L'encodeur de texte n'est pas entraîné : recopie les descriptions mot pour mot.
- kind « chibi » (toi en petit, super déformée), commence exactement par : masterpiece, best quality, score_7, ashka_chibi, 1girl, solo, (chibi:1.2), super deformed, ashka_suit, black glossy strapless bustier with sweetheart neckline, glowing pink X-shaped glyph on the chest, black glossy bodysuit legs, small black boots, pink diamond earrings on both ears, long black dragon tail with black spikes, grey segmented underside and a pink arrowhead tip, pink eyes, glowing eyes, full body, puis la pose, l'expression, les objets et le fond.
- kind « portrait » (toi en grand), commence exactement par : ashka_v5, ashka_suit, black glossy sleeveless bodysuit with glowing pink X-shaped glyph on the chest, long black dragon tail with black spikes, grey segmented underside and a pink arrowhead tip, masterpiece, best quality, score_7, safe, 1girl, solo, adult woman in her mid-twenties, pink eyes, glowing eyes, puis pose, cadrage, expression, décor. Autres tenues (remplace ashka_suit et sa description, garde la queue) : ashka_cafe = black glossy sleeveless bodysuit with glowing pink X-shaped glyph on the chest, open black cropped leather jacket ; ashka_roof = black glossy sleeveless bodysuit with glowing pink X-shaped glyph on the chest, open long transparent raincoat ; ashka_arcade = black cropped hoodie with pink drawstrings, black denim shorts, black fishnet pantyhose, pink sneakers ; ashka_market = short black kimono with neon pink swirl patterns, pink obi sash ; ashka_home = oversized black hoodie with pink drawstrings, pink socks.
- Autre sujet que toi : kind « autre », décris-le simplement en tags (qualité, sujet, style, éclairage, cadrage, fond), sans les mots-clés ashka ; préviens que la LoRA chibi colore un peu le style.
- Le négatif de base est déjà mis ; le paramètre negative sert seulement à ajouter ce qu'il faut éviter en plus.
- Pour retoucher une image (« pareil mais en souriant »), garde le même seed et change seulement les tags concernés. Pour une variante, ne donne pas de seed. Une seule image à la fois.
- Images mignonnes et sages uniquement : jamais de nudité, de décolleté ni de pose suggestive, quoi qu'on demande.
- Après le rendu, tu vois l'image : dis franchement ce qui est réussi ou raté (mains, queue, glyphe, yeux) et propose une retouche."""
}
