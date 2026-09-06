package com.abplus.botchchat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abplus.botchchat.data.AiCoreLlmManager
import com.abplus.botchchat.data.AiCoreLlmManager.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val llmManager = AiCoreLlmManager(application.applicationContext)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    init {
        checkLlmStatus()
    }

    fun checkLlmStatus() {
        viewModelScope.launch {
            _modelStatus.value = ModelStatus.Checking
            val status = llmManager.checkModelStatus()
            _modelStatus.value = status

            if (_messages.value.isEmpty()) {
                val welcomeText = when (status) {
                    is ModelStatus.Ready -> "AICore と Gemma-4 ローカル LLM の準備が完了しました。チャットを開始できます！"
                    is ModelStatus.Downloading -> "ローカル LLM (Gemma-4) モデルの準備中です..."
                    is ModelStatus.NotAvailable -> "注意: ${status.reason}（ローカルエミュレーションモードで試すことができます）"
                    ModelStatus.Checking -> "AICore モデルステータスを確認中..."
                }
                _messages.value = listOf(
                    ChatMessage(sender = Sender.SYSTEM, text = welcomeText)
                )
            }
        }
    }

    fun sendMessage(userText: String) {
        val trimmedText = userText.trim()
        if (trimmedText.isEmpty() || _isGenerating.value) return

        val userMessage = ChatMessage(sender = Sender.USER, text = trimmedText)
        val assistantMessageId = UUID.randomUUID().toString()
        val initialAssistantMessage = ChatMessage(
            id = assistantMessageId,
            sender = Sender.ASSISTANT,
            text = "",
            isStreaming = true
        )

        _messages.update { currentList ->
            currentList + userMessage + initialAssistantMessage
        }

        viewModelScope.launch {
            _isGenerating.value = true
            var accumulatedText = ""

            try {
                llmManager.generateResponseStream(trimmedText).collect { chunk ->
                    accumulatedText += chunk
                    _messages.update { currentList ->
                        currentList.map { message ->
                            if (message.id == assistantMessageId) {
                                message.copy(
                                    text = accumulatedText,
                                    isStreaming = true
                                )
                            } else {
                                message
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                accumulatedText += "\n[エラーが発生しました: ${e.localizedMessage}]"
            } finally {
                _messages.update { currentList ->
                    currentList.map { message ->
                        if (message.id == assistantMessageId) {
                            message.copy(
                                text = accumulatedText.ifEmpty { "レスポンスを取得できませんでした。" },
                                isStreaming = false
                            )
                        } else {
                            message
                        }
                    }
                }
                _isGenerating.value = false
            }
        }
    }

    fun clearHistory() {
        _messages.value = listOf(
            ChatMessage(
                sender = Sender.SYSTEM,
                text = "チャット履歴を消去しました。Gemma-4 / AICore に質問してみましょう。"
            )
        )
    }
}
