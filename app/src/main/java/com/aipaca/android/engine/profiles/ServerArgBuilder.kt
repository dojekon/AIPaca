package com.aipaca.android.engine.profiles

object ServerArgBuilder {

    private const val ENV_KEY_PREFIX = "GGML_HEXAGON_"

    private val FLAG_REGEX = Regex("""^--[a-z0-9][a-z0-9-]*$""")
    private val VALUE_REGEX = Regex("""^[A-Za-z0-9._:+,=-]+$""")
    private val ENV_VALUE_REGEX = Regex("""^[A-Za-z0-9._,:\[\] -]+$""")

    fun build(modelPath: String, doc: ProfileDoc, host: String, port: Int, apiKey: String?): List<String> {
        val args = mutableListOf(
            "-m", modelPath,
            "--host", host,
            "--port", port.toString(),
            "-c", doc.launch.nCtx.toString(),
            "-t", doc.launch.threads.toString(),
            "-ngl", doc.launch.nGpuLayers.toString(),
            "-b", doc.launch.batchSize.toString(),
            "-ub", doc.launch.ubatchSize.toString(),
            "--jinja",
        )
        doc.launch.cpuMask?.let { args += listOf("-C", it) }
        if (doc.launch.flashAttn) args += listOf("--flash-attn", "on")
        doc.launch.kvTypeK?.let { args += listOf("-ctk", it) }
        doc.launch.kvTypeV?.let { args += listOf("-ctv", it) }
        if (!doc.launch.mmap) args += "--no-mmap"
        if (apiKey != null) args += listOf("--api-key", apiKey)
        doc.sampling?.let { sampling ->
            args += listOf(
                "--temp", sampling.temperature.toString(),
                "--top-p", sampling.topP.toString(),
                "--top-k", sampling.topK.toString(),
                "--min-p", sampling.minP.toString(),
                "--repeat-penalty", sampling.repeatPenalty.toString(),
            )
        }
        args += sanitizeExtraArgs(doc.launch.extraArgs)
        return args
    }

    fun sanitizeExtraArgs(raw: List<String>): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < raw.size) {
            val token = raw[index]
            if (!FLAG_REGEX.matches(token)) {
                index++
                continue
            }
            val next = raw.getOrNull(index + 1)
            when {
                next == null -> {
                    out += token
                    index++
                }
                FLAG_REGEX.matches(next) -> {
                    out += token
                    index++
                }
                isValidValue(next) -> {
                    out += token
                    out += next
                    index += 2
                }
                else -> index++
            }
        }
        return out
    }

    fun buildEnv(doc: ProfileDoc): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        for ((key, value) in doc.launch.env) {
            if (key.startsWith(ENV_KEY_PREFIX) && ENV_VALUE_REGEX.matches(value)) {
                env[key] = value
            }
        }
        return env
    }

    private fun isValidValue(value: String): Boolean =
        VALUE_REGEX.matches(value) && !value.startsWith("/") && !value.contains("..")
}
