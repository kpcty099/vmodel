package com.dograh.voiceagent.tts

import android.content.Context
import android.util.Log

/**
 * Kotlin wrapper for the native Piper TTS library.
 * The native library (libpiper.so) will be built from the `mobile/piper` submodule.
 * This stub exposes a simple `synthesize` method that returns PCM data.
 */
class PiperEngine(private val context: Context) {
    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "Native-lib loaded for PiperEngine")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native-lib for PiperEngine", e)
        }
    }

    /**
     * Convert text to speech PCM (16‑kHz mono).
     * @param text Input string
     * @return ShortArray of PCM samples or null on error
     */
    fun synthesize(text: String): ShortArray? {
        Log.d(TAG, "synthesize called with text: $text")
        return nativeSynthesize(text)
    }

    /** Release native resources */
    fun release() {
        nativeRelease()
    }

    // ==== Native JNI bindings ====
    private external fun nativeSynthesize(text: String): ShortArray?
    private external fun nativeRelease()

    companion object {
        private const val TAG = "PiperEngine"
    }
}
