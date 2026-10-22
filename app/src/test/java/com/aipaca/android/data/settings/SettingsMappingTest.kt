package com.aipaca.android.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aipaca.android.engine.Accelerator
import com.aipaca.android.engine.NpuPowerMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMappingTest {

    @Test
    fun emptyPreferencesProduceDefaults() {
        val settings = mutablePreferencesOf().toSettings()
        assertEquals(Settings(), settings)
    }

    @Test
    fun storedValuesOverrideDefaults() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("themeMode") to "dark",
            stringPreferencesKey("language") to LANGUAGE_RUSSIAN,
            stringPreferencesKey("hfToken") to "hf_test_token",
            intPreferencesKey("downloadConnections") to 4,
            booleanPreferencesKey("wifiOnly") to true,
            stringPreferencesKey("accelerator") to "npu",
            stringPreferencesKey("profilesRepoUrl") to "https://example.com/profiles.json",
            intPreferencesKey("serverPort") to 9090,
            stringPreferencesKey("serverApiKey") to "0123456789abcdef0123456789abcdef",
            booleanPreferencesKey("serverLanEnabled") to true,
        )

        val settings = preferences.toSettings()

        assertEquals("dark", settings.themeMode)
        assertEquals(LANGUAGE_RUSSIAN, settings.language)
        assertEquals("hf_test_token", settings.hfToken)
        assertEquals(4, settings.downloadConnections)
        assertEquals(true, settings.wifiOnly)
        assertEquals(Accelerator.NPU, settings.accelerator)
        assertEquals("https://example.com/profiles.json", settings.profilesRepoUrl)
        assertEquals(9090, settings.serverPort)
        assertEquals("0123456789abcdef0123456789abcdef", settings.serverApiKey)
        assertEquals(true, settings.serverLanEnabled)
    }

    @Test
    fun m6SettingsHaveDefaults() {
        val settings = mutablePreferencesOf().toSettings()

        assertEquals("", settings.hfToken)
        assertEquals(DEFAULT_DOWNLOAD_CONNECTIONS, settings.downloadConnections)
        assertEquals(6, settings.downloadConnections)
        assertEquals(false, settings.wifiOnly)
        assertEquals(Accelerator.GPU, settings.accelerator)
    }

    @Test
    fun serverSettingsHaveDefaults() {
        val settings = mutablePreferencesOf().toSettings()

        assertEquals(8080, settings.serverPort)
        assertEquals("", settings.serverApiKey)
        assertEquals(false, settings.serverLanEnabled)
    }

    @Test
    fun serverKeysMapToSettingsFields() {
        val preferences = mutablePreferencesOf(
            intPreferencesKey("serverPort") to 1234,
            stringPreferencesKey("serverApiKey") to "secret",
            booleanPreferencesKey("serverLanEnabled") to true,
        )

        val settings = preferences.toSettings()

        assertEquals(1234, settings.serverPort)
        assertEquals("secret", settings.serverApiKey)
        assertEquals(true, settings.serverLanEnabled)
    }

    @Test
    fun acceleratorKeyAcceptsStorageValues() {
        assertEquals(Accelerator.CPU, mutablePreferencesOf(stringPreferencesKey("accelerator") to "cpu").toSettings().accelerator)
        assertEquals(Accelerator.GPU, mutablePreferencesOf(stringPreferencesKey("accelerator") to "gpu").toSettings().accelerator)
        assertEquals(Accelerator.NPU, mutablePreferencesOf(stringPreferencesKey("accelerator") to "npu").toSettings().accelerator)
    }

    @Test
    fun unknownAcceleratorFallsBackToDefault() {
        val settings = mutablePreferencesOf(stringPreferencesKey("accelerator") to "tpu").toSettings()

        assertEquals(Accelerator.DEFAULT, settings.accelerator)
    }

    @Test
    fun legacyNpuEnabledMigratesToNpu() {
        val preferences = mutablePreferencesOf(booleanPreferencesKey("npuEnabled") to true)

        assertEquals(Accelerator.NPU, preferences.toSettings().accelerator)
    }

    @Test
    fun legacyGpuDisabledMigratesToCpu() {
        val preferences = mutablePreferencesOf(
            booleanPreferencesKey("gpuEnabled") to false,
            booleanPreferencesKey("npuEnabled") to false,
        )

        assertEquals(Accelerator.CPU, preferences.toSettings().accelerator)
    }

    @Test
    fun legacyTogglesDefaultToGpu() {
        val settings = mutablePreferencesOf().toSettings()

        assertEquals(Accelerator.GPU, settings.accelerator)
    }

    @Test
    fun explicitAcceleratorWinsOverLegacyToggles() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("accelerator") to "gpu",
            booleanPreferencesKey("npuEnabled") to true,
        )

        assertEquals(Accelerator.GPU, preferences.toSettings().accelerator)
    }

    @Test
    fun npuPowerModeDefaultsToProfileAndMapsStoredKey() {
        assertEquals(NpuPowerMode.PROFILE, mutablePreferencesOf().toSettings().npuPowerMode)
        assertEquals(
            NpuPowerMode.POWER_SAVER,
            mutablePreferencesOf(stringPreferencesKey("npuPowerMode") to "power_saver").toSettings().npuPowerMode,
        )
        assertEquals(
            NpuPowerMode.PROFILE,
            mutablePreferencesOf(stringPreferencesKey("npuPowerMode") to "turbo").toSettings().npuPowerMode,
        )
    }
}
