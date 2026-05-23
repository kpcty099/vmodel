package com.dograh.voiceagent.sip

import android.content.Context
import android.util.Log

class SipManager(private val context: Context) {

    interface SipListener {
        fun onCallRinging()
        fun onCallConnected()
        fun onCallDisconnected()
        fun onIncomingCall(callerNumber: String)
    }

    private var listener: SipListener? = null
    private var isNativeLibLoaded = false
    private var simulatedActiveCall = false

    // Configurable Telephony details
    var sipRegistrar: String = "sip.exotel.com"
    var sipProxy: String = ""
    var outboundPort: Int = 5060

    init {
        try {
            System.loadLibrary("native-lib")
            isNativeLibLoaded = true
            Log.d(TAG, "Native JNI Sip bindings loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLibLoaded = false
            Log.w(TAG, "Native library not loaded. Running SipManager in High-Fidelity Simulation Mode.")
        }
    }

    fun setListener(sipListener: SipListener) {
        this.listener = sipListener
    }

    fun initializeStack(): Boolean {
        if (isNativeLibLoaded) {
            return try {
                nativeSipInit(sipRegistrar, sipProxy, outboundPort)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipInit: ${e.message}")
                false
            }
        }
        Log.i(TAG, "Simulated PJSIP stack initialized successfully")
        return true
    }

    fun register(username: String, secret: String) {
        Log.d(TAG, "Registering SIP account: $username with registrar $sipRegistrar")
        if (isNativeLibLoaded) {
            try {
                val success = nativeSipRegister(username, secret)
                Log.d(TAG, "Native registration call executed: success=$success")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipRegister: ${e.message}")
            }
            return
        }
        Log.i(TAG, "Simulated registration complete for $username")
    }

    fun placeCall(sipUri: String) {
        Log.d(TAG, "Placing SIP call to: $sipUri")
        if (isNativeLibLoaded) {
            try {
                val success = nativeSipPlaceCall(sipUri)
                Log.d(TAG, "Native call initiation executed: success=$success")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipPlaceCall: ${e.message}")
            }
            return
        }

        // Simulate outbound calling flow
        simulatedActiveCall = true
        listener?.onCallRinging()
        
        // Simulating the ringing duration and answer connection sequence
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (simulatedActiveCall) {
                Log.i(TAG, "Simulated SIP call connected")
                listener?.onCallConnected()
            }
        }, 1500)
    }

    fun answerCall() {
        Log.d(TAG, "Answering incoming SIP call")
        if (isNativeLibLoaded) {
            try {
                nativeSipAnswerCall()
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipAnswerCall")
            }
            return
        }
        listener?.onCallConnected()
    }

    fun sendDtmf(tones: String) {
        Log.d(TAG, "Sending DTMF Tones: $tones")
        if (isNativeLibLoaded) {
            try {
                nativeSipSendDtmf(tones)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipSendDtmf")
            }
            return
        }
        Log.i(TAG, "Simulated DTMF tones [$tones] transmitted successfully")
    }

    fun transferCall(transferUri: String) {
        Log.d(TAG, "Initiating SIP REFER transfer to: $transferUri")
        if (isNativeLibLoaded) {
            try {
                nativeSipTransfer(transferUri)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipTransfer")
            }
            return
        }
        Log.i(TAG, "Simulated SIP Call transfer executed to $transferUri")
        hangup()
    }

    fun hangup() {
        Log.d(TAG, "Terminating current call")
        if (isNativeLibLoaded) {
            try {
                nativeSipHangup()
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed JNI link: nativeSipHangup")
            }
            return
        }
        simulatedActiveCall = false
        listener?.onCallDisconnected()
    }

    // ==== Native JNI Telephony Bindings ====
    private external fun nativeSipInit(registrar: String, proxy: String, port: Int): Boolean
    private external fun nativeSipRegister(username: String, secret: String): Boolean
    private external fun nativeSipPlaceCall(uri: String): Boolean
    private external fun nativeSipAnswerCall()
    private external fun nativeSipSendDtmf(tones: String)
    private external fun nativeSipTransfer(transferUri: String)
    private external fun nativeSipHangup()

    companion object {
        private const val TAG = "SipManager"
    }
}
