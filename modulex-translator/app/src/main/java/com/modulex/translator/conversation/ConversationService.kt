package com.modulex.translator.conversation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.modulex.translator.MainActivity
import com.modulex.translator.ModuleXApp
import com.modulex.translator.R
import kotlinx.coroutines.launch

/**
 * Keeps an active conversation (connection and, when enabled, the microphone) alive while the
 * screen is off, with a persistent notification that says so and offers "End".
 */
class ConversationService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_conversation), NotificationManager.IMPORTANCE_LOW))
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, ID, build(getString(R.string.notif_conversation_title)), types)

        val controller = (application as ModuleXApp).container.conversation
        lifecycleScope.launch {
            controller.state.collect { st ->
                val text = when {
                    st.mic != MicState.OFF -> getString(R.string.notif_conversation_mic)
                    st.peer != null -> getString(R.string.notif_conversation_connected, st.peer.displayName.ifBlank { "…" })
                    else -> getString(R.string.conv_waiting)
                }
                nm.notify(ID, build(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_END) {
            (application as ModuleXApp).container.conversation.end()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 1, Intent(this, ConversationService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_conversation_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_end), end)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "conversation"
        private const val ID = 42
        const val ACTION_END = "com.modulex.translator.END_CONVERSATION"
    }
}
