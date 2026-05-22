package com.dograh.voiceagent.llm

import android.content.Context
import android.util.Log

/**
 * Kotlin wrapper around the native llama.cpp library.
 * The native library (libllama.so) will be compiled from the `mobile/llama.cpp` submodule
 * using the Android NDK. This stub provides a simple `generate` method that returns a JSON
 * string with the model response and any actions.
 */
class LlamaEngine(private val context: Context) {
    init {
        try {
            System.loadLibrary("native-lib")
            Log.d(TAG, "Native-lib loaded for LlamaEngine")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native-lib for LlamaEngine", e)
        }
    }

    /**
     * Initialize the model with the given .gguf file path.
     * @param modelPath absolute path to the quantized model file.
     * @return true if successful.
     */
    fun initModel(modelPath: String): Boolean {
        return nativeInit(modelPath)
    }

    /**
     * Run a prompt and get a JSON response.
     * The JSON is expected to contain fields like `{ "reply": "...", "actions": [] }`.
     */
    fun generate(prompt: String): String {
        return nativeGenerate(prompt)
    }

    /** Release native resources */
    fun release() {
        nativeRelease()
    }

    // ==== JNI native methods ====
    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeGenerate(prompt: String): String
    private external fun nativeRelease()

    companion object {
        private const val TAG = "LlamaEngine"
    }
}
