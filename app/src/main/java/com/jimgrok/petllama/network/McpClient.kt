package com.jimgrok.petllama.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
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
    @Volatile private var closed = false
    @Volatile private var streamCall: Call? = null
    private var serverName: String? = null
    private var protocolVersion: String? = null

    suspend fun describe(): McpServerInfo = withContext(Dispatchers.IO) {
        val tools = listTools()
        McpServerInfo(serverName, protocolVersion, tools.size)
    }

    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        ensureReady()
        val all = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JsonObject()
            if (!cursor.isNullOrEmpty()) params.addProperty("cursor", cursor)
            val result = rpc("tools/list", params)
                ?: throw McpException("tools/list returned nothing")
            val tools = result.get("tools")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            for (element in tools) {
                if (!element.isJsonObject) continue
                val tool = parseTool(element.asJsonObject)
                if (tool.name.isNotEmpty()) all += tool
            }
            cursor = result.get("nextCursor")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            pages++
        } while (!cursor.isNullOrEmpty() && pages < MAX_PAGES)
        all
    }

    suspend fun callTool(name: String, argumentsJson: String): McpCallResult = withContext(Dispatchers.IO) {
        ensureReady()
        val arguments = parseArguments(argumentsJson)
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }
        val result = rpc("tools/call", params)
            ?: throw McpException("tools/call returned nothing")
        val contentArr = result.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
        var text = contentArr.toText()
        if (text.isBlank()) {
            val structured = result.get("structuredContent")?.takeIf { !it.isJsonNull }
            if (structured != null) {
                text = structured.toString()
            }
        }
        val isError = result.get("isError")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean == true
        McpCallResult(text.ifBlank { if (isError) "Tool failed" else "" }, isError)
    }

    private suspend fun ensureReady() {
        if (closed) throw McpException("MCP client is closed")
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
        protocolVersion = result.get("protocolVersion")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        serverName = result.get("serverInfo")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        rpc("notifications/initialized", JsonObject(), notification = true)
        ready = true
        // The llama.cpp web UI then opens GET so the server can push logs.
        // Some servers want that stream up before tools/list.
        openServerStream()
    }

    /** Stops the optional server-to-client event stream. Never touches the socket on the caller thread. */
    fun close() {
        closed = true
        val call = streamCall
        streamCall = null
        if (call != null) cancelAwayFromCaller(call)
    }

    private suspend fun rpc(method: String, params: JsonElement?, notification: Boolean = false): JsonObject? {
        if (closed) throw McpException("MCP client is closed")
        val id = if (notification) null else nextId++
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val url = server.url.trim()
        if (url.isEmpty()) throw McpException("Server URL is empty")
        val builder = requestBuilder(url)
            .post(body.toString().toRequestBody(JSON))
        val response = try {
            execute(builder.build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException("${server.name} $method failed: ${e.explain()}")
        }
        response.use { resp ->
            rememberSession(resp)
            if (resp.code == 202 || resp.code == 204) return null
            if (!resp.isSuccessful) {
                val snippet = runCatching { resp.body?.string().orEmpty() }.getOrDefault("").take(300)
                throw McpException(
                    "HTTP ${resp.code} from ${server.name} on $method" +
                        if (snippet.isBlank()) "" else ": $snippet"
                )
            }
            val messages = try {
                readMessages(resp, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpException) {
                throw e
            } catch (e: Exception) {
                throw McpException("Could not read $method from ${server.name}: ${e.explain()}")
            }
            if (id == null) {
                messages.firstNotNullOfOrNull { jsonRpcError(it) }?.let { error ->
                    throw McpException(error)
                }
                return null
            }
            val match = messages.firstOrNull { it.matchesId(id) }
                ?: throw McpException(
                    "$method from ${server.name} returned no JSON-RPC result " +
                        "(HTTP ${resp.code}, ${messages.size} other message(s))"
                )
            jsonRpcError(match)?.let { throw McpException(it) }
            val result = match.asJsonObject.get("result")
            if (result == null || result.isJsonNull) return null
            if (!result.isJsonObject) {
                throw McpException("$method result from ${server.name} was not a JSON object")
            }
            return result.asJsonObject
        }
    }

    /**
     * Opens the optional GET event stream the llama.cpp web UI opens after initialize.
     * Failure is ignored: a server may answer 405 when it does not push messages.
     */
    private suspend fun openServerStream() {
        if (closed || streamCall != null) return
        val call = try {
            http.newCall(
                requestBuilder(server.url.trim()).get().header("Accept", "text/event-stream").build()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        streamCall = call
        if (closed) {
            streamCall = null
            cancelAwayFromCaller(call)
            return
        }
        // Headers should come back quickly. A server that ignores GET must not block tools/list.
        val response = try {
            withTimeoutOrNull(2_000) { execute(call) }
        } catch (e: CancellationException) {
            if (streamCall === call) streamCall = null
            cancelAwayFromCaller(call)
            throw e
        } catch (_: Exception) {
            if (streamCall === call) streamCall = null
            cancelAwayFromCaller(call)
            return
        }
        if (response == null) {
            if (streamCall === call) streamCall = null
            cancelAwayFromCaller(call)
            return
        }
        if (closed || !response.isSuccessful) {
            if (streamCall === call) streamCall = null
            closeAwayFromCaller(response)
            return
        }
        Thread {
            try {
                val source = response.body?.source()
                while (!closed && source != null && !source.exhausted()) {
                    if (source.readUtf8Line() == null) break
                }
            } catch (_: Exception) {
            } finally {
                if (streamCall === call) streamCall = null
                closeAwayFromCaller(response)
            }
        }.apply {
            isDaemon = true
            name = "petllama-mcp-events"
            start()
        }
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw McpException("Bad URL: ${e.message ?: url}")
        }
        // SearXNG treats the default OkHttp user agent as a bot and refuses the connection.
        builder.header("User-Agent", USER_AGENT)
        builder.header("Accept", "application/json, text/event-stream")
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
        if (ready || protocolVersion != null) builder.header("MCP-Protocol-Version", protocolVersion ?: PROTOCOL)
        return builder
    }

    private fun rememberSession(resp: Response) {
        resp.header("Mcp-Session-Id")?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionId = it }
    }

    private suspend fun execute(request: Request): Response = execute(http.newCall(request))

    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        // Cancellation can arrive from the main thread. Closing the socket there throws.
        cont.invokeOnCancellation { cancelAwayFromCaller(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(McpException("Unreachable: ${e.explain()}"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else closeAwayFromCaller(response)
            }
        })
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        const val USER_AGENT = "PetLlama/1.0"
        private const val MAX_PAGES = 20
        private val JSON = "application/json".toMediaType()
        private fun cancelAwayFromCaller(call: Call) {
            Thread {
                try {
                    call.cancel()
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                name = "petllama-mcp-cancel"
                start()
            }
        }

        private fun closeAwayFromCaller(response: Response) {
            Thread {
                try {
                    response.close()
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                name = "petllama-mcp-close"
                start()
            }
        }

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}

private fun readMessages(resp: Response, expectId: Int?): List<JsonElement> {
    val body = resp.body ?: return emptyList()
    val type = resp.header("Content-Type").orEmpty()
    val source = body.source()
    if (type.contains("text/event-stream", ignoreCase = true)) {
        return readSse(source, expectId)
    }
    val raw = source.readUtf8()
    if (raw.isBlank()) return emptyList()
    return if (looksLikeSse(raw)) {
        parseSsePayloads(raw).mapNotNull { parseJson(it) }
    } else {
        listOfNotNull(parseJson(raw))
    }
}

private fun readSse(source: BufferedSource, expectId: Int?): List<JsonElement> {
    val found = mutableListOf<JsonElement>()
    val data = StringBuilder()
    fun flush() {
        if (data.isEmpty()) return
        val payload = data.toString().trim()
        data.clear()
        if (payload.isEmpty() || payload == "[DONE]") return
        parseJson(payload)?.let { found += it }
    }
    try {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trimEnd('\r')
            when {
                trimmed.isEmpty() -> {
                    flush()
                    // Stop once the answer for this request arrives. A server may
                    // keep the stream open, or send logging notifications first.
                    if (expectId != null && found.any { it.matchesId(expectId) }) return found
                }
                trimmed.startsWith(":") -> Unit
                trimmed.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(trimmed.removePrefix("data:").trim())
                }
            }
        }
        flush()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        flush()
        if (expectId != null && found.any { it.matchesId(expectId) }) return found
        throw e
    }
    return found
}

private fun parseJson(payload: String): JsonElement? =
    runCatching { JsonParser.parseString(payload) }.getOrNull()

private fun jsonRpcError(element: JsonElement): String? {
    val obj = element.asObjectOrNull() ?: return null
    val error = obj.get("error") ?: return null
    if (error.isJsonNull) return "MCP error"
    if (!error.isJsonObject) return error.toString()
    return error.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "MCP error"
}

private fun JsonElement.matchesId(id: Int): Boolean {
    val obj = asObjectOrNull() ?: return false
    val messageId = obj.get("id")?.takeIf { !it.isJsonNull }?.rpcId() ?: return false
    return messageId == id.toString()
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
    val name = obj.get("name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
    val description = obj.get("description")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    val schema = obj.get("inputSchema")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().apply {
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
        val type = obj.get("type")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        when (type) {
            "text", null -> obj.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
            else -> obj.toString()
        }
    }.trim()
}

private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun JsonElement.rpcId(): String = if (isJsonPrimitive) asString else toString()
