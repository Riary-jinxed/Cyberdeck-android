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
        // Open the holocall screen first, so the call card posted by add() is the silent one
        // and Android does not drop its own heads-up card on top of it.
        val shown = runCatching {
            startActivity(
                Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        CallNotifications.screenVisible = shown
        CallManager.add(call)
    }

    override fun onCallRemoved(call: Call) {
        CallManager.remove(call)
    }

    @Deprecated("Replaced by call endpoints on Android 14, still delivered")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audioChanged(audioState)
    }
}
