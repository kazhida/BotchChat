package com.abplus.botchchat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

/** Uses only the on-device recognizer; never requests a model download or network fallback. */
@Composable
internal fun OfflineVoiceInputButton(
    enabled: Boolean,
    onRecognized: (String) -> Unit,
    onStatus: (String?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnRecognized by rememberUpdatedState(onRecognized)
    val currentOnStatus by rememberUpdatedState(onStatus)
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun release() {
        val active = recognizer
        recognizer = null // Ignore callbacks from cancelled or completed sessions.
        active?.cancel()
        active?.destroy()
    }

    fun start() {
        if (!currentEnabled || recognizer != null ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            currentOnStatus("この端末はオフライン音声認識に対応していません。")
            return
        }
        try {
            val active = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = active
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            fun fail(message: String) {
                if (recognizer !== active) return
                release()
                currentOnStatus(message)
            }
            active.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (recognizer === active) currentOnStatus("お話しください。もう一度押すと音声入力を中止します。")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (recognizer === active) currentOnStatus("音声を文字に変換しています…")
                }
                override fun onError(error: Int) = fail(when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "日本語のオフライン音声認識モデルが利用できません。"
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声を認識できませんでした。もう一度お試しください。"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "音声入力にはマイクの使用許可が必要です。"
                    else -> "オフライン音声認識に失敗しました（$error）。"
                })
                override fun onResults(results: Bundle?) {
                    if (recognizer !== active) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    release()
                    if (text.isNotEmpty()) {
                        currentOnRecognized(text)
                        currentOnStatus(null)
                    } else {
                        currentOnStatus("音声を認識できませんでした。もう一度お試しください。")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            currentOnStatus("オフライン音声認識を準備しています…")
            active.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (recognizer !== active) return
                    val japaneseInstalled = support.installedOnDeviceLanguages.any {
                        Locale.forLanguageTag(it.replace('_', '-')).language == "ja"
                    }
                    if (!japaneseInstalled) {
                        fail("日本語のオフライン音声認識モデルが端末にありません。事前に端末の音声入力設定で準備してください。")
                        return
                    }
                    try {
                        active.startListening(intent)
                    } catch (_: RuntimeException) {
                        fail("オフライン音声認識を開始できませんでした。")
                    }
                }
                override fun onError(error: Int) {
                    fail("オフライン音声認識の利用可否を確認できませんでした（$error）。")
                }
            })
        } catch (_: RuntimeException) {
            release()
            currentOnStatus("オフライン音声認識を開始できませんでした。")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start()
        else currentOnStatus("音声入力にはマイクの使用許可が必要です。")
    }

    LaunchedEffect(enabled) {
        if (!enabled && recognizer != null) {
            release()
            currentOnStatus(null)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                release()
                currentOnStatus(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            release()
        }
    }

    IconButton(
        enabled = enabled,
        onClick = {
            if (recognizer != null) {
                release()
                currentOnStatus(null)
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                start()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    ) {
        Icon(
            imageVector = if (recognizer != null) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (recognizer != null) "音声入力を中止" else "オフラインで日本語を音声入力"
        )
    }
}
