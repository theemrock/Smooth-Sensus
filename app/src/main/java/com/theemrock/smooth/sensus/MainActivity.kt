package com.theemrock.smooth.sensus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.theemrock.smooth.sensus.ui.LocalModelViewModel
import com.theemrock.smooth.sensus.ui.ChatViewModel
import com.theemrock.smooth.sensus.ui.MainViewModel
import com.theemrock.smooth.sensus.ui.SmoothApp
import com.theemrock.smooth.sensus.ui.theme.SmoothTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val localModelViewModel: LocalModelViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModels { ChatViewModel.factory(application) }
    private var requestedConversationId by mutableStateOf<String?>(null)
    private var requestedConversationVersion by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        if (requestedConversationId != null) requestedConversationVersion++
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            SmoothTheme {
                SmoothApp(
                    viewModel = viewModel,
                    localModelViewModel = localModelViewModel,
                    chatViewModel = chatViewModel,
                    requestedConversationId = requestedConversationId,
                    requestedConversationVersion = requestedConversationVersion,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        if (requestedConversationId != null) requestedConversationVersion++
    }

    companion object { const val EXTRA_CONVERSATION_ID = "chat_conversation_id" }
}
