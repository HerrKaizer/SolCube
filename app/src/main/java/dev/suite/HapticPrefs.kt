package dev.suite

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Shared haptic configuration and in-app playback across all SolCube triggers and controls.
 * Presets and the master toggle also sync to shell UserServices (`dev.suite:cts`, `:routines`,
 * `:profiles`) via [toJson] / [syncShell].
 */
object HapticPrefs {
    const val KEY_MASTER = "haptics_master"
    const val KEY_UI = "haptic_ui"

    const val SLOT_CTS = "cts"
    const val SLOT_GESTURES = "gestures"
    const val SLOT_DOUBLE_PRESS = "double_press"
    const val SLOT_POWER_HOLD = "power_hold"
    const val SLOT_ROUTINE = "routine"

    const val OFF = "off"
    const val TICK = "tick"
    const val CLICK = "click"
    const val THUD = "thud"
    const val DOUBLE_TAP = "double_tap"
    const val PULSE = "pulse"
    const val SWELL = "swell"
    const val BUZZ = "buzz"

    val PRESETS = listOf(OFF, TICK, CLICK, THUD, DOUBLE_TAP, PULSE, SWELL, BUZZ)

    // Matches dev.suite.shell.Haptics.BLUELM_TRIGGER (480 ms soft swell on vivo RichTap)
    private val BLUELM_TRIGGER = intArrayOf(
        2, 2, 17736, 1, 65537, 0, 0, 1, 4096, 18, 1, 0, 100, 50, 480, 4,
        0, 0, -15, 40, 10, -15, 280, 30, -15, 480, 0, -15,
    )

    private fun prefKey(slot: String) = when (slot) {
        SLOT_CTS -> "haptic_cts"
        SLOT_GESTURES -> "haptic_gestures"
        SLOT_DOUBLE_PRESS -> "haptic_double"
        SLOT_POWER_HOLD -> "haptic_power_hold"
        else -> "haptic_routine"
    }

    private fun defaultPreset(slot: String) = when (slot) {
        SLOT_CTS -> SWELL
        SLOT_GESTURES -> PULSE
        SLOT_DOUBLE_PRESS -> PULSE
        SLOT_POWER_HOLD -> CLICK
        else -> BUZZ
    }

    fun labelFor(presetId: String): String = when (presetId) {
        OFF -> "Off"
        TICK -> "Gentle tick"
        CLICK -> "Crisp click"
        THUD -> "Soft thud"
        DOUBLE_TAP -> "Double tap"
        PULSE -> "Pulse"
        SWELL -> "Soft swell"
        BUZZ -> "Double buzz"
        else -> "Pulse"
    }

    fun master(ctx: Context): Boolean = Components.prefs(ctx).getBoolean(KEY_MASTER, true)

    fun setMaster(ctx: Context, on: Boolean) {
        Components.prefs(ctx).edit().putBoolean(KEY_MASTER, on).commit()
        syncShell(ctx)
    }

    fun uiPref(ctx: Context): Boolean = Components.prefs(ctx).getBoolean(KEY_UI, true)

    fun setUiPref(ctx: Context, on: Boolean) {
        Components.prefs(ctx).edit().putBoolean(KEY_UI, on).apply()
    }

    fun uiEnabled(ctx: Context): Boolean = master(ctx) && uiPref(ctx)

    fun preset(ctx: Context, slot: String): String {
        val prefs = Components.prefs(ctx)
        val k = prefKey(slot)
        val saved = prefs.getString(k, null)
        if (saved != null && saved in PRESETS) return saved
        if (slot == SLOT_GESTURES && !prefs.getBoolean("gest_vibrate", true)) return OFF
        if (slot == SLOT_DOUBLE_PRESS && !prefs.getBoolean("double_vibrate", true)) return OFF
        return defaultPreset(slot)
    }

    fun setPreset(ctx: Context, slot: String, presetId: String, pushShell: Boolean = true) {
        val id = if (presetId in PRESETS) presetId else defaultPreset(slot)
        val prefs = Components.prefs(ctx)
        val ed = prefs.edit().putString(prefKey(slot), id)
        if (id != OFF) ed.putString("haptic_last_$slot", id)
        if (slot == SLOT_GESTURES) ed.putBoolean("gest_vibrate", id != OFF)
        if (slot == SLOT_DOUBLE_PRESS) ed.putBoolean("double_vibrate", id != OFF)
        ed.commit()
        if (pushShell) syncShell(ctx)
    }

    fun setSlotEnabled(ctx: Context, slot: String, on: Boolean) {
        val prefs = Components.prefs(ctx)
        val next = if (!on) OFF else {
            val remembered = prefs.getString("haptic_last_$slot", null)
            if (remembered != null && remembered in PRESETS && remembered != OFF) remembered
            else defaultPreset(slot)
        }
        setPreset(ctx, slot, next, pushShell = true)
    }

