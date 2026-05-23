# V-Model - Core System Map & Project Directory Guide

V-Model is a low-latency, on-device multilingual telephony AI voice agent system. The codebase is designed as a unified monorepo containing a high-performance native-first Android application, a silent FCM push notification wake-up server, and admin management portals.

## Project Structure

```
vmodel/
├── mobile/             # Primary Subsystem - High-Performance Android Client Application
│   ├── app/            # Android Gradle Application (Kotlin UI, services, JNI bridge)
│   ├── llama.cpp/      # Local LLM Inference Engine Submodule (GGUF runner)
│   ├── whisper.cpp/    # Local ASR Speech-to-Text Submodule (GGML audio stream model)
│   ├── piper/          # Local TTS Text-to-Speech Submodule (raw WAV buffer generation)
│   ├── pjsip/          # Telephony carrier SIP/RTP engine (C/C++ NDK bindings)
│   └── sqlite-vec/     # Local database vector k-NN embedding SQLite extension
├── api/                # Secondary Subsystem - FastAPI Push Wake-Up Notification Server
│   ├── routes/         # Endpoint routing (incoming call webhooks, status checks)
│   ├── db/             # SQLAlchemy configurations & schema modeling
│   ├── mcp_server/     # Model Context Protocol surface for administrative tools
│   ├── services/       # domain logic (e.g. Firebase Admin Push wake-up notifications)
│   └── tests/          # Pytest backend validation suites
├── ui/                 # Web Portal - Next.js Administrative Management Dashboard
└── docs/               # Technical Documentation
```

---

## Technical Architecture & Design Paradigms

Developers working on V-Model should adhere to our core design principles:

### 1. Unified State Flow (Single Source of Truth)
The Android client utilizes a central, process-shared state manager within the foreground agent service (`AgentService.kt`). All subsystems (ASR transcriptions, Call timeline states, VAD gating triggers, and PJSIP connection status) register updates directly to this single store (`AgentUiState` Flow). The Nothing OS-style Dashboard (`MainActivity.kt`) collects this state flow reactively.

### 2. High-Performance Native JNI Linking
Avoid introducing heavy high-level wrappers for audio processing. We stream raw 16kHz audio streams directly from the Android `AudioRecord` MIC capture layer (configured with hardware echo cancellation) through JNI pointers to `whisper.cpp` and `VadEngine.kt` at the C++ layer. Native operations are mapped directly inside `native-lib.cpp`.
*   Ensure that any JNI call matches the package space signature exactly (e.g., `Java_com_dograh_voiceagent_...`) to avoid runtime linking failures.

### 3. Decoupled silent push signaling
To maintain battery efficiency and eliminate phantom rings under weak signal environments:
*   Incoming carrier hooks trigger a silent high-priority FCM notification push payload via the FastAPI backend (`api/services/push_notification`).
*   The Android client receives this payload via `MyFirebaseMessagingService.kt` under a partial background `WakeLock` to re-register the native PJSIP carrier stack.
*   The call overlay screen `IncomingCallActivity.kt` is triggered *only* upon actual receipt of the carrier's incoming SIP `INVITE`, never on raw push arrival.

---

## Environment & Run Configurations

### A. FastAPI Backend Environment Setup
1.  Navigate to `api/` directory.
2.  Set up backend credentials inside `api/.env`.
3.  Specify Firebase private service account credentials for push broadcasting.

Typical execution:
```bash
# Launch FastAPI backend webhook server
uvicorn api.app:app --reload --port 8000
```

### B. Android Native Compilations
1.  Ensure you have Android Studio with CMake and Android NDK installed.
2.  Import the `mobile/` directory as a Gradle project.
3.  NDK tasks compile automatically via CMake integration.

For offline debug testing:
```bash
# Trigger an FCM VoIP push simulation directly via ADB
adb shell am broadcast \
  -a com.dograh.voiceagent.DEBUG_SIMULATE_INCOMING_CALL \
  --es call_id sim-001 \
  --es caller_number +919876543210 \
  -n com.dograh.voiceagent/.debug.IncomingCallSimulationReceiver
```
