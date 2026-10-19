package com.aipaca.android.ui.bench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.BenchResultEntity
import com.aipaca.android.data.db.entity.ModelEntity
import com.aipaca.android.data.device.DeviceInfo
import com.aipaca.android.engine.BenchBackend
import com.aipaca.android.ui.common.buildBenchShareText
import com.aipaca.android.ui.common.shareText
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val LOG_MAX_HEIGHT_DP = 220

@Composable
fun BenchScreen(
    modifier: Modifier = Modifier,
    viewModel: BenchViewModel = viewModel(factory = BenchViewModel.factory()),
) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val selectedModelId by viewModel.selectedModelId.collectAsStateWithLifecycle()
    val backend by viewModel.backend.collectAsStateWithLifecycle()
    val threadsText by viewModel.threadsText.collectAsStateWithLifecycle()
    val cpuMaskText by viewModel.cpuMaskText.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val npuAvailable by viewModel.npuAvailable.collectAsStateWithLifecycle()

    val running = status is BenchViewModel.Status.Running
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
    val context = LocalContext.current
    val deviceKey = remember(context) { DeviceInfo.current(context).deviceKey }
    val shareSubject = stringResource(R.string.share_bench_subject)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.bench_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))
        ModelSelector(
            models = models,
            selected = selectedModel,
            enabled = !running,
            onSelect = viewModel::selectModel,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.bench_backend_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackendButton(
                label = stringResource(R.string.bench_backend_cpu),
                selected = backend == BenchBackend.CPU,
                enabled = !running,
                onClick = { viewModel.selectBackend(BenchBackend.CPU) },
            )
            Spacer(Modifier.size(8.dp))
            BackendButton(
                label = stringResource(R.string.bench_backend_opencl),
                selected = backend == BenchBackend.OPENCL,
                enabled = !running,
                onClick = { viewModel.selectBackend(BenchBackend.OPENCL) },
            )
            Spacer(Modifier.size(8.dp))
            BackendButton(
                label = stringResource(R.string.bench_backend_npu),
                selected = backend == BenchBackend.HEXAGON,
                enabled = !running && npuAvailable,
                onClick = { viewModel.selectBackend(BenchBackend.HEXAGON) },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (npuAvailable) {
                stringResource(R.string.bench_npu_experimental)
            } else {
                stringResource(R.string.bench_npu_disabled)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedTextField(
                value = threadsText,
                onValueChange = viewModel::updateThreads,
                label = { Text(stringResource(R.string.bench_threads_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = cpuMaskText,
                onValueChange = viewModel::updateCpuMask,
                label = { Text(stringResource(R.string.bench_cpu_mask_label)) },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (running) {
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bench_stop))
            }
        } else {
            Button(
                onClick = viewModel::run,
                enabled = models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.bench_run))
            }
        }
        Spacer(Modifier.height(12.dp))
        StatusRow(status)
        result?.let { runResult ->
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.bench_result_pp,
                            formatTps(runResult.ppTps, runResult.ppStddev),
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.bench_result_tg,
                            formatTps(runResult.tgTps, runResult.tgStddev),
                        ),
                    )
                    TextButton(
                        onClick = {
                            context.shareText(
                                shareSubject,
                                buildBenchShareText(
                                    modelName = selectedModel?.fileName ?: "",
                                    backend = backend.tableName,
                                    pp = runResult.ppTps ?: 0.0,
                                    tg = runResult.tgTps ?: 0.0,
                                    threads = threadsText.toIntOrNull() ?: 0,
                                    cpuMask = cpuMaskText.trim().ifEmpty { null },
                                    deviceKey = deviceKey,
                                ),
                            )
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.share_bench_action))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bench_log_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            if (log.isEmpty()) {
                Text(
                    text = stringResource(R.string.bench_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                Text(
                    text = log.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = LOG_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bench_history_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.bench_history_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            history.forEach { entity ->
                HistoryItem(
                    entity = entity,
                    onShare = {
                        val config = entity.shareConfig()
                        val modelName = models.firstOrNull { it.id == entity.modelId }?.fileName
                            ?: config.modelPath.substringAfterLast('/')
                        context.shareText(
                            shareSubject,
                            buildBenchShareText(
                                modelName = modelName,
                                backend = entity.backend,
                                pp = entity.pp,
                                tg = entity.tg,
                                threads = config.threads,
                                cpuMask = config.cpuMask,
                                deviceKey = entity.deviceKey,
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ModelSelector(
    models: List<ModelEntity>,
    selected: ModelEntity?,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = stringResource(R.string.bench_model_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selected?.fileName ?: stringResource(R.string.bench_no_models),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.fileName) },
                        onClick = {
                            expanded = false
                            onSelect(model.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackendButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(label)
        }
    }
}

@Composable
private fun StatusRow(status: BenchViewModel.Status) {
    when (status) {
        BenchViewModel.Status.Idle -> Unit
        BenchViewModel.Status.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.bench_running))
        }
        BenchViewModel.Status.Done -> Text(stringResource(R.string.bench_done))
        is BenchViewModel.Status.Error -> Text(
            text = status.message,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun HistoryItem(entity: BenchResultEntity, onShare: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(
                    R.string.bench_history_item,
                    entity.backend,
                    formatValue(entity.pp),
                    formatValue(entity.tg),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(entity.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onShare,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.share_bench_action))
            }
        }
    }
}

@Serializable
private data class BenchShareConfig(
    val modelPath: String = "",
    val threads: Int = 0,
    val cpuMask: String? = null,
)

private val benchShareConfigJson = Json { ignoreUnknownKeys = true }

private fun BenchResultEntity.shareConfig(): BenchShareConfig =
    runCatching { benchShareConfigJson.decodeFromString<BenchShareConfig>(configJson) }
        .getOrDefault(BenchShareConfig())

private fun formatTps(tps: Double?, stddev: Double?): String {
    val value = tps?.let { formatValue(it) } ?: "—"
    val deviation = stddev?.let { formatValue(it) }
    return if (deviation == null) value else "$value ± $deviation"
}

private fun formatValue(value: Double): String = String.format(Locale.US, "%.2f", value)
