@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimgrok.petllama.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.jimgrok.petllama.network.ContextBand
import com.jimgrok.petllama.network.contextFill
import com.jimgrok.petllama.network.formatSpeed
import com.jimgrok.petllama.network.formatTokenCount
import com.jimgrok.petllama.ui.rememberChatViewModel
import com.jimgrok.petllama.ui.theme.PetLlamaColors
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    serverId: Long,
    onBack: () -> Unit,
    onOpenMcp: () -> Unit = {}
) {
    val vm = rememberChatViewModel(serverId)
    val state by vm.state.collectAsState()

    LaunchedEffect(serverId) { vm.init(serverId) }

    var showParams by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var menuMessageId by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<UiMessage?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboardContext = LocalContext.current
    val markwon = remember(clipboardContext) {
        Markwon.builder(clipboardContext).usePlugin(TablePlugin.create(clipboardContext)).build()
    }

    // auto-scroll to bottom on new messages
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(state.messages.size - 1) }
        }
    }

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
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                state.server?.name ?: "Chat",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val modelLabel = state.selectedModel.ifEmpty { "No model loaded" }
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = if (state.models.size > 1) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { showModelMenu = true }
                                            .padding(end = 4.dp)
                                    } else {
                                        Modifier
                                    }
                                ) {
                                    Text(
                                        modelLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (state.models.size > 1) PetLlamaColors.Amber else PetLlamaColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (state.models.size > 1) {
                                        Icon(
                                            Icons.Default.ExpandMore,
                                            contentDescription = "Choose model",
                                            tint = PetLlamaColors.Amber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (state.models.size > 1) {
                                    DropdownMenu(
                                        expanded = showModelMenu,
                                        onDismissRequest = { showModelMenu = false },
                                        modifier = Modifier.background(PetLlamaColors.SurfaceElevated)
                                    ) {
                                        state.models.forEach { m ->
                                            val selected = m.id == state.selectedModel
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        m.id,
                                                        color = if (selected) PetLlamaColors.Amber else PetLlamaColors.TextPrimary,
                                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                                    )
                                                },
                                                trailingIcon = if (selected) {
                                                    {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = PetLlamaColors.Amber,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                } else null,
                                                onClick = {
                                                    vm.selectModel(m.id)
                                                    showModelMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenMcp) {
                            Icon(Icons.Default.Extension, contentDescription = "MCP servers")
                        }
                        Surface(
                            onClick = { vm.setToolsEnabled(!state.toolsEnabled) },
                            shape = RoundedCornerShape(999.dp),
                            color = if (state.toolsEnabled) PetLlamaColors.AmberSoft else Color.Transparent,
                            border = BorderStroke(
                                1.dp,
                                if (state.toolsEnabled) PetLlamaColors.Amber.copy(alpha = 0.55f)
                                else PetLlamaColors.BorderSubtle
                            ),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = if (state.toolsEnabled) "Tools on" else "Tools off",
                                tint = if (state.toolsEnabled) PetLlamaColors.Amber else PetLlamaColors.TextMuted,
                                modifier = Modifier.padding(7.dp).size(18.dp)
                            )
                        }
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Default.History, contentDescription = "History")
                        }
                        IconButton(onClick = { showParams = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Parameters")
                        }
                    }
                )
                HorizontalDivider(color = PetLlamaColors.BorderSubtle)
            }
        },
        bottomBar = {
            ChatComposerBar(
                input = input,
                onInputChange = { input = it },
                streaming = state.streaming,
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = { vm.stop() }
            )
        }
    ) { padding ->
        Column(modifier = modifier.fillMaxSize().padding(padding)) {
            ChatStatusStrip(
                tokensPerSecond = state.tokensPerSecond,
                promptPerSecond = state.promptPerSecond,
                speedLive = state.speedLive,
                usedTokens = state.usedTokens,
                contextSize = state.contextSize
            )

            // error banner
            state.error?.let { err ->
                NoticeBanner(
                    text = err,
                    isError = true,
                    onDismiss = { vm.clearError() }
                )
            }
            state.toolNotice?.let { notice ->
                NoticeBanner(
                    text = notice,
                    isError = false,
                    onDismiss = { vm.clearToolNotice() }
                )
            }

            // messages
            if (state.messages.isEmpty()) {
                EmptyChatState(
                    hasModels = state.models.isNotEmpty(),
                    modelName = state.selectedModel,
                    toolsEnabled = state.toolsEnabled,
                    onPickStarter = { starter -> input = starter }
                )
            } else {
                val lastAssistantId = state.messages.lastOrNull { it.role == "assistant" && !it.isStreaming }?.id
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (msg.role == "tool") {
                                ToolTraceBubble(
                                    name = msg.toolName ?: "Tool",
                                    arguments = msg.toolArgs,
                                    result = msg.content,
                                    running = msg.isStreaming,
                                    failed = msg.toolError,
                                    onLongClick = { menuMessageId = msg.id }
                                )
                            } else {
                                MessageBubble(
                                    role = msg.role,
                                    content = msg.content,
                                    reasoning = msg.reasoning,
                                    isStreaming = msg.isStreaming,
                                    markwon = markwon,
                                    onLongClick = { menuMessageId = msg.id }
                                )
                            }
                            DropdownMenu(
                                expanded = menuMessageId == msg.id,
                                onDismissRequest = { menuMessageId = null },
                                modifier = Modifier.background(PetLlamaColors.SurfaceElevated)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy", color = PetLlamaColors.TextPrimary) },
                                    onClick = {
                                        copyToClipboard(clipboardContext, msg.content)
                                        menuMessageId = null
                                    }
                                )
                                if (msg.role == "user" && !state.streaming) {
                                    DropdownMenuItem(
                                        text = { Text("Edit and resend", color = PetLlamaColors.TextPrimary) },
                                        onClick = { editing = msg; menuMessageId = null }
                                    )
                                }
                                if (msg.role == "assistant" && msg.id == lastAssistantId && !state.streaming) {
                                    DropdownMenuItem(
                                        text = { Text("Regenerate", color = PetLlamaColors.TextPrimary) },
                                        onClick = { vm.regenerate(); menuMessageId = null }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Continue", color = PetLlamaColors.TextPrimary) },
                                        onClick = { vm.continueReply(); menuMessageId = null }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { msg ->
        var draft by remember(msg.id) { mutableStateOf(msg.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = PetLlamaColors.SurfaceCard,
            titleContentColor = PetLlamaColors.TextPrimary,
            textContentColor = PetLlamaColors.TextPrimary,
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = petLlamaFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.editAndResend(msg.id, draft)
                        editing = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PetLlamaColors.Amber,
                        contentColor = PetLlamaColors.AmberOn
                    )
                ) { Text("Resend") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text("Cancel", color = PetLlamaColors.TextSecondary)
                }
            }
        )
    }

    if (showParams) {
        ParamsSheet(
            temperature = state.temperature,
            topP = state.topP,
            maxTokens = state.maxTokens,
            maxToolCalls = state.maxToolCalls,
            systemPrompt = state.systemPrompt,
            onTemperature = vm::setTemperature,
            onTopP = vm::setTopP,
            onMaxTokens = vm::setMaxTokens,
            onMaxToolCalls = vm::setMaxToolCalls,
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
private fun ChatComposerBar(
    input: String,
    onInputChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = PetLlamaColors.Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            HorizontalDivider(color = PetLlamaColors.BorderSubtle)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    placeholder = {
                        Text(
                            "Message your model…",
                            color = PetLlamaColors.TextMuted
                        )
                    },
                    colors = petLlamaFieldColors(containerColor = PetLlamaColors.SurfaceCard)
                )
                if (streaming) {
                    Surface(
                        onClick = onStop,
                        shape = CircleShape,
                        color = PetLlamaColors.CoralSoft,
                        border = BorderStroke(1.dp, PetLlamaColors.Coral.copy(alpha = 0.6f)),
                        modifier = Modifier.padding(bottom = 4.dp).size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = PetLlamaColors.Coral,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                } else {
                    val canSend = input.isNotBlank()
                    Surface(
                        onClick = { if (canSend) onSend() },
                        shape = CircleShape,
                        color = if (canSend) PetLlamaColors.Amber else PetLlamaColors.SurfaceElevated,
                        modifier = Modifier.padding(bottom = 4.dp).size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) PetLlamaColors.AmberOn else PetLlamaColors.TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState(
    hasModels: Boolean,
    modelName: String,
    toolsEnabled: Boolean,
    onPickStarter: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = PetLlamaColors.AmberSoft,
            border = BorderStroke(1.dp, PetLlamaColors.Amber.copy(alpha = 0.45f)),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("\uD83E\uDD99", fontSize = 30.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (hasModels) "Ready to chat" else "Connecting to server…",
            style = MaterialTheme.typography.titleLarge,
            color = PetLlamaColors.TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasModels) {
                modelName.ifBlank { "Pick a prompt below or type anything." }
            } else {
                "Verify the server URL in Servers if no models load."
            },
            style = MaterialTheme.typography.bodySmall,
            color = PetLlamaColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        if (hasModels) {
            Spacer(Modifier.height(22.dp))
            val starters = if (toolsEnabled) {
                listOf(
                    "What MCP tools do you have access to?",
                    "Summarize key takeaways from a topic",
                    "Help me review and debug a snippet of code"
                )
            } else {
                listOf(
                    "Explain a complex concept simply",
                    "Help me draft a clear, concise message",
                    "Help me review and debug a snippet of code"
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
            ) {
                starters.forEach { prompt ->
                    Surface(
                        onClick = { onPickStarter(prompt) },
                        shape = RoundedCornerShape(14.dp),
                        color = PetLlamaColors.SurfaceCard,
                        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PetLlamaColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    val bg = if (isError) PetLlamaColors.CoralSoft else PetLlamaColors.SkySoft
    val border = if (isError) PetLlamaColors.Coral.copy(alpha = 0.45f) else PetLlamaColors.Sky.copy(alpha = 0.4f)
    val accent = if (isError) PetLlamaColors.Coral else PetLlamaColors.Sky
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) Icons.Default.Error else Icons.Default.Info,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                color = PetLlamaColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ToolTraceBubble(
    name: String,
    arguments: String?,
    result: String,
    running: Boolean,
    failed: Boolean,
    onLongClick: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val parts = remember(name) { name.split(" / ", limit = 2) }
    val serverLabel = if (parts.size == 2) parts[0] else null
    val toolLabel = if (parts.size == 2) parts[1] else name
    val statusColor = when {
        running -> PetLlamaColors.Amber
        failed -> PetLlamaColors.Coral
        else -> PetLlamaColors.Emerald
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PetLlamaColors.Surface,
        border = BorderStroke(1.dp, if (failed) PetLlamaColors.Coral.copy(alpha = 0.45f) else PetLlamaColors.BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = { open = !open }, onLongClick = onLongClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> CircularProgressIndicator(
                        color = PetLlamaColors.Amber,
                        modifier = Modifier.padding(end = 8.dp).size(14.dp),
                        strokeWidth = 2.dp
                    )
                    failed -> Icon(
                        Icons.Default.Error,
                        contentDescription = "Failed",
                        tint = statusColor,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp)
                    )
                    else -> Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = statusColor,
                        modifier = Modifier.padding(end = 8.dp).size(16.dp)
                    )
                }
                if (serverLabel != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PetLlamaColors.SurfaceElevated,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            serverLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = PetLlamaColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    toolLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = PetLlamaColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (open) "Collapse" else "Expand",
                    tint = PetLlamaColors.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = open) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    HorizontalDivider(color = PetLlamaColors.BorderSubtle)
                    if (!arguments.isNullOrBlank()) {
                        Text(
                            "ARGUMENTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = PetLlamaColors.TextMuted,
                            letterSpacing = 0.8.sp
                        )
                        PrettyBlock(arguments, showAll)
                    }
                    Text(
                        "RESULT",
                        style = MaterialTheme.typography.labelSmall,
                        color = PetLlamaColors.TextMuted,
                        letterSpacing = 0.8.sp
                    )
                    PrettyBlock(result, showAll)
                    if (!showAll && (arguments.orEmpty().length + result.length) > 900) {
                        TextButton(
                            onClick = { showAll = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Show full output", color = PetLlamaColors.Amber)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatStatusStrip(
    tokensPerSecond: Double?,
    promptPerSecond: Double?,
    speedLive: Boolean,
    usedTokens: Int?,
    contextSize: Int?
) {
    val fill = contextFill(usedTokens, contextSize)
    if (tokensPerSecond == null && promptPerSecond == null && fill == null && usedTokens == null) return
    Surface(
        color = PetLlamaColors.Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(0.42f)) {
                    val speed = when {
                        tokensPerSecond == null -> "— tok/s"
                        speedLive -> "${formatSpeed(tokensPerSecond)} tok/s"
                        else -> "${formatSpeed(tokensPerSecond)} tok/s"
                    }
                    Text(
                        speed,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (speedLive) PetLlamaColors.Amber else PetLlamaColors.TextPrimary,
                        maxLines = 1
                    )
                    promptPerSecond?.let {
                        Text(
                            "prompt ${formatSpeed(it)} t/s",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = PetLlamaColors.TextMuted,
                            maxLines = 1
                        )
                    }
                }
                Column(modifier = Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        fill != null -> {
                            val color = when (fill.band) {
                                ContextBand.GREEN -> PetLlamaColors.Emerald
                                ContextBand.YELLOW -> PetLlamaColors.Amber
                                ContextBand.RED -> PetLlamaColors.Coral
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${formatTokenCount(fill.used)} / ${formatTokenCount(fill.context)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = PetLlamaColors.TextSecondary,
                                    maxLines = 1
                                )
                                Text(
                                    "${(fill.fraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    maxLines = 1
                                )
                            }
                            LinearProgressIndicator(
                                progress = { fill.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = color,
                                trackColor = PetLlamaColors.SurfaceElevated
                            )
                        }
                        usedTokens != null -> Text(
                            "${formatTokenCount(usedTokens)} tokens used",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = PetLlamaColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
            HorizontalDivider(color = PetLlamaColors.BorderSubtle)
        }
    }
}

@Composable
private fun PrettyBlock(raw: String, showAll: Boolean) {
    val pretty = remember(raw) { prettyPrint(raw) }
    val shown = if (showAll || pretty.length <= 900) pretty else pretty.take(900) + "…"
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PetLlamaColors.CodeBackground,
        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            shown,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = PetLlamaColors.TextPrimary,
            modifier = Modifier.padding(10.dp)
        )
    }
}

private fun prettyPrint(raw: String): String {
    val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return raw
    return GsonBuilder().setPrettyPrinting().create().toJson(parsed)
}

@Composable
fun MessageBubble(
    role: String,
    content: String,
    reasoning: String?,
    isStreaming: Boolean,
    markwon: Markwon? = null,
    onLongClick: () -> Unit
) {
    val isUser = role == "user"
    val bg = if (isUser) PetLlamaColors.UserBubble else PetLlamaColors.AssistantBubble
    val border = if (isUser) PetLlamaColors.UserBubbleBorder else PetLlamaColors.AssistantBubbleBorder
    val fg = PetLlamaColors.TextPrimary
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }
    val split = if (isUser) ReasonedText("", content, false) else extractThinkTags(content)
    val thought = listOfNotNull(reasoning?.takeIf { it.isNotBlank() }, split.reasoning.takeIf { it.isNotEmpty() })
        .joinToString("\n\n")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) 36.dp else 0.dp,
                end = if (isUser) 0.dp else 16.dp
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(bubbleShape)
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (thought.isNotEmpty() || split.stillThinking) {
                    ThinkingBlock(
                        text = thought.ifBlank { "…" },
                        active = split.stillThinking || (isStreaming && split.answer.isEmpty())
                    )
                }
                val answer = if (isUser) content else split.answer
                if (answer.isNotEmpty()) {
                    if (isUser || isStreaming) {
                        Text(answer, style = MaterialTheme.typography.bodyLarge, color = fg)
                    } else {
                        MarkdownAnswer(answer, fg, markwon)
                    }
                } else if (isStreaming && thought.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = PetLlamaColors.Amber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "Generating…",
                            style = MaterialTheme.typography.bodySmall,
                            color = PetLlamaColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PetLlamaColors.CodeBackground,
        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = !open }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    CircularProgressIndicator(
                        color = PetLlamaColors.Amber,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp).size(13.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Psychology,
                        contentDescription = null,
                        tint = PetLlamaColors.TextMuted,
                        modifier = Modifier.padding(end = 6.dp).size(16.dp)
                    )
                }
                Text(
                    if (active) "Thinking…" else "Thought process",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) PetLlamaColors.Amber else PetLlamaColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (open) "Hide thoughts" else "Show thoughts",
                    tint = PetLlamaColors.TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = open) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = PetLlamaColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MarkdownAnswer(markdown: String, color: Color, sharedMarkwon: Markwon? = null) {
    val blocks = remember(markdown) { splitFencedCode(markdown) }
    val context = LocalContext.current
    val markwon = sharedMarkwon ?: remember(context) {
        Markwon.builder(context).usePlugin(TablePlugin.create(context)).build()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Prose -> AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextColor(color.toArgb())
                            setLinkTextColor(PetLlamaColors.Sky.toArgb())
                            textSize = 15.5f
                            setLineSpacing(4f, 1.08f)
                        }
                    },
                    update = { view ->
                        view.setTextColor(color.toArgb())
                        markwon.setMarkdown(view, block.markdown)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PetLlamaColors.CodeBackground,
        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PetLlamaColors.CodeHeader)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = PetLlamaColors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = PetLlamaColors.TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            copyToClipboard(context, code)
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (copied) PetLlamaColors.Emerald else PetLlamaColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) PetLlamaColors.Emerald else PetLlamaColors.TextSecondary
                    )
                }
            }
            HorizontalDivider(color = PetLlamaColors.BorderSubtle)
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    color = PetLlamaColors.TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PetLlama", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamsSheet(
    temperature: Float,
    topP: Float,
    maxTokens: Int?,
    maxToolCalls: Int,
    systemPrompt: String,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onMaxTokens: (Int?) -> Unit,
    onMaxToolCalls: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var temp by remember { mutableStateOf(temperature) }
    var top by remember { mutableStateOf(topP) }
    var maxTok by remember { mutableStateOf(maxTokens?.toString().orEmpty()) }
    var maxTools by remember { mutableStateOf(maxToolCalls.toString()) }
    var sys by remember { mutableStateOf(systemPrompt) }

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Chat parameters", style = MaterialTheme.typography.titleLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Temperature", style = MaterialTheme.typography.bodyMedium, color = PetLlamaColors.TextSecondary)
                ValueBadge("%.2f".format(temp))
            }
            Slider(
                value = temp,
                onValueChange = { temp = it },
                valueRange = 0f..2f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = PetLlamaColors.Amber,
                    activeTrackColor = PetLlamaColors.Amber,
                    inactiveTrackColor = PetLlamaColors.SurfaceElevated
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Top-P", style = MaterialTheme.typography.bodyMedium, color = PetLlamaColors.TextSecondary)
                ValueBadge("%.2f".format(top))
            }
            Slider(
                value = top,
                onValueChange = { top = it },
                valueRange = 0f..1f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = PetLlamaColors.Amber,
                    activeTrackColor = PetLlamaColors.Amber,
                    inactiveTrackColor = PetLlamaColors.SurfaceElevated
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = maxTok,
                    onValueChange = { raw -> maxTok = raw.filter { it.isDigit() } },
                    label = { Text("Max tokens") },
                    placeholder = { Text("Auto", color = PetLlamaColors.TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = petLlamaFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxTools,
                    onValueChange = { raw -> maxTools = raw.filter { it.isDigit() } },
                    label = { Text("Max tool calls") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = petLlamaFieldColors(),
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = sys,
                onValueChange = { sys = it },
                label = { Text("System prompt") },
                placeholder = { Text("Optional instructions for the model…", color = PetLlamaColors.TextMuted) },
                shape = RoundedCornerShape(14.dp),
                colors = petLlamaFieldColors(),
                modifier = Modifier.fillMaxWidth().height(124.dp)
            )

            Button(
                onClick = {
                    onTemperature(temp)
                    onTopP(top)
                    onMaxTokens(maxTok.toIntOrNull()?.takeIf { it > 0 })
                    onMaxToolCalls(maxTools.toIntOrNull()?.takeIf { it > 0 } ?: maxToolCalls)
                    onSystemPrompt(sys)
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PetLlamaColors.Amber,
                    contentColor = PetLlamaColors.AmberOn
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Apply", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ValueBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = PetLlamaColors.SurfaceElevated,
        border = BorderStroke(1.dp, PetLlamaColors.BorderSubtle)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = PetLlamaColors.Amber,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PetLlamaColors.Surface,
        contentColor = PetLlamaColors.TextPrimary
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onNew,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PetLlamaColors.Amber,
                    contentColor = PetLlamaColors.AmberOn
                ),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text("New chat", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            if (conversations.isEmpty()) {
                Text(
                    "No saved chats yet.",
                    color = PetLlamaColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                conversations.forEach { conv ->
                    val isActive = conv.id == activeId
                    Surface(
                        onClick = { onOpen(conv.id) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isActive) PetLlamaColors.SurfaceElevated else PetLlamaColors.SurfaceCard,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) PetLlamaColors.Amber.copy(alpha = 0.6f) else PetLlamaColors.BorderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(PetLlamaColors.Amber)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    conv.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = PetLlamaColors.TextPrimary
                                )
                                if (conv.model.isNotBlank()) {
                                    Text(
                                        conv.model,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PetLlamaColors.TextMuted
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(conv.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete conversation",
                                    tint = PetLlamaColors.TextMuted,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun petLlamaFieldColors(containerColor: Color = PetLlamaColors.SurfaceCard) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PetLlamaColors.Amber,
        unfocusedBorderColor = PetLlamaColors.BorderSubtle,
        focusedContainerColor = containerColor,
        unfocusedContainerColor = containerColor,
        cursorColor = PetLlamaColors.Amber,
        focusedLabelColor = PetLlamaColors.Amber,
        unfocusedLabelColor = PetLlamaColors.TextSecondary,
        focusedTextColor = PetLlamaColors.TextPrimary,
        unfocusedTextColor = PetLlamaColors.TextPrimary
    )
