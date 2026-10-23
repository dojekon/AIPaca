package com.aipaca.android.engine

import android.content.Context
import android.util.Log
import com.aipaca.android.R
import com.aipaca.android.engine.profiles.ProfileDoc
import com.aipaca.android.engine.profiles.ServerArgBuilder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class EngineManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val lock = Any()
    private val loadMutex = Mutex()
    private val logRing = LogRing(LOG_RING_CAPACITY)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)

    private var process: Process? = null
    private var currentModelPath: String? = null
    private var currentDoc: ProfileDoc? = null
    private var currentApiKey: String? = null
    private var currentLanEnabled = false
    private var currentPort: Int? = null
    private var unloading = false
    private var stateToken = 0L

    val state: StateFlow<EngineState> = _state.asStateFlow()

    val serverUrl: String?
        get() = when (val current = _state.value) {
            is EngineState.Ready -> "http://$HOST:${current.port}"
            else -> null
        }

    val contextSize: Int?
        get() = synchronized(lock) { currentDoc?.launch?.nCtx }

    suspend fun load(
        modelPath: String,
        doc: ProfileDoc,
        apiKey: String? = null,
        lanEnabled: Boolean = false,
        requestedPort: Int? = null,
    ): Result<Unit> {
        val key = apiKey?.trim().orEmpty().ifEmpty { null }
        if (isSameEngineActive(modelPath, doc, key, lanEnabled, requestedPort)) return Result.success(Unit)

        return loadMutex.withLock {
            if (isSameEngineActive(modelPath, doc, key, lanEnabled, requestedPort)) return Result.success(Unit)

            withContext(Dispatchers.IO) { unload() }

            val token = synchronized(lock) {
                stateToken++
                unloading = false
                currentModelPath = modelPath
                currentDoc = doc
                currentApiKey = key
                currentLanEnabled = lanEnabled
                stateToken
            }
            if (!publish(token) { _state.value = EngineState.Loading(modelPath) }) {
                return Result.failure(IllegalStateException(ENGINE_ABORTED))
            }

            val port = try {
                requestedPort ?: ServerSocket(0).use { it.localPort }
            } catch (e: IOException) {
                failLoad(token, appContext.getString(R.string.engine_error_port, e.message ?: e.javaClass.simpleName))
                return Result.failure(e)
            }
            publish(token) { currentPort = port }

            val host = if (lanEnabled) HOST_LAN else HOST
            val binary = File(appContext.applicationInfo.nativeLibraryDir, ENGINE_BINARY)
            val spawnRef = AtomicReference<Process?>(null)
            val started = try {
                withContext(Dispatchers.IO) {
                    startProcess(binary, modelPath, doc, host, port, key).also(spawnRef::set)
                }
            } catch (e: CancellationException) {
                spawnRef.get()?.destroyForcibly()
                throw e
            } catch (e: IOException) {
                failLoad(token, appContext.getString(R.string.engine_error_start, e.message ?: e.javaClass.simpleName))
                return Result.failure(e)
            }

            if (!registerProcess(started, token)) {
                started.destroyForcibly()
                return Result.failure(IllegalStateException(ENGINE_ABORTED))
            }

            val exited = AtomicBoolean(false)
            val exitCode = AtomicInteger(0)
            val reader = startLogReader(started)
            startExitWatcher(started, token, exited, exitCode)

            try {
                if (awaitHealthy(port, started, exited)) {
                    if (publish(token) { _state.value = EngineState.Ready(modelPath, port) }) {
                        return Result.success(Unit)
                    }
                    started.destroyForcibly()
                    return Result.failure(IllegalStateException(ENGINE_ABORTED))
                }

                started.destroyForcibly()
                joinReader(reader)
                val reason = if (exited.get()) {
                    exitMessage(exitCode.get())
                } else {
                    appContext.getString(R.string.engine_error_health_timeout, (HEALTH_TIMEOUT_MS / 1000L).toInt())
                }
                val message = reason + "\n" + appContext.getString(R.string.engine_error_log_tail, logTail())
                failLoad(token, message)
                return Result.failure(IllegalStateException(message))
            } catch (e: CancellationException) {
                started.destroyForcibly()
                synchronized(lock) {
                    if (process === started) {
                        process = null
                        currentModelPath = null
                        currentDoc = null
                        currentApiKey = null
                        currentLanEnabled = false
                        currentPort = null
                    }
                }
                publish(token) {
                    if (_state.value is EngineState.Loading) {
                        _state.value = EngineState.Unloaded
                    }
                }
                throw e
            }
        }
    }

    fun unload() {
        val (token, previous) = synchronized(lock) {
            unloading = true
            stateToken++
            val current = process
            process = null
            currentModelPath = null
            currentDoc = null
            currentApiKey = null
            currentLanEnabled = false
            currentPort = null
            stateToken to current
        }
        if (previous != null) {
            previous.destroy()
            try {
                if (!previous.waitFor(UNLOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    previous.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                previous.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
        synchronized(lock) {
            if (stateToken == token) {
                _state.value = EngineState.Unloaded
            }
        }
    }

    fun recentLog(): List<String> = logRing.snapshot()

    private fun isSameEngineActive(
        modelPath: String,
        doc: ProfileDoc,
        apiKey: String?,
        lanEnabled: Boolean,
        port: Int?,
    ): Boolean = synchronized(lock) {
        currentModelPath == modelPath &&
            currentDoc == doc &&
            currentApiKey == apiKey &&
            currentLanEnabled == lanEnabled &&
            (port == null || currentPort == port) &&
            (_state.value is EngineState.Ready || _state.value is EngineState.Loading)
    }

    private fun registerProcess(started: Process, token: Long): Boolean = synchronized(lock) {
        if (stateToken != token) {
            false
        } else {
            process = started
            true
        }
    }

    private fun publish(token: Long, block: () -> Unit): Boolean = synchronized(lock) {
        if (stateToken != token) {
            false
        } else {
            block()
            true
        }
    }

    private fun failLoad(token: Long, message: String) {
        publish(token) {
            process = null
            currentModelPath = null
            currentDoc = null
            currentApiKey = null
            currentLanEnabled = false
            currentPort = null
            _state.value = EngineState.Error(message)
        }
    }

    private fun startProcess(
        binary: File,
        modelPath: String,
        doc: ProfileDoc,
        host: String,
        port: Int,
        apiKey: String?,
    ): Process {
        val args = ServerArgBuilder.build(modelPath, doc, host, port, apiKey)
        val builder = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(appContext.filesDir)
            .redirectErrorStream(true)
        val environment = builder.environment()
        environment.putAll(ServerArgBuilder.buildEnv(doc))
        val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
        val stage = NpuSupport.stage(appContext)
        environment[LD_LIBRARY_PATH] = NpuSupport.libraryPathFor(nativeLibraryDir, stage.dir, stage.staged)
        environment[ADSP_LIBRARY_PATH] = nativeLibraryDir
        return builder.start()
    }

    private fun startLogReader(started: Process): Thread {
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(started.inputStream)).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        logRing.add(line)
                        if (isMemoryLogLine(line)) Log.i(LOGCAT_TAG, line)
                    }
                }
            } catch (_: IOException) {
            }
        }, THREAD_LOG_READER)
        reader.isDaemon = true
        reader.start()
        return reader
    }

    private fun exitMessage(code: Int): String = when (classifyEngineExit(code)) {
        EngineExitKind.KILLED -> appContext.getString(R.string.engine_error_exit_killed, code)
        EngineExitKind.CRASHED -> appContext.getString(R.string.engine_error_exit_crashed, exitSignal(code) ?: code)
        EngineExitKind.EXITED -> appContext.getString(R.string.engine_error_exit, code)
    }

    private fun startExitWatcher(
        started: Process,
        token: Long,
        exited: AtomicBoolean,
        exitCode: AtomicInteger,
    ) {
        val watcher = Thread({
            val code = try {
                started.waitFor()
            } catch (_: InterruptedException) {
                return@Thread
            }
            exitCode.set(code)
            exited.set(true)
            publish(token) {
                if (process === started) {
                    _state.value = EngineState.Error(exitMessage(code))
                }
            }
        }, THREAD_EXIT_WATCHER)
        watcher.isDaemon = true
        watcher.start()
    }

    private suspend fun awaitHealthy(port: Int, started: Process, exited: AtomicBoolean): Boolean =
        withTimeoutOrNull(HEALTH_TIMEOUT_MS) {
            var healthy = false
            while (!healthy && !exited.get() && started.isAlive) {
                healthy = withContext(Dispatchers.IO) { probeHealth(port) }
                if (!healthy) {
                    delay(HEALTH_POLL_INTERVAL_MS)
                }
            }
            healthy
        } ?: false

    private fun probeHealth(port: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("http://$HOST:$port/health").openConnection() as HttpURLConnection
            connection.connectTimeout = HEALTH_CONNECT_TIMEOUT_MS
            connection.readTimeout = HEALTH_READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun joinReader(reader: Thread) {
        withContext(Dispatchers.IO) {
            try {
                reader.join(READER_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun logTail(): String = logRing.snapshot().takeLast(LOG_TAIL_LINES).joinToString("\n")

    companion object {

        private const val HOST = "127.0.0.1"
        private const val HOST_LAN = "0.0.0.0"
        private const val ENGINE_BINARY = "libllama-server.so"
        private const val LD_LIBRARY_PATH = "LD_LIBRARY_PATH"
        private const val LOGCAT_TAG = "LlamaServer"
        private const val ADSP_LIBRARY_PATH = "ADSP_LIBRARY_PATH"
        private const val THREAD_LOG_READER = "engine-log-reader"
        private const val THREAD_EXIT_WATCHER = "engine-exit-watcher"
        private const val LOG_RING_CAPACITY = 500
        private const val LOG_TAIL_LINES = 10
        private const val HEALTH_POLL_INTERVAL_MS = 500L
        private const val HEALTH_TIMEOUT_MS = 180_000L
        private const val HEALTH_CONNECT_TIMEOUT_MS = 2_000
        private const val HEALTH_READ_TIMEOUT_MS = 2_000
        private const val UNLOAD_WAIT_MS = 5_000L
        private const val READER_JOIN_MS = 500L
        private const val ENGINE_ABORTED = "Engine was unloaded while loading"
    }
}
