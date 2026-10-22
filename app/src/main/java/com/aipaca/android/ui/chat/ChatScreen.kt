package com.aipaca.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.ChatMode
import com.aipaca.android.engine.ThermalLevel
import com.aipaca.android.engine.agent.AgentToolCall
import com.aipaca.android.engine.agent.AgentToolPayload
import com.aipaca.android.engine.agent.AgentToolStatus
import com.aipaca.android.engine.agent.decodeToolResult
import com.aipaca.android.engine.chat.ChatTimings
import com.aipaca.android.engine.thermalLevelFor
import com.aipaca.android.ui.common.ThermalWatcher
import com.aipaca.android.ui.common.thermalMessageRes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val STREAMING_CURSOR = "▍"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onChatDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val streamingToolCalls by viewModel.streamingToolCalls.collectAsStateWithLifecycle()
    val streamingMessageId by viewModel.streamingMessageId.collectAsStateWithLifecycle()
    val lastTimings by viewModel.lastTimings.collectAsStateWithLifecycle()
    val chatTitle by viewModel.title.collectAsStateWithLifecycle()
    val modelName by viewModel.modelName.collectAsStateWithLifecycle()
    val params by viewModel.params.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val agentStep by viewModel.agentStep.collectAsStateWithLifecycle()
    val compacting by viewModel.compacting.collectAsStateWithLifecycle()

    val thermalWatcher = remember(context) { ThermalWatcher(context) }
    val thermalStatus by thermalWatcher.status
        .collectAsStateWithLifecycle(initialValue = THERMAL_STATUS_NONE)
    val thermalLevel = thermalLevelFor(thermalStatus)

    var draft by rememberSaveable { mutableStateOf("") }
    var overflowOpen by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var followTail by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val tailThresholdPx = with(LocalDensity.current) { CHAT_TAIL_THRESHOLD.roundToPx() }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                followTail = false
            }
        }
    }

    LaunchedEffect(listState, tailThresholdPx) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) return@collect
                val atTail = isAtChatTail(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    thresholdPx = tailThresholdPx,
                )
                followTail = atTail
                if (atTail && listState.layoutInfo.totalItemsCount > 0) {
                    listState.animateScrollToItem(0)
                }
            }
    }

    val streaming = streamingText != null
    val ownedToolCallIds = remember(messages) {
        messages
            .filter { it.role == ChatViewModel.ROLE_ASSISTANT }
            .flatMap { it.toolCalls }
            .mapTo(mutableSetOf()) { it.id }
    }
    val orphanToolMessageIds = remember(messages, ownedToolCallIds) {
        messages
            .filter {
                it.role == ChatViewModel.ROLE_TOOL &&
                    (it.toolCallId == null || it.toolCallId !in ownedToolCallIds)
            }
            .mapTo(mutableSetOf()) { it.id }
    }
    val visibleMessages = remember(messages, streamingMessageId, orphanToolMessageIds) {
        messages.filter {
            it.id != streamingMessageId &&
                (it.role != ChatViewModel.ROLE_TOOL || orphanToolMessageIds.contains(it.id))
        }
    }
    val lastAssistantId = messages.lastOrNull { it.role == ChatViewModel.ROLE_ASSISTANT }?.id
    val canRegenerate = !streaming && messages.any { it.role == ChatViewModel.ROLE_ASSISTANT }
    val copiedMessage = stringResource(R.string.chat_copied)
    val toolResults = remember(messages) {
        messages
            .filter { it.role == ChatViewModel.ROLE_TOOL }
            .mapNotNull { m ->
                m.toolCallId?.let { id -> id to decodeToolResult(m.content) }
            }
            .toMap()
    }

    LaunchedEffect(visibleMessages.size, streaming) {
        if (followTail && (visibleMessages.isNotEmpty() || streaming)) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = modelName ?: chatTitle ?: stringResource(R.string.chat_title_new),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (mode == ChatMode.AGENT) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ) {
                                    Text(
                                        text = stringResource(R.string.chat_agent_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        agentStep?.let { step ->
                            Text(
                                text = stringResource(
                                    R.string.agent_step,
                                    step.step,
                                    step.maxIterations,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.drawer_open),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.chat_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            if (mode != ChatMode.AGENT) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_regenerate)) },
                                    enabled = canRegenerate,
                                    onClick = {
                                        overflowOpen = false
                                        if (viewModel.regenerate()) {
                                            followTail = true
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_params)) },
                                onClick = {
                                    overflowOpen = false
                                    paramsOpen = true
                                },
                            )
                            if (mode == ChatMode.AGENT) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (compacting) R.string.agent_compacting
                                                else R.string.agent_compact,
                                            ),
                                        )
                                    },
                                    enabled = !compacting,
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.compact()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_rename)) },
                                onClick = {
                                    overflowOpen = false
                                    renameOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_delete)) },
                                onClick = {
                                    overflowOpen = false
                                    deleteOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ThermalBanner(thermalLevel)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                reverseLayout = true,
            ) {
                if (streaming) {
                    item(key = STREAMING_ITEM_KEY) {
                        StreamingBubble(
                            text = streamingText.orEmpty(),
                            reasoning = streamingReasoning.orEmpty(),
                            toolCalls = streamingToolCalls,
                            onCopyToolResult = { text ->
                                clipboard.setPrimaryClip(ClipData.newPlainText("tool", text))
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                        )
                    }
                }
                items(visibleMessages.asReversed(), key = { it.id }) { message ->
                    if (message.role == ChatViewModel.ROLE_TOOL) {
                        ToolCallCard(
                            messageId = message.id,
                            call = AgentToolCall(
                                id = message.toolCallId ?: "",
                                name = message.toolCallId ?: "tool",
                                argsJson = "",
                            ),
                            result = decodeToolResult(message.content),
                            onCopy = { text ->
                                clipboard.setPrimaryClip(ClipData.newPlainText("tool", text))
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                        )
                    } else if (message.role == ChatViewModel.ROLE_SYSTEM) {
                        CompactionDivider(message)
                    } else {
                        MessageBubble(
                            message = message,
                            timings = if (message.id == lastAssistantId) lastTimings else null,
                            toolResults = toolResults,
                            onCopy = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(message.role, message.content),
                                )
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                            onCopyToolResult = { text ->
                                clipboard.setPrimaryClip(ClipData.newPlainText("tool", text))
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            },
                        )
                    }
                }
            }
            InputRow(
                draft = draft,
                onDraftChange = { draft = it },
                streaming = streaming,
                onSend = {
                    if (viewModel.send(draft)) {
                        draft = ""
                        followTail = true
                    }
                },
                onStop = viewModel::stop,
            )
        }
    }

    if (paramsOpen) {
        ModalBottomSheet(
            onDismissRequest = { paramsOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            ParamsContent(
                params = params,
                systemPrompt = systemPrompt,
                isAgent = mode == ChatMode.AGENT,
                onTemperature = viewModel::updateTemperature,
                onTopP = viewModel::updateTopP,
                onTopK = viewModel::updateTopK,
                onMinP = viewModel::updateMinP,
                onRepeatPenalty = viewModel::updateRepeatPenalty,
                onReasoningBudget = viewModel::updateReasoningBudget,
                onMaxIterations = viewModel::updateMaxIterations,
                onSystemPrompt = viewModel::updateSystemPrompt,
                onReset = viewModel::resetParams,
            )
        }
    }

    if (renameOpen) {
        RenameChatDialog(
            initialTitle = chatTitle.orEmpty(),
            onConfirm = { text ->
                renameOpen = false
                viewModel.renameChat(text)
            },
            onDismiss = { renameOpen = false },
        )
    }

    if (deleteOpen) {
        DeleteChatDialog(
            onConfirm = {
                deleteOpen = false
                viewModel.deleteChat()
                onChatDeleted()
            },
            onDismiss = { deleteOpen = false },
        )
    }
}

