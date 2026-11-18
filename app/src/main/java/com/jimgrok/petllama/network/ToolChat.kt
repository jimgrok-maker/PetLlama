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

/** How the loop ended: a final answer, the round limit, or a round that produced nothing at all. */
data class LoopResult(
    val hitToolLimit: Boolean,
    val hitEmptyRound: Boolean
)

/** Text shown under the chat when the loop stops on its own. Null means nothing to say. */
data class LoopNotice(val title: String, val detail: String)

private const val MAX_TOOL_RESULT_CHARS = 4_000
private const val MAX_TOOL_ROUNDS = 12

/**
 * Talks to llama-server, and when the model asks for a tool, runs it on the
 * matching MCP server and sends the result back. Two kinds of asks are
 * understood: structured tool_calls, and raw <tool_call> text, which is what
 * small models write once the prompt is full of tool history. Stops after
 * [maxToolRounds] rounds and asks once more without tools so there is a final
 * answer, and stops after two empty rounds in a row.
 */
suspend fun runToolLoop(
    llama: LlamaClient,
    messages: MutableList<Msg>,
    buildRequest: (List<Msg>, List<ToolSpec>?) -> ChatRequest,
    catalog: ToolCatalog?,
    callTool: suspend (ExposedTool, String) -> McpCallResult,
    maxToolRounds: Int = MAX_TOOL_ROUNDS,
    onContent: (String) -> Unit,
    onMetrics: (CompletionStats) -> Unit = {},
    onReasoning: (String) -> Unit = {},
    onRoundDone: suspend () -> Unit,
    onToolStart: suspend (serverName: String, toolName: String, arguments: String) -> Unit,
    onToolResult: suspend (ToolTrace) -> Unit
): Pair<LoopResult, LoopNotice?> {
    var toolRounds = 0
    var emptyRounds = 0
    while (true) {
        val allowTools = catalog != null && catalog.tools.isNotEmpty() && toolRounds < maxToolRounds
        val tools = if (allowTools) catalog?.toOpenAiTools() else null
        val roundText = StringBuilder()
        val partials = sortedMapOf<Int, PartialCall>()
        var attempt = 0
        while (true) {
            roundText.clear()
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
                        is StreamEvent.Reasoning -> onReasoning(event.text)
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
        // A degraded model writes tool calls as text. Recover them before
        // deciding this round produced no calls.
        val textCalls = splitTextToolCalls(roundText.toString())
        for (tc in textCalls.calls) {
            partials[partials.size] = PartialCall("", tc.name, tc.arguments, tc.truncated)
        }
        val calls = partials.values.filter { it.name.isNotBlank() }
        onRoundDone()
        if (calls.isEmpty()) {
            emptyRounds++
            if (!allowTools) {
                return LoopResult(false, false) to null
            }
            if (emptyRounds >= 2) {
                return LoopResult(false, true) to LoopNotice(
                    "Stopped: the model produced nothing to run.",
                    "Two rounds in a row had no tool calls and no final answer. Continue, or turn tools off and ask again."
                )
            }
            messages += Msg(
                role = "assistant",
                content = roundText.toString().ifBlank { null }
            )
            messages += Msg(
                role = "user",
                content = "You did not call a tool. If the task still needs one, call it now; otherwise answer in plain text."
            )
            continue
        }
        emptyRounds = 0
        val toolCalls = calls.mapIndexed { index, partial ->
            ToolCall(
                id = partial.id.ifBlank { "call_${toolRounds}_$index" },
                function = ToolCallFunction(
                    partial.name,
                    if (partial.truncated) partial.arguments.toString()
                    else partial.arguments.toString().ifBlank { "{}" }
                )
            )
        }
        messages += Msg(
            role = "assistant",
            content = textCalls.answer.ifBlank { null },
            toolCalls = toolCalls
        )
        for ((index, call) in toolCalls.withIndex()) {
            val partial = calls[index]
            val exposed = catalog?.find(call.function.name)
            val arguments = call.function.arguments
            val labelServer = exposed?.server?.name ?: "MCP"
            val labelTool = exposed?.tool?.name ?: call.function.name
            onToolStart(labelServer, labelTool, arguments)
            val outcome = when {
                // The model ran out of output mid-block: the arguments are not
                // a complete JSON object, so do not run them.
                partial.truncated -> McpCallResult(
                    "Tool call was cut off before its arguments finished. Continue to complete it.",
                    isError = true
                )
                exposed == null -> McpCallResult("Unknown tool: ${call.function.name}", isError = true)
                call.function.arguments.isBlank() -> McpCallResult("No arguments provided.", isError = true)
                else -> safeCallTool(exposed, arguments)
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
        toolRounds++
        if (toolRounds >= maxToolRounds) {
            messages += Msg(
                role = "user",
                content = "You have used all " + maxToolRounds + " tool rounds. Answer now in plain text, without any tool calls."
            )
            return LoopResult(true, false) to LoopNotice(
                "Stopped after $maxToolRounds tool rounds.",
                "The final answer above was generated without tools. Start a new chat for a long task, or turn tools off and ask again."
            )
        }
    }
}

private suspend fun safeCallTool(exposed: ExposedTool, arguments: String): McpCallResult =
    try {
        callToolRef!!.invoke(exposed, arguments)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        McpCallResult("Tool error: ${e.explain()}", isError = true)
    }

private var callToolRef: (suspend (ExposedTool, String) -> McpCallResult)? = null

private class PartialCall(
    val id: String = "",
    val name: String = "",
    val arguments: StringBuilder = StringBuilder(),
    val truncated: Boolean = false
)

internal fun capToolResult(text: String): String {
    if (text.length <= MAX_TOOL_RESULT_CHARS) return text
    val omitted = text.length - MAX_TOOL_RESULT_CHARS
    return text.take(MAX_TOOL_RESULT_CHARS) +
        "\n…(truncated, $omitted more characters left out so the model can finish)"
}
