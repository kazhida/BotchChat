package com.abplus.botchchat.data

import com.abplus.botchchat.ui.ChatMessage
import com.abplus.botchchat.ui.Sender
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatHistoryStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun file() = File(folder.root, "history.json")
    private fun pair(text: String) = listOf(
        ChatMessage(sender = Sender.USER, text = text),
        ChatMessage(sender = Sender.ASSISTANT, text = "回答: $text")
    )

    @Test fun restartRestoresMessagesAndContextIncludingJapaneseAndNewlines() {
        val history = pair("私の名前は太郎です。\n\"よろしく\" 👋")
        ChatHistoryStore(file()).save(history)
        val restored = ChatHistoryStore(file()).load()
        assertEquals(history, restored)
        assertEquals(history, ChatHistory.context(restored, "私の名前は？"))
    }

    @Test fun evictsOldestMessagesAndContextAt1024() {
        val history = (1..600).flatMap { pair("$it") }
        ChatHistoryStore(file()).save(history)
        val restored = ChatHistoryStore(file()).load()
        assertEquals(1024, restored.size)
        assertEquals(history.takeLast(1024), restored)
        val context = ChatHistory.context(restored, "続けて")
        assertTrue(context.all { it in restored })
        assertEquals(restored.last(), context.last())
    }

    @Test fun interruptedResponseIsVisibleButNeverUsedAsCompletedContext() {
        val history = pair("完了") + pair("中断").map {
            if (it.sender == Sender.ASSISTANT) it.copy(isStreaming = true) else it
        }
        ChatHistoryStore(file()).save(history)
        val restored = ChatHistoryStore(file()).load()
        assertFalse(restored.last().isStreaming)
        assertFalse(restored.last().includeInContext)
        assertEquals(history.take(2), ChatHistory.context(restored, "次の質問"))
    }

    @Test fun clearRemovesStoredContextAfterRestart() {
        val store = ChatHistoryStore(file())
        store.save(pair("秘密"))
        store.save(emptyList())
        val restored = ChatHistoryStore(file()).load()
        assertTrue(restored.isEmpty())
        assertTrue(ChatHistory.context(restored, "質問").isEmpty())
    }

    @Test fun corruptFileIsReportedAndLeftUntouched() {
        file().writeText("broken json")
        assertThrows(Exception::class.java) { ChatHistoryStore(file()).load() }
        assertEquals("broken json", file().readText())
    }

    @Test fun incompleteTemporaryWriteDoesNotReplaceLastSnapshot() {
        val history = pair("保存済み")
        ChatHistoryStore(file()).save(history)
        File(folder.root, "history.json.tmp").writeText("partial write")
        assertEquals(history, ChatHistoryStore(file()).load())
    }

    @Test fun contextUsesRecentWholePairsWithinUtf8Budget() {
        val older = pair("あ".repeat(1000))
        val recent = pair("直近の質問")
        val context = ChatHistory.context(older + recent, "今の質問")
        assertEquals(recent, context)
        assertTrue(context.sumOf { it.text.toByteArray(Charsets.UTF_8).size } < ChatHistory.INPUT_BYTE_BUDGET)
    }

    @Test fun contextSkipsOrphanedAndFailedResponsesAndSystemMessages() {
        val history = listOf(ChatMessage(sender = Sender.ASSISTANT, text = "先頭が削除された応答")) +
            pair("有効") + listOf(ChatMessage(sender = Sender.SYSTEM, text = "通知")) +
            pair("失敗").map { if (it.sender == Sender.ASSISTANT) it.copy(includeInContext = false) else it }
        assertEquals(history.subList(1, 3), ChatHistory.context(history, "続き"))
    }

    @Test fun oversizedPromptIsRejectedWithoutSplittingUnicode() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatHistory.context(emptyList(), "あ".repeat(2001))
        }
    }
}
