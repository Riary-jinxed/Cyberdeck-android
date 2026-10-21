package dev.netrunner.launcher.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings

data class BatteryState(val level: Int, val charging: Boolean, val temperatureC: Float?)

data class MemoryState(val availBytes: Long, val totalBytes: Long)

object SystemInfo {

    /**
     * Reads the battery from BatteryManager first (works on every OEM skin),
     * then fills the gaps from the sticky ACTION_BATTERY_CHANGED intent.
     */
    fun battery(context: Context): BatteryState {
        val manager = context.getSystemService(BatteryManager::class.java)
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        var level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (level !in 0..100 && sticky != null) {
            val raw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            level = if (raw >= 0 && scale > 0) raw * 100 / scale else -1
        }

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = manager?.isCharging == true ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }

        return BatteryState(level, charging, temp)
    }

    fun memory(context: Context): MemoryState {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return MemoryState(info.availMem, info.totalMem)
    }

    fun storage(): MemoryState {
        val stat = StatFs(Environment.getDataDirectory().path)
        return MemoryState(stat.availableBytes, stat.totalBytes)
    }

    fun uptimeMillis(): Long = SystemClock.elapsedRealtime()

    /** The name the user gave the phone ("Xiaomi 13 Pro"), or the model code. */
    fun deviceName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
}

fun Long.toGb(): String = String.format(java.util.Locale.FRANCE, "%.1f Go", this / 1_073_741_824.0)
