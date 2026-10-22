package com.aipaca.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AppContainer
import com.aipaca.android.data.db.AiPacaDatabase
import com.aipaca.android.data.db.entity.ModelEntity
import com.aipaca.android.data.download.DownloadManager
import com.aipaca.android.data.hf.HfApi
import com.aipaca.android.data.hf.detectTools
import com.aipaca.android.data.hf.quantFromFileName
import com.aipaca.android.data.hf.toolsSupportedValue
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    database: AiPacaDatabase,
    private val downloadManager: DownloadManager,
    private val hfApi: HfApi,
) : ViewModel() {

    data class DownloadItem(
        val id: Long,
        val fileName: String,
        val state: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Double,
        val etaSec: Long?,
        val connections: Int,
        val error: String?,
    )

    private val downloadDao = database.downloadDao()
    private val modelDao = database.modelDao()

    val downloads: StateFlow<List<DownloadItem>> = combine(
        downloadDao.observeAll(),
        downloadManager.progress,
    ) { entities, progress ->
        val liveById = progress.associateBy { it.downloadId }
        entities.map { entity ->
            val live = liveById[entity.id]
            DownloadItem(
                id = entity.id,
                fileName = File(entity.targetPath).name,
                state = live?.state ?: entity.state,
                downloadedBytes = live?.downloadedBytes ?: entity.downloadedBytes,
                totalBytes = live?.takeIf { it.totalBytes > 0L }?.totalBytes ?: entity.totalBytes,
                speedBps = live?.speedBps ?: 0.0,
                etaSec = live?.etaSec,
                connections = live?.connections ?: entity.connections,
                error = live?.error ?: entity.error,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            downloadDao.observeAll().collect { entities ->
                entities
                    .filter { it.state == DownloadManager.STATE_COMPLETED }
                    .forEach { registerModel(it.targetPath, it.modelKey, it.totalBytes, it.completedAt) }
            }
        }
    }

    fun pause(id: Long) {
        viewModelScope.launch { downloadManager.pause(id) }
    }

    fun resume(id: Long) {
        viewModelScope.launch { downloadManager.resume(id) }
    }

    fun cancel(id: Long) {
        viewModelScope.launch { downloadManager.cancel(id) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { downloadManager.remove(id) }
    }

    fun clearAll() {
        viewModelScope.launch { downloadManager.clearAll() }
    }

    private suspend fun registerModel(
        targetPath: String,
        modelKey: String,
        totalBytes: Long,
        completedAt: Long?,
    ) {
        if (modelDao.getByPath(targetPath) != null) return
        val file = File(targetPath)
        if (!file.isFile) return
        val toolsSupported = if (modelKey.isBlank()) {
            null
        } else {
            try {
                val details = hfApi.modelDetails(modelKey).getOrNull()
                val template = hfApi.fetchChatTemplate(modelKey).getOrNull()
                val detected = details?.let {
                    detectTools(it.tags, it.siblings.map { sibling -> sibling.rfilename }, template)
                }
                toolsSupportedValue(detected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        modelDao.insert(
            ModelEntity(
                repoId = modelKey.ifBlank { null },
                fileName = file.name,
                path = targetPath,
                sizeBytes = if (totalBytes > 0L) totalBytes else file.length(),
                quant = quantFromFileName(file.name),
                addedAt = completedAt ?: System.currentTimeMillis(),
                toolsSupported = toolsSupported,
            ),
        )
    }

    companion object {

        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DownloadsViewModel(container.database, container.downloadManager, container.hfApi)
            }
        }
    }
}
