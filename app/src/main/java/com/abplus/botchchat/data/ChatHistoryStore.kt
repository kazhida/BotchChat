package com.abplus.botchchat.data

import com.abplus.botchchat.ui.ChatMessage
import com.abplus.botchchat.ui.Sender
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A single atomic snapshot is the source of truth for both display and model context. */
class ChatHistoryStore(private val file: File) {
    @Synchronized
    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        val root = JsonParser.parseString(file.readText()).asJsonObject
        require(root["version"].asInt == 1) { "未対応の履歴形式です。" }
        val messages = root.getAsJsonArray("messages").map { element ->
            val value = element.asJsonObject
            val interrupted = value["isStreaming"].asBoolean
            ChatMessage(
                id = value["id"].asString,
                sender = Sender.valueOf(value["sender"].asString),
                text = value["text"].asString,
                timestamp = value["timestamp"].asLong,
                isStreaming = false,
                includeInContext = value["includeInContext"].asBoolean && !interrupted
            )
        }.takeLast(ChatHistory.MAX_MESSAGES)
        require(messages.map { it.id }.toSet().size == messages.size) { "履歴 ID が重複しています。" }
        return messages
    }

    @Synchronized
    fun save(messages: List<ChatMessage>) {
        val entries = JsonArray()
        messages.takeLast(ChatHistory.MAX_MESSAGES).forEach { message ->
            entries.add(JsonObject().apply {
                addProperty("id", message.id)
                addProperty("sender", message.sender.name)
                addProperty("text", message.text)
                addProperty("timestamp", message.timestamp)
                addProperty("isStreaming", message.isStreaming)
                addProperty("includeInContext", message.includeInContext)
            })
        }
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("messages", entries)
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

object ChatHistory {
    const val MAX_MESSAGES = 1024
    const val MAX_MODEL_TOKENS = 8192
    // Conservative UTF-8 byte budget; leaves room for the template, system prompt and output.
    const val INPUT_BYTE_BUDGET = 6000

    fun context(messages: List<ChatMessage>, prompt: String): List<ChatMessage> {
        var remaining = INPUT_BYTE_BUDGET - prompt.toByteArray(Charsets.UTF_8).size
        require(remaining >= 0) { "入力が長すぎます。UTF-8 で 6000 バイト以内に短くしてください。" }
        val pairs = messages.takeLast(MAX_MESSAGES).windowed(2).filter { (user, assistant) ->
            user.sender == Sender.USER && assistant.sender == Sender.ASSISTANT &&
                user.includeInContext && assistant.includeInContext &&
                !user.isStreaming && !assistant.isStreaming && assistant.text.isNotBlank()
        }
        val selected = mutableListOf<List<ChatMessage>>()
        for (pair in pairs.asReversed()) {
            val cost = pair.sumOf { it.text.toByteArray(Charsets.UTF_8).size } + 64
            if (cost > remaining) break
            selected.add(pair)
            remaining -= cost
        }
        return selected.asReversed().flatten()
    }
}
