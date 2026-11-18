package com.jimgrok.petllama.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One tool call recovered from assistant text (the model wrote markup instead of structured calls). */
data class TextToolCall(
    val name: String,
    val arguments: String,
    val truncated: Boolean
)

data class SplitText(
    /** What is left as the visible answer. */
    val answer: String,
    /** Structured calls found inside <tool_call>…</tool_call> blocks. */
    val calls: List<TextToolCall>
)

private val TOOL_BLOCK = Regex("(<tool_?calls?[^>]*>)([\\s\\S]*?)(?:</tool_?calls?>|\\z)")
private val BLOCK_NAME = Regex("<name>[\\s\\S]*?</name>")
private val BLOCK_ARGS = Regex("<arguments>[\\s\\S]*?</arguments>")
private val OPEN_NAME = Regex("<name>([\\s\\S]*)\\z")
private val OPEN_ARGS = Regex("<arguments>([\\s\\S]*)\\z")

/**
 * Pulls <tool_call>…</tool_call> markup out of assistant text.
 *
 * llama.cpp exposes tools to non-chat-template models as text, and a small model
 * degrades into that style once the prompt fills with tool history. If the
 * closing tag is missing (the model ran out of output mid-block), the block
 * still counts: its arguments are marked [TextToolCall.truncated] so the caller
 * can show a notice instead of running a call that is only half a JSON object.
 */
internal fun splitTextToolCalls(text: String): SplitText {
    val calls = mutableListOf<TextToolCall>()
    val answer = TOOL_BLOCK.replace(text) { m ->
        val inner = m.groupValues[1]
        val truncated = m.groupValues[2].isNullOrEmpty()
        val call = parseBlockInner(inner, truncated)
        if (call != null) calls += call
        ""
    }.trim()
    return SplitText(answer, calls)
}

private fun parseBlockInner(inner: String, truncated: Boolean): TextToolCall? {
    var name: String? = if (!truncated) BLOCK_NAME.find(inner)?.value else null
    var args = if (!truncated) BLOCK_ARGS.find(inner)?.value
        ?.removePrefix("<arguments>")
        ?.removeSuffix("</arguments>")
        ?.trim()
        .orEmpty() else ""
    if (name.isNullOrBlank()) {
        val open = OPEN_NAME.find(inner) ?: return null
        name = open.groupValues[1].trim()
        args = OPEN_ARGS.find(inner)?.groupValues[1]?.trim().orEmpty()
    }
    if (name.isBlank()) return null
    return TextToolCall(name, args, truncated)
}

/**
 * Tool arguments arrive from a local model as raw text. If it is not valid JSON,
 * repair the usual small slips: a missing closing brace, or a trailing comma
 * before the closing brace. Anything still broken returns null so the caller
 * can report the argument error.
 */
internal fun repairToolArguments(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return "{}"
    runCatching { JsonParser.parseString(text) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.let { return text }
    var fixed = text
    if (!fixed.endsWith('}')) fixed += "}"
    fixed = Regex(",\\s*\\}\\s*\\z").replace(fixed, "}")
    return if (isValidJsonArgs(fixed)) fixed else null
}

internal fun isValidJsonArgs(text: String): Boolean =
    runCatching { JsonParser.parseString(text) }.getOrNull()?.isJsonObject == true

/** True when [obj] has an object-typed "properties" member (an inputSchema, not the arguments themselves). */
internal fun looksLikeInputSchema(obj: JsonObject): Boolean {
    val properties = obj.get("properties")
    return properties != null && !properties.isJsonNull &&
        (properties.isJsonObject || properties.isJsonArray)
}
