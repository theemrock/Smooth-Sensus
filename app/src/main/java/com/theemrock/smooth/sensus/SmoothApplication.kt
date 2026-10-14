package com.theemrock.smooth.sensus

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.theemrock.smooth.sensus.conversation.*
import com.theemrock.smooth.sensus.inference.InferenceChatMessage
import com.theemrock.smooth.sensus.inference.LocalModelRuntime
import com.theemrock.smooth.sensus.inference.LocalModelRuntimeBoundary
import com.theemrock.smooth.sensus.ui.ChatCompletionNotifier

class SmoothApplication : Application(), Application.ActivityLifecycleCallbacks {
    val localModelRuntime: LocalModelRuntimeBoundary by lazy { LocalModelRuntime() }
    val chatRuntimeSettings by lazy { ChatRuntimeSettingsStore(getSharedPreferences("chat_runtime", MODE_PRIVATE)) }
    val chatInferenceGateway = ChatInferenceGateway { messages, maxTokens, enableThinking ->
        localModelRuntime.generateChat(
            messages.map { InferenceChatMessage(if (it.role == MessageRole.USER) "user" else "assistant", it.content) },
            enableThinking = enableThinking, maxTokens = maxTokens,
        ).text
    }
    val chatCompletionNotifier = ChatCompletionNotifier { id -> if (startedActivities == 0) showResponseReady(id) }
    private var startedActivities = 0

    override fun onCreate() { super.onCreate(); registerActivityLifecycleCallbacks(this); createNotificationChannel() }
    override fun onActivityStarted(activity: Activity) { startedActivities++ }
    override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun createNotificationChannel() = getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Chat yanıtları", NotificationManager.IMPORTANCE_DEFAULT)
    )
    private fun showResponseReady(conversationId: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name)).setContentText("Yanıt hazır").setAutoCancel(true).setContentIntent(pending).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
    private companion object { const val CHANNEL_ID = "chat_responses"; const val NOTIFICATION_ID = 7001 }
}
