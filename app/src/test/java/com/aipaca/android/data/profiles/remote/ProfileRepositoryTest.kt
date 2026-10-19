package com.aipaca.android.data.profiles.remote

import com.aipaca.android.data.db.dao.ProfileCacheDao
import com.aipaca.android.data.db.entity.ProfileCacheEntity
import com.aipaca.android.engine.profiles.DeviceSpecDoc
import com.aipaca.android.engine.profiles.LoadedProfile
import com.aipaca.android.engine.profiles.ProfileCatalog
import com.aipaca.android.engine.profiles.ProfileIndexDoc
import com.aipaca.android.engine.profiles.ProfileParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryTest {

    @Test
    fun toRawBaseConvertsGithubUrl() {
        assertEquals("https://raw.githubusercontent.com/user/repo/main/", toRawBase("https://github.com/user/repo"))
        assertEquals("https://raw.githubusercontent.com/user/repo/main/", toRawBase("https://github.com/user/repo/"))
    }

    @Test
    fun toRawBaseKeepsRawUrlAndNormalizesSlash() {
        assertEquals("https://raw.githubusercontent.com/user/repo/main/", toRawBase("https://raw.githubusercontent.com/user/repo/main/"))
        assertEquals("https://raw.githubusercontent.com/user/repo/main/", toRawBase("https://raw.githubusercontent.com/user/repo/main"))
        assertEquals("https://raw.githubusercontent.com/user/repo/main/sub/", toRawBase("https://raw.githubusercontent.com/user/repo/main/sub"))
    }

    @Test
    fun toRawBaseConvertsGithubSubdirectoryAndDotGit() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/profiles/",
            toRawBase("https://github.com/user/repo/profiles"),
        )
        assertEquals("https://raw.githubusercontent.com/user/repo/main/", toRawBase("https://github.com/user/repo.git"))
    }

    @Test
    fun toRawBaseKeepsCustomHttpHost() {
        assertEquals("https://profiles.example.com/base/", toRawBase("https://profiles.example.com/base"))
    }

    @Test
    fun toRawBaseReturnsEmptyForBlankOrHostOnly() {
        assertEquals("", toRawBase(""))
        assertEquals("", toRawBase("   "))
        assertEquals("", toRawBase("https://github.com/user"))
    }

    @Test
    fun refreshFetchesIndexAndReferencedFiles() = runBlocking {
        val fetcher = remoteFetcher()
        val dao = FakeProfileCacheDao()
        val repository = ProfileRepository(fetcher, dao) { GITHUB_URL }

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        val sync = result.getOrThrow()
        assertEquals(3, sync.fetched)
        assertEquals(0, sync.cached)
        assertEquals(2, sync.profileCount)
        assertEquals(1, sync.deviceCount)
        assertTrue(sync.fetchedAt > 0L)
        assertEquals(4, dao.entries.size)
        assertEquals("\"index-1\"", dao.entries.getValue("index.json").etag)
        assertEquals("\"dev-1\"", dao.entries.getValue("devices/sm8650-12gb.json").etag)
        assertNull(fetcher.requests.first().second)
        val state = repository.syncState.value
        assertTrue(state is SyncState.Ok)
        assertEquals(2, (state as SyncState.Ok).profileCount)
    }

    @Test
    fun refreshUsesCachedFilesWhenNotModified() = runBlocking {
        val dao = seededCacheDao()
        val fetcher = FakeProfileFetcher()
        referencedFiles().forEach { path -> fetcher.notModified(BASE + path) }
        val repository = ProfileRepository(fetcher, dao) { GITHUB_URL }

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        val sync = result.getOrThrow()
        assertEquals(0, sync.fetched)
        assertEquals(3, sync.cached)
        assertEquals(2, sync.profileCount)
        assertEquals(1_000L, sync.fetchedAt)
        val etags = fetcher.requests.toMap()
        assertEquals("\"index-1\"", etags[BASE + "index.json"])
        assertEquals("\"dev-1\"", etags[BASE + "devices/sm8650-12gb.json"])
        assertEquals("\"p1\"", etags[BASE + "profiles/qwen3-4b-q4_0-sm8650.json"])
    }

    @Test
    fun refreshFailsOnUnsupportedSchemaVersion() = runBlocking {
        val fetcher = FakeProfileFetcher()
        fetcher.ok(BASE + "index.json", indexJson(schemaVersion = 2), "\"index-1\"")
        val dao = FakeProfileCacheDao()
        val repository = ProfileRepository(fetcher, dao) { GITHUB_URL }

        val result = repository.refresh()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("unsupported schema version"))
        assertEquals(1, fetcher.requests.size)
        assertTrue(dao.entries.isEmpty())
        assertTrue(repository.syncState.value is SyncState.Error)
    }

    @Test
    fun refreshFailsWhenRepoNotConfigured() = runBlocking {
        val fetcher = FakeProfileFetcher()
        val repository = ProfileRepository(fetcher, FakeProfileCacheDao()) { "" }

        val result = repository.refresh()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("profiles repo not configured", result.exceptionOrNull()!!.message)
        assertTrue(fetcher.requests.isEmpty())
        assertTrue(repository.syncState.value is SyncState.Error)
    }

    @Test
    fun refreshFailsWhenNotModifiedAndCacheMissing() = runBlocking {
        val dao = FakeProfileCacheDao()
        dao.upsert(ProfileCacheEntity("index.json", indexJson(), "\"index-1\"", 1_000L))
        val fetcher = FakeProfileFetcher()
        fetcher.notModified(BASE + "index.json")
        fetcher.notModified(BASE + "devices/sm8650-12gb.json")
        val repository = ProfileRepository(fetcher, dao) { GITHUB_URL }

        val result = repository.refresh()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("devices/sm8650-12gb.json"))
        assertTrue(repository.syncState.value is SyncState.Error)
    }

    @Test
    fun refreshFailsWhenFileCannotBeFetched() = runBlocking {
        val fetcher = FakeProfileFetcher()
        fetcher.ok(BASE + "index.json", indexJson(), "\"index-1\"")
        fetcher.ok(BASE + "devices/sm8650-12gb.json", deviceJson, "\"dev-1\"")
        fetcher.error(BASE + "profiles/qwen3-4b-q4_0-sm8650.json", "HTTP 500")
        val repository = ProfileRepository(fetcher, FakeProfileCacheDao()) { GITHUB_URL }

        val result = repository.refresh()

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue(message.contains("profiles/qwen3-4b-q4_0-sm8650.json"))
        assertTrue(message.contains("HTTP 500"))
    }

    @Test
    fun mergedCatalogKeepsBundledFirstAndAddsRemote() {
        val bundledIndex = ProfileIndexDoc()
        val bundled = ProfileCatalog(
            index = bundledIndex,
            devices = mapOf("qcom-sm8650/12gb" to deviceSpec("qcom-sm8650/12gb", ramGb = 12)),
            profiles = listOf(
                loadedProfile("a", "profiles/a.json"),
                loadedProfile("b", "profiles/b.json"),
            ),
        )
        val remote = ProfileCatalog(
            index = ProfileIndexDoc(updatedAt = "2026-10-17"),
            devices = mapOf(
                "qcom-sm8650/12gb" to deviceSpec("qcom-sm8650/12gb", ramGb = 99),
                "generic-arm64/8gb" to deviceSpec("generic-arm64/8gb", ramGb = 8),
            ),
            profiles = listOf(
                loadedProfile("a", "profiles/a-remote.json"),
                loadedProfile("c", "profiles/b.json"),
                loadedProfile("d", "profiles/d.json"),
            ),
        )
        val repository = ProfileRepository(FakeProfileFetcher(), FakeProfileCacheDao()) { GITHUB_URL }

        val merged = repository.mergedCatalog(bundled, remote)

        assertSame(bundled.index, merged.index)
        assertEquals(listOf("a", "b", "d"), merged.profiles.map { it.doc.id })
        assertEquals(12, merged.devices.getValue("qcom-sm8650/12gb").ramGb)
        assertEquals(8, merged.devices.getValue("generic-arm64/8gb").ramGb)
        assertEquals(2, merged.devices.size)
    }

    @Test
    fun loadCachedBuildsCatalogAfterRefresh() = runBlocking {
        val dao = FakeProfileCacheDao()
        val repository = ProfileRepository(remoteFetcher(), dao) { GITHUB_URL }
        assertTrue(repository.refresh().isSuccess)

        val loaded = repository.loadCached()

        assertTrue(loaded.isSuccess)
        val catalog = loaded.getOrThrow()
        assertEquals(2, catalog.profiles.size)
        assertEquals(1, catalog.devices.size)
        assertEquals("qwen3-4b/q4_0/sm8650-12gb", catalog.profiles.first().doc.id)
        val indexEntry = dao.entries.getValue("index.json")
        val state = repository.syncState.value
        assertTrue(state is SyncState.Ok)
        assertEquals(2, (state as SyncState.Ok).profileCount)
        assertEquals(indexEntry.fetchedAt, state.fetchedAt)
    }

    @Test
    fun loadCachedFailsWithoutCachedIndex() = runBlocking {
        val repository = ProfileRepository(FakeProfileFetcher(), FakeProfileCacheDao()) { GITHUB_URL }

        val result = repository.loadCached()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("no cached"))
        assertTrue(repository.syncState.value is SyncState.Error)
    }

    private fun remoteFetcher(): FakeProfileFetcher {
        val fetcher = FakeProfileFetcher()
        fetcher.ok(BASE + "index.json", indexJson(), "\"index-1\"")
        fetcher.ok(BASE + "devices/sm8650-12gb.json", deviceJson, "\"dev-1\"")
        fetcher.ok(BASE + "profiles/qwen3-4b-q4_0-sm8650.json", qwenProfileJson, "\"p1\"")
        fetcher.ok(BASE + "profiles/default-generic.json", defaultProfileJson, "\"p2\"")
        return fetcher
    }

    private suspend fun seededCacheDao(): FakeProfileCacheDao {
        val dao = FakeProfileCacheDao()
        dao.upsert(ProfileCacheEntity("index.json", indexJson(), "\"index-1\"", 1_000L))
        dao.upsert(ProfileCacheEntity("devices/sm8650-12gb.json", deviceJson, "\"dev-1\"", 1_000L))
        dao.upsert(ProfileCacheEntity("profiles/qwen3-4b-q4_0-sm8650.json", qwenProfileJson, "\"p1\"", 1_000L))
        dao.upsert(ProfileCacheEntity("profiles/default-generic.json", defaultProfileJson, "\"p2\"", 1_000L))
        return dao
    }

    private class FakeProfileFetcher : ProfileFetcher {

        val responses = mutableMapOf<String, ProfileFetcher.FetchResult>()

        val requests = mutableListOf<Pair<String, String?>>()

        fun ok(url: String, body: String, etag: String? = null) {
            responses[url] = ProfileFetcher.FetchResult.Ok(body, etag)
        }

        fun notModified(url: String) {
            responses[url] = ProfileFetcher.FetchResult.NotModified
        }

        fun error(url: String, message: String) {
            responses[url] = ProfileFetcher.FetchResult.Error(message)
        }

        override suspend fun get(url: String, etag: String?): ProfileFetcher.FetchResult {
            requests += url to etag
            return responses[url] ?: ProfileFetcher.FetchResult.Error("unexpected request: $url")
        }
    }

    private class FakeProfileCacheDao : ProfileCacheDao {

        val entries = mutableMapOf<String, ProfileCacheEntity>()

        override suspend fun upsert(entry: ProfileCacheEntity) {
            entries[entry.key] = entry
        }

        override suspend fun delete(entry: ProfileCacheEntity) {
            entries.remove(entry.key)
        }

        override suspend fun get(key: String): ProfileCacheEntity? = entries[key]

        override suspend fun deleteAll() {
            entries.clear()
        }

        override fun observeAll(): Flow<List<ProfileCacheEntity>> =
            MutableStateFlow(entries.values.toList())
    }

    private companion object {

        const val GITHUB_URL = "https://github.com/user/repo"

        const val BASE = "https://raw.githubusercontent.com/user/repo/main/"

        val qwenProfileJson = profileJson("qwen3-4b/q4_0/sm8650-12gb", "qwen3-4b")

        val defaultProfileJson = profileJson("default/generic-arm64", "default")

        val deviceJson = """
            {
              "key": "qcom-sm8650/12gb",
              "soc": "SM8650",
              "vendor": "qcom",
              "ramGb": 12,
              "htpVersion": 75
            }
        """.trimIndent()

        fun referencedFiles(): List<String> = listOf(
            "index.json",
            "devices/sm8650-12gb.json",
            "profiles/qwen3-4b-q4_0-sm8650.json",
            "profiles/default-generic.json",
        )

        fun indexJson(schemaVersion: Int = 1): String = """
            {
              "schemaVersion": $schemaVersion,
              "updatedAt": "2026-10-17",
              "devices": [
                {
                  "key": "qcom-sm8650/12gb",
                  "soc": "SM8650",
                  "vendor": "qcom",
                  "ramGb": 12,
                  "file": "devices/sm8650-12gb.json"
                }
              ],
              "profiles": [
                {
                  "id": "qwen3-4b/q4_0/sm8650-12gb",
                  "family": "qwen3-4b",
                  "device": "qcom-sm8650/12gb",
                  "quants": ["Q4_0"],
                  "file": "profiles/qwen3-4b-q4_0-sm8650.json"
                },
                {
                  "id": "default/generic-arm64",
                  "family": "default",
                  "device": "generic-arm64/8gb",
                  "quants": [],
                  "file": "profiles/default-generic.json"
                }
              ]
            }
        """.trimIndent()

        fun profileJson(id: String, family: String): String = """
            {
              "schemaVersion": 1,
              "id": "$id",
              "backend": "cpu",
              "model": {
                "family": "$family",
                "matchPatterns": [".*$family.*"],
                "quants": ["Q4_0"]
              },
              "launch": { "nCtx": 4096, "threads": 4 }
            }
        """.trimIndent()

        fun deviceSpec(key: String, ramGb: Int): DeviceSpecDoc =
            DeviceSpecDoc(key = key, soc = "SM8650", vendor = "qcom", ramGb = ramGb)

        fun loadedProfile(id: String, sourcePath: String): LoadedProfile =
            LoadedProfile(ProfileParser.parseProfile(profileJson(id, "family-$id")), sourcePath)
    }
}
