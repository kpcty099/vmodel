package com.dograh.voiceagent.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dograh.voiceagent.IncomingCallActivity
import com.dograh.voiceagent.service.AgentService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (!isIncomingCallPayload(data)) {
            Log.d(TAG, "Ignoring non-call FCM data message: ${data.keys}")
            return
        }

        val callId = data["call_id"] ?: data["callId"] ?: ""
        val callerNumber = data["caller_number"]
            ?: data["callerNumber"]
            ?: data["from"]
            ?: "Unknown Caller"

        val wakeLock = acquireWakeLock(callId)
        try {
            startAgentForIncomingCall(callId, callerNumber)
            showIncomingCallSurface(callId, callerNumber)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun isIncomingCallPayload(data: Map<String, String>): Boolean {
        val type = data["type"] ?: data["event"] ?: data["action"]
        return type == "incoming_call" || data.containsKey("caller_number") || data.containsKey("callerNumber")
    }

    private fun acquireWakeLock(callId: String): PowerManager.WakeLock {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:IncomingCallPush:$callId"
        ).apply {
            setReferenceCounted(false)
            acquire(TEMP_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun startAgentForIncomingCall(callId: String, callerNumber: String) {
        val serviceIntent = Intent(this, AgentService::class.java).apply {
            action = AgentService.ACTION_PREPARE_INCOMING_CALL
            putExtra(AgentService.EXTRA_CALL_ID, callId)
            putExtra(AgentService.EXTRA_CALLER_NUMBER, callerNumber)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showIncomingCallSurface(callId: String, callerNumber: String) {
        createIncomingCallChannel()

        val activityIntent = IncomingCallActivity.createIntent(this, callId, callerNumber).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenIntent = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming V-Model Call")
            .setContentText(callerNumber)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(INCOMING_CALL_NOTIFICATION_ID, notification)

        try {
            startActivity(activityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen activity launch deferred to notification: ${e.message}")
        }
    }

    private fun createIncomingCallChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            INCOMING_CALL_CHANNEL_ID,
            "Incoming V-Model Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen incoming VoIP call alerts"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MyFirebaseMessagingService"
        private const val INCOMING_CALL_CHANNEL_ID = "DograhIncomingCalls"
        private const val INCOMING_CALL_NOTIFICATION_ID = 2001
        private const val TEMP_WAKE_LOCK_TIMEOUT_MS = 30_000L
    }
}
