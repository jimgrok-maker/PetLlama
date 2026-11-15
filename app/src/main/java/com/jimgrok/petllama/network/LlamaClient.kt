package com.jimgrok.petllama.network

import com.jimgrok.petllama.data.ServerEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * Thin client over llama-server's OpenAI-compatible HTTP API.
 * Streaming chat is exposed as a cold Flow<String> of token chunks.
 */
class LlamaClient(private val server: ServerEntity) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun baseUrl(): String {
        val u = server.url.trimEnd('/')
        return u
    }

    private fun authHeaders(builder: Request.Builder) {
        if (server.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${server.apiKey}")
        }
    }

    /** GET /health  → true if reachable */
    suspend fun health(): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url("$baseUrl()/health").build().apply { }
        val r = Request.Builder().url("$baseUrl()/health").build()
        if (server.apiKey.isNotBlank()) r
        // (health is GET, no auth needed, but harmless to add)
        client.newCall(r).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (cont.isActive) cont.cancel(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { if (cont.isActive) cont.resume(it.isSuccessful) }
            }
        })
    }

    /** GET /v1/models → list of loaded model ids */
    suspend fun listModels(): List<ModelInfo> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        var req = Request.Builder().url("$baseUrl()/v1/models").build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (cont.isActive) cont.cancel(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!isSuccessful) {
                        if (cont.isActive) cont.resumeWithException(
                            RuntimeException("HTTP ${code()} on /v1/models")
                        )
                        return
                    }
                    val body = body?.string().orEmpty()
                    val parsed = runCatching {
                        GsonHolder.gson.fromJson(body, ModelListResponse::class.java)
                    }.getOrNull()
                    if (cont.isActive) cont.resume(parsed?.data ?: emptyList())
                }
            }
        })
    }

    /**
     * POST /v1/chat/completions with stream=true.
     * Emits each assistant token (content delta) as it arrives.
     * Completes when the stream ends (finish_reason or data:[DONE]).
     */
    fun streamChat(request: ChatRequest): Flow<String> = callbackFlow {
        val json = GsonHolder.gson.toJson(request)
        val body = json.toRequestBody("application/json".toMediaType())
        var req = Request.Builder()
            .url("$baseUrl()/v1/chat/completions")
            .post(body)
            .build()
        if (server.apiKey.isNotBlank()) {
            req = req.newBuilder().header("Authorization", "Bearer ${server.apiKey}").build()
        }

        val call = client.newCall(req)
        scope.launch {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw RuntimeException("HTTP ${resp.code()} on chat: ${resp.body?.string().orEmpty().take(200)}")
                    }
                    val source = resp.body!!.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) continue
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val chunk = runCatching {
                            GsonHolder.gson.fromJson(payload, ChatChunk::class.java)
                        }.getOrNull() ?: continue
                        for (choice in chunk.choices) {
                            val text = choice.delta.content
                            if (!text.isNullOrEmpty()) send(text)
                        }
                    }
                    close()
                }
            } catch (e: Exception) {
                trySend(e)
                close()
            }
        }
        awaitClose { call.cancel() }
    }

    companion object {
        /** Quick non-blocking connect test that also returns model count. */
        fun testServer(server: ServerEntity, onResult: (Boolean, String) -> Unit) {
            val c = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val r = Request.Builder().url(server.url.trimEnd('/') + "/v1/models").build()
            c.newCall(r).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    onResult(false, "Unreachable: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!isSuccessful) {
                            onResult(false, "HTTP ${code()}")
                        } else {
                            val body = body?.string().orEmpty()
                            val n = runCatching {
                                GsonHolder.gson.fromJson(body, ModelListResponse::class.java)?.data?.size ?: 0
                            }.getOrDefault(0)
                            onResult(true, "$n model(s) loaded")
                        }
                    }
                }
            })
        }
    }
}
