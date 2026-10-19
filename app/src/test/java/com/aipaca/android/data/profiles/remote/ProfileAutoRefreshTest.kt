package com.aipaca.android.data.profiles.remote

import com.aipaca.android.data.db.dao.ProfileCacheDao
import com.aipaca.android.data.db.entity.ProfileCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAutoRefreshTest {

    private val repository = ProfileRepository(
        fetcher = NoopFetcher,
        cacheDao = EmptyCacheDao,
        baseUrlProvider = { "" },
    )

    @Test
    fun missingCacheIsNotRefreshed() {
        assertFalse(repository.shouldAutoRefresh(null, NOW))
    }

    @Test
    fun cacheYoungerThanADayIsNotRefreshed() {
        assertFalse(repository.shouldAutoRefresh(NOW - 23 * HOUR, NOW))
    }

    @Test
    fun cacheOlderThanADayIsRefreshed() {
        assertTrue(repository.shouldAutoRefresh(NOW - 25 * HOUR, NOW))
    }

    @Test
    fun cacheExactlyOneDayOldIsRefreshed() {
        assertTrue(repository.shouldAutoRefresh(NOW - 24 * HOUR, NOW))
    }

    private object NoopFetcher : ProfileFetcher {

        override suspend fun get(url: String, etag: String?): ProfileFetcher.FetchResult =
            ProfileFetcher.FetchResult.Error("unused in tests")
    }

    private object EmptyCacheDao : ProfileCacheDao {

        override suspend fun upsert(entry: ProfileCacheEntity) = Unit

        override suspend fun delete(entry: ProfileCacheEntity) = Unit

        override suspend fun get(key: String): ProfileCacheEntity? = null

        override suspend fun deleteAll() = Unit

        override fun observeAll(): Flow<List<ProfileCacheEntity>> = MutableStateFlow(emptyList())
    }

    private companion object {

        const val HOUR = 60L * 60L * 1000L

        const val NOW = 1_800_000_000_000L
    }
}
