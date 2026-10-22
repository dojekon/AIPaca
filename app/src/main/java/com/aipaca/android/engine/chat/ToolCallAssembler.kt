package com.aipaca.android.engine.chat

class ToolCallAssembler {

    private class Builder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val calls = LinkedHashMap<Int, Builder>()

    fun add(delta: ChatEvent.ToolCallDelta) {
        val builder = calls.getOrPut(delta.index) { Builder() }
        if (builder.id == null && !delta.id.isNullOrEmpty()) builder.id = delta.id
        if (builder.name == null && !delta.name.isNullOrEmpty()) builder.name = delta.name
        builder.arguments.append(delta.argsChunk)
    }

    fun finish(): List<AssembledToolCall> =
        calls.entries
            .sortedBy { it.key }
            .map { (index, builder) ->
                AssembledToolCall(
                    index = index,
                    id = builder.id,
                    name = builder.name,
                    argumentsJson = builder.arguments.toString(),
                )
            }
}
