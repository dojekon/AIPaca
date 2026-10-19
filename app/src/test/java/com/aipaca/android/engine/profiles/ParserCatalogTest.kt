package com.aipaca.android.engine.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserCatalogTest {

    @Test
    fun parseIndexReadsDevicesAndProfiles() {
        val index = ProfileParser.parseIndex(fixture("index.json"))

        assertEquals(1, index.schemaVersion)
        assertEquals("2026-10-16", index.updatedAt)
        assertEquals(2, index.devices.size)
        assertEquals(7, index.profiles.size)
        assertEquals("qcom-sm8650/12gb", index.devices.first().key)
        assertEquals("devices/sm8650-12gb.json", index.devices.first().file)
    }

    @Test
    fun parseDeviceReadsCoresAndMasks() {
        val device = ProfileParser.parseDevice(fixture("devices/sm8650-12gb.json"))

        assertEquals("SM8650", device.soc)
        assertEquals("qcom", device.vendor)
        assertEquals(12, device.ramGb)
        assertEquals(75, device.htpVersion)
        assertEquals(8, device.cores?.total)
        assertEquals(listOf(2, 3, 4, 5, 6, 7), device.cores?.big)
        assertEquals(7, device.cores?.prime)
        assertEquals(listOf(4, 5, 6), device.cores?.perf)
        assertEquals("0xFC", device.cpuMasks["allBig"])
    }

    @Test
    fun parseProfileReadsLaunchSettings() {
        val doc = ProfileParser.parseProfile(fixture("profiles/qwen3-4b-q4_0-sm8650.json"))

        assertEquals("qwen3-4b", doc.model.family)
        assertEquals(4096, doc.launch.nCtx)
        assertEquals(99, doc.launch.nGpuLayers)
        assertEquals("0xF0", doc.launch.cpuMask)
        assertEquals(true, doc.launch.flashAttn)
        assertEquals("q8_0", doc.launch.kvTypeK)
    }

    @Test
    fun catalogLoaderResolvesAllFiles() {
        val catalog = ProfileCatalogLoader.fromJson(fixture("index.json")) { path ->
            resource("/profiles/$path")
        }

        assertEquals(2, catalog.devices.size)
        assertEquals(7, catalog.profiles.size)
        assertEquals(75, catalog.devices["qcom-sm8650/12gb"]?.htpVersion)

        val qwen = catalog.profiles.first { it.doc.id == "qwen3-4b/q4_0/sm8650-12gb" }
        assertEquals("profiles/qwen3-4b-q4_0-sm8650.json", qwen.sourcePath)
        assertEquals("opencl", qwen.doc.backend)
        assertEquals(99, qwen.doc.launch.nGpuLayers)
        assertEquals("0xF0", qwen.doc.launch.cpuMask)
        assertEquals(12.0, qwen.doc.expected?.tgTps ?: 0.0, 0.0)
    }

    @Test
    fun catalogLoaderRejectsMissingFileWithPath() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileCatalogLoader.fromJson(fixture("index.json")) { null }
        }

        assertTrue(error.message.orEmpty().contains("devices/sm8650-12gb.json"))
    }

    @Test
    fun unsupportedBackendIsRejected() {
        val text = fixture("profiles/default-generic.json").replace("\"cpu\"", "\"bogus\"")

        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileParser.parseProfile(text)
        }

        assertTrue(error.message.orEmpty().contains("bogus"))
    }

    @Test
    fun wrongSchemaVersionIsRejected() {
        val text = fixture("profiles/default-generic.json")
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        assertThrows(IllegalArgumentException::class.java) {
            ProfileParser.parseProfile(text)
        }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = fixture("profiles/default-generic.json")
            .replaceFirst("{", "{\n  \"unknownField\": {\"nested\": [1, 2]},")

        val doc = ProfileParser.parseProfile(text)

        assertEquals("default", doc.model.family)
        assertEquals(".*", doc.model.matchPatterns.single())
    }

    private fun fixture(path: String): String =
        checkNotNull(resource("/profiles/$path")) { "Missing fixture: /profiles/$path" }

    private fun resource(path: String): String? =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}
