package com.aipaca.android.engine.agent

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ChatRequest

const val COMPACTION_MIN_MESSAGES = 6
const val COMPACTION_MAX_TRANSCRIPT_CHARS = 24000
const val COMPACTION_MAX_SUMMARY_CHARS = 6000

const val COMPACTION_OMITTED_MARKER = "\n[... middle of transcript omitted ...]\n"

private const val COMPACTION_MIN_TRANSCRIPT_CHARS = 200
private const val COMPACTION_MAX_TOKENS = 700
private const val COMPACTION_HEAD_PERCENT = 40

const val COMPACTION_SYSTEM_PROMPT =
    "You compact an ongoing agent conversation so it can continue within a limited context window. " +
        "Read the transcript and produce a compact summary in English. Preserve: the user's goal and " +
        "intent; constraints, preferences, and requirements; decisions and their reasons; files created " +
        "or modified with their paths and relevant contents; important tool outputs, command results, " +
        "and URLs; and unresolved next steps. Keep exact names, identifiers, paths, and URLs. Omit " +
        "small talk, duplicated tool noise, and details that do not affect future work. Return only the " +
        "summary text, with no preamble, headings, or commentary."

fun shouldCompact(
    estimatedTokens: Int,
    budgetTokens: Int,
    messageCount: Int,
    minMessages: Int = COMPACTION_MIN_MESSAGES,
): Boolean = estimatedTokens > budgetTokens && messageCount >= minMessages

fun buildCompactionRequest(
    messages: List<ApiMessage>,
    maxTranscriptChars: Int = COMPACTION_MAX_TRANSCRIPT_CHARS,
): ChatRequest {
    val limit = maxTranscriptChars.coerceAtLeast(COMPACTION_MIN_TRANSCRIPT_CHARS)
    return ChatRequest(
        messages = listOf(ApiMessage(role = "user", content = buildTranscript(messages, limit))),
        systemPrompt = COMPACTION_SYSTEM_PROMPT,
        temperature = 0.0,
        maxTokens = COMPACTION_MAX_TOKENS,
        tools = emptyList(),
        enableThinking = false,
    )
}

fun sanitizeCompactionSummary(raw: String?): String? {
    if (raw == null) return null
    var text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith(SUMMARY_PREFIX, ignoreCase = true)) {
        text = text.substring(SUMMARY_PREFIX.length).trim()
        if (text.isEmpty()) return null
    }
    if (text.length > COMPACTION_MAX_SUMMARY_CHARS) {
        text = text.take(COMPACTION_MAX_SUMMARY_CHARS).trim()
    }
    return text.ifEmpty { null }
}

private const val SUMMARY_PREFIX = "Summary:"

private fun buildTranscript(messages: List<ApiMessage>, maxChars: Int): String {
    val full = messages.joinToString(separator = "\n") { formatMessage(it) }
    if (full.length <= maxChars) return full

    val headChars = maxChars * COMPACTION_HEAD_PERCENT / 100
    val tailChars = maxChars - headChars
    val headEnd = safeHeadEnd(full, headChars)
    val tailStart = safeTailStart(full, full.length - tailChars)
    return full.substring(0, headEnd) + COMPACTION_OMITTED_MARKER + full.substring(tailStart)
}

private fun formatMessage(message: ApiMessage): String {
    val calls = message.toolCalls.orEmpty()
    val callsSuffix = if (message.role == "assistant" && calls.isNotEmpty()) {
        calls.joinToString(prefix = " [tool calls: ", postfix = "]", separator = ", ") {
            "${it.name}(${it.argumentsJson})"
        }
    } else {
        ""
    }
    val body = "${message.role}: ${message.content}$callsSuffix"
    val toolCallId = message.toolCallId
    return if (message.role == "tool" && !toolCallId.isNullOrBlank()) {
        "tool ($toolCallId): ${message.content}"
    } else {
        body
    }
}

private fun safeHeadEnd(text: String, index: Int): Int {
    var end = index.coerceIn(0, text.length)
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
        end--
    }
    return end
}

private fun safeTailStart(text: String, index: Int): Int {
    var start = index.coerceIn(0, text.length)
    if (start in 1 until text.length && text[start - 1].isHighSurrogate() && text[start].isLowSurrogate()) {
        start++
    }
    return start
}
