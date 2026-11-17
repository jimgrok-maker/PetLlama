package com.jimgrok.petllama.network

import com.google.gson.JsonParser
import com.jimgrok.petllama.data.McpServerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat used to report a cancelled send as
 * "github initialize failed: JobCancellationException: StandaloneCoroutine was cancelled".
 * Cancelling the screen's other work, or the send itself, must not look like that.
 */
class ChatMcpJobTest {

    @Test
    fun cancellingASiblingLeavesInitializeAlone() = runBlocking {
        val server = scriptedServer(delayInitializeMs = 300)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val props = scope.launch { delay(60_000) }
            val outcome = CompletableDeferred<String>()
            scope.launch {
                val client = McpClient(entity(server))
                try {
                    outcome.complete("tools:${client.listTools().size}")
                } catch (e: CancellationException) {
                    outcome.complete("cancel:${e.message}")
                } catch (e: Exception) {
                    outcome.complete("error:${e.message}")
                } finally {
                    client.close()
                }
            }
            delay(50)
            props.cancel()
            assertEquals("tools:1", withTimeout(4_000) { outcome.await() })
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun cancellingTheSendIsNotReportedAsInitializeFailed() = runBlocking {
        val server = scriptedServer(delayInitializeMs = 2_000)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val outcome = CompletableDeferred<Throwable>()
            val send = scope.launch {
                val client = McpClient(entity(server))
                try {
                    client.listTools()
                    outcome.complete(IllegalStateException("initialize finished after cancel"))
                } catch (t: Throwable) {
                    outcome.complete(t)
                } finally {
                    client.close()
                }
            }
            delay(100)
            send.cancel()
            val error = withTimeout(4_000) { outcome.await() }
            assertTrue(error.toString(), error is CancellationException)
            assertFalse(error.toString(), error is McpException)
            val text = error.message.orEmpty()
            assertFalse(text, text.contains("initialize failed"))
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    private fun entity(server: MockWebServer) = McpServerEntity(
        name = "github",
        url = server.url("/mcp").toString().trimEnd('/')
    )

    private fun scriptedServer(delayInitializeMs: Long): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    return MockResponse().setResponseCode(405)
                }
                val json = runCatching { JsonParser.parseString(request.body.readUtf8()).asJsonObject }.getOrNull()
                val method = json?.get("method")?.asString
                if (method == "notifications/initialized") {
                    return MockResponse().setResponseCode(202)
                }
                val id = json?.get("id")?.asInt ?: 1
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","serverInfo":{"name":"github"},"capabilities":{}}"""
                    "tools/list" -> """{"tools":[{"name":"get_me","description":"me","inputSchema":{"type":"object"}}]}"""
                    else -> return MockResponse().setResponseCode(500)
                }
                val response = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Mcp-Session-Id", "sess")
                    .setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                if (method == "initialize" && delayInitializeMs > 0) {
                    response.setHeadersDelay(delayInitializeMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return response
            }
        }
        server.start()
        return server
    }
}
