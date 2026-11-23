package com.jimgrok.petllama.network

import com.jimgrok.petllama.data.McpServerEntity

/** A tool the model is allowed to call, with a name that is unique across servers. */
data class ExposedTool(
    val exposedName: String,
    val server: McpServerEntity,
    val tool: McpTool
)

data class ToolCatalog(val tools: List<ExposedTool>) {
    fun find(exposedName: String): ExposedTool? = tools.find { it.exposedName == exposedName }

    fun toOpenAiTools(): List<ToolSpec> = tools.map { exposed ->
        val description = buildString {
            append("[")
            append(exposed.server.name)
            append("] ")
            append(exposed.tool.description?.take(500) ?: exposed.tool.name)
        }
        ToolSpec(
            function = ToolFunction(
                name = exposed.exposedName,
                description = description,
                parameters = exposed.tool.inputSchema
            )
        )
    }

    companion object {
        fun from(groups: List<Pair<McpServerEntity, List<McpTool>>>): ToolCatalog {
            val used = mutableSetOf<String>()
            val exposed = mutableListOf<ExposedTool>()
            for ((server, tools) in groups) {
                for (tool in tools) {
                    if (tool.name.isBlank()) continue
                    var name = exposeName(server.id, tool.name)
                    var n = 2
                    while (!used.add(name)) {
                        val suffix = "_$n"
                        val base = exposeName(server.id, tool.name)
                        name = base.take(64 - suffix.length) + suffix
                        n++
                    }
                    exposed += ExposedTool(name, server, tool)
                }
            }
            return ToolCatalog(exposed)
        }
    }
}

/** OpenAI function names are safest at 64 characters of letters, digits, _ and -. */
internal fun exposeName(serverId: Long, toolName: String): String {
    val safe = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val prefix = "s${serverId}_"
    val combined = prefix + safe
    return if (combined.length <= 64) combined else prefix + safe.takeLast(64 - prefix.length)
}

data class ToolTrace(
    val serverName: String,
    val toolName: String,
    val arguments: String,
    val result: String,
    val isError: Boolean
)

data class LoopResult(val hitToolLimit: Boolean)

/** How many tool calls one reply may run before it stops and says so. */
internal const val DEFAULT_MAX_TOOL_CALLS = 25

private const val MAX_TOOL_RESULT_CHARS = 4_000

/**
 * Talks to llama-server, and when the model asks for a tool, runs it on the
 * matching MCP server and sends the result back. Stops after [maxToolCalls]
 * tool calls, then asks once more with tools turned off so there is a final answer.
 * If the server does not parse Qwen's `<tool_call>` XML, those blocks are read
 * from the answer, or from reasoning when the answer is empty, and run as calls.
 */
