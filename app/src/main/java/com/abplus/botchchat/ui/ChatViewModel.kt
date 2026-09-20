package com.abplus.botchchat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abplus.botchchat.data.LiteRtLmManager
import com.abplus.botchchat.data.LiteRtLmManager.ModelStatus
import com.abplus.botchchat.data.ChatHistory
import com.abplus.botchchat.data.ChatHistoryStore
import com.abplus.botchchat.data.StreamingSpeechBuffer
import com.abplus.botchchat.data.ReplySpeechEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

private const val NORMAL_DISPLAY_INTERVAL_NANOS = 300_000_000L
private const val SPEECH_FIRST_DISPLAY_INTERVAL_NANOS = 1_000_000_000L
private const val HISTORY_SAVE_INTERVAL_NANOS = 500_000_000L

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val llmManager = LiteRtLmManager(application.applicationContext)
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
    private val actionMutex = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Transient events: restored history must never trigger speech.
    private val _replySpeechEvents = MutableSharedFlow<ReplySpeechEvent>()
    internal val replySpeechEvents = _replySpeechEvents.asSharedFlow()
    private val _readAloud = MutableStateFlow(true)

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

    fun downloadModel() {
        if (!_historyLoaded.value || _isGenerating.value || statusJob?.isActive == true) return
        statusJob = viewModelScope.launch {
            _modelStatus.value = ModelStatus.Downloading(null)
            _modelStatus.value = llmManager.downloadModel { percent ->
                _modelStatus.value = ModelStatus.Downloading(percent)
            }
            if (_modelStatus.value == ModelStatus.Ready) {
                _modelStatus.value = llmManager.checkModelStatus()
            }
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

    fun setReadAloudEnabled(enabled: Boolean) {
        _readAloud.value = enabled
    }

    fun sendMessage(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || !_historyLoaded.value ||
            _modelStatus.value != ModelStatus.Ready || !actionMutex.tryLock()) return
        if (_isGenerating.value) {
            actionMutex.unlock()
            return
        }
        _isGenerating.value = true
        val userMessage = ChatMessage(sender = Sender.USER, text = text)
        val assistant = ChatMessage(sender = Sender.ASSISTANT, text = "", isStreaming = true,
            includeInContext = false)
        _messages.update { (it + userMessage + assistant).takeLast(ChatHistory.MAX_MESSAGES) }
        // Context contains only retained messages preceding this request, never the request twice.
        val history = _messages.value.dropLast(2)
        viewModelScope.launch {
            var speechBuffer = StreamingSpeechBuffer()
            val accumulated = StringBuilder()
            var completed = false
            var lastSaved = System.nanoTime()
            var lastDisplayed = lastSaved
            try {
                check(persistHistory()) { "履歴を保存できなかったため送信を中止しました。" }
                if (_readAloud.value) _replySpeechEvents.emit(ReplySpeechEvent.Start)
                llmManager.generateResponseStream(text, history).collect { chunk ->
                    accumulated.append(chunk)
                    val readAloud = _readAloud.value
                    if (readAloud) {
                        // Deliver speech first. Unbuffered events hand control to the active UI collector.
                        speechBuffer.append(chunk).forEach { spoken ->
                            _replySpeechEvents.emit(ReplySpeechEvent.Chunk(spoken))
                        }
                    } else {
                        speechBuffer = StreamingSpeechBuffer()
                    }
                    val now = System.nanoTime()
                    val displayInterval = if (readAloud) SPEECH_FIRST_DISPLAY_INTERVAL_NANOS
                        else NORMAL_DISPLAY_INTERVAL_NANOS
                    if (now - lastDisplayed >= displayInterval) {
                        updateAssistant(assistant.id, accumulated.toString(), streaming = true, completed = false)
                        lastDisplayed = now
                    }
                    if (now - lastSaved >= HISTORY_SAVE_INTERVAL_NANOS) {
                        // Save all received text, including text not yet published to the screen.
                        persistHistory(_messages.value.map { message ->
                            if (message.id == assistant.id) message.copy(text = accumulated.toString()) else message
                        })
                        lastSaved = System.nanoTime()
                    }
                }
                if (_readAloud.value) {
                    speechBuffer.finish().forEach { spoken ->
                        _replySpeechEvents.emit(ReplySpeechEvent.Chunk(spoken))
                    }
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
                actionMutex.unlock()
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
        if (!_historyLoaded.value || !actionMutex.tryLock()) return
        if (_isGenerating.value) {
            actionMutex.unlock()
            return
        }
        _isGenerating.value = true
        viewModelScope.launch {
            val previous = _messages.value
            _messages.value = emptyList()
            try {
                if (!persistHistory()) _messages.value = previous
            } finally {
                _isGenerating.value = false
                actionMutex.unlock()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeScope.launch {
            try {
                llmManager.close()
            } finally {
                closeScope.cancel()
            }
        }
    }
}
