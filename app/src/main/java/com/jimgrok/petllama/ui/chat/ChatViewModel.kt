package com.jimgrok.petllama.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.ConversationEntity
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.MessageEntity
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.ChatRequest
import com.jimgrok.petllama.network.ExposedTool
import com.jimgrok.petllama.network.LlamaClient
import com.jimgrok.petllama.network.McpClient
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
    val toolArgs: String? = null
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
        val toolsEnabled: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var streamJob: Job? = null
    private var loadJob: Job? = null
    private var nextTempId = -1L

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
                            toolArgs = m.toolArgs
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

    fun stop() {
        streamJob?.cancel()
    }

    private fun tempId(): Long = nextTempId--

    private suspend fun persistMessage(message: MessageEntity): Long =
        withContext(Dispatchers.IO) { Db.conversations.insertMessage(message) }

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
        val savedId = persistMessage(
            MessageEntity(conversationId = convId, role = "assistant", content = streaming.content)
        )
        _state.update { st ->
            st.copy(messages = st.messages.map { msg ->
                if (msg.id == streaming.id) msg.copy(id = savedId, isStreaming = false) else msg
            })
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

    fun send(text: String) {
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

            val userMsg = MessageEntity(conversationId = convId, role = "user", content = text)
            val mid = persistMessage(userMsg)
            _state.update { st ->
                st.copy(messages = st.messages + UiMessage(mid, "user", text), streaming = true, error = null)
            }

            val messages = outgoingMessages(_state.value.messages, s.systemPrompt).toMutableList()
            val client = LlamaClient(server)
            val sessions = mutableMapOf<Long, McpClient>()
            var toolBubbleId: Long? = null

            streamJob = launch {
                var error: String? = null
                try {
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
                        onContent = { chunk -> appendAssistantChunk(chunk) },
                        onRoundDone = { finishAssistantBubble(convId) },
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
                                    toolArgs = trace.arguments
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
                                    toolArgs = trace.arguments
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
                    error = e.message ?: "stream error"
                } finally {
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
