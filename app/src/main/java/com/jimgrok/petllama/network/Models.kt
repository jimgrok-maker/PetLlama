package com.jimgrok.petllama.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/** A model entry returned by /v1/models */
data class ModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String = id
)

data class ModelListResponse(
    @SerializedName("data") val data: List<ModelInfo>? = emptyList()
)

/** One SSE chunk from /v1/chat/completions */
data class ChatChunk(
    @SerializedName("choices") val choices: List<Choice>? = emptyList()
) {
    data class Choice(
        @SerializedName("delta") val delta: Delta? = null,
        @SerializedName("finish_reason") val finishReason: String? = null
    )
    data class Delta(
        @SerializedName("role") val role: String? = null,
        @SerializedName("content") val content: String? = null
    )
}

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<Msg>,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("top_p") val topP: Float? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null
)

data class Msg(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

object GsonHolder { val gson = Gson() }
