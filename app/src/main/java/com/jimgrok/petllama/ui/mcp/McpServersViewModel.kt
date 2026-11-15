package com.jimgrok.petllama.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.McpServerEntity
import com.jimgrok.petllama.network.McpClient
import com.jimgrok.petllama.network.headersToJson
import com.jimgrok.petllama.network.parseHeaderLines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class McpServersViewModel : ViewModel() {
    data class UiState(
        val servers: List<McpServerEntity> = emptyList(),
        val testingId: Long? = null,
        val testResult: Pair<Long, String>? = null,
        val formError: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            Db.mcp.observeAll().collect { list ->
                _state.update { it.copy(servers = list) }
            }
        }
    }

    fun save(
        name: String,
        url: String,
        bearerToken: String,
        headerLines: String,
        existingId: Long?,
        enabled: Boolean
    ): Boolean {
        val headers = try {
            parseHeaderLines(headerLines)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(formError = e.message) }
            return false
        }
        _state.update { it.copy(formError = null) }
        viewModelScope.launch {
            Db.mcp.upsert(
                McpServerEntity(
                    id = existingId ?: 0L,
                    name = name.trim(),
                    url = url.trim(),
                    bearerToken = bearerToken.trim(),
                    headersJson = headersToJson(headers),
                    enabled = enabled
                )
            )
        }
        return true
    }

    fun setEnabled(server: McpServerEntity, enabled: Boolean) {
        viewModelScope.launch { Db.mcp.update(server.copy(enabled = enabled)) }
    }

    fun delete(server: McpServerEntity) {
        viewModelScope.launch { Db.mcp.delete(server) }
    }

    fun test(server: McpServerEntity) {
        _state.update { it.copy(testingId = server.id, testResult = null) }
        viewModelScope.launch {
            val msg = try {
                val info = McpClient(server).describe()
                val from = info.serverName?.let { " from $it" }.orEmpty()
                "\u2705 ${info.toolCount} tool(s)$from"
            } catch (e: Exception) {
                "\u274c ${e.message ?: "failed"}"
            }
            _state.update { it.copy(testingId = null, testResult = server.id to msg) }
        }
    }

    fun clearFormError() = _state.update { it.copy(formError = null) }
}
