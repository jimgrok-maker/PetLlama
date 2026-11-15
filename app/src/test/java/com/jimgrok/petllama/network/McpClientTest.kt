package com.jimgrok.petllama.network

import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
