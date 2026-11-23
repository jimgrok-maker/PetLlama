package com.jimgrok.petllama.network

import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import com.jimgrok.petllama.data.ServerEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolChatTest {

    @Test
    fun modelToolCallIsRunOnMcpAndTheAnswerIsStreamed() = runBlocking {
        val llamaBodies = mutableListOf<String>()
        val llama = MockWebServer()
        var llamaRound = 0
        llama.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                llamaBodies += request.body.readUtf8()
                llamaRound++
                val body = if (llamaRound == 1) {
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"s7_search\",\"arguments\":\"\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"q\\\":\\\"cats\\\"}\"}}]}}]}\n\n" +
                        "data: {\"choices\":[{\"finish_reason\":\"tool_calls\"}]}\n\n" +
                        "data: [DONE]\n\n"
                } else {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Cats are great.\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                }
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
            }
        }

        val mcpCalls = mutableListOf<String>()
        val mcp = MockWebServer()
        mcp.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val method = json.get("method").asString
                val id = json.get("id")?.asInt
                return when (method) {
                    "initialize" -> rpc(id, """{"protocolVersion":"2025-06-18","serverInfo":{"name":"searx"}}""")
                        .setHeader("Mcp-Session-Id", "sess")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> rpc(
                        id,
                        """{"tools":[{"name":"search","description":"web search","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}}}]}"""
                    )
                    "tools/call" -> {
                        mcpCalls += json.getAsJsonObject("params").toString()
                        assertEquals("sess", request.getHeader("Mcp-Session-Id"))
                        assertEquals("Bearer tok", request.getHeader("Authorization"))
                        rpc(id, """{"content":[{"type":"text","text":"found 3 cats"}],"isError":false}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        llama.start()
        mcp.start()
        try {
            val mcpServer = McpServerEntity(
                id = 7,
                name = "SearXNG",
                url = mcp.url("/mcp").toString().trimEnd('/'),
                bearerToken = "tok",
                enabled = true
            )
            val catalog = ToolCatalog.from(listOf(mcpServer to McpClient(mcpServer).listTools()))
            assertEquals("s7_search", catalog.tools.single().exposedName)

            val llamaClient = LlamaClient(ServerEntity(name = "llama", url = llama.url("/").toString().trimEnd('/')))
            val messages = mutableListOf(Msg("user", "find cats"))
            val text = StringBuilder()
            val traces = mutableListOf<ToolTrace>()
            val sessions = mutableMapOf<Long, McpClient>()
            val result = runToolLoop(
                llama = llamaClient,
                messages = messages,
                buildRequest = { msgs, tools ->
                    ChatRequest(model = "tiny", messages = msgs, tools = tools)
                },
                catalog = catalog,
                callTool = { exposed, args ->
                    sessions.getOrPut(exposed.server.id) { McpClient(exposed.server) }
                        .callTool(exposed.tool.name, args)
                },
                onContent = { text.append(it) },
                onRoundDone = {},
                onToolStart = { _, _, _ -> },
                onToolResult = { traces += it }
            )
            assertFalse(result.hitToolLimit)
            assertEquals("Cats are great.", text.toString())
            assertEquals("search", traces.single().toolName)
            assertEquals("found 3 cats", traces.single().result)
            assertTrue(llamaBodies[0].contains("\"s7_search\""))
            assertTrue(llamaBodies[1].contains("found 3 cats"))
            assertTrue(llamaBodies[1].contains("\"role\":\"tool\""))
            assertTrue(mcpCalls.single().contains("\"name\":\"search\""))
            assertTrue(mcpCalls.single().contains("cats"))
        } finally {
            llama.shutdown()
            mcp.shutdown()
        }
    }

    @Test
    fun aBrokenToolIsSentBackAsTextAndTheModelStillAnswers() = runBlocking {
        val llamaBodies = mutableListOf<String>()
        val llama = MockWebServer()
        var round = 0
        llama.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                llamaBodies += request.body.readUtf8()
                round++
                val body = if (round == 1) {
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"function\":{\"name\":\"s3_search\",\"arguments\":\"{}\"}}]}}]}\n\n" +
                        "data: [DONE]\n\n"
                } else {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"I could not search.\"}}]}\n\ndata: [DONE]\n\n"
                }
                return MockResponse().setBody(body)
            }
        }
        val mcp = MockWebServer()
        mcp.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val method = JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("method").asString
                return when (method) {
                    "initialize" -> rpc(1, """{"protocolVersion":"2025-06-18","serverInfo":{"name":"down"}}""")
                        .setHeader("Mcp-Session-Id", "s")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> rpc(2, """{"tools":[{"name":"search","description":"search","inputSchema":{"type":"object"}}]}""")
                    "tools/call" -> MockResponse().setResponseCode(500).setBody("boom")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        llama.start()
        mcp.start()
        try {
            val mcpServer = McpServerEntity(
                id = 3,
                name = "SearXNG",
                url = mcp.url("/mcp").toString().trimEnd('/'),
                enabled = true
            )
            val catalog = ToolCatalog.from(listOf(mcpServer to McpClient(mcpServer).listTools()))
            val text = StringBuilder()
            val traces = mutableListOf<ToolTrace>()
            runToolLoop(
                llama = LlamaClient(ServerEntity(name = "llama", url = llama.url("/").toString().trimEnd('/'))),
                messages = mutableListOf(Msg("user", "hi")),
                buildRequest = { msgs, tools -> ChatRequest(model = "m", messages = msgs, tools = tools) },
                catalog = catalog,
                callTool = { exposed, args -> McpClient(exposed.server).callTool(exposed.tool.name, args) },
                onContent = { text.append(it) },
                onRoundDone = {},
                onToolStart = { _, _, _ -> },
                onToolResult = { traces += it }
            )
            assertTrue(traces.single().isError)
            assertTrue(traces.single().result.contains("HTTP 500"))
            assertTrue(llamaBodies[1].contains("HTTP 500"))
            assertEquals("I could not search.", text.toString())
        } finally {
            llama.shutdown()
            mcp.shutdown()
        }
    }

    @Test
    fun toolRoundsStopAtTheCapThenAskForAPlainAnswer() = runBlocking {
        var rounds = 0
        val llama = MockWebServer()
        val bodies = mutableListOf<String>()
        llama.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                bodies += body
                rounds++
                val sse = if (body.contains("\"tools\"")) {
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c$rounds\",\"function\":{\"name\":\"s1_ping\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"
                } else {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\ndata: [DONE]\n\n"
                }
                return MockResponse().setBody(sse)
            }
        }
        val mcp = MockWebServer()
        var calls = 0
        mcp.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val method = json.get("method").asString
                val id = json.get("id")?.asInt ?: 1
                return when (method) {
                    "initialize" -> rpc(id, """{"protocolVersion":"2025-06-18","serverInfo":{"name":"p"}}""")
                        .setHeader("Mcp-Session-Id", "s")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> rpc(id, """{"tools":[{"name":"ping","description":"ping","inputSchema":{"type":"object"}}]}""")
                    "tools/call" -> {
                        calls++
                        rpc(id, """{"content":[{"type":"text","text":"pong"}],"isError":false}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        llama.start()
        mcp.start()
        try {
            val mcpServer = McpServerEntity(id = 1, name = "Ping", url = mcp.url("/mcp").toString().trimEnd('/'))
            val catalog = ToolCatalog.from(listOf(mcpServer to McpClient(mcpServer).listTools()))
            val text = StringBuilder()
            runToolLoop(
                llama = LlamaClient(ServerEntity(name = "llama", url = llama.url("/").toString().trimEnd('/'))),
                messages = mutableListOf(Msg("user", "go")),
                buildRequest = { msgs, tools -> ChatRequest(model = "m", messages = msgs, tools = tools) },
                catalog = catalog,
                callTool = { exposed, args -> McpClient(exposed.server).callTool(exposed.tool.name, args) },
                maxToolCalls = 1,
                onContent = { text.append(it) },
                onRoundDone = {},
                onToolStart = { _, _, _ -> },
                onToolResult = {}
            )
            assertEquals(1, calls)
            assertEquals(2, rounds)
            assertFalse(bodies.last().contains("\"tools\""))
            assertEquals("done", text.toString())
        } finally {
            llama.shutdown()
            mcp.shutdown()
        }
    }

    @Test
    fun connectionDropBeforeAnyTokenIsTriedOnceMore() = runBlocking {
        val llama = MockWebServer()
        var calls = 0
        llama.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                if (calls == 1) {
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                return MockResponse().setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"still here\"}}]}\n\ndata: [DONE]\n\n"
                )
            }
        }
        llama.start()
        try {
            val text = StringBuilder()
            runToolLoop(
                llama = LlamaClient(ServerEntity(name = "llama", url = llama.url("/").toString().trimEnd('/'))),
                messages = mutableListOf(Msg("user", "hi")),
                buildRequest = { msgs, tools -> ChatRequest(model = "m", messages = msgs, tools = tools) },
                catalog = null,
                callTool = { _, _ -> error("no tool") },
                onContent = { text.append(it) },
                onRoundDone = {},
                onToolStart = { _, _, _ -> },
                onToolResult = {}
            )
            assertEquals("still here", text.toString())
            assertEquals(2, calls)
        } finally {
            llama.shutdown()
        }
    }

    @Test
    fun toolCallHiddenInReasoningIsRunAndStripped() = runBlocking {
        val llama = MockWebServer()
        var round = 0
        val xml = "<tool_call> <function=s2_get_file_contents> <parameter=owner> jimgrok-maker <parameter=repo> PetLlama <parameter=path> app/src/main <parameter=sha> 3a9dc79b617d <parameter=fields> [\"name\",\"type\",\"path\"] </tool_call>"
        llama.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                round++
                val sse = if (round == 1) {
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":${com.google.gson.Gson().toJson(xml)}}}]}\n\ndata: [DONE]\n\n"
                } else {
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Reviewed.\"}}]}\n\ndata: [DONE]\n\n"
                }
                return MockResponse().setBody(sse)
            }
        }
        llama.start()
        try {
            val schema = com.google.gson.JsonParser.parseString(
                """{"type":"object","properties":{"owner":{"type":"string"},"repo":{"type":"string"},"path":{"type":"string"},"ref":{"type":"string"}}}"""
            ).asJsonObject
            val server = McpServerEntity(id = 2, name = "github", url = "https://example/mcp")
            val catalog = ToolCatalog.from(
                listOf(server to listOf(McpTool("get_file_contents", "read", schema)))
            )
            val seen = mutableListOf<String>()
            var revisedThinking: String? = null
            val result = runToolLoop(
                llama = LlamaClient(ServerEntity(name = "llama", url = llama.url("/").toString().trimEnd('/'))),
                messages = mutableListOf(Msg("user", "review the petllama github repo")),
                buildRequest = { msgs, tools -> ChatRequest(model = "m", messages = msgs, tools = tools) },
                catalog = catalog,
                callTool = { _, args ->
                    seen += args
                    McpCallResult("file", isError = false)
                },
                onContent = {},
                onReasoning = {},
                onReasoningRevised = { revisedThinking = it },
                onRoundDone = {},
                onToolStart = { _, _, _ -> },
                onToolResult = {}
            )
            assertEquals(1, seen.size)
            val args = com.google.gson.JsonParser.parseString(seen.single()).asJsonObject
            assertEquals("3a9dc79b617d", args.get("ref").asString)
            assertEquals("app/src/main", args.get("path").asString)
            assertFalse(args.has("sha"))
            assertFalse(args.has("fields"))
            assertEquals("", revisedThinking)
            assertFalse(result.hitToolLimit)
        } finally {
            llama.shutdown()
        }
    }

    @Test
    fun hugeToolResultIsCutDownBeforeItIsSent() {
        val text = capToolResult("x".repeat(5_000))
        assertTrue(text.contains("truncated"))
        assertTrue(text.contains("1000 more characters"))
        assertTrue(text.length < 5_000)
    }

    @Test
    fun exposedNamesStayShortAndUnique() {
        val server = McpServerEntity(id = 12, name = "GitHub", url = "http://192.168.1.5:3000/mcp")
        val longName = "a".repeat(80)
        val catalog = ToolCatalog.from(
            listOf(
                server to listOf(
                    McpTool(longName, "d", com.google.gson.JsonObject()),
                    McpTool("create issue", "d", com.google.gson.JsonObject()),
                    McpTool("create_issue", "d", com.google.gson.JsonObject())
                )
            )
        )
        assertTrue(catalog.tools.all { it.exposedName.length <= 64 })
        assertEquals(catalog.tools.size, catalog.tools.map { it.exposedName }.toSet().size)
        assertEquals("s12_create_issue", catalog.tools[1].exposedName)
    }

    private fun rpc(id: Int?, result: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":${id ?: 1},"result":$result}""")
}
