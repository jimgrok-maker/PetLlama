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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val isStreaming: Boolean = false
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
        val systemPrompt: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var streamJob: Job? = null

    fun init(serverId: Long) {
        viewModelScope.launch {
            val server = Db.servers.getById(serverId) ?: return@launch
            _state.update { it.copy(server = server) }
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
            _state.update { it.copy(activeConversationId = id, messages = emptyList(), error = null) }
        }
    }

    fun openConversation(id: Long) {
        viewModelScope.launch {
            val msgs = Db.conversations.messagesFor(id)
            _state.update {
                it.copy(
                    activeConversationId = id,
                    messages = msgs.map { m -> UiMessage(m.id, m.role, m.content) },
                    error = null
                )
            }
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

    fun selectModel(id: String) = _state.update { it.copy(selectedModel = id, error = null) }
    fun setTemperature(v: Float) = _state.update { it.copy(temperature = v) }
    fun setTopP(v: Float) = _state.update { it.copy(topP = v) }
    fun setMaxTokens(v: Int) = _state.update { it.copy(maxTokens = v) }
    fun setSystemPrompt(v: String) = _state.update { it.copy(systemPrompt = v) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.update { it.copy(streaming = false) }
    }

    private suspend fun persistAssistant(convId: Long, text: String): Long =
        if (text.isEmpty()) -1L
        else withContext(Dispatchers.IO) {
            Db.conversations.insertMessage(
                MessageEntity(conversationId = convId, role = "assistant", content = text)
            )
        }

    fun send(text: String) {
        if (text.isBlank() || _state.value.streaming) return
        val s = _state.value
        val server = s.server ?: return
        val model = s.selectedModel.ifEmpty { s.models.firstOrNull()?.id } ?: return

        viewModelScope.launch {
            val convId = s.activeConversationId ?: withContext(Dispatchers.IO) {
                val id = Db.conversations.insert(
                    ConversationEntity(serverId = server.id, model = model)
                )
                _state.update { it.copy(activeConversationId = id) }
                id
            }

            val userMsg = MessageEntity(conversationId = convId, role = "user", content = text)
            val mid = withContext(Dispatchers.IO) { Db.conversations.insertMessage(userMsg) }
            _state.update { st ->
                st.copy(messages = st.messages + UiMessage(mid, "user", text))
            }

            val history = _state.value.messages.filter { it.id >= 0 }.takeLast(20)
            val msgs = mutableListOf<Msg>()
            if (s.systemPrompt.isNotBlank()) msgs += Msg("system", s.systemPrompt)
            msgs += history.map { Msg(it.role, it.content) }
            msgs += Msg("user", text)

            val request = ChatRequest(
                model = model,
                messages = msgs,
                stream = true,
                temperature = s.temperature,
                topP = s.topP,
                maxTokens = s.maxTokens
            )

            _state.update { it.copy(streaming = true, error = null) }

            var acc = StringBuilder()
            val client = LlamaClient(server)

            streamJob = launch {
                var error: String? = null
                try {
                    client.streamChat(request).collect { token ->
                        acc.append(token)
                        _state.update { st ->
                            val list = st.messages.toMutableList()
                            val lastIdx = list.indexOfLast { it.isStreaming }
                            if (lastIdx >= 0) {
                                list[lastIdx] = list[lastIdx].copy(content = acc.toString())
                            } else {
                                list += UiMessage(-1L, "assistant", acc.toString(), isStreaming = true)
                            }
                            st.copy(messages = list)
                        }
                    }
                } catch (e: CancellationException) {
                    // stop pressed — keep partial (no error)
                } catch (e: Exception) {
                    // keep partial text, report error
                    error = e.message ?: "stream error"
                }

                // finalize: persist partial/final, swap placeholder
                val finalText = acc.toString()
                val persistedId = persistAssistant(convId, finalText)
                _state.update { st ->
                    val list = st.messages.toMutableList()
                    val lastIdx = list.indexOfLast { it.isStreaming }
                    when {
                        lastIdx >= 0 && finalText.isNotEmpty() ->
                            list[lastIdx] = UiMessage(persistedId, "assistant", finalText)
                        lastIdx >= 0 ->
                            list.removeAt(lastIdx)
                    }
                    st.copy(messages = list, streaming = false, error = error)
                }

                // bump conversation title + updatedAt
                withContext(Dispatchers.IO) {
                    val conv = Db.conversations.getById(convId) ?: return@withContext
                    val newConv = conv.copy(
                        updatedAt = System.currentTimeMillis(),
                        title = if (conv.title == "New chat") text.take(40) else conv.title
                    )
                    Db.conversations.update(newConv)
                }
            }
        }
    }
}