@Composable
private fun ThermalBanner(level: ThermalLevel) {
    val messageRes = thermalMessageRes(level) ?: return
    val critical = level == ThermalLevel.CRITICAL
    Surface(
        color = if (critical) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        contentColor = if (critical) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun InputRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            maxLines = 5,
        )
        Spacer(Modifier.width(8.dp))
        if (streaming) {
            FilledIconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_stop),
                )
            }
        } else {
            FilledIconButton(onClick = onSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                )
            }
        }
    }
}

@Composable
private fun CompactionDivider(message: ChatViewModel.MessageUi) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.agent_compaction_divider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            HorizontalDivider(Modifier.weight(1f))
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatViewModel.MessageUi,
    timings: ChatTimings?,
    toolResults: Map<String, AgentToolPayload?>,
    onCopy: () -> Unit,
    onCopyToolResult: (String) -> Unit,
) {
    val isUser = message.role == ChatViewModel.ROLE_USER
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = BUBBLE_MAX_WIDTH)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column {
                        if (!isUser && !message.reasoning.isNullOrEmpty()) {
                            ReasoningSpoiler(
                                messageId = message.id,
                                reasoning = message.reasoning,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        SelectionContainer {
                            if (isUser) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                MarkdownText(
                                    markdown = message.content,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (!isUser) {
                val stats = messageStats(message, timings)
                if (stats != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stats,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                if (message.toolCalls.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .widthIn(max = BUBBLE_MAX_WIDTH)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        message.toolCalls.forEach { call ->
                            ToolCallCard(
                                messageId = message.id,
                                call = call,
                                result = toolResults[call.id],
                                onCopy = onCopyToolResult,
                            )
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy)) },
                onClick = {
                    menuOpen = false
                    onCopy()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallCard(
    messageId: Long,
    call: AgentToolCall,
    result: AgentToolPayload?,
    onCopy: (String) -> Unit,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(messageId, call.id, initiallyExpanded) {
        mutableStateOf(initiallyExpanded)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { onCopy(result?.output ?: call.argsJson) },
            ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (result == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (result.status == AgentToolStatus.OK) {
                            Icons.Filled.Check
                        } else {
                            Icons.Filled.Close
                        },
                        contentDescription = null,
                        tint = when (result.status) {
                            AgentToolStatus.OK -> MaterialTheme.colorScheme.tertiary
                            AgentToolStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        result == null -> stringResource(R.string.agent_tool_running)
                        result.status == AgentToolStatus.OK -> stringResource(R.string.agent_tool_done)
                        result.status == AgentToolStatus.CANCELLED -> stringResource(R.string.agent_tool_cancelled)
                        else -> stringResource(R.string.agent_tool_error)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.agent_tool_args),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = call.argsJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.agent_tool_result),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = result?.output ?: stringResource(R.string.agent_tool_no_result),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else if (call.argsJson.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = call.argsJson,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReasoningSpoiler(messageId: Long, reasoning: String) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.chat_reasoning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    reasoning: String,
    toolCalls: List<ChatViewModel.StreamingToolCallUi>,
    onCopyToolResult: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.align(Alignment.CenterStart).widthIn(max = BUBBLE_MAX_WIDTH),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (reasoning.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = reasoning + if (text.isEmpty()) STREAMING_CURSOR else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                    }
                    if (text.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (text.isNotEmpty()) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = text,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursor = STREAMING_CURSOR,
                        )
                    }
                } else if (reasoning.isEmpty()) {
                    Text(
                        text = STREAMING_CURSOR,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (toolCalls.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        toolCalls.forEach { call ->
                            ToolCallCard(
                                messageId = -1L - call.index,
                                call = AgentToolCall(
                                    id = "stream-${call.index}",
                                    name = call.name.ifBlank { "tool" },
                                    argsJson = call.argsJson,
                                ),
                                result = null,
                                onCopy = onCopyToolResult,
                                initiallyExpanded = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun messageStats(message: ChatViewModel.MessageUi, timings: ChatTimings?): String? {
    val tps = message.tps ?: timings?.predictedPerSecond
    val tokensIn = message.tokensIn ?: timings?.promptN
    val tokensOut = message.tokensOut ?: timings?.predictedN
    val tpsText = tps?.let { stringResource(R.string.chat_tps, formatParam(it)) }
    val tokensText = if (tokensIn != null && tokensOut != null) {
        stringResource(R.string.chat_tokens, tokensIn, tokensOut)
    } else {
        null
    }
    return listOfNotNull(tpsText, tokensText).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun ParamsContent(
    params: ChatViewModel.ChatParamsUi,
    systemPrompt: String,
    isAgent: Boolean,
    onTemperature: (Double) -> Unit,
    onTopP: (Double) -> Unit,
    onTopK: (Int) -> Unit,
    onMinP: (Double) -> Unit,
    onRepeatPenalty: (Double) -> Unit,
    onReasoningBudget: (Int) -> Unit,
    onMaxIterations: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            text = stringResource(R.string.chat_params),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        ParamSlider(
            label = stringResource(R.string.chat_param_temperature),
            valueText = formatParam(params.temperature),
            value = params.temperature.toFloat(),
            valueRange = 0f..2f,
            steps = PARAM_STEPS_0_2,
            onValueChange = { onTemperature(roundParam(it)) },
        )
        ParamSlider(
            label = stringResource(R.string.chat_param_top_p),
            valueText = formatParam(params.topP),
            value = params.topP.toFloat(),
            valueRange = 0f..1f,
            steps = PARAM_STEPS_0_1,
            onValueChange = { onTopP(roundParam(it)) },
        )
        ParamSlider(
            label = stringResource(R.string.chat_param_top_k),
            valueText = params.topK.toString(),
            value = params.topK.toFloat(),
            valueRange = 1f..100f,
            steps = PARAM_STEPS_TOP_K,
            onValueChange = { onTopK(it.roundToInt()) },
        )
        ParamSlider(
            label = stringResource(R.string.chat_param_min_p),
            valueText = formatParam(params.minP),
            value = params.minP.toFloat(),
            valueRange = 0f..1f,
            steps = PARAM_STEPS_0_1,
            onValueChange = { onMinP(roundParam(it)) },
        )
        ParamSlider(
            label = stringResource(R.string.chat_param_repeat_penalty),
            valueText = formatParam(params.repeatPenalty),
            value = params.repeatPenalty.toFloat(),
            valueRange = 0.5f..2f,
            steps = PARAM_STEPS_REPEAT_PENALTY,
            onValueChange = { onRepeatPenalty(roundParam(it)) },
        )
        ParamSlider(
            label = stringResource(R.string.chat_param_reasoning_budget),
            valueText = if (params.reasoningBudget == 0) {
                stringResource(R.string.chat_reasoning_off)
            } else {
                stringResource(R.string.chat_reasoning_tokens, params.reasoningBudget)
            },
            value = params.reasoningBudget.toFloat(),
            valueRange = 0f..2048f,
            steps = REASONING_BUDGET_STEPS,
            onValueChange = { onReasoningBudget((it / 128f).roundToInt() * 128) },
        )
        if (isAgent) {
            ParamSlider(
                label = stringResource(R.string.agent_iterations),
                valueText = params.maxIterations.toString(),
                value = params.maxIterations.toFloat(),
                valueRange = 1f..50f,
                steps = 48,
                onValueChange = { onMaxIterations(it.roundToInt()) },
            )
        }
        if (!isAgent) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPrompt,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.chat_system_prompt)) },
                minLines = 3,
                maxLines = 6,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.chat_reset))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ParamSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

private fun roundParam(value: Float): Double = (value * 100.0).roundToInt() / 100.0

private fun formatParam(value: Double): String = "%.2f".format(value)

private val BUBBLE_MAX_WIDTH = 320.dp
private val CHAT_TAIL_THRESHOLD = 32.dp
private const val STREAMING_ITEM_KEY = "streaming"
private const val THERMAL_STATUS_NONE = 0
private const val PARAM_STEPS_0_1 = 19
private const val PARAM_STEPS_0_2 = 19
private const val PARAM_STEPS_TOP_K = 98
private const val PARAM_STEPS_REPEAT_PENALTY = 29
private const val REASONING_BUDGET_STEPS = 15
