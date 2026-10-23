package com.aipaca.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamThrottleTest {

    @Test
    fun emitsFirstThenAtMostOncePerInterval() {
        var now = 0L
        val throttle = StreamThrottle(intervalMs = 50, clock = { now })
        val emitted = mutableListOf<Long>()

        for (t in 0L..200L step 10) {
            now = t
            if (throttle.ready()) emitted += t
        }

        assertEquals(listOf(0L, 50L, 100L, 150L, 200L), emitted)
    }

    @Test
    fun resetAllowsImmediateEmit() {
        var now = 0L
        val throttle = StreamThrottle(intervalMs = 50, clock = { now })
        throttle.ready()
        now = 10
        throttle.reset()

        assertEquals(true, throttle.ready())
    }
}
