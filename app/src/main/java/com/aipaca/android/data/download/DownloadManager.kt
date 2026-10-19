package com.aipaca.android.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aipaca.android.R
import com.aipaca.android.data.db.AiPacaDatabase
import com.aipaca.android.data.db.entity.DownloadEntity
import com.aipaca.android.data.db.entity.DownloadPartEntity
import com.aipaca.android.data.settings.Settings
import com.aipaca.android.data.settings.SettingsRepository
import com.aipaca.android.engine.diskRequiredBytes
import com.aipaca.android.engine.formatGigabytes
import com.aipaca.android.engine.hasEnoughDisk
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DownloadProgress(
    val downloadId: Long,
    val fileName: String,
    val state: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBps: Double,
    val etaSec: Long?,
    val connections: Int,
    val error: String?,
)

class DownloadBusyException(message: String) : IOException(message)

class DownloadManager(
    context: Context,
    database: AiPacaDatabase,
    private val engine: DownloadEngine,
) {

    private val appContext = context.applicationContext
    private val downloadDao = database.downloadDao()
    private val partDao = database.downloadPartDao()
    private val settingsRepository = SettingsRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val progressLock = Any()
    private val progressMap = LinkedHashMap<Long, DownloadProgress>()
    private val _progress = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val progress: StateFlow<List<DownloadProgress>> = _progress.asStateFlow()

    private val runningIds = ConcurrentHashMap.newKeySet<Long>()
    private val pausedByWifi = ConcurrentHashMap.newKeySet<Long>()
    private val pendingSha256 = ConcurrentHashMap<Long, String>()
    private val generations = ConcurrentHashMap<Long, Long>()

    init {
        scope.launch {
            var serviceActive = false
            progress.collect { items ->
                val active = items.any { it.state == STATE_QUEUED || it.state == STATE_RUNNING }
                if (active && !serviceActive) {
                    DownloadService.start(appContext)
                }
                serviceActive = active
            }
        }
    }

    fun isPausedByWifi(id: Long): Boolean = pausedByWifi.contains(id)

    fun isWifiConnected(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun enqueue(
        url: String,
        fileName: String,
        totalBytes: Long,
        sha256: String?,
        modelKey: String,
    ): Long {
        val settings = readSettings()
        val safeName = sanitizeFileName(fileName).ifBlank { FALLBACK_FILE_NAME }
        val directory = File(appContext.filesDir, MODELS_DIR)
        directory.mkdirs()
        val target = File(directory, safeName)
        val connections = clampConnections(settings.downloadConnections)
        val size = totalBytes.coerceAtLeast(0L)
        val id = downloadDao.insert(
            DownloadEntity(
                modelKey = modelKey,
                url = url,
                targetPath = target.absolutePath,
                totalBytes = size,
                downloadedBytes = 0L,
                state = STATE_QUEUED,
                connections = connections,
                createdAt = System.currentTimeMillis(),
            ),
        )
        if (!sha256.isNullOrBlank()) {
            pendingSha256[id] = sha256
        }
        pausedByWifi.remove(id)
        publish(
            DownloadProgress(
                downloadId = id,
                fileName = safeName,
                state = STATE_QUEUED,
                downloadedBytes = 0L,
                totalBytes = size,
                speedBps = 0.0,
                etaSec = null,
                connections = connections,
                error = null,
            ),
        )
        schedule(id)
        return id
    }

    suspend fun pause(id: Long) {
        bumpGeneration(id)
        cancelWork(id)
        pausedByWifi.remove(id)
        val entity = downloadDao.getById(id) ?: return
        if (entity.state == STATE_COMPLETED || entity.state == STATE_CANCELLED) return
        val downloaded = currentBytes(id, entity.downloadedBytes)
        downloadDao.update(entity.copy(state = STATE_PAUSED, downloadedBytes = downloaded, error = null))
        publish(entity.progress(STATE_PAUSED, downloaded, entity.totalBytes, 0.0, null))
    }

    suspend fun resume(id: Long) {
        val entity = downloadDao.getById(id) ?: return
        if (entity.state == STATE_COMPLETED) return
        bumpGeneration(id)
        pausedByWifi.remove(id)
        downloadDao.updateState(id, STATE_QUEUED, entity.completedAt, null)
        publish(entity.progress(STATE_QUEUED, entity.downloadedBytes, entity.totalBytes, 0.0, null))
        schedule(id)
    }

    suspend fun cancel(id: Long) {
        bumpGeneration(id)
        cancelWork(id)
        val entity = downloadDao.getById(id) ?: return
        partDao.deleteForDownload(id)
        File(entity.targetPath).delete()
        pendingSha256.remove(id)
        pausedByWifi.remove(id)
        downloadDao.update(entity.copy(state = STATE_CANCELLED, downloadedBytes = 0L, error = null))
        publish(entity.progress(STATE_CANCELLED, 0L, entity.totalBytes, 0.0, null))
    }

    suspend fun remove(id: Long) {
        val entity = downloadDao.getById(id) ?: return
        removeOne(entity)
    }

    suspend fun clearAll() {
        downloadDao.observeAll().first().forEach { removeOne(it) }
    }

    private suspend fun removeOne(entity: DownloadEntity) {
        val id = entity.id
        bumpGeneration(id)
        cancelWork(id)
        partDao.deleteForDownload(id)
        if (shouldDeleteFilesOnRemove(entity.state)) {
            File(entity.targetPath).delete()
        }
        pendingSha256.remove(id)
        pausedByWifi.remove(id)
        downloadDao.delete(entity)
        val snapshot = synchronized(progressLock) {
            progressMap.remove(id)
            progressMap.values.toList()
        }
        _progress.value = snapshot
    }

    fun onWorkStopped(id: Long) {
        scope.launch {
            downloadDao.updateStateWhileRunning(id, STATE_QUEUED, null, null)
            val entity = downloadDao.getById(id) ?: return@launch
            publish(
                entity.progress(
                    entity.state,
                    currentBytes(id, entity.downloadedBytes),
                    entity.totalBytes,
                    0.0,
                    entity.error,
                ),
            )
        }
    }

    suspend fun runQueued(id: Long): Result<Unit> {
        val entity = downloadDao.getById(id)
            ?: return Result.failure(IllegalStateException("Download $id not found"))
        if (entity.state == STATE_COMPLETED || entity.state == STATE_CANCELLED) {
            return Result.success(Unit)
        }
        if (!runningIds.add(id)) {
            return Result.failure(DownloadBusyException("Download $id is already running"))
        }
        try {
            val settings = readSettings()
            if (settings.wifiOnly && !isWifiConnected()) {
                pausedByWifi.add(id)
                downloadDao.updateState(id, STATE_PAUSED, entity.completedAt, null)
                publish(entity.progress(STATE_PAUSED, entity.downloadedBytes, entity.totalBytes, 0.0, null))
                return Result.success(Unit)
            }
            val diskError = diskSpaceError(entity)
            if (diskError != null) {
                downloadDao.updateState(id, STATE_ERROR, entity.completedAt, diskError)
                publish(entity.progress(STATE_ERROR, entity.downloadedBytes, entity.totalBytes, 0.0, diskError))
                return Result.failure(IllegalStateException(diskError))
            }
            return execute(entity, settings.hfToken.trim().ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } finally {
            runningIds.remove(id)
        }
    }

    private suspend fun execute(entity: DownloadEntity, token: String?): Result<Unit> {
        val id = entity.id
        val fileName = entity.fileName()
        val file = File(entity.targetPath)
        file.parentFile?.mkdirs()
        val generation = generations[id] ?: 0L

        val probe = engine.probe(entity.url, token)
        val totalBytes = when {
            probe.totalBytes > 0L -> probe.totalBytes
            entity.totalBytes > 0L -> entity.totalBytes
            else -> 0L
        }
        val rangeMode = probe.acceptRanges && totalBytes > 0L

        downloadDao.updateTotalBytes(id, totalBytes)
        downloadDao.updateStateIfPending(id, STATE_RUNNING, null, null)

        if (!rangeMode) {
            partDao.deleteForDownload(id)
        }
        val priorDone = if (rangeMode) {
            ensureParts(id, entity.connections, totalBytes, file)
        } else {
            0L
        }

        val received = AtomicLong(0L)
        val speed = SpeedEma()
        var knownBytes = priorDone
        var lastPersistAt = 0L
        var lastTickAt = SystemClock.elapsedRealtime()

        publishIfCurrent(id, generation, entity.progress(STATE_RUNNING, priorDone, totalBytes, 0.0, null))

        try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        delay(PROGRESS_INTERVAL_MS)
                        val now = SystemClock.elapsedRealtime()
                        val downloaded = clampToTotal(priorDone + received.get(), totalBytes)
                        val rate = speed.add(downloaded - knownBytes, now - lastTickAt)
                        lastTickAt = now
                        knownBytes = downloaded
                        if (isCurrent(id, generation)) {
                            publish(
                                entity.progress(STATE_RUNNING, downloaded, totalBytes, rate, null),
                            )
                            if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                                lastPersistAt = now
                                downloadDao.updateDownloadedBytesWhileRunning(id, downloaded)
                            }
                        }
                    }
                }
                try {
                    if (rangeMode) {
                        downloadParts(
                            url = entity.url,
                            file = file,
                            token = token,
                            connections = entity.connections,
                            parts = partDao.getForDownload(id),
                            received = received,
                        )
                    } else {
                        engine.downloadAll(entity.url, file, token) { delta ->
                            received.addAndGet(delta)
                        }
                    }
                } finally {
                    ticker.cancel()
                }
            }

            val finalBytes = file.length()
            if (totalBytes > 0L && finalBytes != totalBytes) {
                throw IOException("Downloaded size mismatch: $finalBytes of $totalBytes bytes")
            }

            val expectedSha = pendingSha256.remove(id)
            if (expectedSha != null && !engine.verifySha256(file, expectedSha)) {
                downloadDao.updateStateWhileRunning(id, STATE_ERROR, null, SHA_MISMATCH)
                publishIfCurrent(
                    id,
                    generation,
                    entity.progress(STATE_ERROR, finalBytes, totalBytes, 0.0, SHA_MISMATCH),
                )
                return Result.failure(IllegalStateException(SHA_MISMATCH))
            }

            downloadDao.updateDownloadedBytesWhileRunning(id, finalBytes)
            downloadDao.updateStateWhileRunning(id, STATE_COMPLETED, System.currentTimeMillis(), null)
            publishIfCurrent(
                id,
                generation,
                entity.progress(STATE_COMPLETED, finalBytes, totalBytes, 0.0, null),
            )
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            downloadDao.updateStateWhileRunning(id, STATE_QUEUED, entity.completedAt, e.message)
            publishIfCurrent(id, generation, entity.progress(STATE_QUEUED, knownBytes, totalBytes, 0.0, e.message))
            return Result.failure(e)
        } catch (e: Exception) {
            downloadDao.updateStateWhileRunning(id, STATE_ERROR, entity.completedAt, e.message)
            publishIfCurrent(id, generation, entity.progress(STATE_ERROR, knownBytes, totalBytes, 0.0, e.message))
            return Result.failure(e)
        }
    }

    private suspend fun ensureParts(id: Long, connections: Int, totalBytes: Long, file: File): Long {
        val existing = partDao.getForDownload(id)
        val fileLength = if (file.isFile) file.length() else 0L
        resumableBytes(existing, totalBytes, fileLength)?.let { return it }
        partDao.deleteForDownload(id)
        engine.planParts(totalBytes, connections).forEach { spec ->
            partDao.insert(
                DownloadPartEntity(
                    downloadId = id,
                    partIndex = spec.index,
                    start = spec.start,
                    end = spec.endInclusive,
                    done = false,
                ),
            )
        }
        return 0L
    }

    private suspend fun downloadParts(
        url: String,
        file: File,
        token: String?,
        connections: Int,
        parts: List<DownloadPartEntity>,
        received: AtomicLong,
    ) {
        val semaphore = Semaphore(clampConnections(connections))
        coroutineScope {
            parts.asSequence()
                .filter { !it.done }
                .map { part ->
                    async(Dispatchers.IO) {
                        val length = part.end - part.start + 1L
                        semaphore.withPermit {
                            val already = part.downloadedBytes.coerceIn(0L, length)
                            var written = already
                            var lastPersistedAt = SystemClock.elapsedRealtime()
                            engine.downloadPart(
                                url = url,
                                spec = PartSpec(part.partIndex, part.start, part.end),
                                target = file,
                                token = token,
                                fromOffset = already,
                            ) { delta ->
                                written += delta
                                received.addAndGet(delta)
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastPersistedAt >= PART_PERSIST_INTERVAL_MS) {
                                    lastPersistedAt = now
                                    partDao.update(part.copy(downloadedBytes = written))
                                }
                            }
                        }
                        partDao.update(part.copy(done = true, downloadedBytes = length))
                    }
                }
                .toList()
                .awaitAll()
        }
    }

    private fun clampToTotal(bytes: Long, totalBytes: Long): Long =
        if (totalBytes > 0L) bytes.coerceAtMost(totalBytes) else bytes

    private fun currentBytes(id: Long, fallback: Long): Long =
        maxOf(fallback, synchronized(progressLock) { progressMap[id]?.downloadedBytes ?: 0L })

    private fun bumpGeneration(id: Long) {
        generations.merge(id, 1L) { current, _ -> current + 1L }
    }

    private fun isCurrent(id: Long, generation: Long): Boolean =
        (generations[id] ?: 0L) == generation

    private fun publishIfCurrent(id: Long, generation: Long, item: DownloadProgress) {
        if (isCurrent(id, generation)) {
            publish(item)
        }
    }

    private fun DownloadEntity.progress(
        state: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Double,
        error: String?,
    ): DownloadProgress {
        val remaining = totalBytes - downloadedBytes
        val etaSec = if (speedBps > 0.0 && remaining > 0L) {
            (remaining / speedBps).toLong()
        } else {
            null
        }
        return DownloadProgress(
            downloadId = id,
            fileName = fileName(),
            state = state,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBps = speedBps,
            etaSec = etaSec,
            connections = connections,
            error = error,
        )
    }

    private fun DownloadEntity.fileName(): String = File(targetPath).name

    private fun publish(item: DownloadProgress) {
        val snapshot = synchronized(progressLock) {
            progressMap[item.downloadId] = item
            progressMap.values.toList()
        }
        _progress.value = snapshot
    }

    private fun schedule(id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to id))
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelWork(id: Long) {
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(id))
    }

    private fun workName(id: Long): String = "$WORK_PREFIX$id"

    private suspend fun readSettings(): Settings =
        runCatching { settingsRepository.settings.first() }.getOrDefault(Settings())

    private fun clampConnections(count: Int): Int = count.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)

    private fun diskSpaceError(entity: DownloadEntity): String? {
        val totalBytes = entity.totalBytes
        if (totalBytes <= 0L) return null
        val usable = appContext.filesDir.usableSpace
        if (hasEnoughDisk(usable, totalBytes)) return null
        return appContext.getString(
            R.string.preflight_disk_low,
            formatGigabytes(diskRequiredBytes(totalBytes)),
            formatGigabytes(usable),
        )
    }

    companion object {

        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_PAUSED = "paused"
        const val STATE_COMPLETED = "completed"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_ERROR = "error"

        private const val MODELS_DIR = "models"
        private const val FALLBACK_FILE_NAME = "model.gguf"
        private const val WORK_PREFIX = "download-"
        private const val SHA_MISMATCH = "SHA-256 mismatch"
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val PERSIST_INTERVAL_MS = 1_000L
        private const val PART_PERSIST_INTERVAL_MS = 1_000L
        private const val MIN_CONNECTIONS = 1
        private const val MAX_CONNECTIONS = 8
    }
}

internal fun shouldDeleteFilesOnRemove(state: String): Boolean =
    state != DownloadManager.STATE_COMPLETED

internal fun resumableBytes(parts: List<DownloadPartEntity>, totalBytes: Long, fileLength: Long): Long? {
    if (!hasFullCoverage(parts, totalBytes)) return null
    val claimedEnd = parts.maxOfOrNull { part ->
        val length = part.end - part.start + 1L
        if (part.done) part.end + 1L else part.start + part.downloadedBytes.coerceIn(0L, length)
    } ?: 0L
    if (fileLength < claimedEnd) return null
    return parts.sumOf { part ->
        val length = part.end - part.start + 1L
        if (part.done) length else part.downloadedBytes.coerceIn(0L, length)
    }
}

private fun hasFullCoverage(parts: List<DownloadPartEntity>, totalBytes: Long): Boolean {
    if (parts.isEmpty()) return false
    val sorted = parts.sortedBy { it.start }
    if (sorted.first().start != 0L) return false
    var expected = 0L
    for (part in sorted) {
        if (part.start != expected) return false
        expected = part.end + 1L
    }
    return expected == totalBytes
}
