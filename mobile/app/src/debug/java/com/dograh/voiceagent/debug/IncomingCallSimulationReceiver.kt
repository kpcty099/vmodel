package com.dograh.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dograh.voiceagent.IncomingCallActivity
import com.dograh.voiceagent.service.AgentService

class IncomingCallSimulationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: "debug-call"
        val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: "+919999000111"
        val wakeLock = acquireWakeLock(context, callId)

        try {
            val serviceIntent = Intent(context, AgentService::class.java).apply {
                action = AgentService.ACTION_PREPARE_INCOMING_CALL
                putExtra(AgentService.EXTRA_CALL_ID, callId)
                putExtra(AgentService.EXTRA_CALLER_NUMBER, callerNumber)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val activityIntent = IncomingCallActivity.createIntent(context, callId, callerNumber).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(activityIntent)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun acquireWakeLock(context: Context, callId: String): PowerManager.WakeLock {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:DebugIncomingCall:$callId"
        ).apply {
            setReferenceCounted(false)
            acquire(TEMP_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    companion object {
        const val ACTION_SIMULATE_INCOMING_CALL = "com.dograh.voiceagent.DEBUG_SIMULATE_INCOMING_CALL"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_NUMBER = "caller_number"
        private const val TEMP_WAKE_LOCK_TIMEOUT_MS = 30_000L
    }
}
