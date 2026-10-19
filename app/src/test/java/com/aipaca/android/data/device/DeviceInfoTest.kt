package com.aipaca.android.data.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun normalizeSocTrimsAndLowercases() {
        assertEquals("sm8650", normalizeSoc(" SM8650\n"))
    }

    @Test
    fun normalizeSocHandlesNull() {
        assertEquals("", normalizeSoc(null))
    }

    @Test
    fun vendorKeyMapsKnownVendors() {
        assertEquals("qcom", vendorKey("Qualcomm"))
        assertEquals("qcom", vendorKey("QTI"))
        assertEquals("qcom", vendorKey("qcom"))
        assertEquals("mtk", vendorKey("MediaTek"))
        assertEquals("samsung", vendorKey("Samsung"))
        assertEquals("google", vendorKey("Google"))
    }

    @Test
    fun vendorKeyFallsBackForEmptyAndUnknown() {
        assertEquals("other", vendorKey(null))
        assertEquals("other", vendorKey("  "))
        assertEquals("acme", vendorKey("Acme"))
    }

    @Test
    fun ramGbOfRoundsBytesToGibibytes() {
        assertEquals(12, ramGbOf(12L * 1024 * 1024 * 1024))
        assertEquals(12, ramGbOf(12_500_000_000L))
    }

    @Test
    fun deviceKeyCombinesVendorSocAndRam() {
        assertEquals("qcom-sm8650/12gb", deviceKeyOf("qcom", "sm8650", 12))
    }

    @Test
    fun htpVersionIsKnownForSm8650() {
        assertEquals(75, htpBySoc["sm8650"])
    }
}
