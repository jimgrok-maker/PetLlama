package com.jimgrok.petllama.network

import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {

    private data class Seen(
        val path: String,
        val method: String?,
        val auth: String?,
        val session: String?,
        val custom: String?,
        val protocol: String?,
        val body: String
    )

    @Test
    fun listsToolsAcrossPagesAndCallsWithSessionAndAuth() = runBlocking {
        val seen = mutableListOf<Seen>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                val method = json?.get("method")?.asString
                seen += Seen(
                    path = request.path.orEmpty(),
                    method = method,
                    auth = request.getHeader("Authorization"),
                    session = request.getHeader("Mcp-Session-Id"),
                    custom = request.getHeader("X-Custom"),
                    protocol = request.getHeader("MCP-Protocol-Version"),
                    body = body
                )
                if (request.getHeader("Authorization") != "Bearer tok" || request.getHeader("X-Custom") != "yes") {
                    return MockResponse().setResponseCode(401)
                }
                return when (method) {
                    "initialize" -> jsonResult(json, """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"searx","version":"1"}}""")
                        .setHeader("Mcp-Session-Id", "sess-1")
                    "notifications/initialized" -> {
                        if (request.getHeader("Mcp-Session-Id") != "sess-1") {
                            MockResponse().setResponseCode(400).setBody("missing session")
                        } else {
                            MockResponse().setResponseCode(202)
                        }
                    }
                    "tools/list" -> {
                        if (request.getHeader("Mcp-Session-Id") != "sess-1") {
                            return MockResponse().setResponseCode(400).setBody("missing session")
                        }
                        val params = json?.get("params")
                        val cursor = if (params != null && params.isJsonObject) {
                            params.asJsonObject.get("cursor")?.asString
                        } else {
                            null
                        }
                        if (cursor == null) {
                            jsonResult(
                                json,
                                """{"tools":[{"name":"search","description":"web search","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}}],"nextCursor":"p2"}"""
                            )
                        } else {
                            jsonResult(
                                json,
                                """{"tools":[{"name":"ping","description":"ping","inputSchema":{"type":"object"}}]}"""
                            )
                        }
                    }
                    "tools/call" -> {
                        val paramsElement = json?.get("params")
                        val params = if (paramsElement != null && paramsElement.isJsonObject) paramsElement.asJsonObject else null
                        assertEquals("search", params?.get("name")?.asString)
                        val arguments = params?.get("arguments")
                        val query = if (arguments != null && arguments.isJsonObject) arguments.asJsonObject.get("q")?.asString else null
                        assertEquals("cats", query)
                        jsonResult(json, """{"content":[{"type":"text","text":"found 3 cats"}],"isError":false}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val client = McpClient(serverEntity(server, id = 7))
            val info = client.describe()
            assertEquals(2, info.toolCount)
            assertEquals("searx", info.serverName)
            assertEquals("2025-06-18", info.protocolVersion)
            val result = client.callTool("search", """{"q":"cats"}""")
            assertEquals("found 3 cats", result.text)
            assertFalse(result.isError)
            assertNull(seen.first { it.method == "initialize" }.session)
            assertEquals("sess-1", seen.first { it.method == "tools/call" }.session)
            assertEquals("2025-06-18", seen.first { it.method == "tools/call" }.protocol)
            assertTrue(seen.any { it.method == "notifications/initialized" })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun acceptsAnSseInitializeResponse() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val method = JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("method").asString
                return when (method) {
                    "initialize" -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setHeader("Mcp-Session-Id", "sse-sess")
                        .setBody(
                            "event: message\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"serverInfo\":{\"name\":\"github\"}}}\n\n"
                        )
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"create_issue","description":"open an issue","inputSchema":{"type":"object"}}]}}""")
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
        try {
            val info = McpClient(serverEntity(server, id = 1, bearer = "")).describe()
            assertEquals(1, info.toolCount)
            assertEquals("github", info.serverName)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun toolErrorFlagIsReturnedAsText() = runBlocking {
        val server = scripted(
            "initialize" to """{"protocolVersion":"2025-06-18","serverInfo":{"name":"x"}}""",
            "tools/call" to """{"content":[{"type":"text","text":"nope"}],"isError":true}"""
        )
        server.start()
        try {
            val result = McpClient(serverEntity(server, id = 3)).callTool("search", "{}")
            assertTrue(result.isError)
            assertEquals("nope", result.text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpErrorBecomesAnMcpException() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
        server.start()
        try {
            val result = runCatching { McpClient(serverEntity(server, id = 4)).listTools() }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 401"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun searxngStyleHandshakeSkipsLoggingEventsAndDoesNotLookLikeOkHttp() = runBlocking {
        val seen = mutableListOf<String>()
        val userAgents = mutableListOf<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ua = request.getHeader("User-Agent").orEmpty()
                userAgents += ua
                if (ua.contains("okhttp", ignoreCase = true)) {
                    return MockResponse().setResponseCode(429).setBody("bot detected, HTTP header User-Agent: $ua")
                }
                if (request.method == "GET") {
                    seen += "GET"
                    return MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(": connected\n\n")
                }
                val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val method = json.get("method").asString
                seen += method
                val id = if (json.has("id")) json.get("id").asInt else 0
                return when (method) {
                    "initialize" -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("mcp-session-id", "sess-searx")
                        .setBody(
                            """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"logging":{},"tools":{}},"serverInfo":{"name":"Searxng","version":"4.2.0"}}}"""
                        )
                    "notifications/initialized" -> {
                        if (request.getHeader("Mcp-Session-Id") != "sess-searx") {
                            MockResponse().setResponseCode(400).setBody("missing session")
                        } else {
                            MockResponse().setResponseCode(202).setBody("")
                        }
                    }
                    "tools/list" -> {
                        if (request.getHeader("Mcp-Session-Id") != "sess-searx") {
                            return MockResponse().setResponseCode(400).setBody("missing session")
                        }
                        val sse = "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"data\":\"listing tools\"}}\n\n" +
                            "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[{\"name\":\"searxng_search\",\"description\":\"Search\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]}}\n\n" +
                            "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"debug\",\"data\":\"still logging\"}}\n\n"
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(sse)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("no $method")
                }
            }
        }
        server.start()
        val client = McpClient(
            McpServerEntity(
                id = 4,
                name = "searxng",
                url = server.url("/mcp").toString().trimEnd('/'),
                bearerToken = "",
                headersJson = "[]"
            )
        )
        try {
            val info = client.describe()
            assertEquals("Searxng", info.serverName)
            assertEquals("2025-06-18", info.protocolVersion)
            assertEquals(1, info.toolCount)
            assertEquals("searxng_search", client.listTools().single().name)
            assertTrue(userAgents.all { it.contains("PetLlama") && !it.contains("okhttp") })
            assertTrue(seen.indexOf("GET") < seen.indexOf("tools/list"))
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun httpFailureShowsStatusAndBodyInsteadOfBareFailed() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429).setBody("bot detected"))
        server.start()
        try {
            val error = runCatching {
                McpClient(
                    McpServerEntity(name = "searxng", url = server.url("/mcp").toString().trimEnd('/'))
                ).describe()
            }.exceptionOrNull()
            val text = error!!.toUserMessage()
            assertTrue(text.contains("429"))
            assertTrue(text.contains("bot detected"))
            assertFalse(text == "failed" || text.endsWith("failed"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun missingExceptionMessageIsStillDescribed() {
        val cause = IllegalStateException()
        val text = Exception(null as String?, cause).toUserMessage()
        assertTrue(text.contains("java.lang.Exception"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("(no message)"))
        assertFalse(text == "failed")
    }

    @Test
    fun sseInitializeBodyIsNotReadOnTheCallerThread() = runBlocking {
        val caller = Thread.currentThread().name
        val readThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body ?: return@Interceptor response
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun source(): BufferedSource {
                    readThreads.add(Thread.currentThread().name)
                    return body.source()
                }
            }).build()
        }).build()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(405)
                val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val method = json.get("method").asString
                val id = if (json.has("id")) json.get("id").asInt else 0
                return when (method) {
                    "initialize" -> MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setHeader("Mcp-Session-Id", "s")
                        .setBody(
                            "event: message\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"logging\":{}},\"serverInfo\":{\"name\":\"Searxng\",\"version\":\"4.2.0\"}}}\n\n"
                        )
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"searxng_search","description":"Search","inputSchema":{"type":"object"}}]}}"""
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val client = McpClient(
            McpServerEntity(name = "searxng", url = server.url("/mcp").toString().trimEnd('/')),
            http
        )
        try {
            assertEquals(1, client.describe().toolCount)
            assertTrue("body was never read", readThreads.isNotEmpty())
            assertTrue(
                "SSE body was read on the caller thread: $readThreads",
                readThreads.none { it == caller }
            )
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun headerLinesAndSseParser() {
        val headers = parseHeaderLines("X-Api-Key: abc\n\nAccept: text/plain\n")
        assertEquals(listOf(McpHeader("X-Api-Key", "abc"), McpHeader("Accept", "text/plain")), headers)
        assertEquals("X-Api-Key: abc\nAccept: text/plain", formatHeaderLines(headers))
        val roundTrip = headersFromJson(headersToJson(headers))
        assertEquals(headers, roundTrip)
        assertTrue(runCatching { parseHeaderLines("not a header") }.isFailure)
        val payloads = parseSsePayloads("event: message\ndata: {\"a\":1}\n\ndata: [DONE]\n\n")
        assertEquals(listOf("{\"a\":1}"), payloads)
    }

    private fun serverEntity(server: MockWebServer, id: Long, bearer: String = "tok"): McpServerEntity {
        val url = server.url("/mcp").toString().trimEnd('/')
        return McpServerEntity(
            id = id,
            name = "SearXNG",
            url = url,
            bearerToken = bearer,
            headersJson = headersToJson(listOf(McpHeader("X-Custom", "yes"))),
            enabled = true
        )
    }

    private fun jsonResult(request: com.google.gson.JsonObject?, resultJson: String): MockResponse {
        val id = request?.get("id")?.asInt ?: 1
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
    }

    /** Maps an MCP method to a JSON result object. notifications/initialized is answered with 202. */
    private fun scripted(vararg steps: Pair<String, String>): MockWebServer {
        val server = MockWebServer()
        val results = steps.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val method = json.get("method").asString
                if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                val result = results[method] ?: return MockResponse().setResponseCode(500).setBody("unexpected $method")
                val id = json.get("id")?.asInt ?: 1
                val response = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                if (method == "initialize") response.setHeader("Mcp-Session-Id", "s")
                return response
            }
        }
        return server
    }
}
