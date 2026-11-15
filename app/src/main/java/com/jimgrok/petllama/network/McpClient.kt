package com.jimgrok.petllama.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What initialize told us, plus how many tools the server listed. */
data class McpServerInfo(
    val serverName: String?,
    val protocolVersion: String?,
    val toolCount: Int
)

data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject
)

data class McpCallResult(
    val text: String,
    val isError: Boolean
)

class McpException(message: String) : Exception(message)

/**
 * MCP client over Streamable HTTP (protocol 2025-06-18).
 *
 * One instance keeps the session id from initialize and reuses it for
 * tools/list and tools/call. The official Kotlin SDK needs Kotlin 2.4 and Ktor,
 * which this app does not use, so this is a small OkHttp client instead.
 */
class McpClient(
    private val server: McpServerEntity,
    private val http: OkHttpClient = defaultHttp
) {
    private var sessionId: String? = null
    private var ready = false
    private var nextId = 1
    private var serverName: String? = null
    private var protocolVersion: String? = null

    suspend fun describe(): McpServerInfo {
        val tools = listTools()
        return McpServerInfo(serverName, protocolVersion, tools.size)
    }

    suspend fun listTools(): List<McpTool> {
        ensureReady()
        val all = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JsonObject()
            if (!cursor.isNullOrEmpty()) params.addProperty("cursor", cursor)
            val result = rpc("tools/list", params)
                ?: throw McpException("tools/list returned nothing")
            val tools = result.getAsJsonArray("tools") ?: JsonArray()
            for (element in tools) {
                if (!element.isJsonObject) continue
                val tool = parseTool(element.asJsonObject)
                if (tool.name.isNotEmpty()) all += tool
            }
            cursor = result.get("nextCursor")?.takeIf { !it.isJsonNull }?.asString
            pages++
        } while (!cursor.isNullOrEmpty() && pages < MAX_PAGES)
        return all
    }

    suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
        ensureReady()
        val arguments = parseArguments(argumentsJson)
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }
        val result = rpc("tools/call", params)
            ?: throw McpException("tools/call returned nothing")
        val text = result.getAsJsonArray("content").toText()
        val isError = result.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean == true
        return McpCallResult(text.ifBlank { if (isError) "Tool failed" else "" }, isError)
    }

    private suspend fun ensureReady() {
        if (ready) return
        val params = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "PetLlama")
                addProperty("version", "1.0")
            })
        }
        val result = rpc("initialize", params)
            ?: throw McpException("initialize returned nothing")
        protocolVersion = result.get("protocolVersion")?.takeIf { !it.isJsonNull }?.asString
        serverName = result.getAsJsonObject("serverInfo")
            ?.get("name")?.takeIf { !it.isJsonNull }?.asString
        ready = true
        // Notification: the server may answer 202 with an empty body.
        rpc("notifications/initialized", JsonObject(), notification = true)
    }

    private suspend fun rpc(method: String, params: JsonElement?, notification: Boolean = false): JsonObject? {
        val id = if (notification) null else nextId++
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val url = server.url.trim()
        if (url.isEmpty()) throw McpException("Server URL is empty")
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw McpException("Bad URL: ${e.message}")
        }
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (server.bearerToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${server.bearerToken.trim()}")
        }
        for (header in headersFromJson(server.headersJson)) {
            if (header.name.equals("Authorization", ignoreCase = true) && server.bearerToken.isNotBlank()) {
                continue
            }
            builder.header(header.name, header.value)
        }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        if (ready) builder.header("MCP-Protocol-Version", PROTOCOL)

        val response = execute(builder.build())
        response.use { resp ->
            resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (resp.code == 202 || resp.code == 204) return null
            if (!resp.isSuccessful) {
                val snippet = resp.body?.string().orEmpty().take(200)
                throw McpException("HTTP ${resp.code} from ${server.name}${if (snippet.isBlank()) "" else ": $snippet"}")
            }
            val raw = resp.body?.string().orEmpty()
            if (raw.isBlank()) return null
            val contentType = resp.header("Content-Type").orEmpty()
            val payloads = if (contentType.contains("text/event-stream", ignoreCase = true) || looksLikeSse(raw)) {
                parseSsePayloads(raw)
            } else {
                listOf(raw.trim())
            }
            val messages = payloads.mapNotNull { payload ->
                runCatching { JsonParser.parseString(payload) }.getOrNull()
            }
            if (id == null) {
                val error = messages.firstNotNullOfOrNull { it.asObjectOrNull()?.getAsJsonObject("error") }
                if (error != null) throw McpException(error.get("message")?.asString ?: "MCP error")
                return null
            }
            val match = messages.firstNotNullOfOrNull { element ->
                val obj = element.asObjectOrNull() ?: return@firstNotNullOfOrNull null
                val messageId = obj.get("id")?.takeIf { !it.isJsonNull }?.rpcId()
                if (messageId == id.toString()) obj else null
            } ?: throw McpException("$method returned no JSON-RPC result")
            match.getAsJsonObject("error")?.let { error ->
                throw McpException(error.get("message")?.asString ?: "MCP error")
            }
            return match.getAsJsonObject("result")
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(McpException("Unreachable: ${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        private const val MAX_PAGES = 20
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal fun parseSsePayloads(body: String): List<String> {
    val payloads = mutableListOf<String>()
    val data = StringBuilder()
    fun flush() {
        if (data.isNotEmpty()) {
            payloads += data.toString().trim()
            data.clear()
        }
    }
    for (line in body.split("\n")) {
        val trimmed = line.trimEnd('\r')
        when {
            trimmed.isEmpty() -> flush()
            trimmed.startsWith(":") -> Unit
            trimmed.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(trimmed.removePrefix("data:").trim())
            }
        }
    }
    flush()
    return payloads.filter { it.isNotEmpty() && it != "[DONE]" }
}

private fun looksLikeSse(raw: String): Boolean {
    val start = raw.trimStart()
    return start.startsWith("data:") || start.startsWith("event:")
}

private fun parseTool(obj: JsonObject): McpTool {
    val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
    val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString
    val schema = obj.getAsJsonObject("inputSchema") ?: JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
    }
    return McpTool(name, description, schema)
}

private fun parseArguments(argumentsJson: String): JsonObject {
    val raw = argumentsJson.trim().ifEmpty { "{}" }
    val parsed = try {
        JsonParser.parseString(raw)
    } catch (e: Exception) {
        throw McpException("Could not read tool arguments")
    }
    if (!parsed.isJsonObject) throw McpException("Tool arguments must be a JSON object")
    return parsed.asJsonObject
}

private fun JsonArray?.toText(): String {
    if (this == null) return ""
    return joinToString("\n") { element ->
        if (!element.isJsonObject) return@joinToString element.toString()
        val obj = element.asJsonObject
        val type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString
        when (type) {
            "text", null -> obj.get("text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            else -> obj.toString()
        }
    }.trim()
}

private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun JsonElement.rpcId(): String = if (isJsonPrimitive) asString else toString()
