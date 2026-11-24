package com.jimgrok.petllama.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Numbers taken from one llama-server completion (a streamed final chunk or a whole response). */
data class CompletionStats(
    val predictedPerSecond: Double? = null,
    val promptPerSecond: Double? = null,
    val usedTokens: Int? = null
)

enum class ContextBand { GREEN, YELLOW, RED }

data class ContextFill(
    val used: Int,
    val context: Int,
    val fraction: Float,
    val band: ContextBand
)

/**
 * Live tok/s before the server sends timings.
 * Each streamed content delta counts as one token. Speed is that count divided by
 * the seconds since the first delta. The first delta alone has no elapsed time yet.
 */
class LiveTokenSpeed {
    private var firstAtNs: Long? = null
    private var deltas: Int = 0

    fun reset() {
        firstAtNs = null
        deltas = 0
    }

    fun onContentDelta(nowNs: Long = System.nanoTime()): Double? {
        deltas++
        val start = firstAtNs
        if (start == null) {
            firstAtNs = nowNs
            return null
        }
        val seconds = (nowNs - start) / 1_000_000_000.0
        if (seconds <= 0.0) return null
        return deltas / seconds
    }
}

fun parseCompletionStats(payload: String): CompletionStats? {
    val root = runCatching { JsonParser.parseString(payload) }.getOrNull() ?: return null
    if (!root.isJsonObject) return null
    return completionStatsFrom(root.asJsonObject)
}

fun completionStatsFrom(root: JsonObject): CompletionStats? {
    val timings = root.getObject("timings")
    val usage = root.getObject("usage")
    val predicted = timings?.doubleOrNull("predicted_per_second")
    val prompt = timings?.doubleOrNull("prompt_per_second")
    val used = usedTokens(timings, usage)
    if (predicted == null && prompt == null && used == null) return null
    return CompletionStats(predicted, prompt, used)
}

/**
 * Context window from a `/v1/models` body. [modelId] wins when that model
 * reports a positive length; otherwise the first positive length in the list.
 * NInfer and vLLM send `max_model_len`. Other backends use the aliases below.
 */
fun pickModelContextLength(models: List<ModelInfo>, modelId: String?): Int? {
    if (!modelId.isNullOrBlank()) {
        models.firstOrNull { it.id == modelId }?.contextLength?.takeIf { it > 0 }?.let { return it }
    }
    return models.firstNotNullOfOrNull { it.contextLength?.takeIf { n -> n > 0 } }
}

/** A typed context size. Blank, zero, and negative mean Auto. */
fun parseContextOverride(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it > 0 }

/**
 * Manual size wins when it is above zero. Otherwise [detected] is the
 * auto value from /props, /slots, or /v1/models.
 */
fun effectiveContextLength(manual: Int?, detected: Int?): Int? {
    if (manual != null && manual > 0) return manual
    return detected?.takeIf { it > 0 }
}

/** Models from `GET /v1/models` or from one model object. */
fun modelsFromJson(json: String): List<ModelInfo> {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
    if (root.isJsonArray) {
        return root.asJsonArray.mapNotNull { element ->
            if (element.isJsonObject) modelInfoFrom(element.asJsonObject) else null
        }
    }
    if (!root.isJsonObject) return emptyList()
    val obj = root.asJsonObject
    val data = obj.get("data")
    if (data != null && data.isJsonArray) {
        return data.asJsonArray.mapNotNull { element ->
            if (element.isJsonObject) modelInfoFrom(element.asJsonObject) else null
        }
    }
    return listOfNotNull(modelInfoFrom(obj))
}

fun modelContextLength(model: JsonObject): Int? {
    for (key in listOf("max_model_len", "context_length", "max_context_length", "context_window", "n_ctx")) {
        model.intOrNull(key)?.takeIf { it > 0 }?.let { return it }
    }
    val meta = model.getObject("meta") ?: return null
    meta.intOrNull("n_ctx")?.takeIf { it > 0 }?.let { return it }
    return meta.intOrNull("n_ctx_train")?.takeIf { it > 0 }
}

