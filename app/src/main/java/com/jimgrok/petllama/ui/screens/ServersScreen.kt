@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jimgrok.petllama.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jimgrok.petllama.data.ServerEntity
import com.jimgrok.petllama.network.formatTokenCount
import com.jimgrok.petllama.network.parseContextOverride
import com.jimgrok.petllama.ui.ServersViewModel
import com.jimgrok.petllama.ui.serversViewModelFactory
import com.jimgrok.petllama.ui.theme.PetLlamaColors

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
        containerColor = PetLlamaColors.Canvas,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = PetLlamaColors.Canvas,
                        titleContentColor = PetLlamaColors.TextPrimary,
                        actionIconContentColor = PetLlamaColors.TextSecondary
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = PetLlamaColors.AmberSoft,
                                border = BorderStroke(1.dp, PetLlamaColors.Amber.copy(alpha = 0.45f)),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("\uD83E\uDD99", fontSize = 17.sp)
                                }
                            }
                            Column {
                                Text("PetLlama", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Inference servers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PetLlamaColors.TextSecondary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenMcp) {
                            Icon(Icons.Default.Extension, contentDescription = "MCP servers")
                        }
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
                                    contentDescription = "Add server",
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
        if (state.servers.isEmpty()) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = PetLlamaColors.SurfaceCard,
                    border = BorderStroke(1.dp, PetLlamaColors.BorderStrong),
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = PetLlamaColors.Amber,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "No servers configured",
                    style = MaterialTheme.typography.titleLarge,
                    color = PetLlamaColors.TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add your llama-server, vLLM, or NInfer LAN / Tailscale address to start chatting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PetLlamaColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PetLlamaColors.Amber,
                        contentColor = PetLlamaColors.AmberOn
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add server", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
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
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PetLlamaColors.SurfaceCard),
        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PetLlamaColors.SurfaceElevated,
                    border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = PetLlamaColors.Amber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
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
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = PetLlamaColors.TextSecondary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = PetLlamaColors.TextMuted)
                }
            }

            val hasKey = server.apiKey.isNotBlank()
            val manualCtx = server.contextLength?.takeIf { it > 0 }
            if (hasKey || manualCtx != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasKey) {
                        MetaPill(Icons.Default.Key, "API key")
                    }
                    if (manualCtx != null) {
                        MetaPill(Icons.Default.Memory, "${formatTokenCount(manualCtx)} ctx")
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

            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting && !anyTesting,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PetLlamaColors.BorderStrong)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isTesting) "Testing\u2026" else "Test")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PetLlamaColors.Amber,
                        contentColor = PetLlamaColors.AmberOn
                    )
                ) {
                    Text("Open chat", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaPill(icon: ImageVector, label: String) {
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
                icon,
                contentDescription = null,
                tint = PetLlamaColors.TextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = PetLlamaColors.TextSecondary)
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
                placeholder = { Text("4090 llama-server", color = PetLlamaColors.TextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = serverFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL (http://host:port)") },
                placeholder = { Text("http://192.168.1.50:8080", color = PetLlamaColors.TextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = serverFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = serverFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = context,
                onValueChange = { raw -> context = raw.filter { it.isDigit() } },
                label = { Text("Context size (tokens, optional)") },
                placeholder = { Text("Auto-detect", color = PetLlamaColors.TextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = serverFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        onSave(name.trim(), url.trim(), key.trim(), parseContextOverride(context))
                    }
                },
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
private fun serverFieldColors() = OutlinedTextFieldDefaults.colors(
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
