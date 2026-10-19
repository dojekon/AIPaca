package com.aipaca.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.engine.EngineState
import com.aipaca.android.ui.server.ServerViewModel
import com.aipaca.android.ui.server.formatUptime
import com.aipaca.android.ui.server.lanServerUrl
import com.aipaca.android.ui.server.localServerUrl
import com.aipaca.android.ui.server.parseServerPort
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOG_TAIL_LINES = 8
private const val LOG_REFRESH_MS = 2_000L
private const val QR_BITMAP_SIZE = 512
private const val CHECK_IDS_LIMIT = 5

@Composable
fun ServerScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: ServerViewModel = viewModel(factory = ServerViewModel.factory())
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val portText by viewModel.portText.collectAsStateWithLifecycle()
    val apiKeyText by viewModel.apiKeyText.collectAsStateWithLifecycle()
    val apiTest by viewModel.apiTest.collectAsStateWithLifecycle()
    val lanIp by viewModel.lanIp.collectAsStateWithLifecycle()
    val uptimeMillis by viewModel.uptimeMillis.collectAsStateWithLifecycle()

    var logTail by remember { mutableStateOf(emptyList<String>()) }
    var lanWarningOpen by remember { mutableStateOf(false) }

    LaunchedEffect(container) {
        while (true) {
            logTail = container.engineManager.recentLog().takeLast(LOG_TAIL_LINES)
            delay(LOG_REFRESH_MS)
        }
    }

    val copiedMessage = stringResource(R.string.server_copied)
    val portValue = portText ?: settings.serverPort.toString()
    val portValid = parseServerPort(portValue) != null
    val apiKeyValue = apiKeyText ?: settings.serverApiKey
    val localUrl = localServerUrl(settings.serverPort)
    val lanUrl = lanIp?.let { lanServerUrl(it, settings.serverPort) }

    fun copyText(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            StatusCard(
                engineState = engineState,
                apiTest = apiTest,
                uptimeMillis = uptimeMillis,
                onCheck = viewModel::checkModels,
            )
            Spacer(Modifier.height(12.dp))
            SettingsCard(title = stringResource(R.string.server_port_label)) {
                val portError = portValue.isNotEmpty() && !portValid
                OutlinedTextField(
                    value = portValue,
                    onValueChange = viewModel::updatePort,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_port_label)) },
                    isError = portError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (portError) R.string.server_port_invalid else R.string.server_port_hint,
                            ),
                        )
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            SettingsCard(title = stringResource(R.string.server_api_key_label)) {
                OutlinedTextField(
                    value = apiKeyValue,
                    onValueChange = viewModel::updateApiKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_api_key_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = viewModel::generateKey,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.server_api_key_generate))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { copyText(apiKeyValue) },
                        enabled = apiKeyValue.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.server_copy))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_lan_label),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.server_lan_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = settings.serverLanEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                lanWarningOpen = true
                            } else {
                                viewModel.setLanEnabled(false)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsCard(title = stringResource(R.string.server_local_url_label)) {
                UrlRow(
                    label = stringResource(R.string.server_local_url_label),
                    url = localUrl,
                    onCopy = { copyText(localUrl) },
                )
                if (settings.serverLanEnabled) {
                    Spacer(Modifier.height(8.dp))
                    val url = lanUrl
                    if (url == null) {
                        Text(
                            text = stringResource(R.string.server_no_lan_ip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        UrlRow(
                            label = stringResource(R.string.server_lan_url_label),
                            url = url,
                            onCopy = { copyText(url) },
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.server_qr_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        val qr = remember(url) { qrBitmap(url) }
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.server_qr_title),
                            modifier = Modifier.size(QR_PREVIEW_SIZE),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingsCard(title = stringResource(R.string.server_log_title)) {
                Text(
                    text = logTail.joinToString("\n").ifEmpty {
                        stringResource(R.string.server_log_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (lanWarningOpen) {
        AlertDialog(
            onDismissRequest = { lanWarningOpen = false },
            title = { Text(stringResource(R.string.server_lan_warning_title)) },
            text = { Text(stringResource(R.string.server_lan_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        lanWarningOpen = false
                        viewModel.setLanEnabled(true)
                    },
                ) {
                    Text(stringResource(R.string.server_lan_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { lanWarningOpen = false }) {
                    Text(stringResource(R.string.server_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(
    engineState: EngineState,
    apiTest: ServerViewModel.ApiTestState,
    uptimeMillis: Long?,
    onCheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (engineState) {
                is EngineState.Ready -> {
                    Text(
                        text = stringResource(R.string.server_status_ready, modelLabel(engineState.modelPath)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    uptimeMillis?.let { uptime ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.server_uptime, formatUptime(uptime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCheck,
                        enabled = apiTest !is ServerViewModel.ApiTestState.Checking,
                    ) {
                        Text(stringResource(R.string.server_check_button))
                    }
                }
                is EngineState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.server_status_loading),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                is EngineState.Unloaded -> Text(
                    text = stringResource(R.string.server_status_unloaded),
                    style = MaterialTheme.typography.titleMedium,
                )
                is EngineState.Error -> {
                    Text(
                        text = stringResource(R.string.server_engine_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = engineState.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (apiTest) {
                ServerViewModel.ApiTestState.Idle -> Unit
                ServerViewModel.ApiTestState.Checking -> {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.server_check_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is ServerViewModel.ApiTestState.Success -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (apiTest.ids.isEmpty()) {
                            stringResource(R.string.server_check_empty)
                        } else {
                            stringResource(
                                R.string.server_check_ok,
                                apiTest.ids.take(CHECK_IDS_LIMIT).joinToString(", "),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ServerViewModel.ApiTestState.Error -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = apiTest.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun UrlRow(label: String, url: String, onCopy: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.server_copy))
            }
        }
    }
}

private fun modelLabel(modelPath: String): String =
    modelPath.substringAfterLast('/').substringBeforeLast('.').ifEmpty { modelPath }

fun qrBitmap(text: String, size: Int = QR_BITMAP_SIZE): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private const val CLIP_LABEL = "aipaca"
private val QR_PREVIEW_SIZE = 220.dp
