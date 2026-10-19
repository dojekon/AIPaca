package com.aipaca.android.engine.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerArgBuilderTest {

    private val qwen = doc("profiles/qwen3-4b-q4_0-sm8650.json")
    private val default = doc("profiles/default-generic.json")

    @Test
    fun buildAddsCoreLaunchArgs() {
        val args = ServerArgBuilder.build("/x/model.gguf", qwen, "127.0.0.1", 8080, null)

        assertTrue(args.hasPair("-m", "/x/model.gguf"))
        assertTrue(args.hasPair("--host", "127.0.0.1"))
        assertTrue(args.hasPair("--port", "8080"))
        assertTrue(args.hasPair("-ngl", "99"))
        assertTrue(args.hasPair("--flash-attn", "on"))
        assertTrue(args.hasPair("-ctk", "q8_0"))
        assertTrue(args.contains("--jinja"))
    }

    @Test
    fun flashAttnNeverSwallowsNextFlag() {
        val args = ServerArgBuilder.build("/x/model.gguf", qwen, "127.0.0.1", 8080, null)

        val index = args.indexOf("--flash-attn")
        assertTrue(index >= 0)
        val value = args.getOrNull(index + 1)
        assertTrue(value != null && !value.startsWith("-"))
    }

    @Test
    fun buildAddsCpuMaskWhenPresent() {
        val args = ServerArgBuilder.build("/x/model.gguf", qwen, "127.0.0.1", 8080, null)

        assertTrue(args.hasPair("-C", "0xF0"))
    }

    @Test
    fun buildOmitsCpuMaskWhenAbsent() {
        val args = ServerArgBuilder.build("/x/model.gguf", default, "127.0.0.1", 8080, null)

        assertFalse(args.contains("-C"))
    }

    @Test
    fun buildUsesGenericCpuDefaults() {
        val args = ServerArgBuilder.build("/x/model.gguf", default, "127.0.0.1", 8080, null)

        assertTrue(args.hasPair("-ngl", "0"))
        assertFalse(args.contains("--flash-attn"))
    }

    @Test
    fun buildAddsSamplingArgs() {
        val args = ServerArgBuilder.build(
            "/x/model.gguf",
            qwen.copy(sampling = SamplingDoc()),
            "127.0.0.1",
            8080,
            null,
        )

        assertTrue(args.hasPair("--temp", "0.7"))
        assertTrue(args.hasPair("--repeat-penalty", "1.1"))
    }

    @Test
    fun buildAddsNoMmapWhenDisabled() {
        val doc = qwen.copy(launch = qwen.launch.copy(mmap = false))

        assertTrue(ServerArgBuilder.build("/x/model.gguf", doc, "127.0.0.1", 8080, null).contains("--no-mmap"))
    }

    @Test
    fun buildAddsApiKeyWhenPresent() {
        val args = ServerArgBuilder.build("/x/model.gguf", qwen, "127.0.0.1", 8080, "secret")

        assertTrue(args.hasPair("--api-key", "secret"))
    }

    @Test
    fun sanitizeKeepsFlagsAndSafeValues() {
        assertEquals(
            listOf("--no-warmup", "--temp", "0.5"),
            ServerArgBuilder.sanitizeExtraArgs(
                listOf("--no-warmup", "--mmproj", "/etc/passwd", "--temp", "0.5", "../x"),
            ),
        )
    }

    @Test
    fun sanitizeDropsInvalidFlagAndWhitespaceValue() {
        assertEquals(
            emptyList<String>(),
            ServerArgBuilder.sanitizeExtraArgs(listOf("--bad flag", "--ok", "a b")),
        )
    }

    @Test
    fun buildEnvKeepsOnlySafeHexagonVarsInOrder() {
        val doc = default.copy(
            launch = default.launch.copy(
                env = linkedMapOf(
                    "LD_PRELOAD" to "x",
                    "GGML_HEXAGON_POWER_MODE" to "burst",
                    "GGML_FOO" to "1",
                    "GGML_HEXAGON_HTP_ARCH" to "v75",
                ),
            ),
        )

        val env = ServerArgBuilder.buildEnv(doc)

        assertEquals(
            mapOf("GGML_HEXAGON_POWER_MODE" to "burst", "GGML_HEXAGON_HTP_ARCH" to "v75"),
            env,
        )
        assertEquals(listOf("GGML_HEXAGON_POWER_MODE", "GGML_HEXAGON_HTP_ARCH"), env.keys.toList())
    }

    private fun List<String>.hasPair(flag: String, value: String): Boolean =
        windowed(2).any { it[0] == flag && it[1] == value }

    private fun doc(path: String): ProfileDoc =
        ProfileParser.parseProfile(checkNotNull(resource("/profiles/$path")) { "Missing fixture: /profiles/$path" })

    private fun resource(path: String): String? =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}
