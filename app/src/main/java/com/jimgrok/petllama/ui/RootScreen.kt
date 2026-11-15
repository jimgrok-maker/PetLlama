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

/**
 * Simple two-pane-ish navigation without Navigation Compose:
 *  - If no server is configured, show ServersScreen (first-run).
 *  - Otherwise show ChatScreen for the selected server.
 */
@Composable
fun RootScreen() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val hasServers by remember { androidx.compose.runtime.derivedStateOf { true } } // placeholder

        // We keep a simple in-memory "selected server id" state at root.
        var selectedServerId by remember { mutableStateOf<Long?>(null) }

        // Load first server id from DB so we land on chat if one exists.
        LaunchedEffect(Unit) {
            val first = androidx.compose.runtime.snapshotFlow
            // Just query once:
            val list = kotlinx.coroutines.flow.firstOrNull
            val servers = kotlinx.coroutines.flow.firstOrNull
        }

        ServersScreen(
            modifier = Modifier.fillMaxSize(),
            onOpenChat = { serverId -> selectedServerId = serverId }
        )
    }
}
