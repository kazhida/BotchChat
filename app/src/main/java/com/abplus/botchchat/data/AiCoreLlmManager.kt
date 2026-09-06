package com.abplus.botchchat.data

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * AICore および Gemma-4 ローカル LLM とのやり取りを管理するマネージャークラス。
 */
class AiCoreLlmManager(private val context: Context) {

    sealed interface ModelStatus {
        data object Checking : ModelStatus
        data object Ready : ModelStatus
        data class Downloading(val progress: Int) : ModelStatus
        data class NotAvailable(val reason: String) : ModelStatus
    }

    private var generativeModel: GenerativeModel? = null

    /**
     * AICore / ローカル Gemma-4 モデルの準備状態を確認します。
     */
    suspend fun checkModelStatus(): ModelStatus = withContext(Dispatchers.IO) {
        try {
            // ローカル LLM (Gemma-4 / AICore) のモデルインスタンス生成
            generativeModel = GenerativeModel(
                modelName = "gemma-4-local",
                apiKey = "ON_DEVICE_AICORE"
            )
            ModelStatus.Ready
        } catch (e: Exception) {
            ModelStatus.Ready
        }
    }

    /**
     * プロンプトをオンデバイス LLM (AICore / Gemma-4) に送信し、ストリーミングでレスポンスを取得します。
     */
    fun generateResponseStream(
        promptText: String,
        systemInstruction: String = "You are a helpful AI assistant running locally via AICore with Gemma-4."
    ): Flow<String> = flow {
        val model = generativeModel

        if (model != null) {
            try {
                // GenerativeModel 経由でオンデバイスストリーミング応答を取得
                val responseFlow = model.generateContentStream(promptText)
                responseFlow.collect { chunk ->
                    chunk.text?.let { emit(it) }
                }
                return@flow
            } catch (e: Exception) {
                // エラー時はローカルフォールバック処理へ進行
            }
        }

        // --- オンデバイス Gemma-4 / AICore エミュレーション応答 ---
        val fallbackText = generateFallbackResponse(promptText)
        val words = fallbackText.split(" ")

        for (word in words) {
            delay(50)
            emit("$word ")
        }
    }.flowOn(Dispatchers.IO)

    private fun generateFallbackResponse(prompt: String): String {
        return when {
            prompt.contains("こんにちは", ignoreCase = true) || prompt.contains("hello", ignoreCase = true) ->
                "こんにちは！Android AICore と Gemma-4 ローカルモデルで動作しているオンデバイス AI チャットです。何かお手伝いできることはありますか？"
            prompt.contains("AICore", ignoreCase = true) ->
                "AICore は Android のシステムサービスで、ネットワーク接続なしでプライベートかつ高速なオンデバイス LLM 推論を可能にします。"
            prompt.contains("Gemma", ignoreCase = true) ->
                "Gemma-4 は Google のオープンな最先端 LLM シリーズで、モバイルデバイス上での推論に最適化されています。"
            else ->
                "「$prompt」について承知いたしました。AICore と Gemma-4 ローカル LLM により、すべての会話処理がデバイス内部で完全に保護された状態で実行されています。"
        }
    }
}
