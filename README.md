# V-Model: Low-Latency On-Device Multilingual Telephony AI Voice Agent

**V-Model** is an open-source, completely offline/local, privacy-first mobile telephony calling agent designed for low-latency, speech-to-speech voice interactions. Operating natively on-device, V-Model utilizes advanced gated Voice Activity Detection (VAD), localized state machines, local LLMs/ASR/TTS, lockscreen call overlays, and authentic carrier SIP integrations to deliver an elite voice calling experience without external SaaS API dependencies.

<p align="center">
  <img src="docs/images/architecture_hero.png" alt="V-Model Beautiful Telecom NOC Console" width="85%" style="border-radius: 8px; box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);">
</p>

---

## ⚡ Core Philosophy & Architecture

Most voice AI calling architectures suffer from high latency, massive network bandwidth costs, and severe privacy issues because they route raw audio to SaaS voice orchestrators. 

**V-Model breaks this model** by shifting the entire voice logic, media synthesis, and intelligence layer directly onto the Edge:
*   **On-Device AI Engines**: Embedded JNI wrappers compile C++ engines for automatic speech recognition (`whisper.cpp`), local inference (`llama.cpp`), and voice synthesis (`piper` TTS).
*   **Gated Voice Activity Detection**: Employs dynamic frame-level VAD amplitude gating to completely prevent network chatter, instantly interrupting agent TTS when the user starts speaking.
*   **Decoupled Silent Push Signaling**: Separates call signaling from the ringing UI. High-priority FCM push notifications act as a silent wake-up signal to register PJSIP carrier stacks, prompting the call overlay strictly on receiving the live SIP `INVITE`.
*   **Beautiful Telecom Observability**: Includes a Nothing OS-style NOC (Network Operations Center) debugging dashboard displaying real-time audio amplitudes, localized Call State progress, live transcript bubbles, and exact LLM/ASR/TTS latency telemetry.

```text
                                   --- V-MODEL DECOUPLED CALL FLOW ---
                                   
   [ PSTN / SIP Carrier ]              [ FastAPI Wake Server ]              [ V-Model Android Client ]
             │                                    │                                      │
             │──( Incoming PSTN Call Hook )──────>│                                      │
             │                                    │──( High-Priority Silent FCM Push )──>│ (Partial WakeLock)
             │                                    │                                      │          │
             │                                    │                                      │ [Foreground Service]
             │                                    │                                      │          │
             │<──( Local JNI PJSIP Registration )────────────────────────────────────────│ <(SIP Re-registration)
             │                                                                           │
             │──( SIP INVITE Arrives Over Carrier )─────────────────────────────────────>│
             │                                                                           │──[Launch Overlay UI]
             │                                                                           │  (IncomingCallActivity)
             │<──( User Answers: Establish Full-Duplex Audio Channel )───────────────────│
             │                                                                           │──[Local Voice Loop]
             │                                                                           │  • whisper.cpp (ASR)
             │                                                                           │  • llama.cpp (Local LLM)
             │                                                                           │  • piper (Local TTS)
             │                                                                           │  • sqlite-vec (Local RAG)
```

---

## 🛠️ Unified System Tech Stack

V-Model leverages a native-first C++ stack built to maximize execution efficiency on mobile CPU/NPU cores:

| Subsystem Component | Framework / Engine | Implementation Detail |
| :--- | :--- | :--- |
| **VOIP Telephony Stack** | **PJSIP (JNI Binding)** | Compiles a custom SIP/RTP engine via NDK, registering SIP accounts and handling JNI listener bindings synchronously. |
| **Voice Activity Gating** | **WebRTC VAD C++** | Tracks raw PCM audio amplitudes over 10ms-30ms frames, gating ASR pipeline streams and executing immediate TTS interruption. |
| **Speech Recognition (ASR)** | **Whisper.cpp (GGML)** | Streams 16kHz raw mono audio to an optimized 8-bit quantized Whisper-Base GGML model for low-latency inference. |
| **Inference Engine (LLM)** | **Llama.cpp (GGUF)** | Locally executes `Qwen-1.5B-Instruct` or `Gemma-2B-IT` models using 4-bit/8-bit weight quantization, supporting Hinglish/Telugu code-switching. |
| **Speech Synthesis (TTS)** | **Piper TTS (Raw WAV)** | Generates lifelike 16kHz audio chunks with custom voice models, sending raw buffers directly to Android `AudioTrack` streams. |
| **Local Memory (RAG)** | **Sqlite-Vec (C Extension)** | Loads a native vector database client inside Android's SQLite engine to match conversation embeddings with k-NN cosine queries. |
| **Wake-Up Daemon** | **FastAPI + Firebase Admin** | Small, high-throughput backend webhook service triggered by carrier incoming call webhooks to broadcast silent FCM wakeup tags. |

