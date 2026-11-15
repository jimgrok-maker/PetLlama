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

private const val MAX_TOOL_RESULT_CHARS = 12_000

/**
 * Talks to llama-server, and when the model asks for a tool, runs it on the
 * matching MCP server and sends the result back. Stops after [maxToolRounds]
 * tool rounds, then asks once more with tools turned off so there is a final answer.
 */
suspend fun runToolLoop(
    llama: LlamaClient,
    messages: MutableList<Msg>,
    buildRequest: (List<Msg>, List<ToolSpec>?) -> ChatRequest,
    catalog: ToolCatalog?,
    callTool: suspend (ExposedTool, String) -> McpCallResult,
    maxToolRounds: Int = 8,
    onContent: (String) -> Unit,
    onRoundDone: suspend () -> Unit,
    onToolStart: suspend (serverName: String, toolName: String, arguments: String) -> Unit,
    onToolResult: suspend (ToolTrace) -> Unit
): LoopResult {
    var toolRounds = 0
    while (true) {
        val allowTools = catalog != null && catalog.tools.isNotEmpty() && toolRounds < maxToolRounds
        val tools = if (allowTools) catalog?.toOpenAiTools() else null
        val roundText = StringBuilder()
        val partials = sortedMapOf<Int, PartialCall>()
        llama.streamChat(buildRequest(messages, tools)).collect { event ->
            when (event) {
                is StreamEvent.Content -> {
                    roundText.append(event.text)
                    onContent(event.text)
                }
                is StreamEvent.ToolDelta -> {
                    val partial = partials.getOrPut(event.index) { PartialCall() }
                    if (!event.id.isNullOrEmpty()) partial.id = event.id
                    if (!event.name.isNullOrEmpty()) partial.name = event.name
                    if (event.arguments != null) partial.arguments.append(event.arguments)
                }
            }
        }
        val calls = partials.values.filter { it.name.isNotBlank() }
        onRoundDone()
        if (!allowTools || calls.isEmpty()) {
            return LoopResult(hitToolLimit = toolRounds >= maxToolRounds && calls.isNotEmpty())
        }
        val toolCalls = calls.mapIndexed { index, partial ->
            ToolCall(
                id = partial.id.ifBlank { "call_${toolRounds}_$index" },
                function = ToolCallFunction(partial.name, partial.arguments.toString().ifBlank { "{}" })
            )
        }
        messages += Msg(
            role = "assistant",
            content = roundText.toString().ifBlank { null },
            toolCalls = toolCalls
        )
        for (call in toolCalls) {
            val exposed = catalog?.find(call.function.name)
            val arguments = call.function.arguments
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
                    McpCallResult("Tool error: ${e.message ?: "failed"}", isError = true)
                }
            }
            val text = capResult(outcome.text)
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
        toolRounds++
    }
}

private class PartialCall {
    var id: String = ""
    var name: String = ""
    val arguments: StringBuilder = StringBuilder()
}

private fun capResult(text: String): String =
    if (text.length <= MAX_TOOL_RESULT_CHARS) text
    else text.take(MAX_TOOL_RESULT_CHARS) + "\n…(truncated)"
