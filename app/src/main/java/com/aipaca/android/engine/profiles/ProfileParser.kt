package com.aipaca.android.engine.profiles

import kotlinx.serialization.json.Json

object ProfileParser {

    const val SCHEMA_VERSION = 1

    val json = Json { ignoreUnknownKeys = true }

    private val supportedBackends = setOf("cpu", "opencl", "hexagon")

    fun parseIndex(s: String): ProfileIndexDoc = json.decodeFromString<ProfileIndexDoc>(s)

    fun parseDevice(s: String): DeviceSpecDoc = json.decodeFromString<DeviceSpecDoc>(s)

    fun parseProfile(s: String): ProfileDoc {
        val doc = json.decodeFromString<ProfileDoc>(s)
        require(doc.schemaVersion == SCHEMA_VERSION) {
            "Unsupported profile schemaVersion ${doc.schemaVersion}, expected $SCHEMA_VERSION (profile id '${doc.id}')"
        }
        require(doc.backend in supportedBackends) {
            "Unsupported profile backend '${doc.backend}' for profile '${doc.id}', expected one of ${supportedBackends.joinToString()}"
        }
        return doc
    }
}
