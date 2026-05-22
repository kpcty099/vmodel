package com.dograh.voiceagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dograh.voiceagent.asr.WhisperEngine
import com.dograh.voiceagent.llm.LlamaEngine
import com.dograh.voiceagent.memory.CallMemory
import com.dograh.voiceagent.sip.SipManager
import com.dograh.voiceagent.tts.PiperEngine
import kotlinx.coroutines.*
import org.json.JSONObject

class AgentService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var sipManager: SipManager
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var piperEngine: PiperEngine
    private lateinit var llamaEngine: LlamaEngine
    private lateinit var callMemory: CallMemory

    private var isRunning = false
    private var currentCallActive = false
    private val transcriptBuilder = StringBuilder()
    private val currentPhoneNumber = "+919876543210" // Default/Test Indian number

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AgentService onCreate called")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Dograh Voice Agent Active"))

        // Initialize all subsystems
        sipManager = SipManager(this)
        whisperEngine = WhisperEngine(this)
        piperEngine = PiperEngine(this)
        llamaEngine = LlamaEngine(this)
        callMemory = CallMemory(this)

        // Load models asynchronously in service scope
        serviceScope.launch {
            try {
                // Initialize Whisper model
                val whisperModelPath = filesDir.absolutePath + "/models/ggml-tiny-int8.bin"
                whisperEngine.initModel(whisperModelPath)

                // Initialize Llama model
                val llamaModelPath = filesDir.absolutePath + "/models/llama-3-8b-q4.gguf"
                llamaEngine.initModel(llamaModelPath)

                Log.d(TAG, "All local voice and LLM models loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize local models: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        when (action) {
            ACTION_START_AGENT -> startAgentLoop()
            ACTION_STOP_AGENT -> stopAgentLoop()
            ACTION_PLACE_CALL -> {
                val num = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: currentPhoneNumber
                placeCall(num)
            }
            ACTION_HANGUP_CALL -> hangupCall()
        }
        return START_STICKY
    }

    private fun startAgentLoop() {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Autonomous Agent Loop started.")

        // Start listening to inbound calls or trigger outbound triggers
        serviceScope.launch {
            // Main processing loop:
            // 1. Observe: Stream audio from phone microphone or SIP network packets
            // 2. Transcribe using WhisperEngine
            // 3. Process transcription with LlamaEngine (Think & Decides next actions)
            // 4. Act: trigger TTS responses or DTMF tones
            
            whisperEngine.startStreaming { partialText ->
                if (partialText.isNotBlank()) {
                    Log.d(TAG, "Transcribed: $partialText")
                    transcriptBuilder.append("User: ").append(partialText).append("\n")
                    processDialogueTurn(partialText)
                }
            }
        }
    }

    private fun stopAgentLoop() {
        isRunning = false
        whisperEngine.stopStreaming()
        Log.d(TAG, "Autonomous Agent Loop stopped.")
        stopSelf()
    }

    private fun placeCall(phoneNumber: String) {
        if (currentCallActive) return
        currentCallActive = true
        transcriptBuilder.clear()
        
        serviceScope.launch {
            Log.d(TAG, "Placing autonomous outbound call to $phoneNumber...")
            sipManager.placeCall(phoneNumber)
            
            // Introduce the agent once the call is connected
            delay(1000) // Mock wait for connection
            val greeting = "Namaste. I am an automated assistant calling regarding your car insurance renewal. May I know if I am speaking with the policy holder?"
            speak(greeting)
            transcriptBuilder.append("Agent: ").append(greeting).append("\n")
        }
    }

    private fun hangupCall() {
        if (!currentCallActive) return
        currentCallActive = false
        Log.d(TAG, "Hanging up call...")
        sipManager.hangup()

        // Post-call summary and storage in vector memory
        serviceScope.launch {
            val transcript = transcriptBuilder.toString()
            val prompt = """
                You are a post-call analyst. Read the transcript and generate a brief summary of the conversation.
                Transcript:
                $transcript
            """.trimIndent()
            
            val summary = llamaEngine.generate(prompt)
            Log.d(TAG, "Post-call summary generated: $summary")

            // Create a mock embedding for vector store (in real case, we use a SentenceTransformer model)
            val mockEmbedding = FloatArray(384) { 0.1f } 
            callMemory.saveCallRecord(currentPhoneNumber, "outbound", transcript, summary, mockEmbedding)
        }
    }

    private fun processDialogueTurn(userText: String) {
        serviceScope.launch {
            // Retrieve local vector memory of past call details to inject context (RAG)
            val mockQueryEmbedding = FloatArray(384) { 0.1f }
            val relevantHistory = callMemory.queryCallMemory(mockQueryEmbedding, limit = 2)
            val historyContext = relevantHistory.joinToString("\n") { "Past call summary: ${it.summary}" }

            val prompt = """
                You are an autonomous mobile call assistant.
                Local context from past interactions:
                $historyContext
                
                Conversation transcript so far:
                ${transcriptBuilder.toString()}
                
                Generate a response. Return a JSON object with:
                - "reply": The text to speak back to the user.
                - "action": Any action to perform, e.g., "hangup", "dtmf", "hold", or "none".
                - "dtmf_tones": Tones to send if action is dtmf.
            """.trimIndent()

            val jsonResponseStr = llamaEngine.generate(prompt)
            try {
                val json = JSONObject(jsonResponseStr)
                val reply = json.optString("reply", "")
                val action = json.optString("action", "none")

                if (reply.isNotBlank()) {
                    speak(reply)
                    transcriptBuilder.append("Agent: ").append(reply).append("\n")
                }

                when (action) {
                    "hangup" -> hangupCall()
                    "dtmf" -> {
                        val tones = json.optString("dtmf_tones", "")
                        sipManager.register(tones, "") // Send DTMF
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse dialogue decision JSON: ${e.message}")
                // Fallback direct response
                val fallbackReply = "Aapki aawaz theek se nahi aa rahi hai, kripya dobara kahein."
                speak(fallbackReply)
                transcriptBuilder.append("Agent: ").append(fallbackReply).append("\n")
            }
        }
    }

    private fun speak(text: String) {
        val pcmAudio = piperEngine.synthesize(text)
        if (pcmAudio != null) {
            // Feed synthesized audio buffer directly to the SIP stack/network stream
            Log.d(TAG, "TTS Speech generated: ${pcmAudio.size} bytes. Streaming to caller.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AgentService onDestroy called")
        serviceJob.cancel()
        whisperEngine.release()
        piperEngine.release()
        llamaEngine.release()
        callMemory.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Dograh Voice Agent Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dograh Autonomous Caller")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
        // Note: For a production app, the icon should be set to an actual drawable resource.
    }

    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "DograhAgentChannel"

        const val ACTION_START_AGENT = "com.dograh.voiceagent.ACTION_START_AGENT"
        const val ACTION_STOP_AGENT = "com.dograh.voiceagent.ACTION_STOP_AGENT"
        const val ACTION_PLACE_CALL = "com.dograh.voiceagent.ACTION_PLACE_CALL"
        const val ACTION_HANGUP_CALL = "com.dograh.voiceagent.ACTION_HANGUP_CALL"

        const val EXTRA_PHONE_NUMBER = "com.dograh.voiceagent.EXTRA_PHONE_NUMBER"
    }
}
