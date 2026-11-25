package com.jimgrok.petllama.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** A tool call recovered from assistant text, not from a structured tool_calls field. */
data class LooseToolCall(
    val name: String,
    val arguments: String
)

data class LooseToolParse(
    val prose: String,
    val calls: List<LooseToolCall>
)

/**
 * Where a leaked tool call was found. Reasoning is used only when the visible
 * answer is empty, which is how Qwen sometimes hides the call in Thinking.
 */
internal data class RecoveredCalls(
    val answer: String,
    val reasoning: String,
    val calls: List<LooseToolCall>
)

/**
 * Qwen writes `<tool_call> <function=NAME> <parameter=KEY> VALUE` and often
 * skips the closing `</parameter>` and `</function>` tags. A block may instead
 * hold a JSON object. Blocks that do not parse stay in the text.
 */
internal fun parseLooseToolCalls(text: String): LooseToolParse {
    if (!text.contains("<tool_call", ignoreCase = true)) {
        return LooseToolParse(text, emptyList())
    }
    val calls = mutableListOf<LooseToolCall>()
    val ranges = mutableListOf<IntRange>()
    val lower = text.lowercase()
    var cursor = 0
    while (cursor < text.length) {
        val start = lower.indexOf("<tool_call", cursor)
        if (start < 0) break
        val openEnd = text.indexOf('>', start)
        if (openEnd < 0) break
        val closeTag = "</tool_call>"
        val close = lower.indexOf(closeTag, openEnd + 1)
        val next = lower.indexOf("<tool_call", openEnd + 1)
        val bodyEnd: Int
        val blockEnd: Int
        if (close >= 0 && (next < 0 || close < next)) {
            bodyEnd = close
            blockEnd = close + closeTag.length
        } else if (next >= 0) {
            bodyEnd = next
            blockEnd = next
        } else {
            bodyEnd = text.length
            blockEnd = text.length
        }
        val parsed = parseToolCallBody(text.substring(openEnd + 1, bodyEnd))
        if (parsed != null) {
            calls += parsed
            ranges += start until blockEnd
        }
        val nextCursor = if (blockEnd > start) blockEnd else start + 1
        if (nextCursor <= cursor) break
        cursor = nextCursor
    }
    if (calls.isEmpty()) return LooseToolParse(text, emptyList())
    val prose = buildString {
        var from = 0
        for (range in ranges) {
            append(text, from, range.first)
            from = range.last + 1
        }
        if (from < text.length) append(text, from, text.length)
    }.trim()
    return LooseToolParse(prose, calls)
}

/**
 * Structured tool_calls win. Otherwise look in the answer, and if that is
 * empty, in the reasoning text. That is where a Thinking-only `<tool_call>`
 * shows up, after which the chat used to stop.
 */
internal fun recoverLooseCalls(content: String, reasoning: String): RecoveredCalls {
    val fromAnswer = parseLooseToolCalls(content)
    if (fromAnswer.calls.isNotEmpty()) {
        return RecoveredCalls(fromAnswer.prose, reasoning, fromAnswer.calls)
    }
    if (fromAnswer.prose.isNotBlank()) {
        return RecoveredCalls(content, reasoning, emptyList())
    }
    val fromThinking = parseLooseToolCalls(reasoning)
    if (fromThinking.calls.isEmpty()) {
        return RecoveredCalls(content, reasoning, emptyList())
    }
    return RecoveredCalls("", fromThinking.prose, fromThinking.calls)
}

/** Maps `s2_get_file_contents` back to the server and tool it was exposed as. */
internal fun ToolCatalog.findLoose(rawName: String): ExposedTool? {
    val name = rawName.trim()
    if (name.isEmpty()) return null
    find(name)?.let { return it }
    tools.find { it.exposedName.equals(name, ignoreCase = true) }?.let { return it }
    val stripped = name.replace(Regex("""^s\d+_"""), "")
    val matches = tools.filter { tool ->
        tool.tool.name.equals(name, ignoreCase = true) ||
            tool.tool.name.equals(stripped, ignoreCase = true) ||
            tool.exposedName.equals(stripped, ignoreCase = true)
    }
    return matches.singleOrNull()
}

/**
 * Drops arguments the tool's input schema does not list and coerces stringified
 * XML arguments (integers, numbers, booleans, arrays, objects) to match the
 * schema types. `get_file_contents` often receives `sha` from the model; if
 * the schema has `ref` and not `sha`, that value is sent as `ref`.
 */
internal fun fitArguments(tool: McpTool, argumentsJson: String): String {
    val parsed = runCatching { JsonParser.parseString(argumentsJson.ifBlank { "{}" }) }.getOrNull()
    if (parsed == null || !parsed.isJsonObject) return argumentsJson.ifBlank { "{}" }
    val args = parsed.asJsonObject
    val properties = tool.inputSchema.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return args.toString()
    val allowed = properties.keySet()
    if (
        tool.name.equals("get_file_contents", ignoreCase = true) &&
        args.has("sha") && !args.has("ref") && "ref" in allowed && "sha" !in allowed
    ) {
        args.add("ref", args.get("sha"))
        args.remove("sha")
    }
    args.keySet().filter { it !in allowed }.toList().forEach { args.remove(it) }
    for (key in args.keySet().toList()) {
        val value = args.get(key)
        val propSchema = properties.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val coerced = coerceArgument(value, propSchema)
        if (coerced != null) args.add(key, coerced)
    }
    return args.toString()
}

