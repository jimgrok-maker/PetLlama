package com.jimgrok.petllama.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.ConversationEntity
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.MessageEntity
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.ChatRequest
import com.jimgrok.petllama.network.LlamaClient
import com.jimgrok.petllama.network.ModelInfo
import com.jimgrok.petllama.network.Msg
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    data class UiState(
        val server: ServerEntity? = null,
        val models: List<ModelInfo> = emptyList(),
        val selectedModel: String = "",
        val messages: List<MessageEntity> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
        val conversations: List<ConversationEntity> = emptyList(),
        val activeConversationId: Long? = null,
        // params
        val temperature: Float = 0.7f,
        val topP: Float = 0.95f,
        val maxTokens: Int = 512,
        val systemPrompt: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var streamJob: Job? = null

    fun init(serverId: Long) {
        viewModelScope.launch {
            val server = Db.servers.getById(serverId) ?: return@launch
            _state.update { it.copy(server = server) }
            // load models
            try {
                val models = LlamaClient(server).listModels()
                _state.update { s ->
                    s.copy(
                        models = models,
                        selectedModel = s.selectedModel.ifEmpty { models.firstOrNull()?.id ?: "" }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to load models: ${e.message}") }
            }
            // load conversations for this server
            launch {
                Db.conversations.observeAll(serverId).collect { convs ->
                    _state.update { it.copy(conversations = convs) }
                }
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val serverId = _state.value.server?.id ?: return@launch
            val id = Db.conversations.insert(
                ConversationEntity(serverId = serverId, model = _state.value.selectedModel)
            )
            _state.update { it.copy(activeConversationId = id, messages = emptyList()) }
        }
    }

    fun openConversation(id: Long) {
        viewModelScope.launch {
            val msgs = Db.conversations.messagesFor(id)
            _state.update { it.copy(activeConversationId = id, messages = msgs, error = null) }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            val conv = Db.conversations.getById(id) ?: return@launch
            Db.conversations.delete(conv)
            if (_state.value.activeConversationId == id) {
                _state.update { it.copy(activeConversationId = null, messages = emptyList()) }
            }
        }
    }

    fun selectModel(id: String) {
        _state.update { it.copy(selectedModel = id, error = null) }
    }

    fun setTemperature(v: Float) = _state.update { it.copy(temperature = v) }
    fun setTopP(v: Float) = _state.update { it.copy(topP = v) }
    fun setMaxTokens(v: Int) = _state.update { it.copy(maxTokens = v) }
    fun setSystemPrompt(v: String) = _state.update { it.copy(systemPrompt = v) }

    fun send(text: String) {
        val s = _state.value
        val server = s.server ?: return
        val model = s.selectedModel.ifEmpty { s.models.firstOrNull()?.id } ?: return
        if (text.isBlank()) return

        // ensure a conversation exists
        val convId: Long
        if (s.activeConversationId != null) {
            convId = s.activeConversationId!!
        } else {
            convId = viewModelScope.run {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Db.conversations.insert(ConversationEntity(serverId = server.id, model = model))
                }
            }
            _state.update { it.copy(activeConversationId = convId) }
        }

        // append user message
        val userMsg = MessageEntity(conversationId = convId, role = "user", content = text)
        viewModelScope.launch {
            val mid = Db.conversations.insertMessage(userMsg)
            _state.update { it.copy(messages = it.messages + userMsg.copy(id = mid)) }
        }

        // build request with history (last N messages to bound context)
        val history = s.messages.takeLast(20)
        val messages = mutableListOf<Msg>()
        if (s.systemPrompt.isNotBlank()) messages += Msg("system", s.systemPrompt)
        messages += history.map { Msg(it.role, it.content) }
        messages += Msg("user", text)

        val request = ChatRequest(
            model = model,
            messages = messages,
            stream = true,
            temperature = s.temperature,
            topP = s.topP,
            maxTokens = s.maxTokens
        )

        _state.update { it.copy(streaming = true, error = null) }

        // placeholder assistant bubble index
        val assistantId = -1L

        streamJob = viewModelScope.launch {
            var acc = StringBuilder()
            val client = LlamaClient(server)
            try {
                client.streamChat(request).collect { token ->
                    acc.append(token)
                    _state.update { st ->
                        val list = st.messages.toMutableList()
                        // replace last assistant message or append
                        val last = list.lastOrNull { it.role == "assistant" }
                        if (last != null && last.id < 0) {
                            list[list.lastIndex] = last.copy(content = acc.toString())
                        } else {
                            list += MessageEntity(conversationId = convId, role = "assistant", content = acc.toString())
                        }
                        st.copy(messages = list)
                    }
                }
                // persist final assistant message
                val finalText = acc.toString()
                val persisted = Db.conversations.insertMessage(
                    MessageEntity(conversationId = convId, role = "assistant", content = finalText)
                )
                _state.update { st ->
                    val list = st.messages.toMutableList()
                    if (list.isNotEmpty() && list.last().role == "assistant") {
                        list[list.lastIndex] = list.last().copy(id = persisted, content = finalText)
                    }
                    st.copy(messages = list)
                }
                // bump conversation updatedAt + title from first user msg
                val conv = Db.conversations.getById(convId)
                if (conv != null) {
                    val firstUser = st_firstUser(st.messages)
                    val newConv = conv.copy(
                        updatedAt = System.currentTimeMillis(),
                        title = if (conv.title == "New chat") firstUser.take(40) else conv.title
                    )
                    Db.conversations.update(newConv)
                }
            } catch (e: Exception) {
                // keep partial if we have some
                if (acc.isNotEmpty()) {
                    Db.conversations.insertMessage(
                        MessageEntity(conversationId = convId, role = "assistant", content = acc.toString())
                    )
                }
                _state.update { it.copy(error = e.message ?: "stream error") }
            } finally {
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    private fun st_firstUser(msgs: List<MessageEntity>): String {
        val s = _state.value
        return msgs.firstOrNull { it.role == "user" }?.content ?: "New chat"
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.update { it.copy(streaming = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
