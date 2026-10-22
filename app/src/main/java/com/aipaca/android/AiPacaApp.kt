package com.aipaca.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.aipaca.android.data.db.AiPacaDatabase
import com.aipaca.android.data.download.DownloadEngine
import com.aipaca.android.data.download.DownloadManager
import com.aipaca.android.data.hf.HfApi
import com.aipaca.android.data.profiles.remote.OkHttpProfileFetcher
import com.aipaca.android.data.profiles.remote.ProfileRepository
import com.aipaca.android.data.settings.Settings
import com.aipaca.android.data.settings.SettingsRepository
import com.aipaca.android.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AiPacaDatabase by lazy {
        Room.databaseBuilder(appContext, AiPacaDatabase::class.java, "aipaca.db")
            .addMigrations(
                AiPacaDatabase.MIGRATION_1_2,
                AiPacaDatabase.MIGRATION_2_3,
                AiPacaDatabase.MIGRATION_3_4,
                AiPacaDatabase.MIGRATION_4_5,
            )
            .build()
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    private val settingsState = settingsRepository.settings
        .stateIn(appScope, SharingStarted.Eagerly, Settings())

    val engineManager: EngineManager by lazy {
        EngineManager(appContext)
    }

    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(
            OkHttpProfileFetcher(),
            database.profileCacheDao(),
            { isWifiConnected(appContext) },
            { settingsState.value.profilesRepoUrl },
        )
    }

    val downloadManager: DownloadManager by lazy {
        DownloadManager(appContext, database, DownloadEngine())
    }

    val hfApi: HfApi by lazy {
        HfApi(OkHttpClient()) { settingsState.value.hfToken }
    }
}

class AiPacaApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}

private fun isWifiConnected(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
