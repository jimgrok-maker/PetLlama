package com.jimgrok.petllama.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One extra HTTP header the owner typed for an MCP server. */
data class McpHeader(val name: String, val value: String)

private val headerNameRegex = Regex("""^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$""")

private fun isValidHeaderValue(value: String): Boolean =
    value.none { ch -> ch == '\r' || ch == '\n' || ch.code < 0x20 || ch.code == 0x7f }

/**
 * Header box in the edit sheet: one "Name: value" per line.
 * Blank lines are ignored. A line with no colon or invalid header characters is rejected.
 */
fun parseHeaderLines(text: String): List<McpHeader> {
    val headers = mutableListOf<McpHeader>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) throw IllegalArgumentException("Header needs a name and a value: $line")
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        if (name.isEmpty() || !headerNameRegex.matches(name)) {
            throw IllegalArgumentException("Invalid header name: $name")
        }
        if (!isValidHeaderValue(value)) {
            throw IllegalArgumentException("Invalid header value for: $name")
        }
        headers += McpHeader(name, value)
    }
    return headers
}

fun validateBearerToken(token: String): String? {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return null
    if (!isValidHeaderValue(trimmed)) {
        return "Bearer token contains invalid control or newline characters."
    }
    return null
}

fun formatHeaderLines(headers: List<McpHeader>): String =
    headers.joinToString("\n") { "${it.name}: ${it.value}" }

fun headersToJson(headers: List<McpHeader>): String {
    val array = JsonArray()
    for (header in headers) {
        array.add(JsonObject().apply {
            addProperty("name", header.name)
            addProperty("value", header.value)
        })
    }
    return array.toString()
}

fun headersFromJson(json: String?): List<McpHeader> {
    if (json.isNullOrBlank()) return emptyList()
    val parsed = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
    if (!parsed.isJsonArray) return emptyList()
    return parsed.asJsonArray.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val obj = element.asJsonObject
        val name = obj.get("name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (name.isEmpty() || !headerNameRegex.matches(name)) return@mapNotNull null
        val value = obj.get("value")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
        if (!isValidHeaderValue(value)) return@mapNotNull null
        McpHeader(name, value)
    }
}
