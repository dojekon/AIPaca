package com.aipaca.android.ui.server

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUtilTest {

    @Test
    fun portLowerBoundIsAccepted() {
        assertEquals(1024, parseServerPort("1024"))
    }

    @Test
    fun portUpperBoundIsAccepted() {
        assertEquals(65535, parseServerPort("65535"))
    }

    @Test
    fun typicalPortIsAccepted() {
        assertEquals(8080, parseServerPort("8080"))
    }

    @Test
    fun portIsTrimmedBeforeParsing() {
        assertEquals(8080, parseServerPort(" 8080 "))
    }

    @Test
    fun portBelowRangeIsRejected() {
        assertNull(parseServerPort("1023"))
    }

    @Test
    fun portAboveRangeIsRejected() {
        assertNull(parseServerPort("65536"))
    }

    @Test
    fun nonNumericPortIsRejected() {
        assertNull(parseServerPort("abc"))
        assertNull(parseServerPort("80a0"))
    }

    @Test
    fun blankPortIsRejected() {
        assertNull(parseServerPort(""))
        assertNull(parseServerPort("   "))
    }

    @Test
    fun negativePortIsRejected() {
        assertNull(parseServerPort("-1"))
    }

    @Test
    fun apiKeyIsHexOfFixedRandom() {
        val random = FixedRandom(ByteArray(16) { it.toByte() })

        assertEquals("000102030405060708090a0b0c0d0e0f", generateApiKey(random))
    }

    @Test
    fun apiKeyIsThirtyTwoHexChars() {
        val key = generateApiKey()

        assertEquals(32, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun apiKeysDifferBetweenCalls() {
        assertNotEquals(generateApiKey(), generateApiKey())
    }

    @Test
    fun localUrlUsesLoopback() {
        assertEquals("http://127.0.0.1:8080", localServerUrl(8080))
    }

    @Test
    fun lanUrlUsesHostAndPort() {
        assertEquals("http://192.168.1.42:1234", lanServerUrl("192.168.1.42", 1234))
    }

    @Test
    fun ipv4FilterKeepsLanAddresses() {
        assertEquals(
            listOf("192.168.1.42", "10.0.0.2", "172.16.5.4"),
            ipv4Addresses(listOf("192.168.1.42", "10.0.0.2", "172.16.5.4")),
        )
    }

    @Test
    fun ipv4FilterDropsLoopbackLinkLocalAndInvalid() {
        assertEquals(
            emptyList<String>(),
            ipv4Addresses(
                listOf(
                    "127.0.0.1",
                    "169.254.10.1",
                    "fe80::1",
                    "::1",
                    "256.1.1.1",
                    "192.168.1",
                    "192.168.01.1",
                    "",
                ),
            ),
        )
    }

    @Test
    fun ipv4FilterKeepsOrder() {
        assertEquals(
            listOf("10.0.0.2", "192.168.1.42"),
            ipv4Addresses(listOf("junk", "10.0.0.2", "127.0.0.1", "192.168.1.42")),
        )
    }

    @Test
    fun wlanAddressIsPreferred() {
        val candidates = listOf(
            "rmnet0" to "10.1.1.1",
            "wlan0" to "192.168.1.7",
        )

        assertEquals("192.168.1.7", pickLanAddress(candidates))
    }

    @Test
    fun wlanPrefixMatchesAnyWlanInterface() {
        val candidates = listOf(
            "rmnet0" to "10.1.1.1",
            "wlan1" to "192.168.1.8",
        )

        assertEquals("192.168.1.8", pickLanAddress(candidates))
    }

    @Test
    fun firstCandidateIsUsedWithoutWlan() {
        val candidates = listOf(
            "rmnet0" to "10.1.1.1",
            "eth0" to "10.1.1.2",
        )

        assertEquals("10.1.1.1", pickLanAddress(candidates))
    }

    @Test
    fun emptyCandidatesProduceNull() {
        assertNull(pickLanAddress(emptyList()))
    }

    @Test
    fun uptimeZeroFormats() {
        assertEquals("0:00:00", formatUptime(0))
    }

    @Test
    fun uptimeMinutesAndSecondsFormat() {
        assertEquals("0:01:05", formatUptime(65_000))
    }

    @Test
    fun uptimeHoursFormat() {
        assertEquals("1:01:01", formatUptime(3_661_000))
    }

    @Test
    fun uptimeNegativeIsClamped() {
        assertEquals("0:00:00", formatUptime(-5_000))
    }

    private class FixedRandom(private val source: ByteArray) : SecureRandom() {

        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                bytes[index] = source[index % source.size]
            }
        }
    }
}
