package com.aipaca.android.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aipaca.android.engine.Accelerator

object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("themeMode")
    val LANGUAGE = stringPreferencesKey("language")
    val HF_TOKEN = stringPreferencesKey("hfToken")
    val DOWNLOAD_CONNECTIONS = intPreferencesKey("downloadConnections")
    val WIFI_ONLY = booleanPreferencesKey("wifiOnly")
    val ACCELERATOR = stringPreferencesKey("accelerator")
    val GPU_ENABLED = booleanPreferencesKey("gpuEnabled")
    val NPU_ENABLED = booleanPreferencesKey("npuEnabled")
    val PROFILES_REPO_URL = stringPreferencesKey("profilesRepoUrl")
    val SERVER_PORT = intPreferencesKey("serverPort")
    val SERVER_API_KEY = stringPreferencesKey("serverApiKey")
    val SERVER_LAN_ENABLED = booleanPreferencesKey("serverLanEnabled")
}

const val LANGUAGE_SYSTEM = "system"
const val LANGUAGE_RUSSIAN = "ru"
const val LANGUAGE_ENGLISH = "en"

const val THEME_MODE_SYSTEM = "system"

const val DEFAULT_DOWNLOAD_CONNECTIONS = 6

const val DEFAULT_SERVER_PORT = 8080

const val SERVER_PORT_MIN = 1024

const val SERVER_PORT_MAX = 65535

data class Settings(
    val themeMode: String = THEME_MODE_SYSTEM,
    val language: String = LANGUAGE_SYSTEM,
    val hfToken: String = "",
    val downloadConnections: Int = DEFAULT_DOWNLOAD_CONNECTIONS,
    val wifiOnly: Boolean = false,
    val accelerator: Accelerator = Accelerator.DEFAULT,
    val profilesRepoUrl: String = "",
    val serverPort: Int = DEFAULT_SERVER_PORT,
    val serverApiKey: String = "",
    val serverLanEnabled: Boolean = false,
)

fun Preferences.toSettings(): Settings {
    val defaults = Settings()
    return Settings(
        themeMode = this[SettingsKeys.THEME_MODE] ?: defaults.themeMode,
        language = this[SettingsKeys.LANGUAGE] ?: defaults.language,
        hfToken = this[SettingsKeys.HF_TOKEN] ?: defaults.hfToken,
        downloadConnections = this[SettingsKeys.DOWNLOAD_CONNECTIONS] ?: defaults.downloadConnections,
        wifiOnly = this[SettingsKeys.WIFI_ONLY] ?: defaults.wifiOnly,
        accelerator = this[SettingsKeys.ACCELERATOR]?.let(Accelerator::fromKey) ?: legacyAccelerator(),
        profilesRepoUrl = this[SettingsKeys.PROFILES_REPO_URL] ?: defaults.profilesRepoUrl,
        serverPort = this[SettingsKeys.SERVER_PORT] ?: defaults.serverPort,
        serverApiKey = this[SettingsKeys.SERVER_API_KEY] ?: defaults.serverApiKey,
        serverLanEnabled = this[SettingsKeys.SERVER_LAN_ENABLED] ?: defaults.serverLanEnabled,
    )
}

private fun Preferences.legacyAccelerator(): Accelerator {
    val npuEnabled = this[SettingsKeys.NPU_ENABLED] ?: false
    val gpuEnabled = this[SettingsKeys.GPU_ENABLED] ?: true
    return when {
        npuEnabled -> Accelerator.NPU
        !gpuEnabled -> Accelerator.CPU
        else -> Accelerator.DEFAULT
    }
}
