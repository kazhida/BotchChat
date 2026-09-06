package com.abplus.botchchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.abplus.botchchat.ui.ChatScreen
import com.abplus.botchchat.ui.ChatViewModel
import com.abplus.botchchat.ui.theme.BotchChatTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BotchChatTheme {
                ChatScreen(viewModel = viewModel)
            }
        }
    }
}
