package com.aipaca.android.engine

import android.content.Context
import com.aipaca.android.R
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class BenchBackend(val tableName: String, val ngl: Int, val dev: String) {
    CPU("CPU", 0, "GPUOpenCL"),
    OPENCL("OpenCL", 99, "GPUOpenCL"),
    HEXAGON("NPU", 99, "HTP0"),
}

data class BenchRow(
    val backend: String,
    val test: String,
    val tps: Double,
    val stddev: Double?,
    val ngl: Int? = null,
)

data class BenchRunResult(
    val ppTps: Double?,
    val tgTps: Double?,
    val ppStddev: Double?,
    val tgStddev: Double?,
    val rawOutput: String = "",
)

class BenchRunner(context: Context) {

    private val appContext: Context = context.applicationContext

    suspend fun run(
        modelPath: String,
        backend: BenchBackend,
        threads: Int,
        cpuMask: String?,
        onLine: (String) -> Unit,
    ): Result<BenchRunResult> = withContext(Dispatchers.IO) {
        val binary = File(appContext.applicationInfo.nativeLibraryDir, BENCH_BINARY)
        var process: Process? = null
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                process?.destroyForcibly()
            }
        }
        try {
            val builder = ProcessBuilder(listOf(binary.absolutePath) + buildArgs(modelPath, backend, threads, cpuMask))
                .directory(appContext.filesDir)
                .redirectErrorStream(true)
            configureEnvironment(builder.environment())
            val started = try {
                builder.start()
            } catch (e: IOException) {
                return@withContext Result.failure(
                    IOException(appContext.getString(R.string.bench_error_start, e.message ?: e.javaClass.simpleName)),
                )
            }
            process = started
            coroutineContext.ensureActive()

            val lines = ArrayList<String>()
            BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lines.add(line)
                    onLine(line)
                }
            }
            val exitCode = started.waitFor()
            val rawOutput = lines.joinToString("\n")
            if (exitCode != 0) {
                return@withContext Result.failure(
                    IOException(appContext.getString(R.string.bench_error_exit, exitCode)),
                )
            }
            val picked = pickResult(parseBenchOutput(rawOutput), backend.tableName, backend.ngl)
                ?: return@withContext Result.failure(IOException(appContext.getString(R.string.bench_error_parse)))
            Result.success(picked.copy(rawOutput = rawOutput))
        } finally {
            cancellationHandle?.dispose()
            process?.destroyForcibly()
        }
    }

    private fun buildArgs(
        modelPath: String,
        backend: BenchBackend,
        threads: Int,
        cpuMask: String?,
    ): List<String> = buildList {
        add("-m")
        add(modelPath)
        add("-t")
        add(threads.toString())
        cpuMask?.trim()?.takeIf { it.isNotEmpty() }?.let {
            add("-C")
            add(it)
        }
        add("-p")
        add(PROMPT_TOKENS.toString())
        add("-n")
        add(GEN_TOKENS.toString())
        add("-r")
        add(REPEATS.toString())
        add("-dev")
        add(backend.dev)
        add("-ngl")
        add(if (backend == BenchBackend.CPU) "0" else "99")
    }

    private fun configureEnvironment(environment: MutableMap<String, String>) {
        val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir
        val stage = NpuSupport.stage(appContext)
        environment[LD_LIBRARY_PATH] = NpuSupport.libraryPathFor(nativeLibraryDir, stage.dir, stage.staged)
        environment[ADSP_LIBRARY_PATH] = nativeLibraryDir
    }

    companion object {

        private const val BENCH_BINARY = "libllama-bench.so"
        private const val PROMPT_TOKENS = 512
        private const val GEN_TOKENS = 128
        private const val REPEATS = 3
        private const val LD_LIBRARY_PATH = "LD_LIBRARY_PATH"
        private const val ADSP_LIBRARY_PATH = "ADSP_LIBRARY_PATH"
    }
}

private val TPS_PATTERN = Regex("""(-?\d+(?:[.,]\d+)?)\s*±\s*(-?\d+(?:[.,]\d+)?)""")

private data class TableHeader(val testIndex: Int, val backendIndex: Int, val nglIndex: Int?)

fun parseBenchOutput(text: String): List<BenchRow> {
    var header: TableHeader? = null
    val rows = mutableListOf<BenchRow>()
    for (line in text.lineSequence()) {
        val cells = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (cells.size < MIN_CELLS) continue
        if (cells.any { it.equals(HEADER_TEST, ignoreCase = true) } &&
            cells.any { it.equals(HEADER_TPS, ignoreCase = true) }
        ) {
            header = TableHeader(
                testIndex = cells.indexOfFirst { it.equals(HEADER_TEST, ignoreCase = true) },
                backendIndex = cells.indexOfFirst { it.equals(HEADER_BACKEND, ignoreCase = true) },
                nglIndex = cells.indexOfFirst { it.equals(HEADER_NGL, ignoreCase = true) }.takeIf { it >= 0 },
            )
            continue
        }
        parseBenchLine(cells, header)?.let(rows::add)
    }
    return rows
}

fun pickResult(rows: List<BenchRow>, backend: String, expectedNgl: Int? = null): BenchRunResult? {
    val byNgl = expectedNgl?.let { ngl -> rows.filter { it.ngl == ngl } }.orEmpty()
    val matching = byNgl.ifEmpty { rows.filter { backendMatches(it.backend, backend) } }
    val pp = matching.firstOrNull { it.test.startsWith(PP_PREFIX, ignoreCase = true) }
    val tg = matching.firstOrNull { it.test.startsWith(TG_PREFIX, ignoreCase = true) }
    if (pp == null && tg == null) return null
    return BenchRunResult(
        ppTps = pp?.tps,
        tgTps = tg?.tps,
        ppStddev = pp?.stddev,
        tgStddev = tg?.stddev,
    )
}

private fun parseBenchLine(cells: List<String>, header: TableHeader?): BenchRow? {
    val valueIndex = cells.indexOfLast { TPS_PATTERN.containsMatchIn(it) }
    if (valueIndex < MIN_CELLS - 1) return null
    val match = TPS_PATTERN.find(cells[valueIndex]) ?: return null
    val tps = parseNumber(match.groupValues[1]) ?: return null
    val stddev = parseNumber(match.groupValues[2])
    val testIndex = header?.testIndex ?: (valueIndex - 1)
    val backendIndex = header?.backendIndex ?: (valueIndex - 3)
    val test = cells.getOrNull(testIndex) ?: return null
    val backend = cells.getOrNull(backendIndex) ?: return null
    if (test.isEmpty() || backend.isEmpty()) return null
    val ngl = header?.nglIndex?.let { cells.getOrNull(it)?.toIntOrNull() }
    return BenchRow(backend = backend, test = test, tps = tps, stddev = stddev, ngl = ngl)
}

private fun backendMatches(rowBackend: String, requested: String): Boolean {
    val row = rowBackend.trim().lowercase()
    val want = requested.trim().lowercase()
    if (row.isEmpty() || want.isEmpty()) return false
    return row == want || row.contains(want) || want.contains(row)
}

private fun parseNumber(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()

private const val PP_PREFIX = "pp"
private const val TG_PREFIX = "tg"
private const val MIN_CELLS = 5
private const val HEADER_TEST = "test"
private const val HEADER_BACKEND = "backend"
private const val HEADER_NGL = "ngl"
private const val HEADER_TPS = "t/s"