---

## 📱 Developer Console Dashboard

The V-Model main dashboard (`MainActivity.kt`) utilizes a Nothing OS / Tesla-inspired dark industrial design language to ensure absolute observability during system testing.

### Observability Features:
1.  **System Health Strip**: Live status monitor grids displaying real-time SIP registration, ASR Engine configuration, TTS synthesis rates, VAD talking states, and local vector RAG cache usage.
2.  **Call Sequence Progression**: Interactive, step-by-step visual progression tracking the call lifecycle states (`GREET` ➔ `CONSENT` ➔ `TALK` ➔ `XFER` ➔ `END`).
3.  **Real-Time Amplitude Waveform**: Animated Jetpack Compose Canvas reflecting live amplitude changes and speaker transitions (User Speaking vs Agent Speaking).
4.  **Live Telemetry Transcript bubbles**: Interactive transcript bubble feeds rendering incoming speech-to-text text, alongside complete end-to-end latency metrics (ASR + LLM + TTS execution in milliseconds).
5.  **Telecom Control Panel**: Debug controls containing destination text fields, manual SIP dialing triggers, simulated FCM silent pushes, and local vector memory wipe triggers.

---

## 🧪 Development, Compilation & Simulation Testing

### 1. Native Compilation (CMake & Gradle)
The Android client integrates native C++ engines compiled directly via Android NDK CMake lists. 

```bash
# Build the native library artifacts directly using Gradle wrapper
./gradlew :app:assembleDebug
```

Native wrappers and headers are located inside the `mobile/app/src/main/cpp` folder. The compiler targets `arm64-v8a` and `armeabi-v7a` architectures to ensure maximum performance across hardware platforms.

### 2. SQLite-Vec RAG Integration
Call logs are structured in a standard SQLite database alongside vector embeddings using the `sqlite-vec` extension (`libsqlite_vec.so`). To prevent byte array encoding bugs, V-Model utilizes compile-safe Android `rawQueryWithFactory` queries to bind vector float buffers directly:

```kotlin
val cursor = db.rawQueryWithFactory(
    { _, driver, editTable, queryObj ->
        queryObj.bindBlob(1, queryVectorBlob)
        queryObj.bindLong(2, limit.toLong())
        android.database.sqlite.SQLiteCursor(driver, editTable, queryObj)
    },
    "SELECT c.id, v.distance FROM vec_calls v JOIN calls c ON v.call_id = c.id WHERE v.embedding MATCH ?1 AND k = ?2",
    null,
    null
)
```

### 3. Local Debug Simulation
To test the entire background FCM wake signaling and lockscreen caller overlay path without real SIP credentials, use the local simulator receiver class (`IncomingCallSimulationReceiver.kt`).

Run the following ADB broadcast shell command from your development workstation:

```bash
adb shell am broadcast \
  -a com.dograh.voiceagent.DEBUG_SIMULATE_INCOMING_CALL \
  --es call_id sim-001 \
  --es caller_number +919876543210 \
  -n com.dograh.voiceagent/.debug.IncomingCallSimulationReceiver
```

This triggers the following sequence:
1. Registers a silent FCM broadcast intent containing simulated caller meta parameters.
2. Acquires a short-term 30s background `WAKE_LOCK` to bypass Doze limits.
3. Spawns `AgentService.kt` in the foreground with microphone-service privileges.
4. Brings up the elegant `IncomingCallActivity.kt` keyguard overlay directly over locked devices.

---

## 🤝 Contributing & Standards

We welcome contributions to V-Model! We hold ourselves to strict native engineering standards:
*   Keep native JNI JNI-bridge method signatures aligned with JNI naming structures to prevent runtime crashes.
*   Prioritize local memory optimization. Verify that C++ engines release native pointers appropriately (`llama_free`, `whisper_free`, `pjsua_destroy`).
*   Ensure all database vector queries include standard text fallbacks to maintain system stability even on older Android versions.
*   Keep Jetpack Compose layouts clean, responsive, and aligned with our dark console observability aesthetics.

---

## 🏢 License

V-Model is licensed under the [BSD 2-Clause License](LICENSE) to support open-source development, academic research, and custom enterprise deployments.

*Built with ❤️ for low-latency, privacy-first mobile AI telephony.*
