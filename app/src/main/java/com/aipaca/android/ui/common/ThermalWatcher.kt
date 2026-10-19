package com.aipaca.android.ui.common

import android.content.Context
import android.os.PowerManager
import com.aipaca.android.R
import com.aipaca.android.engine.ThermalLevel
import com.aipaca.android.engine.thermalLevelFor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

fun thermalMessageRes(status: Int): Int? = thermalMessageRes(thermalLevelFor(status))

fun thermalMessageRes(level: ThermalLevel): Int? = when (level) {
    ThermalLevel.NONE -> null
    ThermalLevel.MODERATE -> R.string.thermal_moderate
    ThermalLevel.SEVERE -> R.string.thermal_severe
    ThermalLevel.CRITICAL -> R.string.thermal_critical
}

class ThermalWatcher(context: Context) {

    private val powerManager: PowerManager? =
        context.applicationContext.getSystemService(PowerManager::class.java)

    val status: Flow<Int> = callbackFlow {
        val manager = powerManager
        if (manager == null) {
            trySend(PowerManager.THERMAL_STATUS_NONE)
            close()
            return@callbackFlow
        }
        trySend(manager.currentThermalStatus)
        val listener = PowerManager.OnThermalStatusChangedListener { value ->
            trySend(value)
        }
        manager.addThermalStatusListener(listener)
        awaitClose { manager.removeThermalStatusListener(listener) }
    }.distinctUntilChanged()
}
