package dev.netrunner.osint.data

import org.json.JSONArray
import org.json.JSONObject

/** One lookup's answer: label/value rows, a few links to go further, and an optional remark. */
data class Report(
    val module: String,
    val target: String,
    val rows: List<Row>,
    val links: List<Link> = emptyList(),
    val remark: String? = null,
    val error: String? = null,
) {
    data class Row(val label: String, val value: String, val flag: Flag = Flag.None)
    data class Link(val label: String, val url: String)
    enum class Flag { None, Good, Warn }

    /** Plain text, for the notebook, Notes and sharing. */
    fun asText(): String = buildString {
        append("[$module] $target\n")
        if (error != null) append("  ! $error\n")
        rows.forEach { r ->
            val lines = r.value.lines()
            append("  ${r.label} : ${lines.first()}\n")
            lines.drop(1).forEach { append("    $it\n") }
        }
        remark?.let { append("  > $it\n") }
        links.forEach { append("  ↗ ${it.label} ${it.url}\n") }
    }.trimEnd()

    fun toJson(): JSONObject = JSONObject()
        .put("module", module).put("target", target).put("remark", remark).put("error", error)
        .put("rows", JSONArray(rows.map { JSONObject().put("l", it.label).put("v", it.value).put("f", it.flag.name) }))
        .put("links", JSONArray(links.map { JSONObject().put("l", it.label).put("u", it.url) }))

    companion object {
        fun failed(module: String, target: String, error: String) = Report(module, target, emptyList(), error = error)

        fun fromJson(o: JSONObject) = Report(
            module = o.optString("module"),
            target = o.optString("target"),
            rows = o.optJSONArray("rows").objects().map { Row(it.optString("l"), it.optString("v"), runCatching { Flag.valueOf(it.optString("f")) }.getOrDefault(Flag.None)) },
            links = o.optJSONArray("links").objects().map { Link(it.optString("l"), it.optString("u")) },
            remark = o.optString("remark").takeIf { o.has("remark") && !o.isNull("remark") },
            error = o.optString("error").takeIf { o.has("error") && !o.isNull("error") },
        )
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
