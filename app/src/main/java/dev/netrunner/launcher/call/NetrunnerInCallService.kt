package dev.netrunner.launcher.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/**
 * Bound by Android's Telecom when Netrunner is the default phone app. Telecom keeps
 * playing the ringtone itself; this service only feeds the holocall screen.
 */
class NetrunnerInCallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        CallManager.attach(this)
        CallManager.onChanged = { calls -> CallNotifications.update(this, calls) }
    }

    override fun onDestroy() {
        CallManager.onChanged = null
        CallManager.detach(this)
        CallNotifications.cancel(this)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        CallManager.add(call)
        // Incoming calls also go through the full-screen notification, which works from the lock screen.
        runCatching {
            startActivity(
                Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCallRemoved(call: Call) {
        CallManager.remove(call)
    }

    @Deprecated("Replaced by call endpoints on Android 14, still delivered")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audioChanged(audioState)
    }
}
