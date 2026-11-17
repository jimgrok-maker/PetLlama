package com.jimgrok.petllama.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.ConversationEntity
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.MessageEntity
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.GenerationGuard
import com.jimgrok.petllama.network.ChatRequest
import com.jimgrok.petllama.network.CompletionStats
import com.jimgrok.petllama.network.ExposedTool
import com.jimgrok.petllama.network.LiveTokenSpeed
import com.jimgrok.petllama.network.LlamaClient
import com.jimgrok.petllama.network.McpClient
import com.jimgrok.petllama.network.toUserMessage
import com.jimgrok.petllama.network.ModelInfo
import com.jimgrok.petllama.network.Msg
import com.jimgrok.petllama.network.ToolCatalog
import com.jimgrok.petllama.network.runToolLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A message with a flag marking it as the live-streaming bubble (id not yet persisted). */
data class UiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val reasoning: String? = null,
    val toolError: Boolean = false
)

class ChatViewModel : ViewModel() {

    data class UiState(
        val server: ServerEntity? = null,
        val models: List<ModelInfo> = emptyList(),
        val selectedModel: String = "",
        val messages: List<UiMessage> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
        val conversations: List<ConversationEntity> = emptyList(),
        val activeConversationId: Long? = null,
        val temperature: Float = 0.7f,
        val topP: Float = 0.95f,
        val maxTokens: Int = 512,
        val systemPrompt: String = "",
        /** When on, enabled MCP servers are offered to the model for this chat. */
        val toolsEnabled: Boolean = true,
        val tokensPerSecond: Double? = null,
        val promptPerSecond: Double? = null,
        val speedLive: Boolean = false,
        val usedTokens: Int? = null,
        val contextSize: Int? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var streamJob: Job? = null
    private var loadJob: Job? = null
    private var nextTempId = -1L
    private val liveSpeed = LiveTokenSpeed()
    private var contextLoadedFor: Long? = null

    fun init(serverId: Long) {
        // The screen calls this again after rotation. The load job stays active
        // for the life of the screen, so don't start a second collector.
        if (loadJob?.isActive == true && _state.value.server?.id == serverId) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val server = Db.servers.getById(serverId) ?: return@launch
            _state.update { it.copy(server = server) }
            try {
                val models = LlamaClient(server).listModels()
                _state.update { s ->
                    s.copy(
                        models = models,
                        selectedModel = s.selectedModel.ifEmpty { models.firstOrNull()?.id ?: "" },
                        error = if (s.error?.startsWith("Failed to load models") == true) null else s.error
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to load models: ${e.message}") }
            }
            if (contextLoadedFor != serverId) {
                val nCtx = runCatching { LlamaClient(server).contextLength() }.getOrNull()
                contextLoadedFor = serverId
                if (nCtx != null) _state.update { it.copy(contextSize = nCtx) }
            }
            Db.conversations.observeAll(serverId).collect { convs ->
                _state.update { it.copy(conversations = convs) }
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val serverId = _state.value.server?.id ?: return@launch
            val id = Db.conversations.insert(
                ConversationEntity(serverId = serverId, model = _state.value.selectedModel)
            )
            _state.update { it.copy(activeConversationId = id, messages = emptyList(), error = null) }
        }
    }

    fun openConversation(id: Long) {
        viewModelScope.launch {
            val msgs = Db.conversations.messagesFor(id)
            _state.update {
                it.copy(
                    activeConversationId = id,
                    messages = msgs.map { m ->
                        UiMessage(
                            id = m.id,
                            role = m.role,
                            content = m.content,
                            toolName = m.toolName,
                            toolArgs = m.toolArgs,
                            reasoning = m.reasoning,
                            toolError = m.toolError
                        )
                    },
                    error = null
                )
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val conv = Db.conversations.getById(id) ?: return@launch
            Db.conversations.deleteConversation(conv)
            if (_state.value.activeConversationId == id) {
                _state.update { it.copy(activeConversationId = null, messages = emptyList()) }
            }
        }
    }

    fun selectModel(id: String) = _state.update { it.copy(selectedModel = id, error = null) }
    fun setTemperature(v: Float) = _state.update { it.copy(temperature = v) }
    fun setTopP(v: Float) = _state.update { it.copy(topP = v) }
    fun setMaxTokens(v: Int) = _state.update { it.copy(maxTokens = v) }
    fun setSystemPrompt(v: String) = _state.update { it.copy(systemPrompt = v) }
    fun setToolsEnabled(enabled: Boolean) = _state.update { it.copy(toolsEnabled = enabled) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** Drops this user message and everything after it, then sends the edited text. */
    fun editAndResend(messageId: Long, newText: String) {
        if (newText.isBlank() || _state.value.streaming) return
        val convId = _state.value.activeConversationId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Db.conversations.deleteMessagesFrom(convId, messageId) }
            _state.update { st -> st.copy(messages = st.messages.filter { it.id < messageId }) }
            send(newText)
        }
    }

    /** Deletes the reply after the last user message and asks the model again. */
    fun regenerate() {
        if (_state.value.streaming) return
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == "user" && it.id >= 0 } ?: return
        val convId = _state.value.activeConversationId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Db.conversations.deleteMessagesFrom(convId, lastUser.id + 1)
            }
            _state.update { st -> st.copy(messages = st.messages.filter { it.id <= lastUser.id }) }
            beginSend(lastUser.content, reuseExistingUser = true)
        }
    }

