package com.aipaca.android.ui.common

import android.content.Context
import android.content.Intent
import com.aipaca.android.engine.profiles.ProfileDoc
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PROFILE_SHARE_HEADER = "Профиль AiPaca (schema v1)"

private const val BENCH_SHARE_HEADER = "AiPaca benchmark"
private const val SHARE_MIME_TYPE = "text/plain"
private const val TPS_FORMAT = "%.1f"

private val shareJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

data class ShareTextSpec(
    val action: String,
    val type: String,
    val subject: String,
    val text: String,
)

fun buildBenchShareText(
    modelName: String,
    backend: String,
    pp: Double,
    tg: Double,
    threads: Int,
    cpuMask: String?,
    deviceKey: String,
): String = buildString {
    appendLine(BENCH_SHARE_HEADER)
    appendLine("model: $modelName")
    appendLine("backend: $backend")
    appendLine("pp512: ${formatTps(pp)} t/s")
    appendLine("tg128: ${formatTps(tg)} t/s")
    appendLine("threads: $threads")
    cpuMask?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("cpuMask: $it") }
    append("device: $deviceKey")
}

fun buildProfileShareText(profileJson: String): String =
    "$PROFILE_SHARE_HEADER\n\n$profileJson"

fun profileJsonForShare(doc: ProfileDoc): String = shareJson.encodeToString(doc)

fun shareTextSpec(subject: String, text: String): ShareTextSpec = ShareTextSpec(
    action = Intent.ACTION_SEND,
    type = SHARE_MIME_TYPE,
    subject = subject,
    text = text,
)

fun shareTextIntent(subject: String, text: String): Intent {
    val spec = shareTextSpec(subject, text)
    return Intent(spec.action).apply {
        type = spec.type
        putExtra(Intent.EXTRA_SUBJECT, spec.subject)
        putExtra(Intent.EXTRA_TEXT, spec.text)
    }
}

fun Context.shareText(subject: String, text: String) {
    startActivity(Intent.createChooser(shareTextIntent(subject, text), null))
}

private fun formatTps(value: Double): String = String.format(Locale.US, TPS_FORMAT, value)
