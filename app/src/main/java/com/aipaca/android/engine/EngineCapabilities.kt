package com.aipaca.android.engine

import android.content.Context
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EngineCapabilities(
    val revision: String? = null,
    val architectures: List<String> = emptyList(),
    val npuWeightTypes: List<String> = emptyList(),
) {
    fun supportsArchitecture(architecture: String): Boolean? =
        if (architectures.isEmpty()) null else architectures.any { it.equals(architecture, ignoreCase = true) }

    fun npuSupportsType(type: String): Boolean = npuWeightTypes.any { it.equals(type, ignoreCase = true) }

    val shortRevision: String get() = revision?.take(SHORT_REVISION).orEmpty()

    companion object {
        val UNKNOWN = EngineCapabilities()
        private const val SHORT_REVISION = 7
    }
}

object EngineCapabilitiesLoader {

    private const val ASSET_PATH = "engine/capabilities.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): EngineCapabilities = json.decodeFromString(text)

    fun fromAssets(context: Context): EngineCapabilities =
        try {
            context.assets.open(ASSET_PATH).use { parse(it.readBytes().decodeToString()) }
        } catch (_: IOException) {
            EngineCapabilities.UNKNOWN
        } catch (_: IllegalArgumentException) {
            EngineCapabilities.UNKNOWN
        }
}
