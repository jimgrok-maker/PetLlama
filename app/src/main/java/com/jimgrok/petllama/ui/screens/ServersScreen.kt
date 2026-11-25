@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jimgrok.petllama.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.parseContextOverride
import com.jimgrok.petllama.ui.ServersViewModel
import com.jimgrok.petllama.ui.serversViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    modifier: Modifier = Modifier,
    viewModel: ServersViewModel = viewModel(factory = serversViewModelFactory),
    onOpenChat: (Long) -> Unit,
    onOpenMcp: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf<ServerEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Servers") },
                actions = {
                    IconButton(onClick = onOpenMcp) {
                        Icon(Icons.Default.Extension, contentDescription = "MCP servers")
                    }
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add server")
                    }
                })
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    testResult = state.testResult?.second?.takeIf { state.testResult?.first == server.id },
                    isTesting = state.testingId == server.id,
                    anyTesting = state.testingId != null,
                    onOpen = { onOpenChat(server.id) },
                    onEdit = { editing = server },
                    onDelete = { viewModel.delete(server) },
                    onTest = { viewModel.test(server) }
                )
            }
            if (state.servers.isEmpty()) {
                item {
                    Text(
                        "No servers yet. Add your llama-server address (LAN IP or Tailscale).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (showAdd) {
        ServerEditSheet(
            title = "Add server",
            initialName = "",
            initialUrl = "",
            initialKey = "",
            initialContext = "",
            onSave = { name, url, key, context ->
                viewModel.save(name, url, key, context, null)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    editing?.let { server ->
        ServerEditSheet(
            title = "Edit server",
            initialName = server.name,
            initialUrl = server.url,
            initialKey = server.apiKey,
            initialContext = server.contextLength?.takeIf { it > 0 }?.toString().orEmpty(),
            onSave = { name, url, key, context ->
                viewModel.save(name, url, key, context, server.id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
fun ServerCard(
    server: ServerEntity,
    testResult: String?,
    isTesting: Boolean,
    anyTesting: Boolean = false,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
            Text(server.url, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (testResult != null) {
                Text(testResult, style = MaterialTheme.typography.bodySmall,
                    color = if (testResult.startsWith("\u2705")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onTest, enabled = !isTesting && !anyTesting) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (isTesting) "Testing\u2026" else "Test")
                }
                Spacer(Modifier.padding(horizontal = 8.dp))
                TextButton(onClick = onOpen) { Text("Open chat") }
            }
        }
    }
}

@Composable
fun ServerEditSheet(
    title: String,
    initialName: String,
    initialUrl: String,
    initialKey: String,
    initialContext: String,
    onSave: (String, String, String, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }
    var context by remember { mutableStateOf(initialContext) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = url, onValueChange = { url = it },
                label = { Text("URL (http://host:port)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it },
                label = { Text("API key (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = context,
                onValueChange = { raw -> context = raw.filter { it.isDigit() } },
                label = { Text("Context size (tokens, optional)") },
                placeholder = { Text("Auto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                if (name.isNotBlank() && url.isNotBlank()) {
                    onSave(name.trim(), url.trim(), key.trim(), parseContextOverride(context))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}
