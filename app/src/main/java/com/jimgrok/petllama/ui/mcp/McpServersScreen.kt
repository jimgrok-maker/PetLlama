@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jimgrok.petllama.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Switch
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
import com.jimgrok.petllama.data.McpServerEntity
import com.jimgrok.petllama.network.formatHeaderLines
import com.jimgrok.petllama.network.headersFromJson
import com.jimgrok.petllama.ui.mcpServersViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    modifier: Modifier = Modifier,
    viewModel: McpServersViewModel = viewModel(factory = mcpServersViewModelFactory),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf<McpServerEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add MCP server")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "These are tools the model can call. Use the LAN address of each MCP server, not localhost. The wrench in chat turns tools on or off.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { PresetSection(onAdd = { preset, url -> viewModel.addPreset(preset, url) }) }
            item {
                Text("Your servers", style = MaterialTheme.typography.titleMedium)
            }
            items(state.servers, key = { it.id }) { server ->
                McpServerCard(
                    server = server,
                    testResult = state.testResult?.second?.takeIf { state.testResult?.first == server.id },
                    isTesting = state.testingId == server.id,
                    onEnabled = { viewModel.setEnabled(server, it) },
                    onEdit = { editing = server },
                    onDelete = { viewModel.delete(server) },
                    onTest = { viewModel.test(server) }
                )
            }
            if (state.servers.isEmpty()) {
                item {
                    Text(
                        "No MCP servers yet. Add one that speaks Streamable HTTP, such as your GitHub or SearXNG server.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }

    if (showAdd) {
        McpEditSheet(
            title = "Add MCP server",
            initialName = "",
            initialUrl = "",
            initialBearer = "",
            initialHeaders = "",
            formError = state.formError,
            onSave = { name, url, bearer, headers ->
                if (viewModel.save(name, url, bearer, headers, null, enabled = true)) showAdd = false
            },
            onDismiss = { viewModel.clearFormError(); showAdd = false }
        )
    }

    editing?.let { server ->
        McpEditSheet(
            title = "Edit MCP server",
            initialName = server.name,
            initialUrl = server.url,
            initialBearer = server.bearerToken,
            initialHeaders = formatHeaderLines(headersFromJson(server.headersJson)),
            formError = state.formError,
            onSave = { name, url, bearer, headers ->
                if (viewModel.save(name, url, bearer, headers, server.id, server.enabled)) editing = null
            },
            onDismiss = { viewModel.clearFormError(); editing = null }
        )
    }
}

@Composable
private fun PresetSection(onAdd: (McpPreset, String) -> Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Built-in presets", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add saves the server turned off. Test it, then flip the switch. GitHub and SearXNG stay as you saved them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        McpPresets.all.forEach { preset ->
            PresetCard(preset = preset, onAdd = { url -> onAdd(preset, url) })
        }
    }
}

@Composable
private fun PresetCard(preset: McpPreset, onAdd: (String) -> Boolean) {
    var url by remember(preset.id) { mutableStateOf(preset.defaultUrl) }
    var showSetup by remember(preset.id) { mutableStateOf(false) }
    var urlError by remember(preset.id) { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium)
            Text(
                preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = null
                },
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (urlError != null) {
                Text(urlError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showSetup = !showSetup }) {
                Text(if (showSetup) "Hide server setup" else "Server setup")
            }
            if (showSetup) {
                Text(preset.setupHelp, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    if (!onAdd(url)) urlError = "Type a URL first."
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text("Add") }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServerEntity,
    testResult: String?,
    isTesting: Boolean,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = server.enabled, onCheckedChange = onEnabled)
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
            Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (server.bearerToken.isNotBlank()) {
                Text("Bearer token set", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (testResult != null) {
                Text(
                    testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testResult.startsWith("\u2705")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTest, enabled = !isTesting) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (isTesting) "Testing…" else "Test")
            }
        }
    }
}

@Composable
private fun McpEditSheet(
    title: String,
    initialName: String,
    initialUrl: String,
    initialBearer: String,
    initialHeaders: String,
    formError: String?,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var bearer by remember { mutableStateOf(initialBearer) }
    var headers by remember { mutableStateOf(initialHeaders) }
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
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("URL (http://host:port/mcp)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bearer, onValueChange = { bearer = it },
                label = { Text("Bearer token (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = headers, onValueChange = { headers = it },
                label = { Text("Extra headers, one per line") },
                placeholder = { Text("X-Api-Key: abc") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            if (formError != null) {
                Text(formError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onSave(name, url, bearer, headers) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}
