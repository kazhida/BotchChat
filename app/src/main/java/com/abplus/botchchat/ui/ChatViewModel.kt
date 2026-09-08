package com.abplus.botchchat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abplus.botchchat.data.AiCoreLlmManager
import com.abplus.botchchat.data.AiCoreLlmManager.ModelStatus
import com.abplus.botchchat.data.ChatHistory
import com.abplus.botchchat.data.ChatHistoryStore
import com.abplus.botchchat.data.StreamingSpeechBuffer
import com.abplus.botchchat.data.ReplySpeechEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val llmManager = AiCoreLlmManager(application.applicationContext)
    private val historyStore = ChatHistoryStore(File(application.filesDir, "chat-history.json"))
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()
    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus = _modelStatus.asStateFlow()
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()
    private val _historyLoaded = MutableStateFlow(false)
    val historyLoaded = _historyLoaded.asStateFlow()
    private val _historyError = MutableStateFlow<String?>(null)
    val historyError = _historyError.asStateFlow()
    private var statusJob: Job? = null
    // Transient events: restored history must never trigger speech.
    private val _replySpeechEvents = MutableSharedFlow<ReplySpeechEvent>()
    internal val replySpeechEvents = _replySpeechEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                _messages.value = withContext(Dispatchers.IO) { historyStore.load() }
                // Persist pruning and finalize any response interrupted by the previous process.
                if (persistHistory()) {
                    _historyLoaded.value = true
                    checkLlmStatus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _historyError.value = "履歴を読み込めません。保存済みファイルは変更していません: ${e.localizedMessage}"
            }
        }
    }

    fun checkLlmStatus() {
        if (!_historyLoaded.value || _isGenerating.value || statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            _modelStatus.value = ModelStatus.Checking
            _modelStatus.value = llmManager.checkModelStatus()
        }
    }

    private suspend fun persistHistory(snapshot: List<ChatMessage> = _messages.value): Boolean {
        return try {
            withContext(Dispatchers.IO) { historyStore.save(snapshot) }
            _historyError.value = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _historyError.value = "履歴を保存できません: ${e.localizedMessage}"
            false
        }
    }

    fun sendMessage(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || !_historyLoaded.value || _isGenerating.value ||
            _modelStatus.value != ModelStatus.Ready) return
        _isGenerating.value = true
        val userMessage = ChatMessage(sender = Sender.USER, text = text)
        val assistant = ChatMessage(sender = Sender.ASSISTANT, text = "", isStreaming = true,
            includeInContext = false)
        _messages.update { (it + userMessage + assistant).takeLast(ChatHistory.MAX_MESSAGES) }
        // Context contains only retained messages preceding this request, never the request twice.
        val history = _messages.value.dropLast(2)
        viewModelScope.launch {
            val speechBuffer = StreamingSpeechBuffer()
            val accumulated = StringBuilder()
            var completed = false
            var lastSaved = System.nanoTime()
            var lastDisplayed = lastSaved
            try {
                check(persistHistory()) { "履歴を保存できなかったため送信を中止しました。" }
                _replySpeechEvents.emit(ReplySpeechEvent.Start)
                llmManager.generateResponseStream(text, history).collect { chunk ->
                    accumulated.append(chunk)
                    // Deliver speech first. Unbuffered events hand control to the active UI collector.
                    speechBuffer.append(chunk).forEach { spoken ->
                        _replySpeechEvents.emit(ReplySpeechEvent.Chunk(spoken))
                    }
                    val now = System.nanoTime()
                    if (now - lastDisplayed >= 300_000_000L) {
                        updateAssistant(assistant.id, accumulated.toString(), streaming = true, completed = false)
                        lastDisplayed = now
                    }
                    if (now - lastSaved >= 500_000_000L) {
                        // Save all received text, including text not yet published to the screen.
                        persistHistory(_messages.value.map { message ->
                            if (message.id == assistant.id) message.copy(text = accumulated.toString()) else message
                        })
                        lastSaved = System.nanoTime()
                    }
                }
                speechBuffer.finish().forEach { spoken ->
                    _replySpeechEvents.emit(ReplySpeechEvent.Chunk(spoken))
                }
                completed = accumulated.isNotBlank()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { _replySpeechEvents.emit(ReplySpeechEvent.Cancel) }
                throw e
            } catch (e: Exception) {
                _replySpeechEvents.emit(ReplySpeechEvent.Cancel)
                accumulated.append("\n[エラーが発生しました: ${e.localizedMessage}]")
            } finally {
                updateAssistant(assistant.id, accumulated.toString().ifEmpty { "レスポンスを取得できませんでした。" },
                    streaming = false, completed = completed)
                withContext(NonCancellable) { persistHistory() }
                _isGenerating.value = false
            }
        }
    }

    private fun updateAssistant(id: String, text: String, streaming: Boolean, completed: Boolean) {
        _messages.update { messages ->
            messages.map { message ->
                if (message.id == id) message.copy(text = text, isStreaming = streaming,
                    includeInContext = completed) else message
            }
        }
    }

    fun clearHistory() {
        if (_isGenerating.value || !_historyLoaded.value) return
        _isGenerating.value = true
        viewModelScope.launch {
            val previous = _messages.value
            _messages.value = emptyList()
            try {
                if (!persistHistory()) _messages.value = previous
            } finally {
                _isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        CoroutineScope(Dispatchers.IO).launch { llmManager.close() }
    }
}
