package com.jimgrok.petllama.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One extra HTTP header the owner typed for an MCP server. */
data class McpHeader(val name: String, val value: String)

/**
 * Header box in the edit sheet: one "Name: value" per line.
 * Blank lines are ignored. A line with no colon is rejected.
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
        if (name.isEmpty()) throw IllegalArgumentException("Header needs a name: $line")
        headers += McpHeader(name, value)
    }
    return headers
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
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        val value = obj.get("value")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        McpHeader(name, value)
    }
}
