package com.aipaca.android.ui.chat

import com.aipaca.android.engine.chat.ApiMessage
import com.aipaca.android.engine.chat.ChatRequest

const val TITLE_EXCHANGE_MESSAGES = 2
const val TITLE_RETRY_MAX_MESSAGES = 6

private const val TITLE_USER_INPUT_LIMIT = 500
private const val TITLE_ASSISTANT_INPUT_LIMIT = 200
private const val TITLE_MAX_LENGTH = 40
private const val TITLE_TEMPERATURE = 0.2
private const val TITLE_MAX_TOKENS = 24

private const val TITLE_SYSTEM_PROMPT =
    "You write short titles for chat conversations. Name the topic of the user's request in 2-6 words. " +
        "Write the title in the same language as the user's message. Do not answer the request and do not " +
        "summarize the assistant's reply. Return ONLY the title, without quotes, emoji, or a trailing period."

private val MARKDOWN_CHARS = Regex("[*`~#\\[\\]()]")
private val WHITESPACE = Regex("\\s+")
private val QUOTE_CHARS = charArrayOf('«', '»', '"', '\'', '“', '”', '‘', '’')
private val TITLE_PREFIXES = listOf("Заголовок:", "Title:")

fun buildTitleRequest(userMessage: String, assistantMessage: String): ChatRequest = ChatRequest(
    messages = listOf(
        ApiMessage(
            role = "user",
            content = buildString {
                append("User message:\n")
                append(userMessage.trim().take(TITLE_USER_INPUT_LIMIT))
                val reply = assistantMessage.trim().take(TITLE_ASSISTANT_INPUT_LIMIT)
                if (reply.isNotEmpty()) {
                    append("\n\nAssistant reply (beginning):\n")
                    append(reply)
                }
            },
        ),
    ),
    systemPrompt = TITLE_SYSTEM_PROMPT,
    temperature = TITLE_TEMPERATURE,
    maxTokens = TITLE_MAX_TOKENS,
    enableThinking = false,
)

fun sanitizeGeneratedTitle(raw: String?): String? {
    val source = raw ?: return null
    val firstLine = source.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    if (firstLine.startsWith("```")) return null

    var title = WHITESPACE.replace(MARKDOWN_CHARS.replace(firstLine, " "), " ").trim()
    var changed = true
    while (changed) {
        changed = false
        TITLE_PREFIXES.firstOrNull { title.startsWith(it, ignoreCase = true) }?.let { prefix ->
            title = title.substring(prefix.length).trim()
            changed = true
        }
        val unquoted = title.trim(*QUOTE_CHARS).trim()
        if (unquoted != title) {
            title = unquoted
            changed = true
        }
    }

    if (title.isEmpty() || title.none { it.isLetterOrDigit() }) return null
    return title.take(TITLE_MAX_LENGTH).trim()
}

fun shouldGenerateTitle(messageCount: Int, currentTitle: String, autoTitle: String): Boolean =
    messageCount in TITLE_EXCHANGE_MESSAGES..TITLE_RETRY_MAX_MESSAGES && currentTitle == autoTitle
