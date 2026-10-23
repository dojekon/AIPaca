package com.aipaca.android.ui.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AiPacaApp
import com.aipaca.android.R
import com.aipaca.android.engine.agent.tools.FileWorkspace
import com.aipaca.android.engine.agent.tools.WorkspaceEntry
import com.aipaca.android.engine.agent.tools.agentSandboxRoot
import com.aipaca.android.engine.agent.tools.resolveInSandbox
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkspaceViewModel(private val appContext: Context) : ViewModel() {

    data class PreviewUi(val path: String, val text: String?, val truncated: Boolean)

    private val root: File = agentSandboxRoot(appContext.filesDir).apply { mkdirs() }
    private val workspace = FileWorkspace(root)

    private val _directory = MutableStateFlow("")
    val directory: StateFlow<String> = _directory.asStateFlow()

    private val _entries = MutableStateFlow<List<WorkspaceEntry>>(emptyList())
    val entries: StateFlow<List<WorkspaceEntry>> = _entries.asStateFlow()

    private val _preview = MutableStateFlow<PreviewUi?>(null)
    val preview: StateFlow<PreviewUi?> = _preview.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val listed = workspace.list(_directory.value).getOrElse {
                report(it)
                emptyList()
            }
            _entries.value = sortWorkspaceEntries(listed)
        }
    }

    fun open(entry: WorkspaceEntry) {
        if (entry.isDirectory) {
            _directory.value = entry.path
            refresh()
        } else {
            viewModelScope.launch { _preview.value = loadPreview(entry.path) }
        }
    }

    fun up() {
        if (_directory.value.isEmpty()) return
        _directory.value = parentPath(_directory.value)
        refresh()
    }

    fun closePreview() {
        _preview.value = null
    }

    fun delete(entry: WorkspaceEntry) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                resolveInSandbox(root, entry.path).mapCatching { file ->
                    check(file.canonicalFile != root.canonicalFile) { "Cannot delete the workspace root" }
                    check(file.deleteRecursively()) { "Delete failed" }
                }
            }
            result.onFailure(::report)
            refresh()
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = resolveInSandbox(root, _directory.value).getOrThrow()
                    val existing = dir.list().orEmpty().toSet()
                    val name = uniqueName(existing, sanitizeImportName(displayName(uri)))
                    val target = resolveInSandbox(root, childPath(_directory.value, name)).getOrThrow()
                    val input = appContext.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                    name
                }
            }
            result.onSuccess { _messages.trySend(appContext.getString(R.string.workspace_imported, it)) }
                .onFailure(::report)
            refresh()
        }
    }

    fun export(path: String, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = resolveInSandbox(root, path).getOrThrow()
                    val output = appContext.contentResolver.openOutputStream(uri) ?: error("Cannot open $uri")
                    output.use { sink -> file.inputStream().use { it.copyTo(sink) } }
                }
            }
            result.onSuccess { _messages.trySend(appContext.getString(R.string.workspace_exported)) }
                .onFailure(::report)
        }
    }

    private suspend fun loadPreview(path: String): PreviewUi = withContext(Dispatchers.IO) {
        val file = resolveInSandbox(root, path).getOrNull()
        if (file == null || !file.isFile) return@withContext PreviewUi(path, null, false)
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), PREVIEW_MAX_BYTES.toLong()).toInt())
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            buffer.copyOf(read)
        }
        val text = if (looksBinary(bytes)) null else String(bytes, Charsets.UTF_8)
        PreviewUi(path, text, file.length() > PREVIEW_MAX_BYTES)
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment

    private fun report(error: Throwable) {
        if (error is CancellationException) throw error
        _messages.trySend(appContext.getString(R.string.workspace_error, error.message ?: error.javaClass.simpleName))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AiPacaApp
                WorkspaceViewModel(app.applicationContext)
            }
        }
    }
}
