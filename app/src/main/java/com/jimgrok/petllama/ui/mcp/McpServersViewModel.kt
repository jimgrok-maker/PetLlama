package com.jimgrok.petllama.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.data.McpServerEntity
import com.jimgrok.petllama.network.McpClient
import com.jimgrok.petllama.network.headersToJson
import com.jimgrok.petllama.network.parseHeaderLines
import com.jimgrok.petllama.network.toUserMessage
import com.jimgrok.petllama.network.validateBearerToken
import kotlinx.coroutines.CancellationException
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
        val tokenError = validateBearerToken(bearerToken)
        if (tokenError != null) {
            _state.update { it.copy(formError = tokenError) }
            return false
        }
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

    /**
     * Saves a built-in preset as a new row, always turned off.
     * Does not update servers that are already in the list.
     */
    fun addPreset(preset: McpPreset, url: String): Boolean {
        val server = preset.serverFor(url) ?: return false
        viewModelScope.launch { Db.mcp.upsert(server.copy(id = 0, enabled = false)) }
        return true
    }

    fun setEnabled(server: McpServerEntity, enabled: Boolean) {
        viewModelScope.launch { Db.mcp.update(server.copy(enabled = enabled)) }
    }

    fun delete(server: McpServerEntity) {
        viewModelScope.launch { Db.mcp.delete(server) }
    }

    fun test(server: McpServerEntity) {
        if (_state.value.testingId != null) return
        _state.update { it.copy(testingId = server.id, testResult = null) }
        viewModelScope.launch {
            val client = McpClient(server)
            val msg = try {
                val info = client.describe()
                val from = info.serverName?.let { " from $it" }.orEmpty()
                "\u2705 ${info.toolCount} tool(s)$from"
            } catch (e: CancellationException) {
                _state.update { it.copy(testingId = null) }
                throw e
            } catch (e: Exception) {
                "\u274c ${e.toUserMessage()}"
            } finally {
                runCatching { client.close() }
            }
            _state.update { it.copy(testingId = null, testResult = server.id to msg) }
        }
    }

    fun clearFormError() = _state.update { it.copy(formError = null) }
}
