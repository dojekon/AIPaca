package com.aipaca.android.engine

enum class NpuPowerMode(val storageKey: String, val envValue: String?) {
    PROFILE("profile", null),
    BURST("burst", "burst"),
    SUSTAINED("sustained", "sustained_high_performance"),
    BALANCED("balanced", "balanced"),
    POWER_SAVER("power_saver", "power_saver"),
    ;

    companion object {

        val DEFAULT = PROFILE

        fun fromKey(key: String?): NpuPowerMode =
            entries.firstOrNull { it.storageKey.equals(key, ignoreCase = true) } ?: DEFAULT
    }
}

fun thermalPowerMode(selected: NpuPowerMode, level: ThermalLevel): NpuPowerMode = when (level) {
    ThermalLevel.CRITICAL -> NpuPowerMode.POWER_SAVER
    ThermalLevel.SEVERE -> if (selected == NpuPowerMode.POWER_SAVER) selected else NpuPowerMode.BALANCED
    else -> selected
}
