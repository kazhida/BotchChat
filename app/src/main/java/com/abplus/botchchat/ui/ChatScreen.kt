package com.abplus.botchchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.abplus.botchchat.data.OfflineSpeechOutput
import com.abplus.botchchat.data.ReplySpeechEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abplus.botchchat.data.AiCoreLlmManager.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val modelStatus by viewModel.modelStatus.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val historyLoaded by viewModel.historyLoaded.collectAsState()
    val historyError by viewModel.historyError.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var voiceInputStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val speechOutput = remember(context) { OfflineSpeechOutput(context) }
    val speechStatus by speechOutput.status.collectAsState()
    val isSpeaking by speechOutput.isSpeaking.collectAsState()
    var readAloud by rememberSaveable { mutableStateOf(true) }
    val currentReadAloud by rememberUpdatedState(readAloud)

    DisposableEffect(speechOutput, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) speechOutput.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            speechOutput.close()
        }
    }
    LaunchedEffect(viewModel, speechOutput, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.replySpeechEvents.collect { event ->
                when (event) {
                    ReplySpeechEvent.Start, ReplySpeechEvent.Cancel -> speechOutput.stop()
                    is ReplySpeechEvent.Chunk -> if (currentReadAloud) speechOutput.enqueue(event.text)
                }
            }
        }
    }

    // 新しいメッセージが追加されたら一番下まで自動スクロール
    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("会話履歴を削除しますか？") },
            text = { Text("保存済みの会話履歴と会話のコンテキストをすべて削除します。この操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    enabled = historyLoaded && !isGenerating,
                    onClick = {
                        showDeleteConfirmation = false
                        speechOutput.stop()
                        viewModel.clearHistory()
                    }
                ) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "BotchChat",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        ModelStatusBadge(status = modelStatus)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        readAloud = !readAloud
                        if (!readAloud) speechOutput.stop()
                    }) {
                        Icon(
                            imageVector = if (readAloud) Icons.AutoMirrored.Filled.VolumeUp
                                else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (readAloud) "読み上げをオフにする" else "読み上げをオンにする"
                        )
                    }
                    IconButton(onClick = { viewModel.checkLlmStatus() }, enabled = historyLoaded && !isGenerating && modelStatus != ModelStatus.Checking) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "ステータス再確認"
                        )
                    }
                    IconButton(onClick = { showDeleteConfirmation = true }, enabled = historyLoaded && !isGenerating) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "履歴消去"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // メッセージ一覧
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageBubble(message = message)
                }
            }

            if (!historyLoaded && historyError == null) {
                Text("会話履歴を読み込み中…", modifier = Modifier.padding(16.dp))
            }
            historyError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            (modelStatus as? ModelStatus.NotAvailable)?.let { status ->
                Text(status.reason, modifier = Modifier.padding(16.dp))
            }

            if (readAloud) {
                speechStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (isSpeaking) {
                    Text("読み上げ中… スピーカーボタンで停止できます。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }

            voiceInputStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // テキスト入力部分
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("BotchChatにつぶやく...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            OfflineVoiceInputButton(
                                enabled = !isGenerating,
                                onRecognized = { inputText += it },
                                onStatus = { voiceInputStatus = it },
                                onStart = { speechOutput.stop() }
                            )
                        },
                        enabled = !isGenerating
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            speechOutput.stop()
                            val textToSend = inputText
                            inputText = ""
                            viewModel.sendMessage(textToSend)
                        },
                        enabled = inputText.isNotBlank() && historyLoaded && !isGenerating && modelStatus == ModelStatus.Ready,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (inputText.isNotBlank() && historyLoaded && !isGenerating && modelStatus == ModelStatus.Ready)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = CircleShape
                            )
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "送信",
                                tint = if (inputText.isNotBlank())
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelStatusBadge(status: ModelStatus) {
    val (text, color) = when (status) {
        ModelStatus.Ready -> "Gemma 4 E2B: Ready (On-Device)" to Color(0xFF4CAF50)
        is ModelStatus.NotAvailable -> "Gemma 4 E2B: 利用不可" to Color(0xFF2196F3)
        ModelStatus.Checking -> "Gemma 4 E2B: 読み込み中..." to Color.Gray
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChatMessageBubble(message: ChatMessage) {
    when (message.sender) {
        Sender.USER -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier.padding(start = 48.dp)
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Sender.ASSISTANT -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                    modifier = Modifier.padding(end = 48.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Gemma 4 E2B",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            AnimatedVisibility(
                                visible = message.isStreaming,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        MarkdownReply(
                            text = message.text.ifEmpty { "思考中..." },
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        Sender.SYSTEM -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = message.text,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
