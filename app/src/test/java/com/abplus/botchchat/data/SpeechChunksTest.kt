package com.abplus.botchchat.data

import org.junit.Assert.*
import org.junit.Test

class SpeechChunksTest {
    @Test fun longJapaneseReplyIsKeptInOrderWithoutDroppingText() {
        val text = "これは長い返答です。\n".repeat(1000)
        val chunks = speechChunks(text, 4000)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4000 && it.isNotEmpty() })
    }

    @Test fun doesNotSplitSurrogatePairsAtLimit() {
        val text = "abc😀def😀ghi"
        val chunks = speechChunks(text, 4)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun prefersSentenceBoundaries() {
        assertEquals(listOf("こんにちは。", "元気ですか。"), speechChunks("こんにちは。元気ですか。", 9))
    }

    @Test fun handlesEmptyAndExactLimit() {
        assertTrue(speechChunks("", 4).isEmpty())
        assertEquals(listOf("abcd"), speechChunks("abcd", 4))
    }
}
