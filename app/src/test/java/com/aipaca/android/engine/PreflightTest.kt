package com.aipaca.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {

    @Test
    fun thermalLevelMappingSkipsLowStatuses() {
        assertEquals(ThermalLevel.NONE, thermalLevelFor(-1))
        assertEquals(ThermalLevel.NONE, thermalLevelFor(0))
        assertEquals(ThermalLevel.NONE, thermalLevelFor(1))
        assertEquals(ThermalLevel.MODERATE, thermalLevelFor(2))
        assertEquals(ThermalLevel.SEVERE, thermalLevelFor(3))
        assertEquals(ThermalLevel.CRITICAL, thermalLevelFor(4))
        assertEquals(ThermalLevel.CRITICAL, thermalLevelFor(6))
    }

    @Test
    fun ramRequiresFilePlusReserve() {
        val file = 2L * 1024L * 1024L * 1024L
        assertTrue(hasEnoughRam(file, file + RAM_RESERVE_BYTES))
        assertFalse(hasEnoughRam(file, file + RAM_RESERVE_BYTES - 1L))
    }

    @Test
    fun ramZeroFileNeedsOnlyReserve() {
        assertTrue(hasEnoughRam(0L, RAM_RESERVE_BYTES))
        assertFalse(hasEnoughRam(0L, RAM_RESERVE_BYTES - 1L))
    }

    @Test
    fun ramNegativeFileIsTreatedAsZero() {
        assertTrue(hasEnoughRam(-100L, RAM_RESERVE_BYTES))
        assertEquals(ramRequiredBytes(-100L), ramRequiredBytes(0L))
    }

    @Test
    fun diskUnknownTotalAlwaysFits() {
        assertTrue(hasEnoughDisk(0L, 0L))
        assertTrue(hasEnoughDisk(-1L, 0L))
        assertTrue(hasEnoughDisk(-1L, -5L))
    }

    @Test
    fun diskRequiresHeadroomAndReserve() {
        val total = 1_000_000_000L
        val required = diskRequiredBytes(total)
        assertEquals(total + total / 10L + DISK_RESERVE_BYTES, required)
        assertTrue(hasEnoughDisk(required, total))
        assertFalse(hasEnoughDisk(required - 1L, total))
    }

    @Test
    fun diskSmallTotalBoundary() {
        assertFalse(hasEnoughDisk(DISK_RESERVE_BYTES + 10L, 10L))
        assertTrue(hasEnoughDisk(DISK_RESERVE_BYTES + 11L, 10L))
    }
}
