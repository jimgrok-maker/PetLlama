@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jimgrok.petllama.ui.mcp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jimgrok.petllama.data.McpServerEntity
import com.jimgrok.petllama.network.formatHeaderLines
import com.jimgrok.petllama.network.headersFromJson
import com.jimgrok.petllama.ui.mcpServersViewModelFactory
import com.jimgrok.petllama.ui.theme.PetLlamaColors
import kotlinx.coroutines.delay

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
        containerColor = PetLlamaColors.Canvas,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = PetLlamaColors.Canvas,
                        titleContentColor = PetLlamaColors.TextPrimary,
                        navigationIconContentColor = PetLlamaColors.TextPrimary,
                        actionIconContentColor = PetLlamaColors.TextSecondary
                    ),
                    title = {
                        Column {
                            Text("MCP servers", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Streamable HTTP tool providers",
                                style = MaterialTheme.typography.labelSmall,
                                color = PetLlamaColors.TextSecondary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Surface(
                            onClick = { showAdd = true },
                            shape = RoundedCornerShape(999.dp),
                            color = PetLlamaColors.Amber,
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add MCP server",
                                    tint = PetLlamaColors.AmberOn,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Add",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PetLlamaColors.AmberOn
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(color = PetLlamaColors.BorderSubtle)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PetLlamaColors.SkySoft,
                    border = BorderStroke(1.dp, PetLlamaColors.Sky.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = PetLlamaColors.Sky,
                            modifier = Modifier.padding(top = 1.dp).size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Use the LAN or Tailscale address of each MCP server, not localhost. The wrench icon in chat turns tools on or off.",
                            color = PetLlamaColors.TextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        "Your servers",
                        style = MaterialTheme.typography.titleMedium,
                        color = PetLlamaColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.servers.isNotEmpty()) {
                        val enabledCount = state.servers.count { it.enabled }
                        Text(
                            "$enabledCount / ${state.servers.size} enabled",
                            style = MaterialTheme.typography.labelSmall,
                            color = PetLlamaColors.TextSecondary
                        )
                    }
                }
            }

            items(state.servers, key = { it.id }) { server ->
                McpServerCard(
                    server = server,
                    testResult = state.testResult?.second?.takeIf { state.testResult?.first == server.id },
                    isTesting = state.testingId == server.id,
                    anyTesting = state.testingId != null,
                    onEnabled = { viewModel.setEnabled(server, it) },
                    onEdit = { editing = server },
                    onDelete = { viewModel.delete(server) },
                    onTest = { viewModel.test(server) }
                )
            }

            if (state.servers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = PetLlamaColors.SurfaceCard,
                        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "No MCP servers yet. Tap Add for GitHub or SearXNG, or pick a LAN preset below.",
                            color = PetLlamaColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                PresetSection(onAdd = { preset, url -> viewModel.addPreset(preset, url) })
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
        Text("Built-in LAN presets", style = MaterialTheme.typography.titleMedium, color = PetLlamaColors.TextPrimary)
        Text(
            "Add saves the server turned off. Test it, then flip the switch. GitHub and SearXNG stay untouched.",
            color = PetLlamaColors.TextSecondary,
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
    var justAdded by remember(preset.id) { mutableStateOf(false) }

    LaunchedEffect(justAdded) {
        if (justAdded) {
            delay(1800)
            justAdded = false
        }
    }

    val icon = when (preset.id) {
        "fetch" -> Icons.Default.Language
        "time" -> Icons.Default.Schedule
        "filesystem" -> Icons.Default.Folder
        else -> Icons.Default.Extension
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PetLlamaColors.SurfaceCard),
        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PetLlamaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = PetLlamaColors.Sky,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium, color = PetLlamaColors.TextPrimary)
                    Text(
                        preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = PetLlamaColors.TextSecondary
                    )
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = null
                },
                label = { Text("URL") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = mcpFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (urlError != null) {
                Text(urlError!!, color = PetLlamaColors.Coral, style = MaterialTheme.typography.bodySmall)
            }

            AnimatedVisibility(visible = showSetup) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PetLlamaColors.CodeBackground,
                    border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        preset.setupHelp,
                        style = MaterialTheme.typography.bodySmall,
                        color = PetLlamaColors.TextSecondary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(
                    onClick = { showSetup = !showSetup },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (showSetup) "Hide setup" else "Server setup",
                        color = PetLlamaColors.TextSecondary
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (showSetup) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = PetLlamaColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (onAdd(url)) {
                            justAdded = true
                        } else {
                            urlError = "Type a URL first."
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (justAdded) PetLlamaColors.Emerald else PetLlamaColors.SurfaceElevated,
                        contentColor = if (justAdded) PetLlamaColors.AmberOn else PetLlamaColors.TextPrimary
                    )
                ) {
                    if (justAdded) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Added", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Add", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServerEntity,
    testResult: String?,
    isTesting: Boolean,
    anyTesting: Boolean,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val headerCount = remember(server.headersJson) { headersFromJson(server.headersJson).size }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PetLlamaColors.SurfaceCard),
        border = BorderStroke(
            1.dp,
            if (server.enabled) PetLlamaColors.Amber.copy(alpha = 0.45f) else PetLlamaColors.BorderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = PetLlamaColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        server.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = PetLlamaColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PetLlamaColors.AmberOn,
                        checkedTrackColor = PetLlamaColors.Amber,
                        uncheckedThumbColor = PetLlamaColors.TextSecondary,
                        uncheckedTrackColor = PetLlamaColors.SurfaceElevated,
                        uncheckedBorderColor = PetLlamaColors.BorderStrong
                    )
                )
            }

            if (server.bearerToken.isNotBlank() || headerCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (server.bearerToken.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PetLlamaColors.SurfaceElevated,
                            border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    tint = PetLlamaColors.TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Bearer token",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PetLlamaColors.TextSecondary
                                )
                            }
                        }
                    }
                    if (headerCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = PetLlamaColors.SurfaceElevated,
                            border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle)
                        ) {
                            Text(
                                "$headerCount header(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = PetLlamaColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (testResult != null) {
                val ok = testResult.startsWith("\u2705")
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (ok) PetLlamaColors.EmeraldSoft else PetLlamaColors.CoralSoft,
                    border = BorderStroke(
                        1.dp,
                        if (ok) PetLlamaColors.Emerald.copy(alpha = 0.4f) else PetLlamaColors.Coral.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        testResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) PetLlamaColors.Emerald else PetLlamaColors.Coral,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting && !anyTesting,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PetLlamaColors.BorderStrong)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isTesting) "Testing…" else "Test")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = PetLlamaColors.TextSecondary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = PetLlamaColors.TextMuted)
                }
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PetLlamaColors.Surface,
        contentColor = PetLlamaColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = mcpFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL (http://host:port/mcp)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = mcpFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bearer,
                onValueChange = { bearer = it },
                label = { Text("Bearer token (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = mcpFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = headers,
                onValueChange = { headers = it },
                label = { Text("Extra headers, one per line") },
                placeholder = { Text("X-Api-Key: abc", color = PetLlamaColors.TextMuted) },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
                colors = mcpFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (formError != null) {
                Text(formError, color = PetLlamaColors.Coral, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onSave(name, url, bearer, headers) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PetLlamaColors.Amber,
                    contentColor = PetLlamaColors.AmberOn
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel", color = PetLlamaColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun mcpFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PetLlamaColors.Amber,
    unfocusedBorderColor = PetLlamaColors.BorderSubtle,
    focusedContainerColor = PetLlamaColors.SurfaceCard,
    unfocusedContainerColor = PetLlamaColors.SurfaceCard,
    cursorColor = PetLlamaColors.Amber,
    focusedLabelColor = PetLlamaColors.Amber,
    unfocusedLabelColor = PetLlamaColors.TextSecondary,
    focusedTextColor = PetLlamaColors.TextPrimary,
    unfocusedTextColor = PetLlamaColors.TextPrimary
)
