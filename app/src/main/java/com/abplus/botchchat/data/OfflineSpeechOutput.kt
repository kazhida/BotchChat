package com.abplus.botchchat.data

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner of an installed Japanese voice that does not require a network. */
class OfflineSpeechOutput(context: Context) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking = _isSpeaking.asStateFlow()
    private var initialized = false
    private var ready = false
    private var closed = false
    private val pendingTexts = ArrayDeque<String>()
    private var utteranceSequence = 0L
    private var generation = 0L
    private var lastUtterance: String? = null
    private var tts: TextToSpeech? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { result ->
                // Always enqueue: the constructor must finish before we access tts.
                handler.post { initialize(result) }
            }
        } catch (_: RuntimeException) {
            initialized = true
            _status.value = "音声読み上げを初期化できませんでした。"
        }
    }

    private fun initialize(result: Int) {
        if (closed) return
        initialized = true
        val engine = tts
        if (result != TextToSpeech.SUCCESS || engine == null) {
            fail("音声読み上げを初期化できませんでした。")
            return
        }
        try {
            val voice = engine.voices.orEmpty()
                .filter { it.locale.language == "ja" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "JP" }
                    .thenByDescending { it.quality }.thenBy { it.name })
                .firstOrNull()
            if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
                fail("日本語のオフライン読み上げ音声がありません。端末の音声合成設定で事前に準備してください。")
                return
            }
            engine.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (!closed && utteranceId == lastUtterance) {
                            _isSpeaking.value = false
                            lastUtterance = null
                        }
                    }
                }
                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
                override fun onError(utteranceId: String?, errorCode: Int) {
                    handler.post {
                        if (!closed && utteranceId?.startsWith("$generation:") == true) {
                            fail("オフライン読み上げに失敗しました（$errorCode）。")
                        }
                    }
                }
            })
            ready = true
            val waiting = pendingTexts.toList()
            pendingTexts.clear()
            waiting.forEach { enqueue(it) }
        } catch (_: RuntimeException) {
            fail("オフライン読み上げ音声を準備できませんでした。")
        }
    }

    fun enqueue(text: String) {
        if (closed || text.isBlank()) return
        if (!initialized) {
            pendingTexts.addLast(text)
            return
        }
        if (!ready) return
        _status.value = null
        val engine = tts ?: return
        try {
            val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
            _isSpeaking.value = true
            val params = Bundle().apply {
                // Explicitly request embedded synthesis in addition to selecting an offline voice.
                putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
            }
            chunks.forEach { chunk ->
                val utteranceId = "$generation:${utteranceSequence++}"
                lastUtterance = utteranceId
                if (engine.speak(chunk, TextToSpeech.QUEUE_ADD, params, utteranceId) != TextToSpeech.SUCCESS) {
                    fail("オフライン読み上げを開始できませんでした。")
                    return
                }
            }
        } catch (_: RuntimeException) {
            fail("オフライン読み上げに失敗しました。")
        }
    }

    fun stop() {
        generation++
        pendingTexts.clear()
        lastUtterance = null
        _isSpeaking.value = false
        runCatching { tts?.stop() }
    }

    private fun fail(message: String) {
        stop()
        _status.value = message
    }

    override fun close() {
        closed = true
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }
}