suspend fun runToolLoop(
    llama: LlamaClient,
    messages: MutableList<Msg>,
    buildRequest: (List<Msg>, List<ToolSpec>?) -> ChatRequest,
    catalog: ToolCatalog?,
    callTool: suspend (ExposedTool, String) -> McpCallResult,
    maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
    onContent: (String) -> Unit,
    onContentRevised: (String) -> Unit = {},
    onMetrics: (CompletionStats) -> Unit = {},
    onReasoning: (String) -> Unit = {},
    onReasoningRevised: (String) -> Unit = {},
    onRoundDone: suspend () -> Unit,
    onToolStart: suspend (serverName: String, toolName: String, arguments: String) -> Unit,
    onToolResult: suspend (ToolTrace) -> Unit
): LoopResult {
    val limit = maxToolCalls.coerceAtLeast(1)
    var executed = 0
    while (true) {
        val toolsAvailable = catalog != null && catalog.tools.isNotEmpty()
        val underLimit = toolsAvailable && executed < limit
        val tools = if (underLimit) catalog?.toOpenAiTools() else null
        val roundText = StringBuilder()
        val roundReasoning = StringBuilder()
        val partials = sortedMapOf<Int, PartialCall>()
        var attempt = 0
        while (true) {
            roundText.clear()
            roundReasoning.clear()
            partials.clear()
            var events = 0
            try {
                llama.streamChat(buildRequest(messages, tools)).collect { event ->
                    events++
                    when (event) {
                        is StreamEvent.Content -> {
                            roundText.append(event.text)
                            onContent(event.text)
                        }
                        is StreamEvent.Metrics -> onMetrics(event.stats)
                        is StreamEvent.Reasoning -> {
                            roundReasoning.append(event.text)
                            onReasoning(event.text)
                        }
                        is StreamEvent.ToolDelta -> {
                            val partial = partials.getOrPut(event.index) { PartialCall() }
                            if (!event.id.isNullOrEmpty()) partial.id = event.id
                            if (!event.name.isNullOrEmpty()) partial.name = event.name
                            if (event.arguments != null) partial.arguments.append(event.arguments)
                        }
                    }
                }
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // One retry when the socket dies before any token. A dropped
                // connection after text has started is left as-is so we don't
                // print the same words twice.
                if (attempt == 0 && events == 0 && isTransientNetworkError(e)) {
                    attempt++
                    continue
                }
                throw e
            }
        }
        val structured = partials.values.filter { it.name.isNotBlank() }
        var visible = roundText.toString()
        var thinking = roundReasoning.toString()
        val recovered = if (structured.isEmpty() && toolsAvailable) {
            recoverLooseCalls(visible, thinking)
        } else {
            null
        }
        if (recovered != null && recovered.calls.isNotEmpty()) {
            visible = recovered.answer
            thinking = recovered.reasoning
            onContentRevised(visible)
            onReasoningRevised(thinking)
        }
        val pending = when {
            structured.isNotEmpty() && underLimit -> structured.map {
                PendingCall(it.id, it.name, it.arguments.toString().ifBlank { "{}" })
            }
            recovered != null && recovered.calls.isNotEmpty() && underLimit -> recovered.calls.mapIndexed { index, call ->
                PendingCall("xml_${executed}_$index", call.name, call.arguments.ifBlank { "{}" })
            }
            else -> emptyList()
        }
        onRoundDone()
        if (pending.isEmpty()) {
            return LoopResult(hitToolLimit = !underLimit && toolsAvailable && executed > 0)
        }
        val batch = pending.take((limit - executed).coerceAtLeast(0))
        if (batch.isEmpty()) {
            return LoopResult(hitToolLimit = true)
        }
        val toolCalls = batch.mapIndexed { index, call ->
            ToolCall(
                id = call.id.ifBlank { "call_${executed}_$index" },
                function = ToolCallFunction(call.name, call.arguments)
            )
        }
        messages += Msg(
            role = "assistant",
            content = visible.ifBlank { null },
            toolCalls = toolCalls
        )
        for (call in toolCalls) {
            val exposed = catalog?.findLoose(call.function.name)
            val arguments = if (exposed == null) call.function.arguments else fitArguments(exposed.tool, call.function.arguments)
            val labelServer = exposed?.server?.name ?: "MCP"
            val labelTool = exposed?.tool?.name ?: call.function.name
            onToolStart(labelServer, labelTool, arguments)
            val outcome = if (exposed == null) {
                McpCallResult("Unknown tool: ${call.function.name}", isError = true)
            } else {
                try {
                    callTool(exposed, arguments)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    McpCallResult("Tool error: ${e.explain()}", isError = true)
                }
            }
            val text = capToolResult(outcome.text)
            onToolResult(
                ToolTrace(
                    serverName = labelServer,
                    toolName = labelTool,
                    arguments = arguments,
                    result = text,
                    isError = outcome.isError
                )
            )
            messages += Msg(role = "tool", content = text, toolCallId = call.id)
        }
        executed += batch.size
    }
}

private data class PendingCall(
    val id: String,
    val name: String,
    val arguments: String
)

private class PartialCall {
    var id: String = ""
    var name: String = ""
    val arguments: StringBuilder = StringBuilder()
}

internal fun capToolResult(text: String): String {
    if (text.length <= MAX_TOOL_RESULT_CHARS) return text
    val omitted = text.length - MAX_TOOL_RESULT_CHARS
    return text.take(MAX_TOOL_RESULT_CHARS) +
        "\n…(truncated, $omitted more characters left out so the model can finish)"
}
