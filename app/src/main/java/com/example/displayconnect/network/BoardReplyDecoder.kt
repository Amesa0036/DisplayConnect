package com.example.displayconnect.network

/** Bounded ASCII newline decoder. A too-long frame is discarded in full. */
class BoardReplyDecoder {
    private val line = StringBuilder()
    private var discarding = false
    @Synchronized fun reset() { line.clear(); discarding = false }
    @Synchronized fun feed(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        for (byte in bytes) {
            val c = (byte.toInt() and 255).toChar()
            if (c == '\n' || c == '\r') {
                if (!discarding && line.isNotEmpty()) result.add(line.toString())
                line.clear(); discarding = false
            } else if (!discarding) {
                if (c.code !in 32..126 || line.length >= 1024) { line.clear(); discarding = true }
                else line.append(c)
            }
        }
        return result
    }
}
