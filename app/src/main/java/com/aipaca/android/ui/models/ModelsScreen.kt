package com.aipaca.android.ui.models

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.ModelEntity
import com.aipaca.android.engine.EngineState
import com.aipaca.android.ui.common.buildProfileShareText
import com.aipaca.android.ui.common.shareText

@Composable
fun ModelsScreen(
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.Factory),
) {
    val context = LocalContext.current
    val entities by viewModel.models.collectAsStateWithLifecycle()
    val models by viewModel.modelItems.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    val recentLog by viewModel.recentLog.collectAsStateWithLifecycle()
    val shareProfileSubject = stringResource(R.string.share_profile_subject)

    var pendingDelete by remember { mutableStateOf<ModelEntity?>(null) }
    var engineErrorText by remember { mutableStateOf<String?>(null) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importModel(uri)
            }
        }

    LaunchedEffect(Unit) {
        if (!notificationRequested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val busy = actionState is ModelsViewModel.ActionState.Busy

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        EngineStatusCard(
            engineState = engineState,
            loadedName = models.firstOrNull { it.loaded }?.name,
            busyLabel = (actionState as? ModelsViewModel.ActionState.Busy)?.label,
            onStop = viewModel::unloadModel,
            onErrorClick = { message ->
                viewModel.refreshLog()
                engineErrorText = message
            },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !busy && importState !is ModelsViewModel.ImportState.Running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.models_import_button))
        }
        when (val state = importState) {
            is ModelsViewModel.ImportState.Running -> {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.models_percent, state.percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            is ModelsViewModel.ImportState.Failed -> InlineMessage(
                message = state.message,
                onDismiss = viewModel::clearImportState,
            )
            ModelsViewModel.ImportState.Idle -> Unit
        }
        val action = actionState
        if (action is ModelsViewModel.ActionState.Error) {
            InlineMessage(message = action.message, onDismiss = viewModel::clearActionState)
        }
        Spacer(Modifier.height(8.dp))
        if (models.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.models_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(models, key = { it.id }) { model ->
                    ModelRow(
                        model = model,
                        busy = busy,
                        onLoad = { entities.firstOrNull { it.id == model.id }?.let(viewModel::loadModel) },
                        onUnload = viewModel::unloadModel,
                        onDelete = { pendingDelete = entities.firstOrNull { it.id == model.id } },
                        onShareProfile = model.profileJson?.let { json ->
                            {
                                context.shareText(
                                    shareProfileSubject,
                                    buildProfileShareText(json),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { entity ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(stringResource(R.string.models_delete_confirm, entity.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteModel(entity)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.models_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.models_cancel))
                }
            },
        )
    }

    engineErrorText?.let { message ->
        AlertDialog(
            onDismissRequest = { engineErrorText = null },
            title = { Text(stringResource(R.string.models_engine_error_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.models_log_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    val logTail = recentLog.takeLast(LOG_DIALOG_LINES).joinToString("\n")
                    val logEmpty = stringResource(R.string.models_log_empty)
                    Text(
                        text = logTail.ifEmpty { logEmpty },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { engineErrorText = null }) {
                    Text(stringResource(R.string.models_ok))
                }
            },
        )
    }
}

@Composable
private fun EngineStatusCard(
    engineState: EngineState,
    loadedName: String?,
    busyLabel: String?,
    onStop: () -> Unit,
    onErrorClick: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (engineState) {
                is EngineState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.models_status_ready,
                            loadedName ?: engineState.modelPath.substringAfterLast('/'),
                            engineState.port,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (busyLabel == null) {
                        OutlinedButton(onClick = onStop) {
                            Text(stringResource(R.string.models_stop))
                        }
                    }
                }
                is EngineState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.models_status_loading),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                is EngineState.Unloaded -> Text(
                    text = stringResource(R.string.models_status_unloaded),
                    style = MaterialTheme.typography.titleMedium,
                )
                is EngineState.Error -> {
                    Text(
                        text = stringResource(R.string.models_engine_error_title),
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
                        modifier = Modifier.clickable { onErrorClick(engineState.message) },
                    )
                }
            }
            if (busyLabel != null && engineState !is EngineState.Loading) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = busyLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMessage(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.models_ok))
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelsViewModel.ModelUi,
    busy: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit,
    onShareProfile: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatBytes(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    model.quant?.let { quant ->
                        Spacer(Modifier.width(8.dp))
                        QuantBadge(quant)
                    }
                }
                Text(
                    text = model.profileLabel ?: stringResource(R.string.models_no_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (model.loaded) {
                IconButton(onClick = onUnload, enabled = !busy) {
                    Icon(
                        imageVector = StopSquareIcon,
                        contentDescription = stringResource(R.string.models_stop),
                    )
                }
            } else {
                IconButton(onClick = onLoad, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.models_load),
                    )
                }
            }
            onShareProfile?.let { share ->
                IconButton(onClick = share, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.share_profile_action),
                    )
                }
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.models_delete),
                )
            }
        }
    }
}

@Composable
private fun QuantBadge(quant: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = quant,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private const val LOG_DIALOG_LINES = 30

private val StopSquareIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Stop") {
        materialPath {
            moveTo(6.0f, 6.0f)
            horizontalLineTo(18.0f)
            verticalLineTo(18.0f)
            horizontalLineTo(6.0f)
            close()
        }
    }
}
