package com.aipaca.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BenchOutputTest {

    @Test
    fun parsesRowsFromBothBackendSections() {
        val rows = parseBenchOutput(TYPICAL_OUTPUT)

        assertEquals(4, rows.size)
        assertEquals(BenchRow("CPU", "pp512", 649.42, 4.42, 0), rows[0])
        assertEquals(BenchRow("CPU", "tg128", 38.21, 0.31, 0), rows[1])
        assertEquals(BenchRow("OpenCL", "pp512", 412.05, 3.10, 99), rows[2])
        assertEquals(BenchRow("OpenCL", "tg128", 21.77, 0.12, 99), rows[3])
    }

    @Test
    fun parsesRowsFromFormatWithThreadsCpuMaskAndDeviceColumns() {
        val rows = parseBenchOutput(REAL_OUTPUT)

        assertEquals(2, rows.size)
        assertEquals(BenchRow("OpenCL,HTP", "pp32", 29.04, 0.00, 0), rows[0])
        assertEquals(BenchRow("OpenCL,HTP", "tg16", 18.88, 0.00, 0), rows[1])
    }

    @Test
    fun pickResultPrefersNglSectionOverBackendName() {
        val rows = listOf(
            BenchRow("OpenCL,HTP", "pp512", 70.0, 1.0, 0),
            BenchRow("OpenCL,HTP", "tg128", 16.0, 0.5, 0),
            BenchRow("OpenCL,HTP", "pp512", 97.0, 2.0, 99),
            BenchRow("OpenCL,HTP", "tg128", 9.6, 0.3, 99),
        )

        val cpu = pickResult(rows, "CPU", 0)
        assertEquals(70.0, cpu!!.ppTps!!, TOLERANCE)
        assertEquals(16.0, cpu.tgTps!!, TOLERANCE)

        val opencl = pickResult(rows, "OpenCL", 99)
        assertEquals(97.0, opencl!!.ppTps!!, TOLERANCE)
        assertEquals(9.6, opencl.tgTps!!, TOLERANCE)
    }

    @Test
    fun pickResultFallsBackToBackendNameWithoutNglMatch() {
        val rows = parseBenchOutput(TYPICAL_OUTPUT)

        val cpu = pickResult(rows, "CPU", 123)

        assertEquals(649.42, cpu!!.ppTps!!, TOLERANCE)
    }

    @Test
    fun headerSeparatorAndBuildLinesAreIgnored() {
        val text = """
            | model | size | params | backend | ngl | test | t/s |
            | ----- | ---: | -----: | ------- | --: | ---: | --: |
            build: 1e2f3a4 with cc for Android
            """.trimIndent()

        assertEquals(emptyList<BenchRow>(), parseBenchOutput(text))
    }

    @Test
    fun rowWithoutStddevIsIgnored() {
        val text = "| Qwen3 4B Q4_0 | 2.32 GiB | 4.02 B | CPU | 0 | pp512 | 649.42 |"

        assertEquals(emptyList<BenchRow>(), parseBenchOutput(text))
    }

    @Test
    fun emptyOutputGivesEmptyList() {
        assertEquals(emptyList<BenchRow>(), parseBenchOutput(""))
        assertEquals(emptyList<BenchRow>(), parseBenchOutput("\n\n"))
    }

    @Test
    fun garbageLinesAreIgnored() {
        val text = """
            not a table at all
            | broken | row |
            | Qwen3 4B Q4_0 | 2.32 GiB | 4.02 B | CPU | 0 | pp512 | 649.42 ± 4.42 |
            """
        val rows = parseBenchOutput(text.trimIndent())

        assertEquals(listOf(BenchRow("CPU", "pp512", 649.42, 4.42)), rows)
    }

    @Test
    fun whitespaceAndCommaDecimalsAreParsed() {
        val text = "  |  Qwen3  4B  |  2.32 GiB  |  4.02 B  |  CPU  |  0  |  pp512  |  649,42  ±  4,42  |  "

        assertEquals(listOf(BenchRow("CPU", "pp512", 649.42, 4.42)), parseBenchOutput(text))
    }

    @Test
    fun pickResultSelectsBackendSection() {
        val rows = parseBenchOutput(TYPICAL_OUTPUT)

        val cpu = pickResult(rows, "CPU")
        assertNotNull(cpu)
        assertEquals(649.42, cpu!!.ppTps!!, TOLERANCE)
        assertEquals(38.21, cpu.tgTps!!, TOLERANCE)
        assertEquals(4.42, cpu.ppStddev!!, TOLERANCE)
        assertEquals(0.31, cpu.tgStddev!!, TOLERANCE)

        val opencl = pickResult(rows, "OpenCL")
        assertNotNull(opencl)
        assertEquals(412.05, opencl!!.ppTps!!, TOLERANCE)
        assertEquals(21.77, opencl.tgTps!!, TOLERANCE)
    }

    @Test
    fun pickResultMatchesDeviceAlias() {
        val rows = parseBenchOutput(TYPICAL_OUTPUT)

        val opencl = pickResult(rows, "GPUOpenCL")

        assertNotNull(opencl)
        assertEquals(412.05, opencl!!.ppTps!!, TOLERANCE)
    }

    @Test
    fun pickResultUnknownBackendGivesNull() {
        assertNull(pickResult(parseBenchOutput(TYPICAL_OUTPUT), "Vulkan"))
    }

    @Test
    fun pickResultKeepsMissingTestAsNull() {
        val rows = parseBenchOutput("| Qwen3 4B Q4_0 | 2.32 GiB | 4.02 B | CPU | 0 | tg128 | 38.21 ± 0.31 |")

        val result = pickResult(rows, "CPU")

        assertNotNull(result)
        assertNull(result!!.ppTps)
        assertEquals(38.21, result.tgTps!!, TOLERANCE)
        assertNull(result.ppStddev)
    }

    @Test
    fun rawOutputIsEmptyOnPickedResult() {
        val result = pickResult(parseBenchOutput(TYPICAL_OUTPUT), "CPU")

        assertEquals("", result!!.rawOutput)
    }

    private companion object {

        private const val TOLERANCE = 0.000_1

        private val REAL_OUTPUT = """
            ggml_opencl: selected platform: 'QUALCOMM Snapdragon(TM)'

            ggml_opencl: device: 'QUALCOMM Adreno(TM) 750 (OpenCL 3.0 Adreno(TM) 750)'
            ggml-hex: Loading driver libcdsprpc.so
            ggml-hex: FastRPC capability query failed (err 512)
            | model                          |       size |     params | backend    | ngl | threads | cpu_mask   | dev          |            test |                  t/s |
            | ------------------------------ | ---------: | ---------: | ---------- | --: | ------: | ---------- | ------------ | --------------: | -------------------: |
            | qwen3 0.6B BF16                |   1.11 GiB |   596.05 M | OpenCL,HTP |   0 |       4 | 0xF0       | GPUOpenCL    |            pp32 |         29.04 ± 0.00 |
            | qwen3 0.6B BF16                |   1.11 GiB |   596.05 M | OpenCL,HTP |   0 |       4 | 0xF0       | GPUOpenCL    |            tg16 |         18.88 ± 0.00 |

            build: 23f7aab (1)
        """.trimIndent()

        private val TYPICAL_OUTPUT = """
            llama-bench: benchmarking 2 backends

            | model                          |       size |     params | backend    | ngl |          test |                  t/s |
            | ------------------------------ | ---------: | ---------: | ---------- | --: | ------------: | -------------------: |
            | Qwen3 4B Q4_0                  |   2.32 GiB |     4.02 B | CPU        |   0 |         pp512 |        649.42 ± 4.42 |
            | Qwen3 4B Q4_0                  |   2.32 GiB |     4.02 B | CPU        |   0 |         tg128 |         38.21 ± 0.31 |

            | model                          |       size |     params | backend    | ngl |          test |                  t/s |
            | ------------------------------ | ---------: | ---------: | ---------- | --: | ------------: | -------------------: |
            | Qwen3 4B Q4_0                  |   2.32 GiB |     4.02 B | OpenCL     |  99 |         pp512 |        412.05 ± 3.10 |
            | Qwen3 4B Q4_0                  |   2.32 GiB |     4.02 B | OpenCL     |  99 |         tg128 |         21.77 ± 0.12 |

            build: 1e2f3a4 with clang for Android
        """.trimIndent()
    }
}
