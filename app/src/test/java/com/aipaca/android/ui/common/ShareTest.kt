package com.aipaca.android.ui.common

import com.aipaca.android.engine.profiles.LaunchDoc
import com.aipaca.android.engine.profiles.ModelMatchDoc
import com.aipaca.android.engine.profiles.ProfileDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTest {

    @Test
    fun benchTextContainsAllFields() {
        val text = buildBenchShareText(
            modelName = "Qwen3-4B-Q4_K_M.gguf",
            backend = "CPU",
            pp = 42.5,
            tg = 7.5,
            threads = 6,
            cpuMask = "0xF0",
            deviceKey = "qcom-sm8650/12gb",
        )

        assertTrue(text.startsWith("AiPaca benchmark"))
        assertTrue(text.contains("model: Qwen3-4B-Q4_K_M.gguf"))
        assertTrue(text.contains("backend: CPU"))
        assertTrue(text.contains("pp512: 42.5 t/s"))
        assertTrue(text.contains("tg128: 7.5 t/s"))
        assertTrue(text.contains("threads: 6"))
        assertTrue(text.contains("cpuMask: 0xF0"))
        assertTrue(text.contains("device: qcom-sm8650/12gb"))
    }

    @Test
    fun benchTextRoundsThroughputToOneDecimal() {
        val text = buildBenchShareText(
            modelName = "model.gguf",
            backend = "OpenCL",
            pp = 12.34,
            tg = 4.56,
            threads = 4,
            cpuMask = null,
            deviceKey = "mtk-dimensity9300/12gb",
        )

        assertTrue(text.contains("pp512: 12.3 t/s"))
        assertTrue(text.contains("tg128: 4.6 t/s"))
    }

    @Test
    fun benchTextOmitsCpuMaskLineWhenNull() {
        val text = buildBenchShareText("model.gguf", "CPU", 1.0, 2.0, 4, null, "dev")

        assertFalse(text.contains("cpuMask"))
        assertEquals(7, text.lines().size)
    }

    @Test
    fun benchTextOmitsCpuMaskLineWhenBlank() {
        val text = buildBenchShareText("model.gguf", "CPU", 1.0, 2.0, 4, "   ", "dev")

        assertFalse(text.contains("cpuMask"))
    }

    @Test
    fun benchTextKeepsCpuMaskAndEndsWithDeviceLine() {
        val text = buildBenchShareText("model.gguf", "CPU", 1.0, 2.0, 6, "0xFF", "qcom-sm8750/12gb")

        assertTrue(text.contains("cpuMask: 0xFF"))
        assertTrue(text.endsWith("device: qcom-sm8750/12gb"))
        assertEquals(8, text.lines().size)
    }

    @Test
    fun profileTextStartsWithHeaderAndKeepsJson() {
        val json = """{"schemaVersion":1,"id":"qwen3-4b"}"""
        val text = buildProfileShareText(json)

        assertTrue(text.startsWith("Профиль AiPaca (schema v1)"))
        assertTrue(text.endsWith(json))
    }

    @Test
    fun profileTextSeparatesHeaderWithBlankLine() {
        assertEquals(
            "Профиль AiPaca (schema v1)\n\n{}",
            buildProfileShareText("{}"),
        )
    }

    @Test
    fun profileJsonForSharePrettyPrintsAndEncodesDefaults() {
        val doc = ProfileDoc(
            id = "qwen3-4b-pure-cpu",
            backend = "cpu",
            model = ModelMatchDoc(
                family = "qwen3",
                matchPatterns = listOf("Qwen3"),
                quants = listOf("Q4_K_M"),
            ),
            launch = LaunchDoc(threads = 6, cpuMask = "0xF0"),
        )

        val json = profileJsonForShare(doc)

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"experimental\": false"))
        assertTrue(json.contains("\"id\": \"qwen3-4b-pure-cpu\""))
        assertTrue(json.lines().size > 10)
    }

    @Test
    fun shareTextIntentSpecCarriesSendActionTypeAndPayload() {
        val spec = shareTextSpec("Subject", "Body")

        assertEquals("android.intent.action.SEND", spec.action)
        assertEquals("text/plain", spec.type)
        assertEquals("Subject", spec.subject)
        assertEquals("Body", spec.text)
    }

    @Test
    fun shareTextIntentSubjectAndTextMatchPassedValues() {
        val spec = shareTextSpec("Model result", "line1\nline2")

        assertEquals("Model result", spec.subject)
        assertEquals("line1\nline2", spec.text)
    }
}
