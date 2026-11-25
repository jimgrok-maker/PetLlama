package com.jimgrok.petllama.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jimgrok.petllama.data.Db
import com.jimgrok.petllama.ui.chat.ChatScreen
import com.jimgrok.petllama.ui.mcp.McpServersScreen
import com.jimgrok.petllama.ui.screens.ServersScreen
import kotlinx.coroutines.flow.first

/**
 * Root navigation without Navigation Compose.
 *  - Servers always reachable (add/switch).
 *  - Tap a server to open ChatScreen for it.
 *  - On initial launch, auto-open the first configured server if none is selected.
 */
@Composable
fun RootScreen() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var selectedServerId by rememberSaveable { mutableStateOf<Long?>(null) }
        var showMcp by rememberSaveable { mutableStateOf(false) }
        var didInitialLoad by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (!didInitialLoad) {
                didInitialLoad = true
                if (selectedServerId == null) {
                    val first = Db.servers.observeAll().first().firstOrNull()
                    if (first != null) selectedServerId = first.id
                }
            }
        }

        BackHandler(enabled = showMcp || selectedServerId != null) {
            when {
                showMcp -> showMcp = false
                selectedServerId != null -> selectedServerId = null
            }
        }

        when {
            showMcp -> McpServersScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { showMcp = false }
            )
            selectedServerId != null -> ChatScreen(
                modifier = Modifier.fillMaxSize(),
                serverId = selectedServerId!!,
                onBack = { selectedServerId = null },
                onOpenMcp = { showMcp = true }
            )
            else -> ServersScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenChat = { serverId -> selectedServerId = serverId },
                onOpenMcp = { showMcp = true }
            )
        }
    }
}
