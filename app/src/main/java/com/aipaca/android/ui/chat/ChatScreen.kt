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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aipaca.android.R
import com.aipaca.android.engine.ThermalLevel
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
    val streamingMessageId by viewModel.streamingMessageId.collectAsStateWithLifecycle()
    val lastTimings by viewModel.lastTimings.collectAsStateWithLifecycle()
    val chatTitle by viewModel.title.collectAsStateWithLifecycle()
    val modelName by viewModel.modelName.collectAsStateWithLifecycle()
    val params by viewModel.params.collectAsStateWithLifecycle()
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()

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
    val visibleMessages = remember(messages, streamingMessageId) {
        messages.filter { it.id != streamingMessageId }
    }
    val lastAssistantId = messages.lastOrNull { it.role == ChatViewModel.ROLE_ASSISTANT }?.id
    val canRegenerate = !streaming && messages.any { it.role == ChatViewModel.ROLE_ASSISTANT }
    val copiedMessage = stringResource(R.string.chat_copied)

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
                    Text(
                        text = modelName ?: chatTitle ?: stringResource(R.string.chat_title_new),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_params)) },
                                onClick = {
                                    overflowOpen = false
                                    paramsOpen = true
                                },
                            )
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
                        )
                    }
                }
                items(visibleMessages.asReversed(), key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        timings = if (message.id == lastAssistantId) lastTimings else null,
                        onCopy = {
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(message.role, message.content),
                            )
                            scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                        },
                    )
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
                onTemperature = viewModel::updateTemperature,
                onTopP = viewModel::updateTopP,
                onTopK = viewModel::updateTopK,
                onMinP = viewModel::updateMinP,
                onRepeatPenalty = viewModel::updateRepeatPenalty,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatViewModel.MessageUi,
    timings: ChatTimings?,
    onCopy: () -> Unit,
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
private fun StreamingBubble(text: String, reasoning: String) {
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
    onTemperature: (Double) -> Unit,
    onTopP: (Double) -> Unit,
    onTopK: (Int) -> Unit,
    onMinP: (Double) -> Unit,
    onRepeatPenalty: (Double) -> Unit,
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
