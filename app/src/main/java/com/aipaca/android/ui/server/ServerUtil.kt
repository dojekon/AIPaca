package com.aipaca.android.ui.server

import com.aipaca.android.data.settings.SERVER_PORT_MAX
import com.aipaca.android.data.settings.SERVER_PORT_MIN
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

private const val API_KEY_BYTES = 16
private const val API_KEY_HEX = "0123456789abcdef"
private const val IPV4_PARTS = 4
private const val IPV4_MAX_PART = 255
private const val LOOPBACK_PREFIX = "127."
private const val LINK_LOCAL_PREFIX = "169.254."
private const val WLAN_PREFIX = "wlan"

fun parseServerPort(text: String): Int? {
    val port = text.trim().toIntOrNull() ?: return null
    return port.takeIf { it in SERVER_PORT_MIN..SERVER_PORT_MAX }
}

fun generateApiKey(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(API_KEY_BYTES)
    random.nextBytes(bytes)
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(API_KEY_HEX[value ushr 4])
            append(API_KEY_HEX[value and 0x0F])
        }
    }
}

fun localServerUrl(port: Int): String = "http://127.0.0.1:$port"

fun lanServerUrl(host: String, port: Int): String = "http://$host:$port"

fun ipv4Addresses(candidates: List<String>): List<String> = candidates.filter { it.isLanIpv4() }

fun pickLanAddress(candidates: List<Pair<String, String>>): String? =
    candidates.firstOrNull { it.first.startsWith(WLAN_PREFIX) }?.second
        ?: candidates.firstOrNull()?.second

fun formatUptime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

fun lanIpv4Address(): String? {
    val candidates = mutableListOf<Pair<String, String>>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    for (iface in interfaces) {
        if (!iface.isUp || iface.isLoopback) continue
        val addresses = iface.inetAddresses
            .asSequence()
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toList()
        candidates += ipv4Addresses(addresses).map { iface.name to it }
    }
    return pickLanAddress(candidates)
}

private fun String.isLanIpv4(): Boolean {
    if (startsWith(LOOPBACK_PREFIX) || startsWith(LINK_LOCAL_PREFIX)) return false
    val parts = split('.')
    if (parts.size != IPV4_PARTS) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all { it.isDigit() } &&
            (part == "0" || !part.startsWith('0')) &&
            part.toInt() <= IPV4_MAX_PART
    }
}