private fun coerceArgument(value: JsonElement, propSchema: JsonObject): JsonElement? {
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    val types = schemaTypes(propSchema)
    if (types.isEmpty() || "string" in types) return null
    val raw = value.asString.trim()
    if (raw.isEmpty()) return null
    if ("boolean" in types) {
        if (raw.equals("true", ignoreCase = true)) return JsonPrimitive(true)
        if (raw.equals("false", ignoreCase = true)) return JsonPrimitive(false)
    }
    if ("integer" in types) {
        raw.toLongOrNull()?.let { return JsonPrimitive(it) }
    }
    if ("number" in types) {
        raw.toLongOrNull()?.let { return JsonPrimitive(it) }
        raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return JsonPrimitive(it) }
    }
    if ("array" in types) {
        runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.let { return it }
    }
    if ("object" in types) {
        runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.let { return it }
    }
    return null
}

private fun schemaTypes(schema: JsonObject): Set<String> {
    val typeEl = schema.get("type") ?: return emptySet()
    return when {
        typeEl.isJsonPrimitive && typeEl.asJsonPrimitive.isString -> setOf(typeEl.asString.lowercase())
        typeEl.isJsonArray -> typeEl.asJsonArray
            .mapNotNull { el ->
                if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString.lowercase() else null
            }
            .toSet()
        else -> emptySet()
    }
}

private fun parseToolCallBody(body: String): LooseToolCall? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null
    val hasXmlFunction = trimmed.contains("<function", ignoreCase = true) ||
        trimmed.contains("<invoke", ignoreCase = true)
    if (!hasXmlFunction) {
        val jsonStart = trimmed.indexOf('{')
        val jsonEnd = trimmed.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            parseJsonToolCall(trimmed.substring(jsonStart, jsonEnd + 1))?.let { return it }
        }
    }
    val name = functionName(trimmed) ?: return null
    val args = JsonObject()
    val params = parameterPattern.findAll(trimmed).toList()
    for (i in params.indices) {
        val key = params[i].groupValues[1]
        val valueStart = params[i].range.last + 1
        val valueEnd = if (i + 1 < params.size) params[i + 1].range.first else trimmed.length
        var value = trimmed.substring(valueStart, valueEnd)
        val closer = closeTag.find(value)
        if (closer != null) value = value.substring(0, closer.range.first)
        args.addProperty(key, value.trim())
    }
    return LooseToolCall(name, args.toString())
}

private fun functionName(body: String): String? {
    val match = functionEquals.find(body)
        ?: functionNamed.find(body)
        ?: invokeNamed.find(body)
        ?: return null
    return match.groupValues[1].trim().ifEmpty { null }
}

private fun parseJsonToolCall(json: String): LooseToolCall? {
    val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
    val function = obj.get("function")?.takeIf { it.isJsonObject }?.asJsonObject
    val name = obj.stringOrNull("name")
        ?: function?.stringOrNull("name")
        ?: obj.get("function")?.takeIf { it.isJsonPrimitive }?.asString
        ?: return null
    val argsElement = obj.get("arguments")
        ?: obj.get("parameters")
        ?: function?.get("arguments")
        ?: function?.get("parameters")
    val arguments = when {
        argsElement == null || argsElement.isJsonNull -> "{}"
        argsElement.isJsonPrimitive && argsElement.asJsonPrimitive.isString -> {
            val raw = argsElement.asString.trim()
            if (raw.startsWith("{") || raw.startsWith("[")) raw else {
                JsonObject().apply { addProperty("value", raw) }.toString()
            }
        }
        else -> argsElement.toString()
    }
    return LooseToolCall(name.trim(), arguments.ifBlank { "{}" })
}

private fun JsonObject.stringOrNull(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || value.isJsonNull) return null
    return value.asString
}

private val functionEquals = Regex(
    """<function\s*=\s*["']?([^"'>\s]+)["']?\s*>""",
    RegexOption.IGNORE_CASE
)
private val functionNamed = Regex(
    """<function\s+name\s*=\s*["']?([^"'>\s]+)""",
    RegexOption.IGNORE_CASE
)
private val invokeNamed = Regex(
    """<invoke\s+name\s*=\s*["']?([^"'>\s]+)""",
    RegexOption.IGNORE_CASE
)
private val parameterPattern = Regex(
    """<parameter\s*(?:=\s*|\s+name\s*=\s*)["']?([^"'>\s]+)["']?\s*>""",
    RegexOption.IGNORE_CASE
)
private val closeTag = Regex(
    """</\s*(?:parameter|function|invoke|tool_call)\b""",
    RegexOption.IGNORE_CASE
)
