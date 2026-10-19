package com.aipaca.android.data.profiles.remote

import com.aipaca.android.data.db.dao.ProfileCacheDao
import com.aipaca.android.data.db.entity.ProfileCacheEntity
import com.aipaca.android.engine.profiles.ProfileCatalog
import com.aipaca.android.engine.profiles.ProfileCatalogLoader
import com.aipaca.android.engine.profiles.ProfileIndexDoc
import com.aipaca.android.engine.profiles.ProfileParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

private const val GITHUB_HOST = "github.com"

private const val RAW_HOST = "raw.githubusercontent.com"

private const val DEFAULT_BRANCH = "main"

sealed interface SyncState {

    data object Idle : SyncState

    data object Syncing : SyncState

    data class Ok(val profileCount: Int, val fetchedAt: Long) : SyncState

    data class Error(val message: String) : SyncState
}

data class SyncResult(
    val fetched: Int,
    val cached: Int,
    val profileCount: Int,
    val deviceCount: Int,
    val fetchedAt: Long,
)

fun toRawBase(repoUrl: String): String {
    val trimmed = repoUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
    if (withoutScheme.startsWith(RAW_HOST)) return "https://${withoutScheme.trimEnd('/')}/"
    if (!withoutScheme.startsWith(GITHUB_HOST)) return trimmed.trimEnd('/') + "/"
    val parts = withoutScheme.removePrefix(GITHUB_HOST).trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return ""
    val owner = parts[0]
    val repo = parts[1].removeSuffix(".git")
    val subPath = parts.drop(2)
    val base = "https://$RAW_HOST/$owner/$repo/$DEFAULT_BRANCH/"
    return if (subPath.isEmpty()) base else base + subPath.joinToString("/") + "/"
}

