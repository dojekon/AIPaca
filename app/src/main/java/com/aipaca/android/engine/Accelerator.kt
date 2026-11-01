package com.aipaca.android.engine

enum class Accelerator(val storageKey: String, val profileBackend: String) {
    CPU("cpu", "cpu"),
    GPU("gpu", "opencl"),
    NPU("npu", "hexagon"),
    ;

    companion object {

        val DEFAULT = GPU

        fun fromKey(key: String?): Accelerator =
            entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: DEFAULT

        fun fromBackend(backend: String?): Accelerator? =
            entries.firstOrNull { it.profileBackend.equals(backend, ignoreCase = true) }
    }
}
