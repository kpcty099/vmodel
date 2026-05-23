package com.dograh.voiceagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dograh.voiceagent.asr.WhisperEngine
import com.dograh.voiceagent.asr.VadEngine
import com.dograh.voiceagent.llm.LlamaEngine
import com.dograh.voiceagent.llm.PromptRouter
import com.dograh.voiceagent.llm.DetectedLanguage
import com.dograh.voiceagent.memory.CallMemory
import com.dograh.voiceagent.sip.SipManager
import com.dograh.voiceagent.tts.PiperEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class AgentService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var sipManager: SipManager
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var piperEngine: PiperEngine
    private lateinit var llamaEngine: LlamaEngine
    private lateinit var callMemory: CallMemory
    private lateinit var vadEngine: VadEngine
    private lateinit var callStateMachine: CallStateMachine
    private lateinit var promptRouter: PromptRouter
    private var callWakeLock: PowerManager.WakeLock? = null

    private var isRunning = false
    private var currentCallActive = false
    private val transcriptBuilder = StringBuilder()
    private var currentPhoneNumber = "+919876543210" // Default/Test Indian number

    private var beepJob: Job? = null
    private var toneGenerator: android.media.ToneGenerator? = null

    // Audio Track variables for TTS Playback and Interruption
    private var ttsAudioTrack: AudioTrack? = null
    private var isTtsSpeaking = false
    private var ttsSpeakingJob: Job? = null

    // Audio Record variables for VAD-gated ASR input
    private var isAudioRecording = false
    private var audioRecordJob: Job? = null
    private val accumulatedAudio = mutableListOf<Short>()
    private var isSpeechActive = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AgentService onCreate called")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Dograh Voice Agent Active"))

        // Initialize ToneGenerator for Indian regulatory compliance (audible beep every 15s)
        try {
            toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 80)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator: ${e.message}")
        }

        // Initialize all subsystems
        sipManager = SipManager(this)
        whisperEngine = WhisperEngine(this)
        piperEngine = PiperEngine(this)
        llamaEngine = LlamaEngine(this)
        callMemory = CallMemory(this)
        vadEngine = VadEngine()
        callStateMachine = CallStateMachine()
        promptRouter = PromptRouter()

        initAudioTrack()
        setupVadCallbacks()
        setupSipCallbacks()

        // Load models asynchronously in service scope
        serviceScope.launch {
            try {
                // Initialize Whisper model
                val whisperModelPath = filesDir.absolutePath + "/models/ggml-tiny-int8.bin"
                whisperEngine.initModel(whisperModelPath)

                // Initialize Llama model
                val llamaModelPath = filesDir.absolutePath + "/models/qwen-1_5b-instruct-q4.gguf"
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
        // Prepare local simulated media stack or PJSIP endpoints
        sipManager.initializeStack()
        sipManager.register("exotel_did_user", "exotel_password") // standard registration parameters
        startAudioCapture()
    }

    private fun stopAgentLoop() {
        isRunning = false
        stopAudioCapture()
        stopBeepTimer()
        stopSpeaking()
        Log.d(TAG, "Autonomous Agent Loop stopped.")
        stopSelf()
    }

    private fun startBeepTimer() {
        stopBeepTimer() // Safeguard
        beepJob = serviceScope.launch {
            while (isActive && currentCallActive) {
                delay(15000) // Delay 15s to respect conversational cadence
                if (currentCallActive) {
                    try {
                        // Play compliance beep tone to notify of call recording in India
                        toneGenerator?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                        Log.d(TAG, "Compliance recording beep tone played.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error playing beep tone: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopBeepTimer() {
        beepJob?.cancel()
        beepJob = null
    }

    private fun setupSipCallbacks() {
        sipManager.setListener(object : SipManager.SipListener {
            override fun onCallRinging() {
                Log.d(TAG, "SIP Telephony Channel: Outbound Ringing...")
            }

            override fun onCallConnected() {
                Log.d(TAG, "SIP Telephony Channel: Connected! Initializing conversation flow.")
                startBeepTimer()

                // Present audible recording notification consent on connection
                val consentWarning = "Namaste. Yeh call ek automatic voice agent dwara record ki ja rahi hai. Kya hum aage baat kar sakte hain? This call is being recorded by an automated voice assistant. Do you consent to proceed?"
                speak(consentWarning)
                transcriptBuilder.append("Agent: ").append(consentWarning).append("\n")

                // Explicitly force state machine into Consent tracking mode
                callStateMachine.forceTransition(CallState.CONSENT)
            }

            override fun onCallDisconnected() {
                Log.d(TAG, "SIP Telephony Channel: Disconnected.")
                currentCallActive = false
                stopBeepTimer()
                stopSpeaking()
                releaseCallWakeLock()

                // Post-call summary and storage in vector memory
                serviceScope.launch {
                    val transcript = transcriptBuilder.toString()
                    if (transcript.isNotBlank()) {
                        val prompt = """
                            You are a post-call analyst. Read the transcript and generate a brief summary of the conversation.
                            Transcript:
                            $transcript
                        """.trimIndent()

                        val summary = llamaEngine.generate(prompt)
                        Log.d(TAG, "Post-call summary generated: $summary")

                        val mockEmbedding = FloatArray(384) { 0.1f }
                        callMemory.saveCallRecord(currentPhoneNumber, "outbound", transcript, summary, mockEmbedding)
                    }
                }
            }

            override fun onIncomingCall(callerNumber: String) {
                Log.d(TAG, "Incoming PSTN/SIP Call from $callerNumber. Triggering automatic VoIP answer...")
                currentPhoneNumber = callerNumber
                currentCallActive = true
                transcriptBuilder.clear()
                callStateMachine.reset()
                acquireCallWakeLock()
                sipManager.answerCall()
            }
        })
    }

    private fun placeCall(phoneNumber: String) {
        if (currentCallActive) return
        currentCallActive = true
        currentPhoneNumber = phoneNumber
        transcriptBuilder.clear()
        callStateMachine.reset()
        acquireCallWakeLock()
        
        serviceScope.launch {
            Log.d(TAG, "Placing autonomous outbound call to $phoneNumber...")
            sipManager.initializeStack()
            sipManager.register("exotel_did_user", "exotel_password") // Telecom provider registration pass
            sipManager.placeCall(phoneNumber)
        }
    }

    private fun hangupCall() {
        if (!currentCallActive) return
        currentCallActive = false
        stopBeepTimer()
        stopSpeaking()
        Log.d(TAG, "Hanging up call...")
        sipManager.hangup()
        releaseCallWakeLock()
    }

    // ==========================================
    // CANCELLABLE SPEECH PLAYBACK & INTERRUPTION
    // ==========================================

    private fun initAudioTrack() {
        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            ttsAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                
            ttsAudioTrack?.play()
            Log.d(TAG, "TTS AudioTrack initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack: ${e.message}")
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        stopSpeaking() // Terminate current playback first

        ttsSpeakingJob = serviceScope.launch(Dispatchers.Default) {
            isTtsSpeaking = true
            val pcmAudio = piperEngine.synthesize(text)
            if (pcmAudio != null && isActive && isTtsSpeaking) {
                Log.d(TAG, "Playing synthesized speech chunk of size ${pcmAudio.size} samples")
                
                // Write in chunks to allow fine-grained interruption check
                val chunkSize = 3200 // ~200ms blocks
                var offset = 0
                while (isActive && isTtsSpeaking && offset < pcmAudio.size) {
                    val writeSize = minOf(chunkSize, pcmAudio.size - offset)
                    ttsAudioTrack?.write(pcmAudio, offset, writeSize)
                    offset += writeSize
                }
            }
            isTtsSpeaking = false
        }
    }

    private fun stopSpeaking() {
        isTtsSpeaking = false
        ttsSpeakingJob?.cancel()
        ttsSpeakingJob = null
        try {
            ttsAudioTrack?.stop()
            ttsAudioTrack?.flush()
            ttsAudioTrack?.play() // Re-prime for subsequent play
            Log.d(TAG, "TTS interrupted and AudioTrack flushed")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
        }
    }

    // ==========================================
    // AUDIO RECORDING AND VAD PIPELINE
    // ==========================================

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (isAudioRecording) return
        isAudioRecording = true
        vadEngine.reset()

        audioRecordJob = serviceScope.launch(Dispatchers.Default) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            // 30ms frames of audio is ideal for WebRTC and custom VAD gating: 16000 * 0.03 = 480 samples
            val frameSize = 480 
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
                frameSize * 4
            )

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            var acousticEchoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord for agent service")
                audioRecord.release()
                isAudioRecording = false
                return@launch
            }

            try {
                val audioSessionId = audioRecord.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                        enabled = true
                    }
                    Log.d(TAG, "AcousticEchoCanceler ${if (acousticEchoCanceler != null) "enabled" else "unavailable"} for session $audioSessionId")
                } else {
                    Log.w(TAG, "AcousticEchoCanceler is not available on this device")
                }

                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                        enabled = true
                    }
                    Log.d(TAG, "NoiseSuppressor ${if (noiseSuppressor != null) "enabled" else "unavailable"} for session $audioSessionId")
                } else {
                    Log.w(TAG, "NoiseSuppressor is not available on this device")
                }

                audioRecord.startRecording()
                val audioFrame = ShortArray(frameSize)
                Log.d(TAG, "Voice communication microphone capture loop initialized.")

                while (isActive && isAudioRecording) {
                    val readSamples = audioRecord.read(audioFrame, 0, frameSize)
                    if (readSamples > 0) {
                        val actualFrame = ShortArray(readSamples)
                        System.arraycopy(audioFrame, 0, actualFrame, 0, readSamples)

                        // Process through VAD Engine
                        val speechActive = vadEngine.processFrame(actualFrame)

                        if (speechActive) {
                            synchronized(accumulatedAudio) {
                                accumulatedAudio.addAll(actualFrame.toList())
                            }
                        }
                    }
                }
            } finally {
                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
                }
                acousticEchoCanceler?.release()
                noiseSuppressor?.release()
                audioRecord.release()
                Log.d(TAG, "Microphone capture loop terminated.")
            }
        }
    }

    private fun stopAudioCapture() {
        isAudioRecording = false
        audioRecordJob?.cancel()
        audioRecordJob = null
    }

    private fun setupVadCallbacks() {
        vadEngine.setListener(object : VadEngine.VadListener {
            override fun onSpeechStarted() {
                // Active user speech detected: perform duplex voice interruption
                if (isTtsSpeaking) {
                    Log.i(TAG, "User started speaking while TTS is active. Executing instant duplex interruption.")
                    stopSpeaking()
                }
                isSpeechActive = true
                synchronized(accumulatedAudio) {
                    accumulatedAudio.clear()
                }
            }

            override fun onSpeechStopped() {
                isSpeechActive = false
                Log.i(TAG, "User stopped speaking. Processing speech input...")
                processSpeechInput()
            }
        })
    }

    private fun processSpeechInput() {
        val audioSamples: ShortArray
        synchronized(accumulatedAudio) {
            audioSamples = accumulatedAudio.toShortArray()
            accumulatedAudio.clear()
        }

        if (audioSamples.isEmpty()) return

        serviceScope.launch(Dispatchers.Default) {
            // Retrieve transcription using event-driven on-device ASR buffer execution
            val userText = whisperEngine.transcribeBuffer(audioSamples)
            if (userText.isNotBlank()) {
                Log.d(TAG, "Final user transcription: $userText")
                transcriptBuilder.append("User: ").append(userText).append("\n")
                
                // Enforce compliance and state machine logical routing
                val stateChanged = callStateMachine.processInput(userText)
                
                when (callStateMachine.currentState) {
                    CallState.CONSENT -> handleConsentState(userText)
                    CallState.DIALOGUE -> handleDialogueState(userText)
                    CallState.ESCALATION -> handleEscalationState()
                    CallState.HANGUP -> handleHangupState()
                    CallState.GREETING -> { /* Handled on connection */ }
                }
            }
        }
    }

    // ==========================================
    // DIALOGUE FLOWS & COMPLIANCE ENFORCEMENT
    // ==========================================

    private fun handleConsentState(userText: String) {
        // If we are still in CONSENT state, user did not provide clear yes/no. Ask again.
        val consentReminder = "Kripya spasht roop se kahein, kya aap aage baat karna chahte hain? Please state clearly if you consent to proceed with this recorded automated call."
        speak(consentReminder)
        transcriptBuilder.append("Agent (Reminder): ").append(consentReminder).append("\n")
    }

    private fun handleDialogueState(userText: String) {
        serviceScope.launch {
            // 1. Dynamic Lang preference detection
            val detectedLang = promptRouter.detectLanguage(userText)
            
            // 2. Vector Store memory query (local SQLite-Vec)
            val mockQueryEmbedding = FloatArray(384) { 0.1f }
            val relevantHistory = callMemory.queryCallMemory(mockQueryEmbedding, limit = 2)
            val historyContext = relevantHistory.joinToString("\n") { "Past call summary: ${it.summary}" }

            // 3. Prompt selection optimized for lightweight instruction-following model (e.g. Qwen/Gemma)
            val systemPrompt = promptRouter.getSystemPrompt(detectedLang, historyContext)
            
            // Use Qwen/Gemma-optimized chat template structure for perfect formatting
            val formattedPrompt = """
                <|im_start|>system
                $systemPrompt
                <|im_end|>
                <|im_start|>user
                Conversation transcript so far:
                ${transcriptBuilder.toString()}
                
                User says: $userText
                <|im_end|>
                <|im_start|>assistant
            """.trimIndent()

            val jsonResponseStr = llamaEngine.generate(formattedPrompt)
            try {
                val json = JSONObject(jsonResponseStr)
                val reply = json.optString("reply", "")
                val action = json.optString("action", "none")

                if (reply.isNotBlank()) {
                    speak(reply)
                    transcriptBuilder.append("Agent: ").append(reply).append("\n")
                }

                when (action) {
                    "hangup" -> {
                        delay(2000) // Let final speech play out before hanging up
                        hangupCall()
                    }
                    "dtmf" -> {
                        val tones = json.optString("dtmf_tones", "")
                        sipManager.sendDtmf(tones) // Transmit DTMF
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse dialogue decision JSON: ${e.message}")
                val fallbackReply = "Aapki aawaz theek se nahi aa rahi hai. Kripya dobara kahein."
                speak(fallbackReply)
                transcriptBuilder.append("Agent: ").append(fallbackReply).append("\n")
            }
        }
    }

    private fun handleEscalationState() {
        serviceScope.launch {
            val transferMessage = "Aapka call senior executive ko transfer kiya ja raha hai. Kripya line par bane rahein. Transferring your call to a representative, please hold."
            speak(transferMessage)
            transcriptBuilder.append("Agent: ").append(transferMessage).append("\n")
            
            // Wait for speaking to start and stream
            delay(4000)
            
            // Execute actual SIP transfer protocol
            sipManager.transferCall("sip:transfer_trunk_india@exotel_route")
            
            // Handover state machine to terminate local loop
            callStateMachine.forceTransition(CallState.HANGUP)
            hangupCall()
        }
    }

    private fun handleHangupState() {
        serviceScope.launch {
            val exitMessage = "Dhanyavaad. Yeh call ab samapt ho rahi hai. Thank you, the call is now disconnecting."
            speak(exitMessage)
            transcriptBuilder.append("Agent: ").append(exitMessage).append("\n")
            
            delay(3000)
            hangupCall()
        }
    }

    // ==========================================
    // LIFECYCLE DESTRUCTION
    // ==========================================

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AgentService onDestroy called")
        stopAudioCapture()
        stopSpeaking()
        releaseCallWakeLock()
        
        serviceJob.cancel()
        whisperEngine.release()
        piperEngine.release()
        llamaEngine.release()
        vadEngine.release()
        callMemory.close()
        
        try {
            ttsAudioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
        }
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
    }

    private fun acquireCallWakeLock() {
        if (callWakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        callWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AgentServiceCall"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "Call wake lock acquired")
    }

    private fun releaseCallWakeLock() {
        val wakeLock = callWakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock.release()
            Log.d(TAG, "Call wake lock released")
        }
        callWakeLock = null
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
