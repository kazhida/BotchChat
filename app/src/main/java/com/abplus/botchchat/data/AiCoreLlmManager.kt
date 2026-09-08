package com.abplus.botchchat.data

import android.content.Context
import com.abplus.botchchat.ui.ChatMessage
import com.abplus.botchchat.ui.Sender
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Runs a locally provisioned Gemma 4 E2B model using LiteRT-LM, without network access. */
class AiCoreLlmManager(private val context: Context) {
    companion object {
        const val MODEL_NAME = "Gemma 4 E2B"
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    }

    sealed interface ModelStatus {
        data object Checking : ModelStatus
        data object Ready : ModelStatus
        data class NotAvailable(val reason: String) : ModelStatus
    }

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var closed = false

    private fun modelFile(): File {
        val directory = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        directory.mkdirs()
        return File(directory, MODEL_FILE_NAME)
    }

    suspend fun checkModelStatus(): ModelStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock ModelStatus.NotAvailable("モデルは終了しています。")
            if (engine != null) return@withLock ModelStatus.Ready
            val file = modelFile()
            if (!file.isFile || file.length() == 0L) {
                return@withLock ModelStatus.NotAvailable("Gemma 4 E2B のモデルを端末に配置してください。")
            }
            var candidate: Engine? = null
            try {
                candidate = Engine(EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    maxNumTokens = ChatHistory.MAX_MODEL_TOKENS,
                    cacheDir = context.cacheDir.absolutePath
                ))
                candidate.initialize()
                engine = candidate
                ModelStatus.Ready
            } catch (e: CancellationException) {
                runCatching { candidate?.close() }
                throw e
            } catch (e: Exception) {
                runCatching { candidate?.close() }
                ModelStatus.NotAvailable("Gemma 4 E2B を読み込めません: ${e.localizedMessage}")
            } catch (e: LinkageError) {
                runCatching { candidate?.close() }
                ModelStatus.NotAvailable("この端末では推論ライブラリを利用できません: ${e.localizedMessage}")
            }
        }
    }

    fun generateResponseStream(
        promptText: String,
        history: List<ChatMessage>,
        systemInstruction: String = "You are a helpful assistant. Reply in Japanese unless asked otherwise."
    ): Flow<String> = flow {
        mutex.withLock {
            check(!closed) { "モデルは終了しています。" }
            val active = checkNotNull(engine) { "Gemma 4 E2B の準備が完了していません。" }
            active.createConversation(ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                initialMessages = ChatHistory.context(history, promptText).map { message ->
                    if (message.sender == Sender.USER) Message.user(message.text)
                    else Message.model(message.text)
                }
            )).use { conversation ->
                conversation.sendMessageAsync(promptText).collect { message ->
                    emit(message.toString())
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closed = true
            engine?.close()
            engine = null
        }
    }
}
