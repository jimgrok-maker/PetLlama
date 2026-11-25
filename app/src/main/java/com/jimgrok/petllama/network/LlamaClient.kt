package com.jimgrok.petllama.network

import com.jimgrok.petllama.data.ServerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin client over llama-server's OpenAI-compatible HTTP API.
 * Streaming chat is exposed as a cold Flow of token chunks.
 *
 * Plain http:// URLs are intentional: the app is meant for a server on the home LAN.
 */
class LlamaClient(private val server: ServerEntity) {

    private val client: OkHttpClient = defaultClient

    private fun baseUrl(): String {
        val trimmed = server.url.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1", ignoreCase = true) && trimmed.length > 3) {
            trimmed.dropLast(3).trimEnd('/')
        } else {
            trimmed
        }
    }

    /** GET /health -> true if the server answers with a success status. */
    suspend fun health(): Boolean = suspendCancellableCoroutine { cont ->
        val req = try {
            Request.Builder()
                .url(endpoint(baseUrl(), "/health"))
                .withApiKey(server.apiKey)
                .build()
        } catch (_: IllegalArgumentException) {
            cont.resumeIfActive(false)
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeIfActive(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { cont.resumeIfActive(it.isSuccessful) }
            }
        })
    }

    /** GET /v1/models -> list of loaded model ids. */
    suspend fun listModels(): List<ModelInfo> = suspendCancellableCoroutine { cont ->
        val req = try {
            Request.Builder()
                .url(endpoint(baseUrl(), "/v1/models"))
                .withApiKey(server.apiKey)
                .build()
        } catch (e: IllegalArgumentException) {
            cont.resumeIfActiveWith(Result.failure(e))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeIfActiveWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        cont.resumeIfActiveWith(
                            Result.failure(RuntimeException("HTTP ${resp.code} on /v1/models"))
                        )
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val parsed = try {
                        modelsFromJson(body)
                    } catch (e: Exception) {
                        cont.resumeIfActiveWith(
                            Result.failure(RuntimeException("Could not read model list: ${e.message}"))
                        )
                        return@use
                    }
                    cont.resumeIfActive(parsed)
                }
            }
        })
    }

    /**
     * POST /v1/chat/completions with stream=true.
     * Emits each streamed completion event as it arrives.
     * Completes when the stream ends (data: [DONE] or the connection closes).
     * Failures close the flow with an error so the collector can show it.
     * Cancelling the collector cancels the HTTP call and ends the flow quietly.
     */
    fun streamChat(request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val json = GsonHolder.gson.toJson(request)
        val body = json.toRequestBody("application/json".toMediaType())
        val req = try {
            Request.Builder()
                .url(endpoint(baseUrl(), "/v1/chat/completions"))
                .withApiKey(server.apiKey)
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            close(e)
            return@callbackFlow
        }
        // No read, write, or whole-call timeout. A local model can sit silent for a
        // long time while it reads a big tool result before the first token.
        val call = client.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
            .newCall(req)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty().take(200)
                        throw RuntimeException("HTTP ${resp.code} on chat: $err")
                    }
                    val source = resp.body?.source()
                    if (source != null) {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty() || !line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            parseCompletionStats(payload)?.let { send(StreamEvent.Metrics(it)) }
                            val chunk = runCatching {
                                GsonHolder.gson.fromJson(payload, ChatChunk::class.java)
                            }.getOrNull() ?: continue
                            for (choice in chunk.choices.orEmpty()) {
                                val text = choice.delta?.content
                                if (!text.isNullOrEmpty()) send(StreamEvent.Content(text))
                                val reasoning = choice.delta?.reasoningContent
                                if (!reasoning.isNullOrEmpty()) send(StreamEvent.Reasoning(reasoning))
                                for (toolCall in choice.delta?.toolCalls.orEmpty()) {
                                    send(
                                        StreamEvent.ToolDelta(
                                            index = toolCall.index,
                                            id = toolCall.id,
                                            name = toolCall.function?.name,
                                            arguments = toolCall.function?.arguments
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                close()
            } catch (e: Exception) {
                val canceled = call.isCanceled() || e is CancellationException ||
                    e.message?.contains("Canceled", ignoreCase = true) == true
                if (canceled) close() else close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    /**
     * Context window for the meter. llama-server answers /props then /slots.
     * NInfer and vLLM leave those empty and put max_model_len on /v1/models.
     * [modelId] picks that model's length when the list has more than one.
     */
    suspend fun contextLength(modelId: String? = null): Int? = withContext(Dispatchers.IO) {
        propsOrSlotsContext()?.let { return@withContext it }
        val models = getQuiet("/v1/models") ?: return@withContext null
        pickModelContextLength(modelsFromJson(models), modelId)
    }

    /** n_ctx from llama-server only. Null when this backend has no /props or /slots. */
    suspend fun propsOrSlotsContext(): Int? = withContext(Dispatchers.IO) {
        val props = getQuiet("/props")
        if (props != null) {
            parseContextSize(props)?.let { return@withContext it }
        }
        val slots = getQuiet("/slots")
        if (slots != null) parseContextSize(slots) else null
    }

    private fun getQuiet(path: String): String? {
        val req = try {
            Request.Builder()
                .url(endpoint(baseUrl(), path))
                .withApiKey(server.apiKey)
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        val call = client.newCall(req)
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Quick connect test used by the server list. Reports how many models loaded. */
        fun testServer(server: ServerEntity, onResult: (Boolean, String) -> Unit) {
            val trimmed = server.url.trim().trimEnd('/')
            val root = if (trimmed.endsWith("/v1", ignoreCase = true) && trimmed.length > 3) {
                trimmed.dropLast(3).trimEnd('/')
            } else {
                trimmed
            }
            val url = try {
                endpoint(root, "/v1/models")
            } catch (e: IllegalArgumentException) {
                onResult(false, "Bad URL: ${e.message}")
                return
            }
            val request = try {
                Request.Builder().url(url).withApiKey(server.apiKey).build()
            } catch (e: IllegalArgumentException) {
                onResult(false, "Bad URL: ${e.message}")
                return
            }
            val probeClient = defaultClient.newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            probeClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onResult(false, "Unreachable: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            onResult(false, "HTTP ${resp.code}")
                        } else {
                            val body = resp.body?.string().orEmpty()
                            val n = runCatching { modelsFromJson(body).size }.getOrDefault(0)
                            onResult(true, "$n model(s) loaded")
                        }
                    }
                }
            })
        }
    }
}

private fun endpoint(base: String, path: String): String {
    val root = base.trim().trimEnd('/')
    if (root.isEmpty()) throw IllegalArgumentException("Server URL is empty")
    return root + path
}

private fun Request.Builder.withApiKey(apiKey: String): Request.Builder = apply {
    if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
}

private fun <T> Continuation<T>.resumeIfActive(value: T) {
    if (this is CancellableContinuation && !isActive) return
    resume(value)
}

private fun <T> Continuation<T>.resumeIfActiveWith(result: Result<T>) {
    if (this is CancellableContinuation && !isActive) return
    if (result.isSuccess) resume(result.getOrThrow())
    else resumeWithException(result.exceptionOrNull() ?: RuntimeException("request failed"))
}
