package com.aipaca.android.engine

enum class EngineExitKind { KILLED, CRASHED, EXITED }

private const val SIGNAL_EXIT_BASE = 128
private const val SIGKILL = 9
private val CRASH_SIGNALS = setOf(4, 6, 7, 8, 11)

fun exitSignal(code: Int): Int? = when {
    code > SIGNAL_EXIT_BASE && code < SIGNAL_EXIT_BASE + 64 -> code - SIGNAL_EXIT_BASE
    else -> null
}

fun classifyEngineExit(code: Int): EngineExitKind {
    val signal = exitSignal(code) ?: return EngineExitKind.EXITED
    return when (signal) {
        SIGKILL -> EngineExitKind.KILLED
        in CRASH_SIGNALS -> EngineExitKind.CRASHED
        else -> EngineExitKind.EXITED
    }
}
