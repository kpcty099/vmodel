package com.dograh.voiceagent.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

class WhisperEngine(private val context: Context) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null
    private var isRecording = false

    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "native-lib loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native-lib: ${e.message}")
        }
    }

    /**
     * Initializes the Whisper model using a model file.
     * @param modelPath Absolute path to the ggml model file.
     * @return true if initialization was successful.
     */
    fun initModel(modelPath: String): Boolean {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file does not exist at path: $modelPath")
            return false
        }
        return nativeInit(modelPath)
    }

    /**
     * Starts streaming microphone audio and transcribing in real-time.
     */
    @SuppressLint("MissingPermission")
    fun startStreaming(onPartialResult: (String) -> Unit) {
        if (isRecording) return
        isRecording = true

        recordingJob = coroutineScope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                isRecording = false
                return@launch
            }

            audioRecord.startRecording()
            val audioBuffer = ShortArray(bufferSize / 2)
            Log.d(TAG, "Recording started...")

            while (isActive && isRecording) {
                val readBytes = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (readBytes > 0) {
                    val actualSamples = ShortArray(readBytes)
                    System.arraycopy(audioBuffer, 0, actualSamples, 0, readBytes)
                    
                    // Feed raw PCM to native Whisper engine
                    val success = nativeFeed(actualSamples, readBytes)
                    if (success) {
                        // Retrieve latest transcription result
                        val jsonResult = nativeResult()
                        // Parse JSON result to get transcribed text
                        try {
                            val json = org.json.JSONObject(jsonResult)
                            val text = json.optString("text", "")
                            if (text.isNotBlank()) {
                                onPartialResult(text)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse result JSON: ${e.message}")
                        }
                    }
                }
                delay(100) // Poll interval
            }

            audioRecord.stop()
            audioRecord.release()
            Log.d(TAG, "Recording stopped.")
        }
    }

    /**
     * Stops the real-time transcription and audio recording.
     */
    fun stopStreaming() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * Transcribes a given static buffer of raw 16kHz 16-bit PCM.
     * @param pcm raw samples
     * @return transcribed text
     */
    fun transcribeBuffer(pcm: ShortArray): String {
        if (pcm.isEmpty()) return ""
        val success = nativeFeed(pcm, pcm.size)
        if (success) {
            val jsonResult = nativeResult()
            try {
                val json = org.json.JSONObject(jsonResult)
                return json.optString("text", "").trim()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse result JSON: ${e.message}")
            }
        }
        return ""
    }

    /**
     * Release all native resources.
     */
    fun release() {
        stopStreaming()
        nativeRelease()
    }

    // ==== Native methods implemented in JNI (native-lib.cpp) ====
    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeFeed(pcm: ShortArray, length: Int): Boolean
    private external fun nativeResult(): String
    private external fun nativeRelease()

    companion object {
        private const val TAG = "WhisperEngine"
    }
}
