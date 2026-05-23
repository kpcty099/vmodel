# Security Policy - V-Model

At V-Model, we are committed to maintaining a highly secure, privacy-first, on-device voice agent architecture. Because all speech-to-speech processing, inference, and call logging occurs completely locally on the user's device, maintaining local data integrity is our highest priority. 

## Supported Versions

We recommend keeping your V-Model Android client and notification wakeup backend updated to the latest release to ensure you receive all security patches.

| Version | Supported |
| ------- | --------- |
| >= 1.0  | ✅ Yes    |
| < 1.0   | ❌ No     |

## Reporting a Vulnerability

If you discover a security vulnerability in the local database encryption, native memory handling, JNI interfaces, or the FastAPI push signaling server, please do not disclose it publicly. 

Please report vulnerabilities by opening a private security advisory through the repository:
[Report a Security Vulnerability](https://github.com/kpcty099/vmodel/security/advisories/new)

Thank you for helping us keep edge-voice AI safe and private for everyone!