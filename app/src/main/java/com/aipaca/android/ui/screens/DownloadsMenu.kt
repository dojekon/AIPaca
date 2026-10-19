package com.aipaca.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.download.DownloadManager
import com.aipaca.android.ui.models.formatBytes
import java.util.Locale

@Composable
fun DownloadsMenu(
    container: AppContainer,
    viewModel: DownloadsViewModel = viewModel(factory = DownloadsViewModel.factory(container)),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DownloadsViewModel.DownloadItem?>(null) }
    var clearAllRequested by rememberSaveable { mutableStateOf(false) }
    val active = downloads.any {
        it.state == DownloadManager.STATE_QUEUED || it.state == DownloadManager.STATE_RUNNING
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.downloads_menu_open),
            )
            if (active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(MENU_WIDTH),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.screen_downloads_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clearAllRequested = true },
                    enabled = downloads.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.downloads_clear_all))
                }
            }
            HorizontalDivider()
            if (downloads.isEmpty()) {
                Text(
                    text = stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = MENU_LIST_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    downloads.forEach { item ->
                        DownloadMenuRow(
                            item = item,
                            onPause = { viewModel.pause(item.id) },
                            onResume = { viewModel.resume(item.id) },
                            onCancel = { viewModel.cancel(item.id) },
                            onDelete = { pendingDelete = item },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        val completed = item.state == DownloadManager.STATE_COMPLETED
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.downloads_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.downloads_delete_confirm, item.fileName))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (completed) {
                                R.string.downloads_delete_note_completed
                            } else {
                                R.string.downloads_delete_note_partial
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(item.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.downloads_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.downloads_cancel))
                }
            },
        )
    }

    if (clearAllRequested) {
        AlertDialog(
            onDismissRequest = { clearAllRequested = false },
            title = { Text(stringResource(R.string.downloads_clear_all_title)) },
            text = { Text(stringResource(R.string.downloads_clear_all_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        clearAllRequested = false
                    },
                ) {
                    Text(stringResource(R.string.downloads_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllRequested = false }) {
                    Text(stringResource(R.string.downloads_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadMenuRow(
    item: DownloadsViewModel.DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StateLabel(item.state)
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.downloads_delete),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (item.totalBytes > 0L) {
            LinearProgressIndicator(
                progress = {
                    (item.downloadedBytes.toDouble() / item.totalBytes).coerceIn(0.0, 1.0).toFloat()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = progressText(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = statsText(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.error?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (item.state) {
            DownloadManager.STATE_RUNNING, DownloadManager.STATE_QUEUED -> {
                ActionRow(onPrimary = onPause, primaryRes = R.string.downloads_pause, onCancel = onCancel)
            }
            DownloadManager.STATE_PAUSED -> {
                ActionRow(onPrimary = onResume, primaryRes = R.string.downloads_resume, onCancel = onCancel)
            }
            DownloadManager.STATE_ERROR -> {
                ActionRow(onPrimary = onResume, primaryRes = R.string.downloads_retry, onCancel = onCancel)
            }
            else -> Unit
        }
    }
}

@Composable
private fun ActionRow(onPrimary: () -> Unit, primaryRes: Int, onCancel: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onPrimary) {
            Text(stringResource(primaryRes))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.downloads_cancel))
        }
    }
}

@Composable
private fun StateLabel(state: String) {
    val labelRes = when (state) {
        DownloadManager.STATE_QUEUED -> R.string.downloads_state_queued
        DownloadManager.STATE_RUNNING -> R.string.downloads_state_running
        DownloadManager.STATE_PAUSED -> R.string.downloads_state_paused
        DownloadManager.STATE_COMPLETED -> R.string.downloads_state_completed
        DownloadManager.STATE_ERROR -> R.string.downloads_state_error
        DownloadManager.STATE_CANCELLED -> R.string.downloads_state_cancelled
        else -> R.string.downloads_state_queued
    }
    val (container, content) = when (state) {
        DownloadManager.STATE_RUNNING ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        DownloadManager.STATE_PAUSED ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        DownloadManager.STATE_COMPLETED -> STATE_COMPLETED_BG to STATE_COMPLETED_FG
        DownloadManager.STATE_ERROR ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun progressText(item: DownloadsViewModel.DownloadItem): String =
    if (item.totalBytes > 0L) {
        stringResource(
            R.string.downloads_progress,
            percentOf(item),
            formatBytes(item.downloadedBytes),
            formatBytes(item.totalBytes),
        )
    } else {
        formatBytes(item.downloadedBytes)
    }

@Composable
private fun statsText(item: DownloadsViewModel.DownloadItem): String {
    val parts = mutableListOf<String>()
    if (item.speedBps > 0.0) {
        parts += stringResource(R.string.downloads_speed, formatSpeed(item.speedBps))
    }
    item.etaSec?.let { eta ->
        parts += stringResource(R.string.downloads_eta, formatEta(eta))
    }
    parts += "×${item.connections}"
    return parts.joinToString(" · ")
}

private fun percentOf(item: DownloadsViewModel.DownloadItem): Int {
    if (item.totalBytes <= 0L || item.downloadedBytes <= 0L) return 0
    return (item.downloadedBytes * 100L / item.totalBytes).coerceIn(0L, 100L).toInt()
}

private fun formatSpeed(bytesPerSecond: Double): String =
    String.format(Locale.US, "%.1f", bytesPerSecond / (1024.0 * 1024.0))

private fun formatEta(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    return String.format(Locale.US, "%d:%02d", total / 60L, total % 60L)
}

private val MENU_WIDTH = 320.dp
private val MENU_LIST_HEIGHT = 420.dp
private val STATE_COMPLETED_BG = Color(0xFF2E7D32)
private val STATE_COMPLETED_FG = Color.White
