package com.jimgrok.petllama.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Minimal factory so screens can create a ViewModel without Hilt/DI.
 * ChatViewModel needs a serverId, so it's created via remember(serverId) instead.
 */
val serversViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    private var instance: ServersViewModel? = null
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ServersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return (instance ?: ServersViewModel().also { instance = it }) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}

/**
 * Create a ChatViewModel scoped to a serverId and remember it for the composition.
 */
@Composable
fun rememberChatViewModel(serverId: Long): ChatViewModelHolder {
    val vm = remember(serverId) { ChatViewModel() }
    return ChatViewModelHolder(vm, serverId)
}

class ChatViewModelHolder(val vm: ChatViewModel, val serverId: Long)
