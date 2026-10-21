package dev.netrunner.launcher.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

data class AccessPoint(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val band: String,
    val security: String,
    val standard: String,
    val distanceM: Double,
    val ageSeconds: Long,
    val connected: Boolean,
)

/**
 * Scans nearby Wi-Fi access points. Android limits apps to 4 scans per 2 minutes
 * unless "Wi-Fi scan throttling" is turned off in Developer options; the access point
 * the phone is connected to is refreshed every second regardless.
 */
class WifiScanner(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    private val _points = MutableStateFlow<List<AccessPoint>>(emptyList())
    val points: StateFlow<List<AccessPoint>> = _points.asStateFlow()

    /** True when Android refused our last scan request (throttling). */
    private val _throttled = MutableStateFlow(false)
    val throttled: StateFlow<Boolean> = _throttled.asStateFlow()

    private val history = mutableMapOf<String, ArrayDeque<Int>>()
    private var scanResults: List<ScanResult> = emptyList()
    private var job: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            refreshResults()
            publish()
        }
    }

    val wifiEnabled: Boolean get() = wifi?.isWifiEnabled == true

    fun start(scope: CoroutineScope) {
        if (job != null) return
        if (!registered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = true
        }
        refreshResults()
        publish()
        job = scope.launch {
            var nextScan = 0L
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextScan) {
                    @Suppress("DEPRECATION")
                    val accepted = runCatching { wifi?.startScan() == true }.getOrDefault(false)
                    _throttled.value = !accepted
                    nextScan = now + if (accepted) SCAN_INTERVAL_MS else THROTTLED_RETRY_MS
                }
                // The connected access point updates far more often than scan results.
                publish()
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Last readings (oldest first) for one access point, used for the trend line. */
    fun historyOf(bssid: String): List<Int> = history[bssid]?.toList().orEmpty()

    @SuppressLint("MissingPermission")
    private fun refreshResults() {
        scanResults = runCatching { wifi?.scanResults.orEmpty() }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun publish() {
        val info = runCatching { wifi?.connectionInfo }.getOrNull()
        val connectedBssid = info?.bssid?.lowercase()
        val nowMicros = SystemClock.elapsedRealtime() * 1000

        val list = scanResults.map { result ->
            val bssid = result.BSSID.orEmpty().lowercase()
            val connected = bssid == connectedBssid && info != null && info.rssi > -127
            val rssi = if (connected) info!!.rssi else result.level
            AccessPoint(
                ssid = result.SSID.orEmpty().ifBlank { HIDDEN },
                bssid = bssid,
                rssi = rssi,
                frequencyMhz = result.frequency,
                channel = channelOf(result.frequency),
                band = bandOf(result.frequency),
                security = securityOf(result.capabilities.orEmpty()),
                standard = standardOf(result),
                distanceM = estimateDistance(rssi, result.frequency),
                ageSeconds = ((nowMicros - result.timestamp) / 1_000_000).coerceAtLeast(0),
                connected = connected,
            )
        }.sortedByDescending { it.rssi }

        list.forEach { ap ->
            val readings = history.getOrPut(ap.bssid) { ArrayDeque() }
            readings.addLast(ap.rssi)
            while (readings.size > HISTORY_SIZE) readings.removeFirst()
        }
        _points.value = list
    }

    companion object {
        const val HIDDEN = "‹réseau masqué›"
        private const val SCAN_INTERVAL_MS = 4_000L
        private const val THROTTLED_RETRY_MS = 15_000L
        private const val HISTORY_SIZE = 60

        /**
         * Free-space path loss estimate. Walls and floors absorb signal, so indoors the
         * real access point is usually closer than this number.
         */
        fun estimateDistance(rssi: Int, frequencyMhz: Int): Double {
            if (frequencyMhz <= 0) return -1.0
            val exponent = (27.55 - 20 * log10(frequencyMhz.toDouble()) + abs(rssi)) / 20.0
            return 10.0.pow(exponent)
        }

        fun channelOf(f: Int): Int = when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5160..5885 -> (f - 5000) / 5
            f in 5955..7115 -> (f - 5950) / 5
            else -> 0
        }

        fun bandOf(f: Int): String = when {
            f < 3000 -> "2.4 GHz"
            f < 5925 -> "5 GHz"
            else -> "6 GHz"
        }

        fun securityOf(caps: String): String = when {
            caps.contains("SAE") || caps.contains("WPA3") -> "WPA3"
            caps.contains("EAP") -> "WPA-Entreprise"
            caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            else -> "OUVERT"
        }

        private fun standardOf(result: ScanResult): String =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                when (result.wifiStandard) {
                    4 -> "Wi-Fi 4"
                    5 -> "Wi-Fi 5"
                    6 -> if (result.frequency >= 5925) "Wi-Fi 6E" else "Wi-Fi 6"
                    8 -> "Wi-Fi 7"
                    else -> "legacy"
                }
            } else {
                ""
            }

        /** 0..4 bars for a signal level. */
        fun bars(rssi: Int): Int = when {
            rssi >= -55 -> 4
            rssi >= -65 -> 3
            rssi >= -75 -> 2
            rssi >= -85 -> 1
            else -> 0
        }
    }
}
