package dev.suite

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.io.File

/** Reads SoC temperatures from /sys/class/thermal and battery temperature from the battery broadcast. */
object Thermal {
    private const val TAG = "TempPill"

    private var cpuFiles: List<File> = emptyList()
    private var gpuFiles: List<File> = emptyList()
    private var discovered = false

    private fun discover() {
        if (discovered) return
        discovered = true
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: emptyArray()
        val cpu = ArrayList<File>()
        val gpu = ArrayList<File>()
        for (z in zones) {
            val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: continue
            val temp = File(z, "temp")
            when {
                // Core sensors, e.g. "cpu-0-1-0"; skip fixed trip-point zones like "cpu-hw-trip-0".
                type.startsWith("cpu-") && !type.contains("trip") -> cpu += temp
                type.startsWith("cpu") && type.contains("therm") -> cpu += temp
                type.startsWith("gpuss") || type.startsWith("gpu") -> gpu += temp
            }
        }
        cpuFiles = cpu
        gpuFiles = gpu
        Log.i(TAG, "zones=${zones.size} cpu=${cpu.size} gpu=${gpu.size} " +
            "cpuReadable=${cpu.count { readMilli(it) != null }} gpuReadable=${gpu.count { readMilli(it) != null }}")
    }

    private fun readMilli(f: File): Int? {
        val raw = runCatching { f.readText().trim().toInt() }.getOrNull() ?: return null
        val c = if (raw > 1000 || raw < -1000) raw / 1000 else raw
        return if (c in 1..150) c else null
    }

    /** Hottest reading across the given zones, or null if none could be read. */
    private fun hottest(files: List<File>): Int? = files.mapNotNull { readMilli(it) }.maxOrNull()

    fun cpu(): Int? { discover(); return hottest(cpuFiles) }
    fun gpu(): Int? { discover(); return hottest(gpuFiles) }

    fun battery(ctx: Context): Int? {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else Math.round(t / 10f)
    }
}
