package com.aipaca.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aipaca.android.AiPacaApp
import com.aipaca.android.R
import com.aipaca.android.data.profiles.remote.SyncState
import com.aipaca.android.data.settings.LANGUAGE_ENGLISH
import com.aipaca.android.data.settings.LANGUAGE_RUSSIAN
import com.aipaca.android.data.settings.LANGUAGE_SYSTEM
import com.aipaca.android.data.settings.Settings
import com.aipaca.android.data.settings.SettingsRepository
import com.aipaca.android.engine.Accelerator
import com.aipaca.android.ui.applyLanguage
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settingsRepository: SettingsRepository, modifier: Modifier = Modifier) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val container = remember(appContext) { (appContext as AiPacaApp).container }
    val profileRepository = container.profileRepository
    val focusManager = LocalFocusManager.current
    val syncState by profileRepository.syncState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.settings_storage_copied)
    val modelsPath = remember(context) { File(context.filesDir, MODELS_DIR).absolutePath }

    var urlText by remember(settings.profilesRepoUrl) { mutableStateOf(settings.profilesRepoUrl) }
    var urlFocused by remember { mutableStateOf(false) }
    var tokenText by remember(settings.hfToken) { mutableStateOf(settings.hfToken) }
    var tokenFocused by remember { mutableStateOf(false) }
    var connections by remember(settings.downloadConnections) { mutableStateOf(settings.downloadConnections) }

    fun saveUrl() {
        val url = urlText.trim()
        if (url != settings.profilesRepoUrl) {
            scope.launch { settingsRepository.setProfilesRepoUrl(url) }
        }
    }

    fun saveToken() {
        val token = tokenText.trim()
        if (token != settings.hfToken) {
            scope.launch { settingsRepository.setHfToken(token) }
        }
    }

    fun copyModelsPath() {
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, modelsPath))
        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.screen_settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            LanguageOption(
                label = stringResource(R.string.language_system),
                selected = settings.language == LANGUAGE_SYSTEM,
                onSelect = {
                    applyLanguage(LANGUAGE_SYSTEM)
                    scope.launch { settingsRepository.setLanguage(LANGUAGE_SYSTEM) }
                },
            )
            LanguageOption(
                label = stringResource(R.string.language_russian),
                selected = settings.language == LANGUAGE_RUSSIAN,
                onSelect = {
                    applyLanguage(LANGUAGE_RUSSIAN)
                    scope.launch { settingsRepository.setLanguage(LANGUAGE_RUSSIAN) }
                },
            )
            LanguageOption(
                label = stringResource(R.string.language_english),
                selected = settings.language == LANGUAGE_ENGLISH,
                onSelect = {
                    applyLanguage(LANGUAGE_ENGLISH)
                    scope.launch { settingsRepository.setLanguage(LANGUAGE_ENGLISH) }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_profiles_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (urlFocused && !state.isFocused) saveUrl()
                        urlFocused = state.isFocused
                    },
                label = { Text(stringResource(R.string.settings_profiles_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val url = urlText.trim()
                    scope.launch {
                        if (url != settings.profilesRepoUrl) {
                            settingsRepository.setProfilesRepoUrl(url)
                        }
                        profileRepository.refresh()
                    }
                },
                enabled = syncState !is SyncState.Syncing,
            ) {
                Text(stringResource(R.string.settings_profiles_sync))
            }
            Spacer(Modifier.height(8.dp))
            SyncStatus(state = syncState)
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_hf_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (tokenFocused && !state.isFocused) saveToken()
                        tokenFocused = state.isFocused
                    },
                label = { Text(stringResource(R.string.settings_hf_token_label)) },
                supportingText = { Text(stringResource(R.string.settings_hf_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    tokenText = ""
                    if (settings.hfToken.isNotEmpty()) {
                        scope.launch { settingsRepository.setHfToken("") }
                    }
                },
                enabled = tokenText.isNotEmpty() || settings.hfToken.isNotEmpty(),
            ) {
                Text(stringResource(R.string.settings_hf_clear))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_downloads_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_download_connections, connections),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = connections.toFloat(),
                onValueChange = { connections = it.roundToInt() },
                onValueChangeFinished = {
                    scope.launch { settingsRepository.setDownloadConnections(connections) }
                },
                valueRange = 1f..8f,
                steps = 6,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_download_wifi_only),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.wifiOnly,
                    onCheckedChange = { checked ->
                        scope.launch { settingsRepository.setWifiOnly(checked) }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_engine_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_engine_target),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AcceleratorOption(
                label = stringResource(R.string.settings_engine_cpu),
                selected = settings.accelerator == Accelerator.CPU,
                onSelect = {
                    scope.launch { settingsRepository.setAccelerator(Accelerator.CPU) }
                },
            )
            AcceleratorOption(
                label = stringResource(R.string.settings_engine_gpu),
                selected = settings.accelerator == Accelerator.GPU,
                onSelect = {
                    scope.launch { settingsRepository.setAccelerator(Accelerator.GPU) }
                },
            )
            AcceleratorOption(
                label = stringResource(R.string.settings_engine_npu),
                selected = settings.accelerator == Accelerator.NPU,
                onSelect = {
                    scope.launch { settingsRepository.setAccelerator(Accelerator.NPU) }
                },
            )
            Text(
                text = stringResource(R.string.settings_engine_npu_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_storage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = modelsPath,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { copyModelsPath() }) {
                Text(stringResource(R.string.settings_storage_copy))
            }
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SyncStatus(state: SyncState, modifier: Modifier = Modifier) {
    when (state) {
        is SyncState.Idle -> Text(
            text = stringResource(R.string.settings_profiles_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        is SyncState.Syncing -> CircularProgressIndicator(
            modifier = modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        is SyncState.Ok -> Text(
            text = stringResource(
                R.string.settings_profiles_ok,
                state.profileCount,
                formatFetchedAt(LocalContext.current.applicationContext, state.fetchedAt),
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        is SyncState.Error -> Text(
            text = stringResource(R.string.settings_profiles_error, state.message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}

private fun formatFetchedAt(context: Context, fetchedAt: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
        context.resources.configuration.locales[0],
    ).format(Date(fetchedAt))

@Composable
private fun AcceleratorOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private const val MODELS_DIR = "models"
private const val CLIP_LABEL = "aipaca"