private fun modelInfoFrom(obj: JsonObject): ModelInfo? {
    val id = obj.stringOrNull("id") ?: obj.stringOrNull("name") ?: return null
    val name = obj.stringOrNull("name") ?: id
    return ModelInfo(id = id, name = name, contextLength = modelContextLength(obj))
}

private fun JsonObject.stringOrNull(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || value.isJsonNull) return null
    val text = value.asString.trim()
    return text.ifEmpty { null }
}

fun parseContextSize(propsOrSlotsJson: String): Int? {
    val root = runCatching { JsonParser.parseString(propsOrSlotsJson) }.getOrNull() ?: return null
    if (root.isJsonArray) {
        var best: Int? = null
        for (element in root.asJsonArray) {
            val n = element.takeIf { it.isJsonObject }?.asJsonObject?.intOrNull("n_ctx")
            if (n != null && n > 0 && (best == null || n > best)) best = n
        }
        return best
    }
    if (!root.isJsonObject) return null
    val obj = root.asJsonObject
    obj.intOrNull("n_ctx")?.let { if (it > 0) return it }
    val settings = obj.getObject("default_generation_settings")
    settings?.intOrNull("n_ctx")?.let { if (it > 0) return it }
    settings?.getObject("params")?.intOrNull("n_ctx")?.let { if (it > 0) return it }
    return null
}

fun contextFill(used: Int?, context: Int?): ContextFill? {
    if (used == null || context == null || context <= 0 || used < 0) return null
    val fraction = (used.toFloat() / context.toFloat()).coerceIn(0f, 1f)
    val band = when {
        fraction < 0.70f -> ContextBand.GREEN
        fraction <= 0.90f -> ContextBand.YELLOW
        else -> ContextBand.RED
    }
    return ContextFill(used, context, fraction, band)
}

fun formatTokenCount(n: Int): String {
    val sign = if (n < 0) "-" else ""
    val abs = kotlin.math.abs(n)
    if (abs < 1000) return n.toString()
    val scaled = kotlin.math.round(abs / 100.0) / 10.0
    val body = if (scaled == kotlin.math.floor(scaled)) scaled.toInt().toString() else "%.1f".format(scaled)
    return sign + body + "k"
}

fun formatSpeed(tokensPerSecond: Double): String {
    if (tokensPerSecond >= 100.0) return kotlin.math.round(tokensPerSecond).toInt().toString()
    return "%.1f".format(tokensPerSecond)
}

private fun usedTokens(timings: JsonObject?, usage: JsonObject?): Int? {
    if (timings != null) {
        val cache = timings.intOrNull("cache_n")?.takeIf { it >= 0 }
        val prompt = timings.intOrNull("prompt_n")?.takeIf { it >= 0 }
        val predicted = timings.intOrNull("predicted_n")?.takeIf { it >= 0 }
        // Occupied context is cached prompt + prompt processed this request +
        // tokens just generated. A streamed chunk often sends cache_n: 0 before
        // the real counts, which used to paint the bar as 0%.
        if (cache != null || prompt != null || predicted != null) {
            val sum = (cache ?: 0) + (prompt ?: 0) + (predicted ?: 0)
            if (sum > 0) return sum
        }
    }
    val promptTokens = usage?.intOrNull("prompt_tokens")
    val completion = usage?.intOrNull("completion_tokens")
    if (promptTokens != null || completion != null) {
        val sum = (promptTokens ?: 0) + (completion ?: 0)
        if (sum > 0) return sum
    }
    return usage?.intOrNull("total_tokens")?.takeIf { it > 0 }
}

private fun JsonObject.getObject(name: String): JsonObject? {
    val value = get(name) ?: return null
    return if (value.isJsonObject) value.asJsonObject else null
}

private fun JsonObject.intOrNull(name: String): Int? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || value.isJsonNull) return null
    val prim = value.asJsonPrimitive
    return when {
        prim.isNumber -> prim.asDouble.toInt()
        prim.isString -> prim.asString.toIntOrNull()
        else -> null
    }
}

private fun JsonObject.doubleOrNull(name: String): Double? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || value.isJsonNull) return null
    val prim = value.asJsonPrimitive
    return when {
        prim.isNumber -> prim.asDouble
        prim.isString -> prim.asString.toDoubleOrNull()
        else -> null
    }
}
