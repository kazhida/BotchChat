package com.abplus.botchchat.data

/** Buffers only an unfinished sentence, never the entire reply. */
internal class StreamingSpeechBuffer(private val maxChunkLength: Int = 120) {
    private val pending = StringBuilder()
    private var pendingAsterisks = 0

    init { require(maxChunkLength >= 2) }

    fun append(delta: String): List<String> {
        // Keep a trailing star run across deltas so neither input nor speech chunk
        // boundaries turn a Markdown marker into a spoken single asterisk.
        delta.forEach { character ->
            if (character == '*') {
                pendingAsterisks = minOf(2, pendingAsterisks + 1)
            } else {
                flushAsterisks()
                pending.append(character)
            }
        }
        val ready = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val boundary = pending.indices.firstOrNull { index ->
                pending[index] in "。！？!?\n" ||
                    (pending[index] == '.' && index + 1 < pending.length && pending[index + 1].isWhitespace())
            }
            var end = when {
                boundary != null && boundary < maxChunkLength -> boundary + 1
                pending.length >= maxChunkLength -> maxChunkLength
                else -> break
            }
            // A delta may end between the two UTF-16 units of an emoji.
            if (pending[end - 1].isHighSurrogate()) end--
            if (end == 0) break
            val text = pending.substring(0, end)
            pending.delete(0, end)
            if (text.isNotBlank()) ready += text
        }
        return ready
    }

    fun finish(): List<String> {
        flushAsterisks()
        val tail = pending.toString()
        pending.clear()
        return if (tail.isBlank()) emptyList() else listOf(tail)
    }

    private fun flushAsterisks() {
        if (pendingAsterisks == 1) pending.append('*')
        pendingAsterisks = 0
    }
}

internal sealed interface ReplySpeechEvent {
    data object Start : ReplySpeechEvent
    data class Chunk(val text: String) : ReplySpeechEvent
    data object Cancel : ReplySpeechEvent
}
