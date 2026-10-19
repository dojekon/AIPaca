package com.aipaca.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AcceleratorTest {

    @Test
    fun storageKeysAreStable() {
        assertEquals("cpu", Accelerator.CPU.storageKey)
        assertEquals("gpu", Accelerator.GPU.storageKey)
        assertEquals("npu", Accelerator.NPU.storageKey)
    }

    @Test
    fun profileBackendsMapToEngineWorld() {
        assertEquals("cpu", Accelerator.CPU.profileBackend)
        assertEquals("opencl", Accelerator.GPU.profileBackend)
        assertEquals("hexagon", Accelerator.NPU.profileBackend)
    }

    @Test
    fun fromKeyParsesStorageValues() {
        assertEquals(Accelerator.CPU, Accelerator.fromKey("cpu"))
        assertEquals(Accelerator.GPU, Accelerator.fromKey("gpu"))
        assertEquals(Accelerator.NPU, Accelerator.fromKey("npu"))
        assertEquals(Accelerator.NPU, Accelerator.fromKey("NPU"))
    }

    @Test
    fun fromKeyFallsBackToDefault() {
        assertEquals(Accelerator.DEFAULT, Accelerator.fromKey(null))
        assertEquals(Accelerator.DEFAULT, Accelerator.fromKey("tpu"))
        assertEquals(Accelerator.GPU, Accelerator.DEFAULT)
    }
}
