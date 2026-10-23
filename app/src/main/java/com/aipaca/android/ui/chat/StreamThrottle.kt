package com.aipaca.android.ui.chat

const val STREAM_UI_INTERVAL_MS = 50L

class StreamThrottle(
    private val intervalMs: Long = STREAM_UI_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private var lastEmit: Long? = null

    fun ready(): Boolean {
        val now = clock()
        val previous = lastEmit
        if (previous != null && now - previous < intervalMs) return false
        lastEmit = now
        return true
    }

    fun reset() {
        lastEmit = null
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
