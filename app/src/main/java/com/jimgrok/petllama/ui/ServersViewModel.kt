package com.jimgrok.petllama.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.LlamaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServersViewModel : ViewModel() {
    data class UiState(
        val servers: List<ServerEntity> = emptyList(),
        val testingId: Long? = null,
        val testResult: Pair<Long, String>? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            Db.servers.observeAll().collect { list ->
                _state.update { it.copy(servers = list) }
            }
        }
    }

    fun save(name: String, url: String, apiKey: String, contextLength: Int?, existingId: Long?) {
        viewModelScope.launch {
            val id = existingId ?: 0L
            Db.servers.upsert(
                ServerEntity(
                    id = id,
                    name = name,
                    url = url,
                    apiKey = apiKey,
                    contextLength = contextLength?.takeIf { it > 0 }
                )
            )
        }
    }

    fun delete(server: ServerEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            Db.conversations.deleteServerCascade(server.id)
            _state.update { st ->
                if (st.testResult?.first == server.id) st.copy(testResult = null) else st
            }
        }
    }

    fun test(server: ServerEntity) {
        if (_state.value.testingId != null) return
        _state.update { it.copy(testingId = server.id, testResult = null) }
        viewModelScope.launch {
            try {
                LlamaClient.testServer(server) { ok, msg ->
                    _state.update {
                        it.copy(
                            testingId = null,
                            testResult = server.id to (if (ok) "✅ $msg" else "❌ $msg")
                        )
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(testingId = null) }
                throw e
            }
        }
    }

    fun dismissTestResult(id: Long) {
        _state.update { if (it.testResult?.first == id) it.copy(testResult = null) else it }
    }
}
