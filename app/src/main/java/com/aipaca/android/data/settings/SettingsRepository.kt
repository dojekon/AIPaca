package com.aipaca.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aipaca.android.engine.Accelerator
import com.aipaca.android.engine.NpuPowerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.settingsDataStore.data
        .map { preferences -> preferences.toSettings().withDecodedHfToken() }
        .flowOn(Dispatchers.IO)

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { it[SettingsKeys.THEME_MODE] = mode }
    }

    suspend fun setLanguage(language: String) {
        context.settingsDataStore.edit { it[SettingsKeys.LANGUAGE] = language }
    }

    suspend fun setHfToken(token: String) {
        if (token.isEmpty()) {
            context.settingsDataStore.edit { it[SettingsKeys.HF_TOKEN] = "" }
            return
        }
        val encrypted = withContext(Dispatchers.IO) { TokenCipher.encrypt(context, token) }
        if (encrypted == null) return
        context.settingsDataStore.edit { it[SettingsKeys.HF_TOKEN] = encrypted }
    }

    suspend fun setDownloadConnections(count: Int) {
        context.settingsDataStore.edit { it[SettingsKeys.DOWNLOAD_CONNECTIONS] = count }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.WIFI_ONLY] = enabled }
    }

    suspend fun setAccelerator(accelerator: Accelerator) {
        context.settingsDataStore.edit {
            it[SettingsKeys.ACCELERATOR] = accelerator.storageKey
            it.remove(SettingsKeys.GPU_ENABLED)
            it.remove(SettingsKeys.NPU_ENABLED)
        }
    }

    suspend fun setNpuPowerMode(mode: NpuPowerMode) {
        context.settingsDataStore.edit { it[SettingsKeys.NPU_POWER_MODE] = mode.storageKey }
    }

    suspend fun setProfilesRepoUrl(url: String) {
        context.settingsDataStore.edit { it[SettingsKeys.PROFILES_REPO_URL] = url }
    }

    suspend fun setServerPort(port: Int) {
        context.settingsDataStore.edit { it[SettingsKeys.SERVER_PORT] = port }
    }

    suspend fun setServerApiKey(key: String) {
        context.settingsDataStore.edit { it[SettingsKeys.SERVER_API_KEY] = key }
    }

    suspend fun setServerLanEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SettingsKeys.SERVER_LAN_ENABLED] = enabled }
    }

    private fun Settings.withDecodedHfToken(): Settings {
        val raw = hfToken
        if (raw.isEmpty() || !raw.startsWith(TokenCipher.PREFIX)) return this
        return copy(hfToken = TokenCipher.decrypt(context, raw).orEmpty())
    }
}