    fun continueReply() {
        if (_state.value.streaming) return
        send("Continue.")
    }

    fun stop() {
        streamJob?.cancel()
    }

    private fun tempId(): Long = nextTempId--

    private suspend fun persistMessage(message: MessageEntity): Long =
        withContext(Dispatchers.IO) { Db.conversations.insertMessage(message) }

    private fun applyServerStats(stats: CompletionStats) {
        _state.update { st ->
            st.copy(
                tokensPerSecond = stats.predictedPerSecond ?: st.tokensPerSecond,
                promptPerSecond = stats.promptPerSecond ?: st.promptPerSecond,
                usedTokens = stats.usedTokens ?: st.usedTokens,
                speedLive = stats.predictedPerSecond == null
            )
        }
    }

    private fun appendAssistantChunk(chunk: String) {
        _state.update { st ->
            val list = st.messages.toMutableList()
            val idx = list.indexOfLast { it.isStreaming && it.role == "assistant" }
            if (idx >= 0) {
                list[idx] = list[idx].copy(content = list[idx].content + chunk)
            } else {
                list += UiMessage(tempId(), "assistant", chunk, isStreaming = true)
            }
            st.copy(messages = list)
        }
    }

    private suspend fun finishAssistantBubble(convId: Long) {
        val streaming = _state.value.messages.lastOrNull { it.isStreaming && it.role == "assistant" }
            ?: return
        if (streaming.content.isBlank()) {
            _state.update { st -> st.copy(messages = st.messages.filterNot { it.id == streaming.id }) }
            return
        }
        val split = extractThinkTags(streaming.content)
        val reasoning = listOfNotNull(streaming.reasoning, split.reasoning.takeIf { it.isNotEmpty() })
            .joinToString("\n\n")
            .ifBlank { null }
        val savedId = persistMessage(
            MessageEntity(
                conversationId = convId,
                role = "assistant",
                content = split.answer.ifBlank { streaming.content },
                reasoning = reasoning
            )
        )
        _state.update { st ->
            st.copy(messages = st.messages.map { msg ->
                if (msg.id == streaming.id) {
                    msg.copy(
                        id = savedId,
                        content = split.answer.ifBlank { streaming.content },
                        reasoning = reasoning,
                        isStreaming = false
                    )
                } else {
                    msg
                }
            })
        }
    }

    private fun appendReasoning(chunk: String) {
        _state.update { st ->
            val list = st.messages.toMutableList()
            val idx = list.indexOfLast { it.isStreaming && it.role == "assistant" }
            if (idx >= 0) {
                list[idx] = list[idx].copy(reasoning = (list[idx].reasoning ?: "") + chunk)
            } else {
                list += UiMessage(tempId(), "assistant", "", isStreaming = true, reasoning = chunk)
            }
            st.copy(messages = list)
        }
    }

