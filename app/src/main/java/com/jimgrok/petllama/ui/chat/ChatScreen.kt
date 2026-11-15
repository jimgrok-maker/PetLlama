package com.jimgrok.petllama.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jimgrok.petllama.ui.rememberChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    serverId: Long,
    onBack: () -> Unit
) {
    val vm = rememberChatViewModel(serverId)
    val state by vm.state.collectAsState()

    LaunchedEffect(serverId) { vm.init(serverId) }

    var showParams by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(state.messages.size - 1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.server?.name ?: "Chat", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.padding(horizontal = 8.dp))
                        Text(state.selectedModel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = { showParams = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Params")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        minLines = 1,
                        maxLines = 4,
                        placeholder = { Text("Message\u2026") }
                    )
                    if (state.streaming) {
                        IconButton(onClick = { vm.stop() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(onClick = {
                            vm.send(input)
                            input = ""
                        }, enabled = input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {
            // model picker row
            if (state.models.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Model:", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    TextButton(onClick = { showModelMenu = true }) {
                        Text(state.selectedModel.ifEmpty { "\u2014" }, color = MaterialTheme.colorScheme.primary)
                    }
                    if (showModelMenu) {
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            state.models.forEach { m ->
                                DropdownMenuItem(text = { Text(m.id) },
                                    onClick = { vm.selectModel(m.id); showModelMenu = false })
                            }
                        }
                    }
                }
            }

            // error banner
            state.error?.let { err ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.clearError() }) { Text("dismiss") }
                }
            }

            // messages
            if (state.messages.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state.models.isEmpty()) "Connect to a server to start chatting."
                        else "\uD83E\uDD99 Start a conversation.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            role = msg.role,
                            content = msg.content,
                            isStreaming = msg.isStreaming
                        )
                    }
                }
            }
        }
    }

    if (showParams) {
        ParamsSheet(
            temperature = state.temperature,
            topP = state.topP,
            maxTokens = state.maxTokens,
            systemPrompt = state.systemPrompt,
            onTemperature = vm::setTemperature,
            onTopP = vm::setTopP,
            onMaxTokens = vm::setMaxTokens,
            onSystemPrompt = vm::setSystemPrompt,
            onDismiss = { showParams = false }
        )
    }

    if (showHistory) {
        HistorySheet(
            conversations = state.conversations,
            activeId = state.activeConversationId,
            onNew = { vm.newConversation(); showHistory = false },
            onOpen = { vm.openConversation(it); showHistory = false },
            onDelete = { vm.deleteConversation(it) },
            onDismiss = { showHistory = false }
        )
    }
}

@Composable
fun MessageBubble(role: String, content: String, isStreaming: Boolean) {
    val isUser = role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .weight(1f, fill = false)
                .background(bg, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (content.isNotEmpty()) {
                Text(content, style = MaterialTheme.typography.bodyLarge, color = fg)
            } else if (isStreaming) {
                Text("\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamsSheet(
    temperature: Float,
    topP: Float,
    maxTokens: Int,
    systemPrompt: String,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var temp by remember { mutableStateOf(temperature) }
    var top by remember { mutableStateOf(topP) }
    var maxTok by remember { mutableStateOf(maxTokens) }
    var sys by remember { mutableStateOf(systemPrompt) }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Chat parameters", style = MaterialTheme.typography.titleLarge)

            Text("Temperature: ${"%.2f".format(temp)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..2f, steps = 19)

            Text("Top-P: ${"%.2f".format(top)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = top, onValueChange = { top = it }, valueRange = 0f..1f, steps = 9)

            Text("Max tokens", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = maxTok.toString(), onValueChange = { maxTok = it.toIntOrNull() ?: maxTok },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("System prompt", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = sys, onValueChange = { sys = it },
                modifier = Modifier.fillMaxWidth().height(120.dp))

            androidx.compose.material3.Button(onClick = {
                onTemperature(temp); onTopP(top); onMaxTokens(maxTok); onSystemPrompt(sys)
                onDismiss()
            }, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    conversations: List<com.jimgrok.petllama.data.ConversationEntity>,
    activeId: Long?,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Text("New chat")
            }
            Spacer(Modifier.height(8.dp))
            if (conversations.isEmpty()) {
                Text(
                    "No saved chats yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                conversations.forEach { conv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(conv.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            conv.title,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            style = if (conv.id == activeId) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = { onDelete(conv.id) }) { Text("\u2715") }
                    }
                }
            }
        }
    }
}
