package com.jimgrok.petllama.network

import com.jimgrok.petllama.data.ServerEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to an in-process OpenAI-compatible server (health, models, SSE chat).
 * This is the same shape llama-server speaks on the LAN.
 */
class LlamaClientTest {

    private data class Seen(val path: String, val authorization: String?, val body: String)

    private lateinit var server: MockWebServer
    private val seen = mutableListOf<Seen>()

    @Before
    fun setUp() {
        seen.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                seen += Seen(path, request.getHeader("Authorization"), request.body.readUtf8())
                return when (path) {
                    "/health" -> MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
                    "/v1/models" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"object":"list","data":[{"id":"tiny-llama","object":"model"}]}""")
                    "/v1/chat/completions" -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                                "data: [DONE]\n\n"
                        )
                    else -> MockResponse().setResponseCode(404).setBody("no such path $path")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(apiKey: String = "secret"): LlamaClient {
        val base = server.url("/").toString().trimEnd('/')
        return LlamaClient(ServerEntity(name = "mock", url = base, apiKey = apiKey))
    }

    @Test
    fun healthHitsHealthAndSendsApiKey() = runBlocking {
        assertTrue(client().health())
        val hit = seen.single { it.path == "/health" }
        assertEquals("Bearer secret", hit.authorization)
    }

    @Test
    fun healthOmitsAuthorizationWhenKeyIsBlank() = runBlocking {
        assertTrue(client(apiKey = "").health())
        assertNull(seen.single { it.path == "/health" }.authorization)
    }

    @Test
    fun healthIsFalseWhenUnreachable() = runBlocking {
        val ok = LlamaClient(
            ServerEntity(name = "down", url = "http://127.0.0.1:1", apiKey = "secret")
        ).health()
        assertFalse(ok)
    }

    @Test
    fun listModelsParsesIdsAndSendsApiKey() = runBlocking {
        val models = client().listModels()
        assertEquals(listOf("tiny-llama"), models.map { it.id })
        assertEquals("Bearer secret", seen.single { it.path == "/v1/models" }.authorization)
    }

    @Test
    fun listModelsThrowsOnHttpError() = runBlocking {
        val bad = MockWebServer()
        bad.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        bad.start()
        try {
            val base = bad.url("/").toString().trimEnd('/')
            val result = runCatching {
                LlamaClient(ServerEntity(name = "bad", url = base, apiKey = "secret")).listModels()
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 401"))
            val recorded = bad.takeRequest()
            assertEquals("Bearer secret", recorded.getHeader("Authorization"))
            assertEquals("/v1/models", recorded.path)
        } finally {
            bad.shutdown()
        }
    }

    @Test
    fun streamChatEmitsDeltasAndSendsApiKey() = runBlocking {
        val tokens = withTimeout(10_000) {
            client().streamChat(
                ChatRequest(
                    model = "tiny-llama",
                    messages = listOf(Msg("user", "Hi")),
                    stream = true
                )
            ).toList()
        }
        assertEquals(listOf("Hello", " world"), tokens)
        val hit = seen.single { it.path == "/v1/chat/completions" }
        assertEquals("Bearer secret", hit.authorization)
        assertTrue(hit.body.contains("\"stream\":true"))
        assertEquals(1, Regex("\"content\":\"Hi\"").findAll(hit.body).count())
    }

    @Test
    fun streamChatFailsOnHttpError() = runBlocking {
        val bad = MockWebServer()
        bad.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        bad.start()
        try {
            val base = bad.url("/").toString().trimEnd('/')
            val result = runCatching {
                withTimeout(10_000) {
                    LlamaClient(ServerEntity(name = "bad", url = base, apiKey = "k"))
                        .streamChat(ChatRequest(model = "m", messages = listOf(Msg("user", "Hi"))))
                        .toList()
                }
            }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTTP 500"))
            assertEquals("Bearer k", bad.takeRequest().getHeader("Authorization"))
        } finally {
            bad.shutdown()
        }
    }

    @Test
    fun testServerReportsModelCountAndApiKey() {
        val latch = CountDownLatch(1)
        var result: Pair<Boolean, String>? = null
        val base = server.url("/").toString().trimEnd('/')
        LlamaClient.testServer(
            ServerEntity(name = "mock", url = base, apiKey = "secret")
        ) { ok, msg ->
            result = ok to msg
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(true to "1 model(s) loaded", result)
        assertEquals("Bearer secret", seen.last { it.path == "/v1/models" }.authorization)
    }

    @Test
    fun cancellingTheStreamEndsQuietly() = runBlocking {
        val hanging = MockWebServer()
        // Accepts the socket and never answers, like a server that stalls mid-reply.
        hanging.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        hanging.start()
        try {
            val base = hanging.url("/").toString().trimEnd('/')
            val chat = LlamaClient(ServerEntity(name = "slow", url = base, apiKey = "secret"))
            withTimeout(5_000) {
                val job = launch {
                    chat.streamChat(ChatRequest(model = "m", messages = listOf(Msg("user", "Hi")))).toList()
                }
                delay(200)
                job.cancel()
                job.join()
            }
            assertEquals("Bearer secret", hanging.takeRequest().getHeader("Authorization"))
        } finally {
            hanging.shutdown()
        }
    }

    @Test
    fun testServerReportsBadUrlWithoutCrashing() {
        var result: Pair<Boolean, String>? = null
        LlamaClient.testServer(ServerEntity(name = "x", url = "not a url", apiKey = "")) { ok, msg ->
            result = ok to msg
        }
        assertEquals(false, result?.first)
        assertTrue(result?.second.orEmpty().contains("URL"))
    }
}
