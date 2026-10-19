package com.aipaca.android.ui.server

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AiPacaApp
import com.aipaca.android.AppContainer
import com.aipaca.android.R
import com.aipaca.android.data.settings.Settings
import com.aipaca.android.engine.EngineState
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

private val json = Json

class ServerViewModel(
    private val application: Application,
    container: AppContainer,
) : ViewModel() {

    sealed interface ApiTestState {

        data object Idle : ApiTestState

        data object Checking : ApiTestState

        data class Success(val ids: List<String>) : ApiTestState

        data class Error(val message: String) : ApiTestState
    }

    private val engineManager = container.engineManager
    private val settingsRepository = container.settingsRepository
    private val client = OkHttpClient.Builder()
        .connectTimeout(CHECK_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(CHECK_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    val engineState: StateFlow<EngineState> = engineManager.state

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), Settings())

    private val _portText = MutableStateFlow<String?>(null)
    val portText: StateFlow<String?> = _portText.asStateFlow()

    private val _apiKeyText = MutableStateFlow<String?>(null)
    val apiKeyText: StateFlow<String?> = _apiKeyText.asStateFlow()

    private val _apiTest = MutableStateFlow<ApiTestState>(ApiTestState.Idle)
    val apiTest: StateFlow<ApiTestState> = _apiTest.asStateFlow()

    private val _lanIp = MutableStateFlow<String?>(null)
    val lanIp: StateFlow<String?> = _lanIp.asStateFlow()

    private val _readySince = MutableStateFlow<Long?>(null)

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_INTERVAL_MS)
        }
    }

    val uptimeMillis: StateFlow<Long?> = combine(_readySince, ticker) { since, now ->
        since?.let { now - it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    init {
        viewModelScope.launch {
            engineManager.state.collect { state ->
                if (state is EngineState.Ready) {
                    if (_readySince.value == null) {
                        _readySince.value = System.currentTimeMillis()
                    }
                } else {
                    _readySince.value = null
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (_apiKeyText.value == null) {
                    _apiKeyText.value = settings.serverApiKey
                }
            }
        }
        viewModelScope.launch {
            _lanIp.value = withContext(Dispatchers.IO) { lanIpv4Address() }
        }
    }

    fun updatePort(text: String) {
        val digits = text.filter { it.isDigit() }.take(PORT_MAX_DIGITS)
        _portText.value = digits
        val port = parseServerPort(digits) ?: return
        viewModelScope.launch { settingsRepository.setServerPort(port) }
    }

    fun updateApiKey(value: String) {
        _apiKeyText.value = value
        viewModelScope.launch { settingsRepository.setServerApiKey(value) }
    }

    fun generateKey() {
        val key = generateApiKey()
        _apiKeyText.value = key
        viewModelScope.launch { settingsRepository.setServerApiKey(key) }
    }

    fun setLanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && currentApiKey().isBlank()) {
                val key = generateApiKey()
                _apiKeyText.value = key
                settingsRepository.setServerApiKey(key)
            }
            settingsRepository.setServerLanEnabled(enabled)
        }
    }

    fun checkModels() {
        if (_apiTest.value is ApiTestState.Checking) return
        val serverUrl = engineManager.serverUrl
        if (serverUrl == null) {
            _apiTest.value = ApiTestState.Error(application.getString(R.string.server_check_engine_off))
            return
        }
        _apiTest.value = ApiTestState.Checking
        val apiKey = currentApiKey()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(fetchModelIds("$serverUrl/v1/models", apiKey))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            _apiTest.value = result.fold(
                onSuccess = { ApiTestState.Success(it) },
                onFailure = {
                    ApiTestState.Error(
                        application.getString(
                            R.string.server_check_failed,
                            it.message ?: it.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
    }

    fun clearApiTest() {
        _apiTest.value = ApiTestState.Idle
    }

    private fun currentApiKey(): String =
        (_apiKeyText.value ?: settings.value.serverApiKey).trim()

    private fun fetchModelIds(url: String, apiKey: String): List<String> {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { if (apiKey.isNotEmpty()) header(AUTH_HEADER, "Bearer $apiKey") }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(ERROR_BODY_CHARS)}")
            }
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        }
    }

    companion object {

        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        private const val TICK_INTERVAL_MS = 1_000L
        private const val PORT_MAX_DIGITS = 5
        private const val ERROR_BODY_CHARS = 300
        private const val CHECK_CONNECT_TIMEOUT_S = 5L
        private const val CHECK_READ_TIMEOUT_S = 10L
        private const val AUTH_HEADER = "Authorization"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
                ServerViewModel(app, app.container)
            }
        }
    }
}
