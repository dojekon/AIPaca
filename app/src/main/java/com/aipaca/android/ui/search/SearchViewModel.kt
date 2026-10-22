package com.aipaca.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aipaca.android.AppContainer
import com.aipaca.android.data.db.dao.BookmarkDao
import com.aipaca.android.data.db.entity.BookmarkEntity
import com.aipaca.android.data.download.DownloadManager
import com.aipaca.android.data.hf.HfApi
import com.aipaca.android.data.hf.HfGgufFile
import com.aipaca.android.data.hf.HfModelSummary
import com.aipaca.android.data.hf.detectTools
import com.aipaca.android.data.hf.ggufFiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SearchViewModel(
    private val hfApi: HfApi,
    private val downloadManager: DownloadManager,
    private val bookmarkDao: BookmarkDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _results = MutableStateFlow<List<HfModelSummary>>(emptyList())
    val results: StateFlow<List<HfModelSummary>> = _results.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _detailsOf = MutableStateFlow<String?>(null)
    val detailsOf: StateFlow<String?> = _detailsOf.asStateFlow()

    private val _loadingDetails = MutableStateFlow(false)
    val loadingDetails: StateFlow<Boolean> = _loadingDetails.asStateFlow()

    private val _detailFiles = MutableStateFlow<List<HfGgufFile>>(emptyList())
    val detailFiles: StateFlow<List<HfGgufFile>> = _detailFiles.asStateFlow()

    private val _detailsTools = MutableStateFlow<Boolean?>(null)
    val detailsTools: StateFlow<Boolean?> = _detailsTools.asStateFlow()

    private val _detailsError = MutableStateFlow<String?>(null)
    val detailsError: StateFlow<String?> = _detailsError.asStateFlow()

    private var searchJob: Job? = null
    private var detailsJob: Job? = null

    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    val downloadingUrls: StateFlow<Set<String>> = downloadManager.progress
        .map { items ->
            items
                .filter { it.state == DownloadManager.STATE_QUEUED || it.state == DownloadManager.STATE_RUNNING || it.state == DownloadManager.STATE_PAUSED }
                .map { it.fileName }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptySet())

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun search() {
        val term = _query.value.trim()
        if (term.isEmpty()) {
            searchJob?.cancel()
            _results.value = emptyList()
            _error.value = null
            _searching.value = false
            return
        }
        closeRepo()
        searchJob?.cancel()
        _results.value = emptyList()
        _error.value = null
        _searching.value = true
        searchJob = viewModelScope.launch {
            val fetched = hfApi.searchModels(term)
            if (!isActive) return@launch
            fetched.fold(
                onSuccess = { _results.value = it },
                onFailure = { _error.value = it.message ?: SEARCH_FAILED },
            )
            _searching.value = false
        }
    }

    fun openRepo(repoId: String) {
        detailsJob?.cancel()
        _detailsOf.value = repoId
        _loadingDetails.value = true
        _detailFiles.value = emptyList()
        _detailsTools.value = null
        _detailsError.value = null
        detailsJob = viewModelScope.launch {
            val fetched = hfApi.modelDetails(repoId)
            if (!isActive) return@launch
            val details = fetched.getOrNull()
            if (details == null) {
                _detailsError.value = fetched.exceptionOrNull()?.message ?: DETAILS_FAILED
                _loadingDetails.value = false
                return@launch
            }
            _detailFiles.value = details.ggufFiles(repoId)
            _loadingDetails.value = false
            val template = hfApi.fetchChatTemplate(repoId).getOrNull()
            if (!isActive) return@launch
            _detailsTools.value = detectTools(
                details.tags,
                details.siblings.map { it.rfilename },
                template,
            )
        }
    }

    fun closeRepo() {
        detailsJob?.cancel()
        _detailsOf.value = null
        _loadingDetails.value = false
        _detailFiles.value = emptyList()
        _detailsTools.value = null
        _detailsError.value = null
    }

    fun toggleBookmark(repoId: String) {
        viewModelScope.launch {
            if (bookmarkDao.get(repoId) == null) {
                bookmarkDao.upsert(BookmarkEntity(repoId, System.currentTimeMillis()))
            } else {
                bookmarkDao.delete(repoId)
            }
        }
    }

    fun download(file: HfGgufFile, repoId: String) {
        if (file.fileName in downloadingUrls.value) return
        viewModelScope.launch {
            downloadManager.enqueue(file.downloadUrl, file.fileName, file.sizeBytes, file.sha256, repoId)
        }
    }

    companion object {

        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        private const val SEARCH_FAILED = "Search failed"
        private const val DETAILS_FAILED = "Could not load model files"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchViewModel(container.hfApi, container.downloadManager, container.database.bookmarkDao())
            }
        }
    }
}
