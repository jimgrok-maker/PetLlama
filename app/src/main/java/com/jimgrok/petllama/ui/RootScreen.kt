package com.jimgrok.petllama.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.ui.chat.ChatScreen
import com.jimgrok.petllama.ui.screens.ServersScreen
import kotlinx.coroutines.flow.first

/**
 * Root navigation without Navigation Compose.
 *  - Servers always reachable (add/switch).
 *  - Tap a server to open ChatScreen for it.
 *  - On launch, auto-open the first configured server.
 */
@Composable
fun RootScreen() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var selectedServerId by remember { mutableStateOf<Long?>(null) }

        LaunchedEffect(Unit) {
            val first = Db.servers.observeAll().first().firstOrNull()
            if (first != null) selectedServerId = first.id
        }

        if (selectedServerId != null) {
            ChatScreen(
                modifier = Modifier.fillMaxSize(),
                serverId = selectedServerId!!,
                onBack = { selectedServerId = null }
            )
        } else {
            ServersScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenChat = { serverId -> selectedServerId = serverId }
            )
        }
    }
}
