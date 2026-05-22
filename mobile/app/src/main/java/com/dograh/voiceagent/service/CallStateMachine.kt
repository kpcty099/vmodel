package com.dograh.voiceagent.service

import android.util.Log

enum class CallState {
    GREETING,
    CONSENT,
    DIALOGUE,
    ESCALATION,
    HANGUP
}

class CallStateMachine {

    var currentState: CallState = CallState.GREETING
        private set

    init {
        Log.d(TAG, "CallStateMachine initialized in GREETING state")
    }

    /**
     * Process input transcript and determine if state should transition.
     * Enforces strict regulatory flow and deterministic escalation triggers.
     * 
     * @param userUtterance The last spoken utterance from the user.
     * @return true if a state transition occurred.
     */
    fun processInput(userUtterance: String): Boolean {
        val cleanedUtterance = userUtterance.lowercase().trim()
        val oldState = currentState

        when (currentState) {
            CallState.GREETING -> {
                // Greeting is spoken automatically on call connection.
                // Transition immediately to CONSENT to wait for user's response.
                currentState = CallState.CONSENT
            }
            CallState.CONSENT -> {
                if (isUserConsenting(cleanedUtterance)) {
                    currentState = CallState.DIALOGUE
                    Log.i(TAG, "User consented. Transitioning to DIALOGUE.")
                } else if (isUserRejecting(cleanedUtterance)) {
                    currentState = CallState.HANGUP
                    Log.i(TAG, "User rejected consent. Transitioning to HANGUP.")
                }
                // If the user's answer is ambiguous, we stay in CONSENT state to ask again.
            }
            CallState.DIALOGUE -> {
                if (isEscalationTriggered(cleanedUtterance)) {
                    currentState = CallState.ESCALATION
                    Log.i(TAG, "Escalation requested. Transitioning to ESCALATION.")
                } else if (isHangupRequested(cleanedUtterance)) {
                    currentState = CallState.HANGUP
                    Log.i(TAG, "User requested hangup. Transitioning to HANGUP.")
                }
            }
            CallState.ESCALATION -> {
                // Once escalation triggers and is processed (transfer command executed), we transition to HANGUP.
                currentState = CallState.HANGUP
            }
            CallState.HANGUP -> {
                // Terminal state
            }
        }

        if (currentState != oldState) {
            Log.d(TAG, "State transitioned from $oldState to $currentState")
            return true
        }
        return false
    }

    fun forceTransition(newState: CallState) {
        val oldState = currentState
        currentState = newState
        Log.d(TAG, "State force-transitioned from $oldState to $currentState")
    }

    fun reset() {
        currentState = CallState.GREETING
        Log.d(TAG, "CallStateMachine reset to GREETING")
    }

    private fun isUserConsenting(text: String): Boolean {
        // Hinglish/Hindi/English consent triggers
        val yesTriggers = listOf(
            "yes", "yeah", "ok", "okay", "sure", "consent", "proceed",
            "haan", "ha", "haji", "haanji", "theek hai", "theek", "chalo",
            "kariye", "karo", "sare", "avunu", "sarega", "cheyyandi" // Telugu for 'do/yes'
        )
        return yesTriggers.any { text.contains(it) }
    }

    private fun isUserRejecting(text: String): Boolean {
        // Hinglish/Hindi/English rejection triggers
        val noTriggers = listOf(
            "no", "nay", "never", "stop", "reject", "dont", "don't",
            "nahi", "na", "nahin", "nako", "mat karo", "oddu", "vaddu" // Telugu for 'don't want'
        )
        return noTriggers.any { text.contains(it) }
    }

    private fun isEscalationTriggered(text: String): Boolean {
        // Multi-language escalation triggers (Human agent request)
        val escalationTriggers = listOf(
            "transfer", "escalate", "agent", "human", "representative", "manager", "supervisor",
            "baat karvao", "officer se baat", "insan se", "aadmi se", "kisi aur se",
            "transfer cheyyandi", "manager to matladandi", "matlada", "escalate cheyyi"
        )
        return escalationTriggers.any { text.contains(it) }
    }

    private fun isHangupRequested(text: String): Boolean {
        val hangupTriggers = listOf(
            "bye", "hangup", "hang up", "disconnect", "close", "end call",
            "band karo", "rakho", "phone rakho", "bye bye", "tata", "vachestha"
        )
        return hangupTriggers.any { text.contains(it) }
    }

    companion object {
        private const val TAG = "CallStateMachine"
    }
}