    fun toJson(ctx: Context): org.json.JSONObject = org.json.JSONObject()
        .put("master", master(ctx))
        .put("cts", preset(ctx, SLOT_CTS))
        .put("gestures", preset(ctx, SLOT_GESTURES))
        .put("double_press", preset(ctx, SLOT_DOUBLE_PRESS))
        .put("power_hold", preset(ctx, SLOT_POWER_HOLD))
        .put("routine", preset(ctx, SLOT_ROUTINE))

    fun syncShell(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            runCatching { Shell.service(Shell.Kind.CTS)?.configure(Components.ctsConfig(app)) }
            runCatching { Shell.service(Shell.Kind.ROUTINES)?.configure(Routines.config(app)) }
            runCatching { Shell.service(Shell.Kind.PROFILES)?.configure(Profiles.config(app)) }
        }.start()
    }

    fun playSlot(ctx: Context, slot: String) {
        if (!master(ctx)) return
        val p = preset(ctx, slot)
        if (p != OFF) preview(ctx, p)
    }

    private var lastUiTickAt = 0L
    private var uiTickEff: VibrationEffect? = null
    private var uiLongEff: VibrationEffect? = null
    private val uiAttrs by lazy {
        VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
            .build()
    }

    fun uiTick(ctx: Context) {
        if (!uiEnabled(ctx)) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastUiTickAt < 18L) return
        lastUiTickAt = now
        val vib = ctx.getSystemService(Vibrator::class.java) ?: return
        val eff = uiTickEff ?: (if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
                .compose()
        } else {
            VibrationEffect.createOneShot(10, 120)
        }).also { uiTickEff = it }
        runCatching { vib.vibrate(eff, uiAttrs) }
    }

    fun uiLongPress(ctx: Context) {
        if (!uiEnabled(ctx)) return
        val vib = ctx.getSystemService(Vibrator::class.java) ?: return
        val eff = uiLongEff ?: (if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f)
                .compose()
        } else {
            VibrationEffect.createOneShot(25, 220)
        }).also { uiLongEff = it }
        runCatching { vib.vibrate(eff, uiAttrs) }
    }

    fun resetDefaults(ctx: Context) {
        val ed = Components.prefs(ctx).edit()
            .putBoolean(KEY_MASTER, true)
            .putBoolean(KEY_UI, true)
            .putBoolean("gest_vibrate", true)
            .putBoolean("double_vibrate", true)
        listOf(SLOT_CTS, SLOT_GESTURES, SLOT_DOUBLE_PRESS, SLOT_POWER_HOLD, SLOT_ROUTINE).forEach { slot ->
            ed.putString(prefKey(slot), defaultPreset(slot))
            ed.remove("haptic_last_$slot")
        }
        ed.commit()
        syncShell(ctx)
    }

    fun preview(ctx: Context, presetId: String) {
        if (presetId == OFF) return
        val vib = ctx.getSystemService(Vibrator::class.java) ?: return
        val attrs = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
            .build()
        when (presetId) {
            DOUBLE_TAP -> {
                Thread {
                    vibrateSingle(vib, CLICK, attrs)
                    try { Thread.sleep(65) } catch (_: InterruptedException) { return@Thread }
                    vibrateSingle(vib, CLICK, attrs)
                }.apply { isDaemon = true }.start()
            }
            SWELL -> {
                Thread {
                    vibrateSingle(vib, CLICK, attrs)
                    try { Thread.sleep(40) } catch (_: InterruptedException) { return@Thread }
                    if (!playSwellEffect(vib, attrs)) vibrateFallbackRise(vib, attrs)
                }.apply { isDaemon = true }.start()
            }
            else -> vibrateSingle(vib, presetId, attrs)
        }
    }

    private fun vibrateSingle(vib: Vibrator, presetId: String, attrs: VibrationAttributes) = runCatching {
        val effect = when (presetId) {
            TICK -> if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                    .compose()
            } else {
                VibrationEffect.createOneShot(15, 140)
            }
            CLICK -> if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .compose()
            } else {
                VibrationEffect.createOneShot(30, 255)
            }
            THUD -> if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                    .compose()
            } else {
                VibrationEffect.createOneShot(45, 200)
            }
            BUZZ -> VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), intArrayOf(0, 255, 0, 255), -1)
            else -> VibrationEffect.createOneShot(60, 255)
        }
        vib.vibrate(effect, attrs)
    }

    private fun playSwellEffect(vib: Vibrator, attrs: VibrationAttributes): Boolean = runCatching {
        val rt = Class.forName("android.os.RichTapVibrationEffect")
        val eff = rt.getMethod(
            "createPatternHeWithParam",
            IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(null, BLUELM_TRIGGER, 1, 0, 255, 0) as VibrationEffect
        vib.vibrate(eff, attrs)
        true
    }.getOrDefault(false)

    private fun vibrateFallbackRise(vib: Vibrator, attrs: VibrationAttributes) = runCatching {
        val eff = if (vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.85f)
                .compose()
        } else {
            VibrationEffect.createOneShot(60, 220)
        }
        vib.vibrate(eff, attrs)
    }
}
