package com.aipaca.android.ui.bench

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AiPacaApp
import com.aipaca.android.R
import com.aipaca.android.data.db.AiPacaDatabase
import com.aipaca.android.data.db.entity.BenchResultEntity
import com.aipaca.android.data.db.entity.ModelEntity
import com.aipaca.android.data.device.DeviceInfo
import com.aipaca.android.data.settings.SettingsRepository
import com.aipaca.android.engine.Accelerator
import com.aipaca.android.engine.BenchBackend
import com.aipaca.android.engine.BenchRunResult
import com.aipaca.android.engine.BenchRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BenchViewModel(
    database: AiPacaDatabase,
    private val runner: BenchRunner,
    settingsRepository: SettingsRepository,
    context: Context,
) : ViewModel() {

    sealed interface Status {

        data object Idle : Status

        data object Running : Status

        data object Done : Status

        data class Error(val message: String) : Status
    }

    @Serializable
    private data class BenchConfig(
        val modelPath: String,
        val threads: Int,
        val cpuMask: String?,
    )

    private val appContext: Context = context.applicationContext
    private val modelDao = database.modelDao()
    private val benchResultDao = database.benchResultDao()
    private val deviceKey = DeviceInfo.current(appContext).deviceKey

    private var runJob: Job? = null

    val models: StateFlow<List<ModelEntity>> = modelDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    private val _selectedModelId = MutableStateFlow<Long?>(null)
    val selectedModelId: StateFlow<Long?> = _selectedModelId.asStateFlow()

    private val _backend = MutableStateFlow(BenchBackend.CPU)
    val backend: StateFlow<BenchBackend> = _backend.asStateFlow()

    private val _threadsText = MutableStateFlow(DEFAULT_THREADS.toString())
    val threadsText: StateFlow<String> = _threadsText.asStateFlow()

    private val _cpuMaskText = MutableStateFlow(DEFAULT_CPU_MASK)
    val cpuMaskText: StateFlow<String> = _cpuMaskText.asStateFlow()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _result = MutableStateFlow<BenchRunResult?>(null)
    val result: StateFlow<BenchRunResult?> = _result.asStateFlow()

    val history: StateFlow<List<BenchResultEntity>> = benchResultDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    val npuAvailable: StateFlow<Boolean> = settingsRepository.settings
        .map { it.accelerator == Accelerator.NPU }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun selectModel(id: Long) {
        _selectedModelId.value = id
    }

    fun selectBackend(value: BenchBackend) {
        _backend.value = value
    }

    fun updateThreads(value: String) {
        _threadsText.value = value.filter { it.isDigit() }.take(THREADS_MAX_DIGITS)
    }

    fun updateCpuMask(value: String) {
        _cpuMaskText.value = value
    }

    fun run() {
        if (runJob?.isActive == true) return
        val model = models.value.firstOrNull { it.id == _selectedModelId.value } ?: models.value.firstOrNull()
        if (model == null) {
            _status.value = Status.Error(appContext.getString(R.string.bench_error_no_model))
            return
        }
        _selectedModelId.value = model.id
        val backend = _backend.value
        val threads = _threadsText.value.toIntOrNull() ?: DEFAULT_THREADS
        val cpuMask = _cpuMaskText.value.trim().ifEmpty { null }
        _log.value = emptyList()
        _result.value = null
        _status.value = Status.Running
        runJob = viewModelScope.launch {
            val outcome = runner.run(model.path, backend, threads, cpuMask) { line ->
                _log.update { it + line }
            }
            outcome.fold(
                onSuccess = { runResult ->
                    _result.value = runResult
                    _status.value = Status.Done
                    withContext(Dispatchers.IO) {
                        benchResultDao.insert(
                            BenchResultEntity(
                                modelId = model.id,
                                deviceKey = deviceKey,
                                backend = backend.tableName,
                                configJson = Json.encodeToString(BenchConfig(model.path, threads, cpuMask)),
                                pp = runResult.ppTps ?: 0.0,
                                tg = runResult.tgTps ?: 0.0,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    _status.value = Status.Error(
                        error.message ?: appContext.getString(R.string.bench_error_failed),
                    )
                },
            )
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        if (_status.value is Status.Running) {
            _status.value = Status.Idle
        }
    }

    companion object {

        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        private const val DEFAULT_THREADS = 4
        private const val DEFAULT_CPU_MASK = "0xF0"
        private const val THREADS_MAX_DIGITS = 2

    fun factory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
            BenchViewModel(
                database = app.container.database,
                runner = BenchRunner(app),
                settingsRepository = app.container.settingsRepository,
                context = app,
            )
        }
    }
    }
}
