@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimgrok.petllama.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
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
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.server?.name ?: "Chat", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.padding(horizontal = 8.dp))
                        Text(
                            state.selectedModel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
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
                    IconButton(onClick = { vm.setToolsEnabled(!state.toolsEnabled) }) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = if (state.toolsEnabled) "Tools on" else "Tools off",
                            tint = if (state.toolsEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            ChatStatusStrip(
                tokensPerSecond = state.tokensPerSecond,
                promptPerSecond = state.promptPerSecond,
                speedLive = state.speedLive,
                usedTokens = state.usedTokens,
                contextSize = state.contextSize
            )
            // model picker row
            if (state.models.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Model:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Box {
                        TextButton(onClick = { showModelMenu = true }) {
                            Text(state.selectedModel.ifEmpty { "\u2014" }, color = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            state.models.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.id) },
                                    onClick = { vm.selectModel(m.id); showModelMenu = false }
                                )
                            }
                        }
                    }
                }
            }

            // error banner
            state.error?.let { err ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, maxLines = 6)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.clearError() }) { Text("dismiss") }
                }
            }
            state.toolNotice?.let { notice ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 4)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.clearToolNotice() }) { Text("dismiss") }
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
                val lastAssistantId = state.messages.lastOrNull { it.role == "assistant" && !it.isStreaming }?.id
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
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
                                onDismissRequest = { menuMessageId = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy") },
                                    onClick = {
                                        copyToClipboard(clipboardContext, msg.content)
                                        menuMessageId = null
                                    }
                                )
                                if (msg.role == "user" && !state.streaming) {
                                    DropdownMenuItem(
                                        text = { Text("Edit and resend") },
                                        onClick = { editing = msg; menuMessageId = null }
                                    )
                                }
                                if (msg.role == "assistant" && msg.id == lastAssistantId && !state.streaming) {
                                    DropdownMenuItem(
                                        text = { Text("Regenerate") },
                                        onClick = { vm.regenerate(); menuMessageId = null }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Continue") },
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
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editAndResend(msg.id, draft)
                    editing = null
                }) { Text("Resend") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { open = !open }, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                running -> CircularProgressIndicator(
                    modifier = Modifier.padding(end = 6.dp).size(14.dp),
                    strokeWidth = 2.dp
                )
                failed -> Icon(
                    Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 6.dp).size(16.dp)
                )
                else -> Icon(
                    Icons.Default.Check,
                    contentDescription = "Done",
                    tint = Color(0xFF3DDC84),
                    modifier = Modifier.padding(end = 6.dp).size(16.dp)
                )
            }
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (open) {
            if (!arguments.isNullOrBlank()) {
                Text("Arguments", style = MaterialTheme.typography.labelMedium)
                PrettyBlock(arguments, showAll)
            }
            Text("Result", style = MaterialTheme.typography.labelMedium)
            PrettyBlock(result, showAll)
            if (!showAll && (arguments.orEmpty().length + result.length) > 900) {
                TextButton(onClick = { showAll = true }) { Text("Show more") }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.42f)) {
            val speed = when {
                tokensPerSecond == null -> "— tok/s"
                speedLive -> "${formatSpeed(tokensPerSecond)} tok/s"
                else -> "${formatSpeed(tokensPerSecond)} tok/s"
            }
            Text(speed, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            promptPerSecond?.let {
                Text(
                    "prompt ${formatSpeed(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Column(modifier = Modifier.weight(0.58f)) {
            when {
                fill != null -> {
                    val color = when (fill.band) {
                        ContextBand.GREEN -> Color(0xFF3DDC84)
                        ContextBand.YELLOW -> Color(0xFFFFC107)
                        ContextBand.RED -> MaterialTheme.colorScheme.error
                    }
                    LinearProgressIndicator(
                        progress = fill.fraction,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        "${formatTokenCount(fill.used)} / ${formatTokenCount(fill.context)}  ${(fill.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        maxLines = 1
                    )
                }
                usedTokens != null -> Text(
                    "${formatTokenCount(usedTokens)} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PrettyBlock(raw: String, showAll: Boolean) {
    val pretty = remember(raw) { prettyPrint(raw) }
    val shown = if (showAll || pretty.length <= 900) pretty else pretty.take(900) + "…"
    Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val split = if (isUser) ReasonedText("", content, false) else extractThinkTags(content)
    val thought = listOfNotNull(reasoning?.takeIf { it.isNotBlank() }, split.reasoning.takeIf { it.isNotEmpty() })
        .joinToString("\n\n")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .weight(1f, fill = false)
                .background(bg, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (thought.isNotEmpty() || split.stillThinking) {
                ThinkingBlock(thought.ifBlank { "…" }, split.stillThinking || isStreaming && split.answer.isEmpty())
            }
            val answer = if (isUser) content else split.answer
            if (answer.isNotEmpty()) {
                if (isUser || isStreaming) {
                    Text(answer, style = MaterialTheme.typography.bodyLarge, color = fg)
                } else {
                    MarkdownAnswer(answer, fg, markwon)
                }
            } else if (isStreaming && thought.isEmpty()) {
                Text("\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(bottom = 6.dp)) {
        Text(
            if (active) "Thinking…" else "Thought",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (open) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Prose -> AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextColor(color.toArgb())
                            textSize = 16f
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFBBBBBB),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { copyToClipboard(context, code) }) { Text("Copy") }
        }
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(code, fontFamily = FontFamily.Monospace, color = Color(0xFFECECEC), style = MaterialTheme.typography.bodySmall)
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
    maxTokens: Int,
    maxToolCalls: Int,
    systemPrompt: String,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onMaxToolCalls: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var temp by remember { mutableStateOf(temperature) }
    var top by remember { mutableStateOf(topP) }
    var maxTok by remember { mutableStateOf(maxTokens.toString()) }
    var maxTools by remember { mutableStateOf(maxToolCalls.toString()) }
    var sys by remember { mutableStateOf(systemPrompt) }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Chat parameters", style = MaterialTheme.typography.titleLarge)

            Text("Temperature: ${"%.2f".format(temp)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..2f, steps = 19)

            Text("Top-P: ${"%.2f".format(top)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = top, onValueChange = { top = it }, valueRange = 0f..1f, steps = 9)

            Text("Max tokens", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = maxTok,
                onValueChange = { raw -> maxTok = raw.filter { it.isDigit() } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Max tool calls (default 25)", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = maxTools,
                onValueChange = { raw -> maxTools = raw.filter { it.isDigit() } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("System prompt", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = sys,
                onValueChange = { sys = it },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            androidx.compose.material3.Button(onClick = {
                onTemperature(temp)
                onTopP(top)
                onMaxTokens(maxTok.toIntOrNull()?.takeIf { it > 0 } ?: maxTokens)
                onMaxToolCalls(maxTools.toIntOrNull()?.takeIf { it > 0 } ?: maxToolCalls)
                onSystemPrompt(sys)
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