class ProfileRepository(
    private val fetcher: ProfileFetcher,
    private val cacheDao: ProfileCacheDao,
    private val wifiCheck: () -> Boolean = { true },
    private val baseUrlProvider: () -> String,
) {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    fun shouldAutoRefresh(lastFetchedAt: Long?, now: Long): Boolean {
        if (lastFetchedAt == null) return false
        return now - lastFetchedAt >= AUTO_REFRESH_INTERVAL_MS
    }

    suspend fun maybeAutoRefresh(): Result<SyncResult>? {
        val base = toRawBase(baseUrlProvider())
        if (base.isEmpty()) return null
        val lastFetchedAt = (_syncState.value as? SyncState.Ok)?.fetchedAt
            ?: cacheDao.get(INDEX_KEY)?.fetchedAt
        if (!shouldAutoRefresh(lastFetchedAt, System.currentTimeMillis())) return null
        if (!wifiCheck()) return null
        return refresh()
    }

    suspend fun refresh(): Result<SyncResult> {
        val base = toRawBase(baseUrlProvider())
        if (base.isEmpty()) {
            _syncState.value = SyncState.Error(NOT_CONFIGURED_MESSAGE)
            return Result.failure(IllegalStateException(NOT_CONFIGURED_MESSAGE))
        }
        _syncState.value = SyncState.Syncing
        return try {
            val result = sync(base)
            _syncState.value = SyncState.Ok(result.profileCount, result.fetchedAt)
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    suspend fun loadCached(): Result<ProfileCatalog> {
        return try {
            val indexEntry = cacheDao.get(INDEX_KEY)
                ?: throw IllegalStateException("no cached profiles index")
            val catalog = catalogFrom(indexEntry.json, loadCachedFiles(indexEntry.json))
            _syncState.value = SyncState.Ok(catalog.profiles.size, indexEntry.fetchedAt)
            Result.success(catalog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _syncState.value = SyncState.Error(e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    fun mergedCatalog(bundled: ProfileCatalog, remote: ProfileCatalog): ProfileCatalog {
        val bundledIds = bundled.profiles.map { it.doc.id }.toSet()
        val bundledPaths = bundled.profiles.map { it.sourcePath }.toSet()
        val extraProfiles = remote.profiles.filter {
            it.doc.id !in bundledIds && it.sourcePath !in bundledPaths
        }
        val devices = LinkedHashMap(bundled.devices)
        remote.devices.forEach { (key, spec) -> devices.putIfAbsent(key, spec) }
        return ProfileCatalog(bundled.index, devices, bundled.profiles + extraProfiles)
    }

    private suspend fun sync(base: String): SyncResult {
        val indexEntity = fetchIndex(base)
        val index = parseIndex(indexEntity.text)
        val files = LinkedHashMap<String, String>()
        var fetched = 0
        var cached = 0
        for (path in referencedFiles(index)) {
            val cachedEntry = cacheDao.get(path)
            when (val result = fetcher.get(base + path, cachedEntry?.etag)) {
                is ProfileFetcher.FetchResult.NotModified -> {
                    val body = cachedEntry?.json
                        ?: throw IllegalStateException("server returned 304 for uncached file: $path")
                    files[path] = body
                    cached++
                }
                is ProfileFetcher.FetchResult.Ok -> {
                    cacheDao.upsert(ProfileCacheEntity(path, result.body, result.etag, indexEntity.fetchedAt))
                    files[path] = result.body
                    fetched++
                }
                is ProfileFetcher.FetchResult.Error ->
                    throw IllegalStateException("failed to fetch $path: ${result.message}")
            }
        }
        val catalog = catalogFrom(indexEntity.text, files)
        return SyncResult(
            fetched = fetched,
            cached = cached,
            profileCount = catalog.profiles.size,
            deviceCount = catalog.devices.size,
            fetchedAt = indexEntity.fetchedAt,
        )
    }

    private suspend fun fetchIndex(base: String): CachedIndex {
        val cachedEntry = cacheDao.get(INDEX_KEY)
        return when (val result = fetcher.get(base + INDEX_KEY, cachedEntry?.etag)) {
            is ProfileFetcher.FetchResult.NotModified -> {
                val cachedIndex = cachedEntry
                    ?: throw IllegalStateException("server returned 304 for uncached $INDEX_KEY")
                parseIndex(cachedIndex.json)
                CachedIndex(cachedIndex.json, cachedIndex.fetchedAt)
            }
            is ProfileFetcher.FetchResult.Ok -> {
                parseIndex(result.body)
                val fetchedAt = System.currentTimeMillis()
                cacheDao.upsert(ProfileCacheEntity(INDEX_KEY, result.body, result.etag, fetchedAt))
                CachedIndex(result.body, fetchedAt)
            }
            is ProfileFetcher.FetchResult.Error ->
                throw IllegalStateException("failed to fetch $INDEX_KEY: ${result.message}")
        }
    }

    private suspend fun loadCachedFiles(indexText: String): Map<String, String> {
        val index = parseIndex(indexText)
        val files = LinkedHashMap<String, String>()
        for (path in referencedFiles(index)) {
            val entry = cacheDao.get(path)
                ?: throw IllegalStateException("missing cached profile file: $path")
            files[path] = entry.json
        }
        return files
    }

    private fun parseIndex(text: String): ProfileIndexDoc {
        val index = ProfileParser.parseIndex(text)
        if (index.schemaVersion != ProfileParser.SCHEMA_VERSION) {
            throw IllegalStateException(
                "unsupported schema version ${index.schemaVersion}, expected ${ProfileParser.SCHEMA_VERSION}",
            )
        }
        return index
    }

    private fun catalogFrom(indexText: String, files: Map<String, String>): ProfileCatalog =
        try {
            ProfileCatalogLoader.fromJson(indexText) { path -> files[path] }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(e.message ?: "invalid profile catalog", e)
        }

    private fun referencedFiles(index: ProfileIndexDoc): List<String> =
        (index.devices.map { it.file } + index.profiles.map { it.file }).distinct()

    private data class CachedIndex(val text: String, val fetchedAt: Long)

    companion object {

        const val INDEX_KEY = "index.json"

        const val AUTO_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

        private const val NOT_CONFIGURED_MESSAGE = "profiles repo not configured"
    }
}
