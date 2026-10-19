package com.aipaca.android.engine

sealed interface EngineState {

    data object Unloaded : EngineState

    data class Loading(val modelPath: String) : EngineState

    data class Ready(val modelPath: String, val port: Int) : EngineState

    data class Error(val message: String) : EngineState
}

class LogRing(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()
    private val lines = ArrayDeque<String>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    fun add(line: String) {
        synchronized(lock) {
            if (lines.size >= capacity) {
                lines.removeFirst()
            }
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        java.util.Collections.unmodifiableList(ArrayList(lines))
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
