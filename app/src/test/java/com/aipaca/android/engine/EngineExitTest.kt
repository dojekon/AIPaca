package com.aipaca.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineExitTest {

    @Test
    fun sigkillIsReportedAsKilled() {
        assertEquals(EngineExitKind.KILLED, classifyEngineExit(137))
    }

    @Test
    fun abortAndSegfaultAreCrashes() {
        assertEquals(EngineExitKind.CRASHED, classifyEngineExit(134))
        assertEquals(EngineExitKind.CRASHED, classifyEngineExit(139))
        assertEquals(6, exitSignal(134))
    }

    @Test
    fun plainExitCodesAndSigtermAreExits() {
        assertEquals(EngineExitKind.EXITED, classifyEngineExit(1))
        assertEquals(EngineExitKind.EXITED, classifyEngineExit(0))
        assertEquals(EngineExitKind.EXITED, classifyEngineExit(143))
        assertNull(exitSignal(1))
    }
}
