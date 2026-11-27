package com.jimgrok.petllama.ui.chat

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.PetLlamaApp
import com.jimgrok.petllama.GenerationGuard
import com.jimgrok.petllama.data.ConversationEntity
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.McpServerEntity
import com.jimgrok.petllama.data.MessageEntity
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.ChatRequest
import com.jimgrok.petllama.network.CompletionStats
import com.jimgrok.petllama.network.DEFAULT_MAX_TOOL_CALLS
import com.jimgrok.petllama.network.ExposedTool
import com.jimgrok.petllama.network.LiveTokenSpeed
import com.jimgrok.petllama.network.LlamaClient
import com.jimgrok.petllama.network.McpClient
import com.jimgrok.petllama.network.McpTool
import com.jimgrok.petllama.network.ModelInfo
import com.jimgrok.petllama.network.Msg
import com.jimgrok.petllama.network.ToolCatalog
import com.jimgrok.petllama.network.effectiveContextLength
import com.jimgrok.petllama.network.explain
import com.jimgrok.petllama.network.pickModelContextLength
import com.jimgrok.petllama.network.runToolLoop
import com.jimgrok.petllama.network.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // params
        val temperature: Float = 0.7f,
        val topP: Float = 0.95f,
        /** Null means Auto: the request omits max_tokens so reasoning models are not cut off. */
        val maxTokens: Int? = null,
        val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
        val systemPrompt: String = "",
        /** When on, enabled MCP servers are offered to the model for this chat. */
        val toolsEnabled: Boolean = true,
        val tokensPerSecond: Double? = null,
        val promptPerSecond: Double? = null,
        val speedLive: Boolean = false,
        val usedTokens: Int? = null,
        val contextSize: Int? = null,
        /** Shown when a reply stops because it used the tool-call limit. */
        val toolNotice: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Sends are not tied to the screen loader. Cancelling a /props read or
     * reloading models must not cancel MCP initialize.
     */
    private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamJob: Job? = null
    private var loadJob: Job? = null
    private var propsJob: Job? = null
    private var nextTempId = -1L
    private val liveSpeed = LiveTokenSpeed()
    private var contextLoadedFor: Long? = null

    /** n_ctx from /props or /slots. Null on backends that do not have those. */
    private var propsContext: Int? = null

    /** Brief guard (ms) after Send starts so a stray tap on the swapping button does not abort immediately. */
    private var stopAllowedAtElapsedMs: Long = 0L

    init {
        loadChatParams()
    }

    fun init(serverId: Long) {
        // The screen calls this again after rotation or returning from ServersScreen.
        // Keep the existing loader unless the server URL or API key changed.
        if (loadJob?.isActive == true && _state.value.server?.id == serverId) {
            viewModelScope.launch {
                val server = Db.servers.getById(serverId) ?: return@launch
                val prev = _state.value.server
                val endpointChanged = prev == null || prev.url != server.url || prev.apiKey != server.apiKey
                _state.update { it.copy(server = server) }
                if (endpointChanged) {
                    contextLoadedFor = null
                    propsContext = null
                    _state.update { it.copy(models = emptyList(), selectedModel = "") }
                    startLoad(serverId)
                } else {
                    publishContext()
                }
            }
            return
        }
        if (contextLoadedFor != serverId) {
            propsContext = null
        }
        startLoad(serverId)
    }

    private fun startLoad(serverId: Long) {
        loadJob?.cancel()
        propsJob?.cancel()
        loadJob = viewModelScope.launch {
            val server = Db.servers.getById(serverId) ?: return@launch
            _state.update { it.copy(server = server) }
            publishContext()
            try {
                val models = LlamaClient(server).listModels()
                _state.update { s ->
                    val chosen = if (s.selectedModel.isNotEmpty() && models.any { it.id == s.selectedModel }) {
                        s.selectedModel
                    } else {
                        models.firstOrNull()?.id ?: ""
                    }
                    s.copy(
                        models = models,
                        selectedModel = chosen,
                        error = if (s.error?.startsWith("Failed to load models") == true) null else s.error
                    )
                }
                publishContext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to load models: ${e.message}") }
            }
            Db.conversations.observeAll(serverId).collect { convs ->
                _state.update { it.copy(conversations = convs) }
            }
        }
        // /props can block. It is its own job so cancelling it cannot cancel a send,
        // and cancellation must not be swallowed into the model loader.
        propsJob = viewModelScope.launch {
            if (contextLoadedFor == serverId) return@launch
            val server = Db.servers.getById(serverId) ?: return@launch
            val nCtx = try {
                LlamaClient(server).propsOrSlotsContext()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            contextLoadedFor = serverId
            propsContext = nCtx
            publishContext()
        }
    }

    fun selectModel(id: String) {
        _state.update { it.copy(selectedModel = id, error = null) }
        publishContext()
    }

    /** Manual size, then llama-server n_ctx, then the selected model's /v1/models length. */
    private fun publishContext() {
        val st = _state.value
        val detected = propsContext ?: pickModelContextLength(st.models, st.selectedModel)
        val size = effectiveContextLength(st.server?.contextLength, detected)
        _state.update { if (it.contextSize == size) it else it.copy(contextSize = size) }
    }

    fun newConversation() {
        streamJob?.cancel()
        viewModelScope.launch {
            val serverId = _state.value.server?.id ?: return@launch
            val id = withContext(Dispatchers.IO) {
                Db.conversations.insert(
                    ConversationEntity(serverId = serverId, model = _state.value.selectedModel)
                )
            }
            _state.update {
                it.copy(
                    activeConversationId = id,
                    messages = emptyList(),
                    streaming = false,
                    error = null,
                    toolNotice = null,
                    usedTokens = null,
                    tokensPerSecond = null,
                    promptPerSecond = null,
                    speedLive = false
                )
            }
        }
    }

    fun openConversation(id: Long) {
        streamJob?.cancel()
        viewModelScope.launch {
            val msgs = withContext(Dispatchers.IO) { Db.conversations.messagesFor(id) }
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
                    streaming = false,
                    error = null,
                    toolNotice = null,
                    usedTokens = null,
                    tokensPerSecond = null,
                    promptPerSecond = null,
                    speedLive = false
                )
            }
        }
    }

    fun deleteConversation(id: Long) {
        val deletingActive = _state.value.activeConversationId == id
        if (deletingActive) {
            streamJob?.cancel()
        }
        viewModelScope.launch {
            val conv = withContext(Dispatchers.IO) { Db.conversations.getById(id) } ?: return@launch
            withContext(Dispatchers.IO) { Db.conversations.deleteConversation(conv) }
            if (_state.value.activeConversationId == id) {
                _state.update {
                    it.copy(
                        activeConversationId = null,
                        messages = emptyList(),
                        streaming = false,
                        toolNotice = null,
                        usedTokens = null,
                        tokensPerSecond = null,
                        promptPerSecond = null,
                        speedLive = false
                    )
                }
            }
        }
    }

    fun setTemperature(v: Float) {
        _state.update { it.copy(temperature = v) }
        saveChatParams()
    }

    fun setTopP(v: Float) {
        _state.update { it.copy(topP = v) }
        saveChatParams()
    }

    /** Zero or negative means Auto, so the request leaves max_tokens off. */
    fun setMaxTokens(v: Int?) {
        _state.update { it.copy(maxTokens = v?.takeIf { n -> n > 0 }) }
        saveChatParams()
    }

    fun setMaxToolCalls(v: Int) {
        _state.update { it.copy(maxToolCalls = v.coerceIn(1, 100)) }
        saveChatParams()
    }

    fun clearToolNotice() = _state.update { it.copy(toolNotice = null) }

    fun setSystemPrompt(v: String) {
        _state.update { it.copy(systemPrompt = v) }
        saveChatParams()
    }

    private fun loadChatParams() {
        val prefs = chatParamsPrefs() ?: return
        val storedMax = prefs.getInt(KEY_MAX_TOKENS, 0)
        _state.update { st ->
            st.copy(
                temperature = prefs.getFloat(KEY_TEMPERATURE, st.temperature),
                topP = prefs.getFloat(KEY_TOP_P, st.topP),
                maxTokens = storedMax.takeIf { it > 0 },
                maxToolCalls = prefs.getInt(KEY_MAX_TOOL_CALLS, st.maxToolCalls).coerceIn(1, 100),
                systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, st.systemPrompt) ?: st.systemPrompt
            )
        }
    }

    private fun saveChatParams() {
        val prefs = chatParamsPrefs() ?: return
        val st = _state.value
        prefs.edit()
            .putFloat(KEY_TEMPERATURE, st.temperature)
            .putFloat(KEY_TOP_P, st.topP)
            .putInt(KEY_MAX_TOKENS, st.maxTokens ?: 0)
            .putInt(KEY_MAX_TOOL_CALLS, st.maxToolCalls)
            .putString(KEY_SYSTEM_PROMPT, st.systemPrompt)
            .apply()
    }

    private fun chatParamsPrefs() = runCatching {
        PetLlamaApp.app.getSharedPreferences(CHAT_PARAMS_PREFS, Context.MODE_PRIVATE)
    }.getOrNull()
    fun setToolsEnabled(enabled: Boolean) = _state.update { it.copy(toolsEnabled = enabled) }
    fun clearError() = _state.update { it.copy(error = null) }

    /** Drops this user message and everything after it, then sends the edited text. */
    fun editAndResend(messageId: Long, newText: String) {
        if (newText.isBlank() || _state.value.streaming) return
        val convId = _state.value.activeConversationId ?: return
        _state.update { st ->
            st.copy(
                streaming = true,
                error = null,
                toolNotice = null,
                messages = st.messages.filter { it.id < messageId }
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Db.conversations.deleteMessagesFrom(convId, messageId) }
            launchSendJob(newText, reuseExistingUser = false, presetConvId = convId)
        }
    }

    /** Deletes the reply after the last user message and asks the model again. */
    fun regenerate() {
        if (_state.value.streaming) return
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == "user" && it.id >= 0 } ?: return
        val convId = _state.value.activeConversationId ?: return
        _state.update { st ->
            st.copy(
                streaming = true,
                error = null,
                toolNotice = null,
                messages = st.messages.filter { it.id <= lastUser.id }
            )
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Db.conversations.deleteMessagesFrom(convId, lastUser.id + 1)
            }
            launchSendJob(lastUser.content, reuseExistingUser = true, presetConvId = convId)
        }
    }

    fun continueReply() {
        if (_state.value.streaming) return
        send("Continue.")
    }

    fun stop() {
        if (SystemClock.elapsedRealtime() < stopAllowedAtElapsedMs) return
        streamJob?.cancel()
    }

    override fun onCleared() {
        generationScope.cancel()
        super.onCleared()
    }

    private fun tempId(): Long = nextTempId--

    private suspend fun persistMessage(message: MessageEntity): Long =
        withContext(Dispatchers.IO) { Db.conversations.insertMessage(message) }

    private fun applyServerStats(stats: CompletionStats) {
        _state.update { st ->
            st.copy(
                tokensPerSecond = stats.predictedPerSecond ?: st.tokensPerSecond,
                promptPerSecond = stats.promptPerSecond ?: st.promptPerSecond,
                // Ignore a streamed 0 so it cannot wipe a real fill from this turn.
                usedTokens = stats.usedTokens?.takeIf { it > 0 } ?: st.usedTokens,
                speedLive = stats.predictedPerSecond == null
            )
        }
    }

    private fun appendAssistantChunk(convId: Long, chunk: String) {
        _state.update { st ->
            if (st.activeConversationId != convId) return@update st
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
        val split = extractThinkTags(streaming.content)
        val reasoning = listOfNotNull(streaming.reasoning, split.reasoning.takeIf { it.isNotEmpty() })
            .joinToString("\n\n")
            .ifBlank { null }
        val answer = split.answer
        if (answer.isBlank() && reasoning.isNullOrBlank()) {
            _state.update { st -> st.copy(messages = st.messages.filterNot { it.id == streaming.id }) }
            return
        }
        val savedId = persistMessage(
            MessageEntity(
                conversationId = convId,
                role = "assistant",
                content = answer,
                reasoning = reasoning
            )
        )
        _state.update { st ->
            st.copy(messages = st.messages.map { msg ->
                if (msg.id == streaming.id) {
                    msg.copy(
                        id = savedId,
                        content = answer,
                        reasoning = reasoning,
                        isStreaming = false
                    )
                } else {
                    msg
                }
            })
        }
    }

    private fun replaceAssistantContent(convId: Long, text: String) {
        _state.update { st ->
            if (st.activeConversationId != convId) return@update st
            val list = st.messages.toMutableList()
            val idx = list.indexOfLast { it.isStreaming && it.role == "assistant" }
            if (idx >= 0) list[idx] = list[idx].copy(content = text)
            st.copy(messages = list)
        }
    }

    private fun replaceAssistantReasoning(convId: Long, text: String) {
        _state.update { st ->
            if (st.activeConversationId != convId) return@update st
            val list = st.messages.toMutableList()
            val idx = list.indexOfLast { it.isStreaming && it.role == "assistant" }
            if (idx >= 0) list[idx] = list[idx].copy(reasoning = text.ifBlank { null })
            st.copy(messages = list)
        }
    }

    private fun appendReasoning(convId: Long, chunk: String) {
        _state.update { st ->
            if (st.activeConversationId != convId) return@update st
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

    private suspend fun loadCatalog(sessions: MutableMap<Long, McpClient>): ToolCatalog? {
        val servers = withContext(Dispatchers.IO) { Db.mcp.listEnabled() }
        if (servers.isEmpty()) return null
        val groups = mutableListOf<Pair<McpServerEntity, List<McpTool>>>()
        val problems = mutableListOf<String>()
        for (server in servers) {
            val client = sessions.getOrPut(server.id) { McpClient(server) }
            try {
                groups += server to client.listTools()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessions.remove(server.id)?.let { runCatching { it.close() } }
                problems += "${server.name}: ${e.explain()}"
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
        _state.update { it.copy(streaming = true, error = null, toolNotice = null) }
        launchSendJob(text, reuseExistingUser = false, presetConvId = _state.value.activeConversationId)
    }

    private fun launchSendJob(
        text: String,
        reuseExistingUser: Boolean,
        presetConvId: Long?
    ) {
        val s = _state.value
        val server = s.server
        if (server == null) {
            _state.update { it.copy(streaming = false) }
            return
        }
        val model = s.selectedModel.ifEmpty { s.models.firstOrNull()?.id }
        if (model == null) {
            _state.update { it.copy(streaming = false, error = "No model loaded on this server.") }
            return
        }

        stopAllowedAtElapsedMs = SystemClock.elapsedRealtime() + 300L
        streamJob?.cancel()
        streamJob = generationScope.launch {
            val convId = presetConvId ?: withContext(Dispatchers.IO) {
                val id = Db.conversations.insert(
                    ConversationEntity(serverId = server.id, model = model)
                )
                _state.update { st ->
                    if (st.activeConversationId == null) st.copy(activeConversationId = id) else st
                }
                id
            }

            if (!reuseExistingUser) {
                val userMsg = MessageEntity(conversationId = convId, role = "user", content = text)
                val mid = persistMessage(userMsg)
                _state.update { st ->
                    val nextMsgs = if (st.activeConversationId == convId) {
                        st.messages + UiMessage(mid, "user", text)
                    } else {
                        st.messages
                    }
                    st.copy(messages = nextMsgs)
                }
            }

            val messages = outgoingMessages(_state.value.messages, s.systemPrompt).toMutableList()
            val client = LlamaClient(server)
            val sessions = mutableMapOf<Long, McpClient>()
            var toolBubbleId: Long? = null

            var error: String? = null
            try {
                liveSpeed.reset()
                _state.update { it.copy(speedLive = true, tokensPerSecond = null, promptPerSecond = null) }
                val catalog = if (s.toolsEnabled) loadCatalog(sessions) else null
                GenerationGuard.start()
                val loop = runToolLoop(
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
                    maxToolCalls = s.maxToolCalls,
                    catalog = catalog,
                    callTool = { exposed: ExposedTool, args: String ->
                        val mcp = sessions.getOrPut(exposed.server.id) { McpClient(exposed.server) }
                        mcp.callTool(exposed.tool.name, args)
                    },
                    onContent = { chunk ->
                        appendAssistantChunk(convId, chunk)
                        val speed = liveSpeed.onContentDelta()
                        if (speed != null) {
                            _state.update { st ->
                                if (st.speedLive) st.copy(tokensPerSecond = speed) else st
                            }
                        }
                    },
                    onContentRevised = { revised -> replaceAssistantContent(convId, revised) },
                    onMetrics = { stats -> applyServerStats(stats) },
                    onReasoning = { chunk -> appendReasoning(convId, chunk) },
                    onReasoningRevised = { revised -> replaceAssistantReasoning(convId, revised) },
                    onRoundDone = {
                        finishAssistantBubble(convId)
                        liveSpeed.reset()
                        _state.update { it.copy(speedLive = false) }
                    },
                    onToolStart = { serverName, toolName, arguments ->
                        val id = tempId()
                        toolBubbleId = id
                        _state.update { st ->
                            if (st.activeConversationId != convId) return@update st
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
                            if (st.activeConversationId != convId) return@update st
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
                if (loop.hitToolLimit) {
                    val cap = s.maxToolCalls.coerceIn(1, 100)
                    _state.update {
                        it.copy(
                            toolNotice = "Stopped after $cap tool calls. Open chat settings and raise Max tool calls if it should keep going."
                        )
                    }
                }
            } catch (_: CancellationException) {
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
                            speedLive = false,
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

    private companion object {
        const val CHAT_PARAMS_PREFS = "petllama_chat_params"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_MAX_TOOL_CALLS = "max_tool_calls"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
    }
}

/**
 * Messages to send to the model. [history] already includes the new user row.
 * Streaming placeholders (negative ids), tool bubbles, and blank reasoning-only
 * rows are left out. The user text is not added again.
 */
internal fun outgoingMessages(
    history: List<UiMessage>,
    systemPrompt: String,
    limit: Int = 20
): List<Msg> {
    val msgs = ArrayList<Msg>(history.size + 1)
    if (systemPrompt.isNotBlank()) msgs += Msg("system", systemPrompt)
    history.filter { it.id >= 0 && it.role != "tool" && it.content.isNotBlank() }.takeLast(limit).forEach {
        msgs += Msg(it.role, it.content)
    }
    return msgs
}
