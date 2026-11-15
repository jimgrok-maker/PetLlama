package com.jimgrok.petllama.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jimgrok.petllama.ui.chat.ChatViewModel
import com.jimgrok.petllama.ui.mcp.McpServersViewModel

/**
 * Minimal factory so screens can create a ViewModel without Hilt/DI.
 * The Compose viewModel() call keeps the instance across rotation.
 */
val serversViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ServersViewModel::class.java)) {
            return ServersViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}

val mcpServersViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(McpServersViewModel::class.java)) {
            return McpServersViewModel() as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}

/**
 * Chat screen ViewModel, kept by the activity and separated per server.
 */
@Composable
fun rememberChatViewModel(serverId: Long): ChatViewModel {
    return viewModel(key = "chat-$serverId") { ChatViewModel() }
}
