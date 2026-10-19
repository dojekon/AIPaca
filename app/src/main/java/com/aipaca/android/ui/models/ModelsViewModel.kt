package com.aipaca.android.ui.models

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AiPacaApp
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.db.entity.ModelEntity
import com.aipaca.android.data.device.DeviceInfo
import com.aipaca.android.engine.Accelerator
import com.aipaca.android.engine.EngineService
import com.aipaca.android.engine.EngineState
import com.aipaca.android.engine.LaunchTarget
import com.aipaca.android.engine.formatGigabytes
import com.aipaca.android.engine.hasEnoughRam
import com.aipaca.android.engine.profiles.ProfileCatalog
import com.aipaca.android.engine.profiles.ProfileCatalogLoader
import com.aipaca.android.engine.profiles.ProfileMatcher
import com.aipaca.android.engine.profiles.ProfilePolicy
import com.aipaca.android.engine.ramRequiredBytes
import com.aipaca.android.ui.common.profileJsonForShare
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelsViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    data class ModelUi(
        val id: Long,
        val name: String,
        val fileName: String,
        val sizeBytes: Long,
        val quant: String?,
        val profileLabel: String?,
        val profileJson: String?,
        val loaded: Boolean,
        val matched: Boolean,
    )

    sealed interface ImportState {

        data object Idle : ImportState

        data class Running(val percent: Int) : ImportState

        data class Failed(val message: String) : ImportState
    }

    sealed interface ActionState {

        data object Idle : ActionState

        data class Busy(val modelId: Long?, val label: String) : ActionState

        data class Error(val message: String) : ActionState
    }

    private val modelDao = container.database.modelDao()
    private val engineManager = container.engineManager
    private val bundledCatalog: ProfileCatalog by lazy { ProfileCatalogLoader.fromAssets(appContext) }
    private val catalogState = MutableStateFlow(filteredCatalog(bundledCatalog, Accelerator.DEFAULT))
    private val matcherState = MutableStateFlow(ProfileMatcher(catalogState.value))
    private val matcher: ProfileMatcher get() = matcherState.value
    private val _accelerator = MutableStateFlow(Accelerator.DEFAULT)
    val accelerator: StateFlow<Accelerator> = _accelerator.asStateFlow()
    private val device by lazy { DeviceInfo.current(appContext) }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val remote = container.profileRepository.loadCached().getOrNull()
            val catalog = if (remote == null) {
                bundledCatalog
            } else {
                container.profileRepository.mergedCatalog(bundledCatalog, remote)
            }
            container.settingsRepository.settings.collect { settings ->
                val filtered = filteredCatalog(catalog, settings.accelerator)
                catalogState.value = filtered
                matcherState.value = ProfileMatcher(filtered)
                _accelerator.value = settings.accelerator
            }
        }
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState.asStateFlow()

    private val _recentLog = MutableStateFlow<List<String>>(emptyList())
    val recentLog: StateFlow<List<String>> = _recentLog.asStateFlow()

    val engineState: StateFlow<EngineState> = engineManager.state

    val models: StateFlow<List<ModelEntity>> = modelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    val modelItems: StateFlow<List<ModelUi>> = combine(
        models,
        engineState,
        matcherState,
        accelerator,
    ) { entities, state, matcher, accel ->
        entities.map { it.toUi(state, matcher, accel) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    fun importModel(uri: Uri) {
        if (_importState.value is ImportState.Running) return
        viewModelScope.launch {
            _importState.value = ImportState.Running(0)
            var target: File? = null
            try {
                val (displayName, totalSize) = withContext(Dispatchers.IO) { queryMetadata(uri) }
                val fileName = sanitizeFileName(displayName ?: uri.lastPathSegment.orEmpty())
                val file = withContext(Dispatchers.IO) {
                    val dir = File(appContext.filesDir, MODELS_DIR)
                    dir.mkdirs()
                    uniqueTarget(dir, fileName)
                }
                target = file
                val copied = withContext(Dispatchers.IO) { copyInto(uri, file, totalSize) }
                val sizeBytes = if (copied > 0L) copied else totalSize ?: 0L
                withContext(Dispatchers.IO) {
                    modelDao.insert(
                        ModelEntity(
                            fileName = file.name,
                            path = file.absolutePath,
                            sizeBytes = sizeBytes,
                            quant = matcher.extractQuant(file.name),
                            addedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                _importState.value = ImportState.Idle
            } catch (e: CancellationException) {
                withContext(NonCancellable) { target?.delete() }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { target?.delete() }
                _importState.value = ImportState.Failed(
                    e.message ?: appContext.getString(R.string.models_import_failed),
                )
            }
        }
    }

    fun loadModel(entity: ModelEntity) {
        if (_actionState.value is ActionState.Busy) return
        viewModelScope.launch {
            _actionState.value = ActionState.Busy(entity.id, appContext.getString(R.string.models_action_loading))
            val accel = _accelerator.value
            val doc = withContext(Dispatchers.IO) {
                matcher.match(device, entity.fileName, null, accel).profile?.doc
            }
            if (doc == null) {
                _actionState.value = ActionState.Error(appContext.getString(R.string.models_profile_missing))
                return@launch
            }
            val ramError = withContext(Dispatchers.IO) { ramErrorMessage(entity) }
            if (ramError != null) {
                _actionState.value = ActionState.Error(ramError)
                return@launch
            }
            EngineService.start(appContext)
            val settings = container.settingsRepository.settings.first()
            val launchDoc = LaunchTarget.apply(doc, accel)
            val result = withContext(Dispatchers.IO) {
                engineManager.load(
                    entity.path,
                    launchDoc,
                    apiKey = settings.serverApiKey,
                    lanEnabled = settings.serverLanEnabled,
                    requestedPort = settings.serverPort,
                )
            }
            if (result.isSuccess) {
                _actionState.value = ActionState.Idle
                return@launch
            }
            if (accel == Accelerator.CPU) {
                _actionState.value = ActionState.Error(
                    result.exceptionOrNull()?.message ?: appContext.getString(R.string.models_load_failed),
                )
                return@launch
            }
            val fallback = ProfilePolicy.cpuFallback(catalogState.value.profiles, doc)?.doc ?: doc
            val retry = withContext(Dispatchers.IO) {
                engineManager.load(
                    entity.path,
                    LaunchTarget.apply(fallback, Accelerator.CPU),
                    apiKey = settings.serverApiKey,
                    lanEnabled = settings.serverLanEnabled,
                    requestedPort = settings.serverPort,
                )
            }
            val fallbackMessage = appContext.getString(
                if (accel == Accelerator.GPU) R.string.models_gpu_fallback else R.string.models_npu_fallback,
                fallback.id,
            )
            _actionState.value = retry.fold(
                onSuccess = { ActionState.Error(fallbackMessage) },
                onFailure = {
                    ActionState.Error(it.message ?: appContext.getString(R.string.models_load_failed))
                },
            )
        }
    }

    fun unloadModel() {
        if (_actionState.value is ActionState.Busy) return
        viewModelScope.launch {
            _actionState.value = ActionState.Busy(null, appContext.getString(R.string.models_action_unloading))
            withContext(Dispatchers.IO) { engineManager.unload() }
            EngineService.stop(appContext)
            _actionState.value = ActionState.Idle
        }
    }

    fun deleteModel(entity: ModelEntity) {
        if (_actionState.value is ActionState.Busy) return
        viewModelScope.launch {
            _actionState.value = ActionState.Busy(entity.id, appContext.getString(R.string.models_action_deleting))
            val ready = engineManager.state.value as? EngineState.Ready
            if (ready?.modelPath == entity.path) {
                withContext(Dispatchers.IO) { engineManager.unload() }
                EngineService.stop(appContext)
            }
            withContext(Dispatchers.IO) {
                val file = File(entity.path)
                if (file.exists()) {
                    file.delete()
                }
                modelDao.delete(entity)
            }
            _actionState.value = ActionState.Idle
        }
    }

    fun clearImportState() {
        _importState.value = ImportState.Idle
    }

    fun clearActionState() {
        _actionState.value = ActionState.Idle
    }

    fun refreshLog() {
        _recentLog.value = engineManager.recentLog()
    }

    private fun ModelEntity.toUi(
        state: EngineState,
        matcher: ProfileMatcher,
        accelerator: Accelerator,
    ): ModelUi {
        val match = matcher.match(device, fileName, null, accelerator)
        return ModelUi(
            id = id,
            name = fileName.substringBeforeLast('.', fileName),
            fileName = fileName,
            sizeBytes = sizeBytes,
            quant = quant ?: matcher.extractQuant(fileName),
            profileLabel = match.profile?.let { "${it.doc.id} · ${it.doc.backend}" },
            profileJson = match.profile?.doc?.let(::profileJsonForShare),
            loaded = state is EngineState.Ready && state.modelPath == path,
            matched = match.profile != null,
        )
    }

    private fun ramErrorMessage(entity: ModelEntity): String? {
        val fileBytes = File(entity.path).length()
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        if (hasEnoughRam(fileBytes, info.availMem)) return null
        return appContext.getString(
            R.string.preflight_ram_low,
            formatGigabytes(ramRequiredBytes(fileBytes)),
            formatGigabytes(info.availMem),
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String?, Long?> {
        var displayName: String? = null
        var size: Long? = null
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        return displayName to size
    }

    private fun copyInto(uri: Uri, target: File, totalSize: Long?): Long {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException(appContext.getString(R.string.models_import_failed))
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(IMPORT_BUFFER_SIZE)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (totalSize != null && totalSize > 0L) {
                        val percent = importProgressPercent(copied, totalSize)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _importState.value = ImportState.Running(percent)
                        }
                    }
                }
                return copied
            }
        }
    }

    private fun filteredCatalog(catalog: ProfileCatalog, accelerator: Accelerator): ProfileCatalog =
        ProfileCatalog(
            index = catalog.index,
            devices = catalog.devices,
            profiles = ProfilePolicy.filterForSettings(catalog.profiles, accelerator),
        )

    private fun uniqueTarget(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem-$index.$extension")
            index++
        }
        return candidate
    }

    companion object {

        private const val MODELS_DIR = "models"
        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        private const val IMPORT_BUFFER_SIZE = 128 * 1024

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
                ModelsViewModel(app.container, app.applicationContext)
            }
        }
    }
}
