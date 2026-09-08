package com.abplus.botchchat.data

import org.junit.Assert.*
import org.junit.Test

class StreamingSpeechBufferTest {
    @Test fun omitsRepeatedAsterisksButKeepsEmphasizedText() {
        val buffer = StreamingSpeechBuffer()
        assertEquals(listOf("重要。", "強調です。"), buffer.append("**重要。**\u002a**強調**です。"))
        assertTrue(buffer.finish().isEmpty())
    }

    @Test fun omitsStarRunsSplitAcrossReceivedAndSpokenChunks() {
        val buffer = StreamingSpeechBuffer(4)
        val chunks = buffer.append("abcd*") + buffer.append("*") +
            buffer.append("efgh***") + buffer.finish()
        assertEquals("abcdefgh", chunks.joinToString(""))
    }

    @Test fun keepsSingleAsterisksIncludingAtEnd() {
        val buffer = StreamingSpeechBuffer()
        val chunks = buffer.append("a*") + buffer.append("b*") + buffer.finish()
        assertEquals("a*b*", chunks.joinToString(""))
        assertTrue(buffer.finish().isEmpty())
    }

    @Test fun asteriskOnlyReplyProducesNoSpeech() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("*****").isEmpty())
        assertTrue(buffer.finish().isEmpty())
    }

    @Test fun startsAtFirstSentenceBeforeReplyIsFinished() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("こんにちは").isEmpty())
        assertEquals(listOf("こんにちは。"), buffer.append("。続きは"))
        assertEquals(listOf("続きはあとで。"), buffer.append("あとで。"))
        assertTrue(buffer.finish().isEmpty())
    }

    @Test fun returnsSeveralSentencesInOrderAndFlushesTailOnce() {
        val buffer = StreamingSpeechBuffer()
        assertEquals(listOf("一文。", "二文！", "三文？"), buffer.append("一文。二文！三文？末尾"))
        assertEquals(listOf("末尾"), buffer.finish())
        assertTrue(buffer.finish().isEmpty())
    }

    @Test fun startsLongReplyWithoutWaitingForPunctuation() {
        val buffer = StreamingSpeechBuffer(8)
        assertEquals(listOf("あ".repeat(8), "あ".repeat(8)), buffer.append("あ".repeat(18)))
        assertEquals(listOf("ああ"), buffer.finish())
    }

    @Test fun preservesEmojiSplitAcrossNetworkDeltas() {
        val buffer = StreamingSpeechBuffer(4)
        val chunks = buffer.append("abc\uD83D") + buffer.append("\uDE00def。") + buffer.finish()
        assertEquals("abc😀def。", chunks.joinToString(""))
        assertTrue(chunks.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun recognizesEnglishSentenceBoundaryWithoutSplittingDecimal() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("Value is 3.14.").isEmpty())
        assertEquals(listOf("Value is 3.14."), buffer.append(" Next sentence"))
        assertEquals(listOf(" Next sentence"), buffer.finish())
    }

    @Test fun blankDeltaAndBlankTailProduceNoSpeech() {
        val buffer = StreamingSpeechBuffer()
        assertTrue(buffer.append("").isEmpty())
        assertTrue(buffer.append(" \n").isEmpty())
        assertTrue(buffer.finish().isEmpty())
    }
}
