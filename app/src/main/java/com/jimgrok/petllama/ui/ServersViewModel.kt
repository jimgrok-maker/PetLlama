package com.jimgrok.petllama.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.LlamaClient
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

    fun save(name: String, url: String, apiKey: String, existingId: Long?) {
        viewModelScope.launch {
            val id = if (existingId == null) 0L else existingId
            Db.servers.upsert(ServerEntity(id = id, name = name, url = url, apiKey = apiKey))
        }
    }

    fun delete(server: ServerEntity) {
        viewModelScope.launch { Db.servers.delete(server) }
    }

    fun test(server: ServerEntity) {
        _state.update { it.copy(testingId = server.id) }
        LlamaClient.testServer(server) { ok, msg ->
            _state.update { it.copy(testingId = null, testResult = server.id to (if (ok) "\u2705 $msg" else "\u274c $msg")) }
        }
    }

    fun dismissTestResult(id: Long) {
        _state.update { if (it.testResult?.first == id) it.copy(testResult = null) else it }
    }
}
