#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

// Include Whisper.cpp headers
#include "whisper.h"

// Include Llama.cpp headers
#include "llama.h"

#define TAG "DograhNativeJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global pointers for Whisper & Llama contexts
static struct whisper_context * g_whisper_ctx = nullptr;
static struct llama_model * g_llama_model = nullptr;
static struct llama_context * g_llama_ctx = nullptr;

// ==========================================
// WHISPER SPEECH-TO-TEXT JNI BINDINGS
// ==========================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dograh_voiceagent_asr_WhisperEngine_nativeInit(
    JNIEnv *env, jobject thiz, jstring model_path) {
    
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Initializing Whisper with model path: %s", path);
    
    // Set system parameters for Whisper
    struct whisper_context_params params = whisper_context_default_params();
    g_whisper_ctx = whisper_init_from_file_with_params(path, params);
    
    env->ReleaseStringUTFChars(model_path, path);
    
    if (g_whisper_ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return JNI_FALSE;
    }
    LOGD("Whisper context initialized successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dograh_voiceagent_asr_WhisperEngine_nativeFeed(
    JNIEnv *env, jobject thiz, jshortArray pcm_data, jint length) {
    
    if (g_whisper_ctx == nullptr) {
        LOGE("Whisper engine is not initialized");
        return JNI_FALSE;
    }

    // Convert short PCM data to float array (Whisper expects 16kHz float mono)
    jshort *samples = env->GetShortArrayElements(pcm_data, nullptr);
    std::vector<float> pcm_float(length);
    for (int i = 0; i < length; ++i) {
        pcm_float[i] = samples[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(pcm_data, samples, JNI_ABORT);

    // Call native whisper process
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.language = "hi"; // Hindi for the Indian market, fallback to English
    params.translate = false;

    int ret = whisper_full(g_whisper_ctx, params, pcm_float.data(), pcm_float.size());
    if (ret != 0) {
        LOGE("Whisper inference run failed: %d", ret);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dograh_voiceagent_asr_WhisperEngine_nativeResult(
    JNIEnv *env, jobject thiz) {
    
    if (g_whisper_ctx == nullptr) {
        return env->NewStringUTF("{\"text\":\"\", \"is_final\":false}");
    }

    std::string text = "";
    int n_segments = whisper_full_n_segments(g_whisper_ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *segment_text = whisper_full_get_segment_text(g_whisper_ctx, i);
        text += segment_text;
    }

    std::string json_result = "{\"text\":\"" + text + "\", \"is_final\":true}";
    return env->NewStringUTF(json_result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_dograh_voiceagent_asr_WhisperEngine_nativeRelease(
    JNIEnv *env, jobject thiz) {
    if (g_whisper_ctx != nullptr) {
        whisper_free(g_whisper_ctx);
        g_whisper_ctx = nullptr;
        LOGD("Whisper native resources released");
    }
}

// ==========================================
// LLAMA REASONING ENGINE JNI BINDINGS
// ==========================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dograh_voiceagent_llm_LlamaEngine_nativeInit(
    JNIEnv *env, jobject thiz, jstring model_path) {
    
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGD("Initializing Llama model from path: %s", path);
    
    llama_backend_init();
    
    // Load model
    auto mparams = llama_model_default_params();
    g_llama_model = llama_load_model_from_file(path, mparams);
    if (g_llama_model == nullptr) {
        LOGE("Failed to load Llama model file");
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }
    
    // Create context
    auto cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_threads = 4;
    g_llama_ctx = llama_new_context_with_model(g_llama_model, cparams);
    
    env->ReleaseStringUTFChars(model_path, path);
    
    if (g_llama_ctx == nullptr) {
        LOGE("Failed to create llama context");
        return JNI_FALSE;
    }
    
    LOGD("Llama engine initialized successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dograh_voiceagent_llm_LlamaEngine_nativeGenerate(
    JNIEnv *env, jobject thiz, jstring prompt) {
    
    if (g_llama_ctx == nullptr || g_llama_model == nullptr) {
        return env->NewStringUTF("{\"reply\":\"\", \"action\":\"none\"}");
    }
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGD("Running LLM generation for prompt: %s", prompt_str);
    
    // Perform llama tokenization and inference (stubbed for concise size, returning mock structural JSON for flow safety)
    std::string response = "{\"reply\": \"Sure, I can help you with your car insurance details. Could you verify your registration number?\", \"action\": \"none\"}";
    
    env->ReleaseStringUTFChars(prompt, prompt_str);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_dograh_voiceagent_llm_LlamaEngine_nativeRelease(
    JNIEnv *env, jobject thiz) {
    if (g_llama_ctx != nullptr) {
        llama_free(g_llama_ctx);
        g_llama_ctx = nullptr;
    }
    if (g_llama_model != nullptr) {
        llama_free_model(g_llama_model);
        g_llama_model = nullptr;
    }
    llama_backend_free();
    LOGD("Llama native resources released");
}

// ==========================================
// PIPER SPEECH SYNTHESIS JNI BINDINGS
// ==========================================

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_dograh_voiceagent_tts_PiperEngine_nativeSynthesize(
    JNIEnv *env, jobject thiz, jstring text) {
    
    const char *input_text = env->GetStringUTFChars(text, nullptr);
    LOGD("Synthesizing speech via Piper for: %s", input_text);
    
    // Piper synthesis outputs a chunk of mono raw 16kHz PCM.
    // Stubbing speech output buffer of 1 second silence for execution safety.
    int dummy_sample_count = 16000;
    std::vector<int16_t> dummy_pcm(dummy_sample_count, 0);

    jshortArray result = env->NewShortArray(dummy_sample_count);
    env->SetShortArrayRegion(result, 0, dummy_sample_count, (jshort*)dummy_pcm.data());
    
    env->ReleaseStringUTFChars(text, input_text);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dograh_voiceagent_tts_PiperEngine_nativeRelease(
    JNIEnv *env, jobject thiz) {
    LOGD("Piper native resources released");
}
