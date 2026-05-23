# Contributing to V-Model ❤️

Thank you for your interest in contributing to V-Model—the low-latency, on-device multilingual telephony AI voice agent! 

V-Model is fully open-source. Because it combines native C++ engines, Kotlin services, and Python signaling backends, we follow systematic standards to ensure the codebase remains stable, secure, and extremely performant.

---

## 🏗️ Technical Architecture Overview

V-Model consists of two core systems:
1.  **Android Client (`mobile/`)**: Native application containing our core pipelines:
    *   **PJSIP**: Standard carrier VoIP stack integration.
    *   **whisper.cpp**: Automatic speech recognition.
    *   **llama.cpp**: Local GGUF LLM execution (with Hinglish/Telugu routing support).
    *   **piper**: Local text-to-speech engine.
    *   **sqlite-vec**: Embedded vector database for local conversation RAG.
2.  **Wakeup Server (`api/`)**: Lightweight FastAPI server designed to catch incoming carrier webhooks and dispatch silent, high-priority FCM notifications to wake the Android client.

---

## 🛠️ Contribution Guidelines

We welcome contributions across all areas of the project. To maintain system stability, please follow these guidelines:

### 1. Code Quality & Formatting
*   **Kotlin / Android UI**: Ensure Jetpack Compose components follow clean dark industrial design principles. Avoid heavy asset allocations in render loops.
*   **C++ / JNI**: Keep your JNI methods clean and aligned with the `com.dograh.voiceagent` namespace. Release all native allocations inside destructor interfaces to prevent memory leaks.
*   **Python**: Follow PEP 8 styles for FastAPI endpoints. Keep route handlers thin; all core notification logic belongs in `api/services/`.

### 2. Submitting Pull Requests
1.  Fork the repository and create your feature branch:
    ```bash
    git checkout -b feature/AmazingFeature
    ```
2.  Commit your changes following standard prefix guidelines (e.g. `feat(android): add German whisper support`, `fix(jni): resolve audio buffer overflow`).
3.  Push to your fork and submit a Pull Request to our `main` branch.

### 3. Reporting Issues & Feedback
*   Report bugs or request enhancements by opening a [GitHub Issue](https://github.com/kpcty099/vmodel/issues).
*   For questions or suggestions, join our repository discussions.

Thank you for helping us make privacy-first voice AI calling open and accessible! 🚀
