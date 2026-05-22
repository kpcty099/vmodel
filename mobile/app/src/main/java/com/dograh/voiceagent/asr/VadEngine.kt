package com.dograh.voiceagent.asr

import android.util.Log

class VadEngine {

    interface VadListener {
        fun onSpeechStarted()
        fun onSpeechStopped()
    }

    private var isSpeechDetected = false
    private var listener: VadListener? = null

    // Handover thresholds to prevent toggling flutter
    private var speechFramesCount = 0
    private var silentFramesCount = 0
    private val SPEECH_TRIGGER_FRAMES = 2   // Requires ~60ms of speech to trigger
    private val SILENT_TRIGGER_FRAMES = 12  // Requires ~360ms of silence to end speech

    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "Native-lib loaded for VadEngine")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native-lib for VadEngine", e)
        }
        nativeInit()
    }

    fun setListener(vadListener: VadListener) {
        this.listener = vadListener
    }

    /**
     * Process an incoming audio frame of raw 16kHz 16-bit PCM.
     * Updates internal tracking and fires listener events on transition.
     * 
     * @param pcm Frame samples
     * @return true if speech is currently active
     */
    fun processFrame(pcm: ShortArray): Boolean {
        // Run native voice activity check
        val isFrameActive = nativeProcessFrame(pcm, pcm.size)

        if (isFrameActive) {
            speechFramesCount++
            silentFramesCount = 0
            if (!isSpeechDetected && speechFramesCount >= SPEECH_TRIGGER_FRAMES) {
                isSpeechDetected = true
                Log.i(TAG, "Speech Started detected by VAD")
                listener?.onSpeechStarted()
            }
        } else {
            silentFramesCount++
            speechFramesCount = 0
            if (isSpeechDetected && silentFramesCount >= SILENT_TRIGGER_FRAMES) {
                isSpeechDetected = false
                Log.i(TAG, "Speech Stopped detected by VAD")
                listener?.onSpeechStopped()
            }
        }

        return isSpeechDetected
    }

    fun reset() {
        nativeReset()
        isSpeechDetected = false
        speechFramesCount = 0
        silentFramesCount = 0
        Log.d(TAG, "VadEngine reset")
    }

    fun release() {
        nativeRelease()
    }

    // ==== Native JNI Bindings ====
    private external fun nativeInit(): Boolean
    private external fun nativeProcessFrame(pcm: ShortArray, length: Int): Boolean
    private external fun nativeReset()
    private external fun nativeRelease()

    companion object {
        private const val TAG = "VadEngine"
    }
}