    private suspend fun loadCatalog(): ToolCatalog? {
        val servers = withContext(Dispatchers.IO) { Db.mcp.listEnabled() }
        if (servers.isEmpty()) return null
        val groups = mutableListOf<Pair<com.jimgrok.petllama.data.McpServerEntity, List<com.jimgrok.petllama.network.McpTool>>>()
        val problems = mutableListOf<String>()
        for (server in servers) {
            try {
                groups += server to McpClient(server).listTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "${server.name}: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) {
            _state.update { it.copy(error = "Some MCP servers failed: ${problems.joinToString("; ")}") }
        }
        val catalog = ToolCatalog.from(groups)
        return catalog.takeIf { it.tools.isNotEmpty() }
    }

    fun send(text: String) = beginSend(text, reuseExistingUser = false)

    private fun beginSend(text: String, reuseExistingUser: Boolean) {
        if (text.isBlank() || _state.value.streaming) return
        val s = _state.value
        val server = s.server ?: return
        val model = s.selectedModel.ifEmpty { s.models.firstOrNull()?.id }
        if (model == null) {
            _state.update { it.copy(error = "No model loaded on this server.") }
            return
        }

        viewModelScope.launch {
            val convId = s.activeConversationId ?: withContext(Dispatchers.IO) {
                val id = Db.conversations.insert(
                    ConversationEntity(serverId = server.id, model = model)
                )
                _state.update { it.copy(activeConversationId = id) }
                id
            }

            if (!reuseExistingUser) {
                val userMsg = MessageEntity(conversationId = convId, role = "user", content = text)
                val mid = persistMessage(userMsg)
                _state.update { st ->
                    st.copy(messages = st.messages + UiMessage(mid, "user", text), streaming = true, error = null)
                }
            } else {
                _state.update { it.copy(streaming = true, error = null) }
            }

            val messages = outgoingMessages(_state.value.messages, s.systemPrompt).toMutableList()
            val client = LlamaClient(server)
            val sessions = mutableMapOf<Long, McpClient>()
            var toolBubbleId: Long? = null

            streamJob = launch {
                var error: String? = null
                try {
                    GenerationGuard.start()
                    liveSpeed.reset()
                    _state.update { it.copy(speedLive = true, tokensPerSecond = null, promptPerSecond = null) }
                    val catalog = if (s.toolsEnabled) loadCatalog() else null
                    runToolLoop(
                        llama = client,
                        messages = messages,
                        buildRequest = { msgs, tools ->
                            ChatRequest(
                                model = model,
                                messages = msgs,
                                stream = true,
                                temperature = s.temperature,
                                topP = s.topP,
                                maxTokens = s.maxTokens,
                                tools = tools
                            )
                        },
                        catalog = catalog,
                        callTool = { exposed: ExposedTool, args: String ->
                            val mcp = sessions.getOrPut(exposed.server.id) { McpClient(exposed.server) }
                            mcp.callTool(exposed.tool.name, args)
                        },
                        onContent = { chunk ->
                            appendAssistantChunk(chunk)
                            val speed = liveSpeed.onContentDelta()
                            if (speed != null) {
                                _state.update { st ->
                                    if (st.speedLive) st.copy(tokensPerSecond = speed) else st
                                }
                            }
                        },
                        onMetrics = { stats -> applyServerStats(stats) },
                        onReasoning = { chunk -> appendReasoning(chunk) },
                        onRoundDone = {
                            finishAssistantBubble(convId)
                            liveSpeed.reset()
                            _state.update { it.copy(speedLive = false) }
                        },
                        onToolStart = { serverName, toolName, arguments ->
                            val id = tempId()
                            toolBubbleId = id
                            _state.update { st ->
                                st.copy(
                                    messages = st.messages + UiMessage(
                                        id = id,
                                        role = "tool",
                                        content = "Running…",
                                        isStreaming = true,
                                        toolName = "$serverName / $toolName",
                                        toolArgs = arguments
                                    )
                                )
                            }
                        },
                        onToolResult = { trace ->
                            val id = toolBubbleId ?: tempId()
                            val savedId = persistMessage(
                                MessageEntity(
                                    conversationId = convId,
                                    role = "tool",
                                    content = trace.result,
                                    toolName = "${trace.serverName} / ${trace.toolName}",
                                    toolArgs = trace.arguments,
                                    toolError = trace.isError
                                )
                            )
                            _state.update { st ->
                                val list = st.messages.toMutableList()
                                val idx = list.indexOfLast { it.id == id }
                                val bubble = UiMessage(
                                    id = savedId,
                                    role = "tool",
                                    content = trace.result,
                                    toolName = "${trace.serverName} / ${trace.toolName}",
                                    toolArgs = trace.arguments,
                                    toolError = trace.isError
                                )
                                if (idx >= 0) list[idx] = bubble else list += bubble
                                st.copy(messages = list)
                            }
                            toolBubbleId = null
                        }
                    )
                } catch (e: CancellationException) {
                    // stop pressed — keep partial text and finished tool bubbles
                } catch (e: Exception) {
                    error = e.toUserMessage()
                } finally {
                    sessions.values.forEach { runCatching { it.close() } }
                    GenerationGuard.stop()
                    withContext(NonCancellable) {
                        finishAssistantBubble(convId)
                        _state.update { st ->
                            st.copy(
                                messages = st.messages.filterNot { it.isStreaming && it.role == "tool" && it.content == "Running…" },
                                streaming = false,
                                error = error ?: st.error
                            )
                        }
                        withContext(Dispatchers.IO) {
                            val conv = Db.conversations.getById(convId)
                            if (conv != null) {
                                Db.conversations.update(
                                    conv.copy(
                                        updatedAt = System.currentTimeMillis(),
                                        title = if (conv.title == "New chat") text.take(40) else conv.title
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Messages to send to the model. [history] already includes the new user row.
 * Streaming placeholders (negative ids) and tool bubbles are left out.
 * The user text is not added again.
 */
internal fun outgoingMessages(
    history: List<UiMessage>,
    systemPrompt: String,
    limit: Int = 20
): List<Msg> {
    val msgs = ArrayList<Msg>(history.size + 1)
    if (systemPrompt.isNotBlank()) msgs += Msg("system", systemPrompt)
    history.filter { it.id >= 0 && it.role != "tool" }.takeLast(limit).forEach {
        msgs += Msg(it.role, it.content)
    }
    return msgs
}
