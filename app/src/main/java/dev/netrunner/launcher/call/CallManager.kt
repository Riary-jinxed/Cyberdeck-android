package dev.netrunner.launcher.call

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallInfo(
    val call: Call,
    val state: Int,
    val number: String,
    val displayName: String?,
    val connectTimeMillis: Long,
    val incoming: Boolean,
    val canHold: Boolean,
) {
    val ringing: Boolean get() = state == Call.STATE_RINGING
    val onHold: Boolean get() = state == Call.STATE_HOLDING
    val active: Boolean get() = state == Call.STATE_ACTIVE
}

/** Calls handed to us by Android's Telecom while Netrunner is the default phone app. */
object CallManager {

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio.asStateFlow()

    /** Called after every change, used by the service to refresh its notification. */
    internal var onChanged: ((List<CallInfo>) -> Unit)? = null

    private var service: InCallService? = null
    private val tracked = mutableListOf<Call>()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    internal fun attach(s: InCallService) {
        service = s
    }

    internal fun detach(s: InCallService) {
        if (service === s) service = null
    }

    internal fun add(call: Call) {
        if (call in tracked) return
        tracked += call
        call.registerCallback(callback)
        publish()
    }

    internal fun remove(call: Call) {
        call.unregisterCallback(callback)
        tracked -= call
        publish()
    }

    internal fun audioChanged(state: CallAudioState?) {
        _audio.value = state
    }

    fun answer(info: CallInfo) = info.call.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun reject(info: CallInfo) = info.call.reject(false, null)

    fun hangUp(info: CallInfo) = info.call.disconnect()

    fun toggleHold(info: CallInfo) {
        if (info.onHold) info.call.unhold() else info.call.hold()
    }

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    fun dtmf(info: CallInfo, digit: Char) {
        info.call.playDtmfTone(digit)
        info.call.stopDtmfTone()
    }

    @Suppress("DEPRECATION")
    private fun stateOf(call: Call): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) call.details.state else call.state

    private fun publish() {
        val list = tracked.map { call ->
            val details = call.details
            CallInfo(
                call = call,
                state = stateOf(call),
                number = details.handle?.schemeSpecificPart.orEmpty(),
                displayName = details.contactDisplayNameCompat() ?: details.callerDisplayName?.takeIf { it.isNotBlank() },
                connectTimeMillis = details.connectTimeMillis,
                incoming = details.callDirection == Call.Details.DIRECTION_INCOMING,
                canHold = details.can(Call.Details.CAPABILITY_HOLD),
            )
        }.filter { it.state != Call.STATE_DISCONNECTED }
        _calls.value = list
        onChanged?.invoke(list)
    }

    private fun Call.Details.contactDisplayNameCompat(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) contactDisplayName?.takeIf { it.isNotBlank() } else null
}
