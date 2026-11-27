package com.jimgrok.petllama.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** A model entry returned by /v1/models */
data class ModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String = id,
    /** Context window reported by /v1/models, when the backend sends one. */
    val contextLength: Int? = null
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
        @SerializedName("content") val content: String? = null,
        @SerializedName("reasoning_content") val reasoningContent: String? = null,
        @SerializedName("tool_calls") val toolCalls: List<ToolCallDelta>? = null
    )
    data class ToolCallDelta(
        @SerializedName("index") val index: Int = 0,
        @SerializedName("id") val id: String? = null,
        @SerializedName("type") val type: String? = null,
        @SerializedName("function") val function: FunctionDelta? = null
    )
    data class FunctionDelta(
        @SerializedName("name") val name: String? = null,
        @SerializedName("arguments") val arguments: String? = null
    )
}

/** One piece of a streamed chat completion. */
sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class ToolDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?
    ) : StreamEvent()
    data class Metrics(val stats: CompletionStats) : StreamEvent()
}

data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<Msg>,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("top_p") val topP: Float? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("tools") val tools: List<ToolSpec>? = null,
    /** Omitted when null so Default stays identical to a request without this field. */
    @SerializedName("reasoning_effort") val reasoningEffort: String? = null,
    @SerializedName("chat_template_kwargs") val chatTemplateKwargs: com.google.gson.JsonObject? = null
)

/** OpenAI-style tool definition. [parameters] is the MCP tool's input schema. */
data class ToolSpec(
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: ToolFunction
)

data class ToolFunction(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("parameters") val parameters: JsonElement? = null
)

data class ToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: ToolCallFunction
)

data class ToolCallFunction(
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: String
)

data class Msg(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null
)

object GsonHolder { val gson = Gson() }
