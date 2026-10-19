package com.aipaca.android.engine.profiles

import kotlinx.serialization.Serializable

@Serializable
data class ProfileIndexDoc(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val devices: List<DeviceIndexEntry> = emptyList(),
    val profiles: List<ProfileIndexEntry> = emptyList(),
)

@Serializable
data class ProfileIndexEntry(
    val id: String,
    val family: String,
    val device: String,
    val quants: List<String> = emptyList(),
    val file: String,
)

@Serializable
data class DeviceIndexEntry(
    val key: String,
    val soc: String,
    val vendor: String,
    val ramGb: Int,
    val file: String,
)

@Serializable
data class DeviceSpecDoc(
    val key: String,
    val soc: String,
    val vendor: String,
    val ramGb: Int,
    val htpVersion: Int? = null,
    val cores: CoresDoc? = null,
    val cpuMasks: Map<String, String> = emptyMap(),
)

@Serializable
data class CoresDoc(
    val total: Int,
    val big: List<Int> = emptyList(),
    val prime: Int? = null,
    val perf: List<Int> = emptyList(),
)

@Serializable
data class ProfileDoc(
    val schemaVersion: Int = 1,
    val id: String,
    val backend: String,
    val model: ModelMatchDoc,
    val launch: LaunchDoc,
    val sampling: SamplingDoc? = null,
    val expected: ExpectedDoc? = null,
    val minAppVersion: String? = null,
    val experimental: Boolean = false,
)

@Serializable
data class ModelMatchDoc(
    val family: String,
    val matchPatterns: List<String> = emptyList(),
    val quants: List<String> = emptyList(),
)

@Serializable
data class LaunchDoc(
    val nCtx: Int = 4096,
    val threads: Int = 4,
    val cpuMask: String? = null,
    val nGpuLayers: Int = 0,
    val flashAttn: Boolean = false,
    val kvTypeK: String? = null,
    val kvTypeV: String? = null,
    val batchSize: Int = 512,
    val ubatchSize: Int = 128,
    val mmap: Boolean = true,
    val extraArgs: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
)

@Serializable
data class SamplingDoc(
    val temperature: Double = 0.7,
    val topP: Double = 0.95,
    val topK: Int = 40,
    val minP: Double = 0.05,
    val repeatPenalty: Double = 1.1,
)

@Serializable
data class ExpectedDoc(
    val tgTps: Double? = null,
    val ppTps: Double? = null,
)
