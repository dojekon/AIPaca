package com.aipaca.android.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class Probe(
    val totalBytes: Long,
    val acceptRanges: Boolean,
    val etag: String?,
)

data class PartSpec(
    val index: Int,
    val start: Long,
    val endInclusive: Long,
) {
    val length: Long
        get() = endInclusive - start + 1L
}

class SpeedEma(private val alpha: Double = DEFAULT_ALPHA) {

    private var lastRate: Double? = null

    fun add(bytesDelta: Long, dtMillis: Long): Double {
        if (dtMillis <= 0L || bytesDelta < 0L) return lastRate ?: 0.0
        val rate = bytesDelta * MILLIS_PER_SECOND / dtMillis.toDouble()
        val previous = lastRate
        val next = if (previous == null) rate else alpha * rate + (1.0 - alpha) * previous
        lastRate = next
        return next
    }

    companion object {

        private const val DEFAULT_ALPHA = 0.3
        private const val MILLIS_PER_SECOND = 1000.0
    }
}

class DownloadEngine(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun probe(url: String, token: String?): Probe = withContext(Dispatchers.IO) {
        val request = newRequest(url, token).header(HEADER_RANGE, PROBE_RANGE).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Probe failed with HTTP ${response.code}")
            }
            val acceptRanges = response.code == HTTP_PARTIAL_CONTENT &&
                !"none".equals(response.header(HEADER_ACCEPT_RANGES), ignoreCase = true)
            val totalBytes = if (response.code == HTTP_PARTIAL_CONTENT) {
                parseContentRangeTotal(response.header(HEADER_CONTENT_RANGE)) ?: 0L
            } else {
                response.header(HEADER_CONTENT_LENGTH)?.toLongOrNull() ?: 0L
            }
            Probe(
                totalBytes = totalBytes,
                acceptRanges = acceptRanges,
                etag = response.header(HEADER_ETAG),
            )
        }
    }

    fun planParts(totalBytes: Long, connections: Int): List<PartSpec> {
        if (totalBytes <= 0L) return emptyList()
        val count = connections
            .coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
            .toLong()
            .coerceAtMost(totalBytes)
            .toInt()
        val chunk = totalBytes / count
        val remainder = totalBytes % count
        var start = 0L
        return List(count) { index ->
            val length = chunk + if (index == count - 1) remainder else 0L
            val spec = PartSpec(
                index = index,
                start = start,
                endInclusive = start + length - 1L,
            )
            start += length
            spec
        }
    }

    fun rangeHeader(spec: PartSpec, fromOffset: Long = 0L): String =
        "bytes=${spec.start + fromOffset.coerceIn(0L, spec.length)}-${spec.endInclusive}"

    suspend fun downloadPart(
        url: String,
        spec: PartSpec,
        target: File,
        token: String?,
        fromOffset: Long = 0L,
        onBytes: suspend (Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val offset = fromOffset.coerceIn(0L, spec.length)
        if (offset >= spec.length) return@withContext
        val start = spec.start + offset
        val request = newRequest(url, token)
            .header(HEADER_RANGE, "bytes=$start-${spec.endInclusive}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != HTTP_PARTIAL_CONTENT) {
                throw IOException("Expected HTTP $HTTP_PARTIAL_CONTENT but got ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            target.parentFile?.mkdirs()
            RandomAccessFile(target, FILE_MODE).use { file ->
                file.seek(start)
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        written += read
                        onBytes(read.toLong())
                    }
                    if (written != spec.length) {
                        throw IOException(
                            "Range ${rangeHeader(spec, offset)} truncated: $written of ${spec.length} bytes",
                        )
                    }
                }
            }
        }
    }

    suspend fun downloadAll(
        url: String,
        target: File,
        token: String?,
        onBytes: (Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val request = newRequest(url, token).build()
        client.newCall(request).execute().use { response ->
            if (response.code != HTTP_OK) {
                throw IOException("Expected HTTP $HTTP_OK but got ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body")
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        onBytes(read.toLong())
                    }
                }
            }
        }
    }

    suspend fun verifySha256(file: File, expectedHex: String): Boolean = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext false
        val digest = MessageDigest.getInstance(SHA_256)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex().equals(expectedHex.trim(), ignoreCase = true)
    }

    private fun newRequest(url: String, token: String?): Request.Builder {
        val builder = Request.Builder().url(url).get()
        if (!token.isNullOrBlank()) {
            builder.header(HEADER_AUTHORIZATION, "Bearer $token")
        }
        return builder
    }

    private fun parseContentRangeTotal(header: String?): Long? =
        header?.substringAfter('/', missingDelimiterValue = "")?.trim()?.toLongOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {

        private const val PROBE_RANGE = "bytes=0-0"
        private const val FILE_MODE = "rw"
        private const val BUFFER_SIZE = 128 * 1024
        private const val SHA_256 = "SHA-256"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HEADER_RANGE = "Range"
        private const val HEADER_ACCEPT_RANGES = "Accept-Ranges"
        private const val HEADER_CONTENT_RANGE = "Content-Range"
        private const val HEADER_CONTENT_LENGTH = "Content-Length"
        private const val HEADER_ETAG = "ETag"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val MIN_CONNECTIONS = 1
        private const val MAX_CONNECTIONS = 8

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

private val FORBIDDEN_FILE_NAME_CHARS = Regex("[/\\\\:*?\"<>|]")

fun sanitizeFileName(name: String): String = FORBIDDEN_FILE_NAME_CHARS.replace(name, "_")
