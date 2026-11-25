package dev.suite.shell;

import android.os.Binder;
import android.os.IBinder;
import org.json.JSONObject;
import java.lang.reflect.Method;

/**
 * Unified shell-side haptic player used across CtsListener, PowerKey, GestureListener,
 * RoutinesUserService, and ShellActions. Supports user-selected presets and a global master switch
 * synced from [dev.suite.HapticPrefs].
 */
final class Haptics {
    // BlueLM's pattern stretched to 2x its length (user's request): event 240 -> 480 ms and the
    // envelope point times 0/20/140/240 -> 0/40/280/480 ms; intensities and frequency unchanged.
    static final int[] BLUELM_TRIGGER = {
        2, 2, 17736, 1, 65537, 0, 0, 1, 4096, 18, 1, 0, 100, 50, 480, 4,
        0, 0, -15, 40, 10, -15, 280, 30, -15, 480, 0, -15,
    };

    private static final int PRIM_CLICK = 1;
    private static final int PRIM_QUICK_RISE = 4;
    private static final int PRIM_TICK = 7;
    private static final int PRIM_THUD = 2;

    static volatile boolean master = true;
    static volatile String ctsPreset = "swell";
    static volatile String gesturePreset = "pulse";
    static volatile String powerHoldPreset = "click";
    static volatile String routinePreset = "buzz";

    private static Object service, doneAttrs;
    private static Object swellCombined, tickCombined, clickCombined, thudCombined, pulseCombined, buzzCombined, riseCombined;
    private static Method vibrate;
    private static boolean broken;

    static void configure(JSONObject o) {
        if (o == null) return;
        master = o.optBoolean("master", true);
        ctsPreset = o.optString("cts", ctsPreset);
        gesturePreset = o.optString("gestures", gesturePreset);
        powerHoldPreset = o.optString("power_hold", powerHoldPreset);
        routinePreset = o.optString("routine", routinePreset);
    }

    private static synchronized boolean init() {
        if (vibrate != null) return true;
        if (broken) return false;
        try {
            Class<?> ve = Class.forName("android.os.VibrationEffect");
            Class<?> cv = Class.forName("android.os.CombinedVibration");
            Method createParallel = cv.getMethod("createParallel", ve);

            try {
                Class<?> rt = Class.forName("android.os.RichTapVibrationEffect");
                Object effect = rt.getMethod("createPatternHeWithParam", int[].class, int.class, int.class, int.class, int.class)
                        .invoke(null, BLUELM_TRIGGER, 1, 0, 255, 0);
                swellCombined = createParallel.invoke(null, effect);
            } catch (Throwable ignored) {}

            // 60 ms full-strength pulse: long enough that vivo plays a real Step buzz instead of
            // substituting Prebaked=CLICK, matching DoublePress.vibrateOnce
            pulseCombined = createParallel.invoke(null, ve.getMethod("createOneShot", long.class, int.class).invoke(null, 60L, 255));
            Object buzz = ve.getMethod("createWaveform", long[].class, int[].class, int.class)
                    .invoke(null, new long[]{0, 250, 150, 250}, new int[]{0, 255, 0, 255}, -1);
            buzzCombined = createParallel.invoke(null, buzz);

            tickCombined = primitiveCombined(ve, createParallel, PRIM_TICK, 0.7f, 15L, 140);
            clickCombined = primitiveCombined(ve, createParallel, PRIM_CLICK, 1.0f, 30L, 255);
            thudCombined = primitiveCombined(ve, createParallel, PRIM_THUD, 1.0f, 45L, 200);
            riseCombined = primitiveCombined(ve, createParallel, PRIM_QUICK_RISE, 0.85f, 60L, 220);

            Class<?> va = Class.forName("android.os.VibrationAttributes");
            Class<?> vab = Class.forName("android.os.VibrationAttributes$Builder");
            int bypassFlags = flagOrDefault(va, "FLAG_BYPASS_INTERRUPTION_POLICY", 1)
                    | flagOrDefault(va, "FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF", 2)
                    | flagOrDefault(va, "FLAG_BYPASS_USER_VIBRATION_INTENSITY_SCALE", 8);

            Object db = vab.getConstructor().newInstance();
            int hwUsage = va.getField("USAGE_HARDWARE_FEEDBACK").getInt(null);
            int doneUsage = flagOrDefault(va, "USAGE_COMMUNICATION_REQUEST",
                    flagOrDefault(va, "USAGE_ALARM", hwUsage));
            vab.getMethod("setUsage", int.class).invoke(db, doneUsage);
            try {
                vab.getMethod("setFlags", int.class, int.class).invoke(db, bypassFlags, bypassFlags);
            } catch (Throwable ignored) {}
            doneAttrs = vab.getMethod("build").invoke(db);

            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "vibrator_manager");
            service = Class.forName("android.os.IVibratorManagerService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            for (Method m : service.getClass().getMethods()) {
                if (!m.getName().equals("vibrate")) continue;
                boolean ok = true;
                for (Class<?> p : m.getParameterTypes()) {
                    if (p != int.class && p != String.class && p != IBinder.class && p != cv && p != va) ok = false;
                }
                if (ok) vibrate = m;
            }
            if (vibrate == null) throw new NoSuchMethodException("IVibratorManagerService.vibrate");
            L.i("haptics: ready (" + vibrate.getParameterCount() + "-arg vibrate)");
            return true;
        } catch (Throwable t) {
            broken = true;
            L.e("haptics: init failed, falling back to cmd vibrator_manager", t);
            return false;
        }
    }

    private static Object primitiveCombined(Class<?> ve, Method createParallel, int prim, float scale, long fallbackMs, int fallbackAmp) throws Exception {
        try {
            Object comp = ve.getMethod("startComposition").invoke(null);
            comp.getClass().getMethod("addPrimitive", int.class, float.class).invoke(comp, prim, scale);
            Object eff = comp.getClass().getMethod("compose").invoke(comp);
            return createParallel.invoke(null, eff);
        } catch (Throwable ignored) {
            Object eff = ve.getMethod("createOneShot", long.class, int.class).invoke(null, fallbackMs, fallbackAmp);
            return createParallel.invoke(null, eff);
        }
    }

    private static int flagOrDefault(Class<?> cls, String name, int fallback) {
        try { return cls.getField(name).getInt(null); } catch (Throwable ignored) { return fallback; }
    }

    /** Builds effects ahead of time so the first trigger isn't slowed by reflection. */
    static void warm() { init(); }

    static void ctsStart() { playPreset(ctsPreset); }

    static void ctsReveal() {
        if (!master || "off".equals(ctsPreset)) return;
        playPreset("click");
    }

    static void powerHold() { playPreset(powerHoldPreset); }

    static void gesture() { playPreset(gesturePreset); }

    static void routineDone() { playPreset(routinePreset); }

    static void pop() { gesture(); }

    static void done() { routineDone(); }

    static void playPreset(String preset) {
        if (!master || preset == null || "off".equals(preset)) return;
        if ("double_tap".equals(preset)) {
            Thread t = new Thread(() -> {
                playSingle("click");
                try { Thread.sleep(65); } catch (InterruptedException ignored) { return; }
                playSingle("click");
            }, "haptic-double");
            t.setDaemon(true);
            t.start();
            return;
        }
        if ("swell".equals(preset)) {
            Thread t = new Thread(() -> {
                playSingle("click");
                try { Thread.sleep(40); } catch (InterruptedException ignored) { return; }
                if (!init() || (swellCombined != null && !play(swellCombined, doneAttrs))) {
                    if (riseCombined != null) play(riseCombined, doneAttrs);
                } else if (swellCombined == null && riseCombined != null) {
                    play(riseCombined, doneAttrs);
                }
            }, "haptic-swell");
            t.setDaemon(true);
            t.start();
            return;
        }
        playSingle(preset);
    }

    private static void playSingle(String preset) {
        if (init()) {
            Object eff = switch (preset) {
                case "tick" -> tickCombined;
                case "click" -> clickCombined;
                case "thud" -> thudCombined;
                case "buzz" -> buzzCombined;
                default -> pulseCombined;
            };
            if (eff != null && play(eff, doneAttrs)) return;
        }
        switch (preset) {
            case "tick" -> SysSettings.exec("cmd", "vibrator_manager", "synced", "-f", "oneshot", "15");
            case "click" -> SysSettings.exec("cmd", "vibrator_manager", "synced", "-f", "waveform", "-a", "30", "255");
            case "thud" -> SysSettings.exec("cmd", "vibrator_manager", "synced", "-f", "oneshot", "45");
            case "buzz" -> SysSettings.exec("cmd", "vibrator_manager", "synced", "-f", "waveform", "-a", "250", "255", "150", "0", "250", "255");
            default -> SysSettings.exec("cmd", "vibrator_manager", "synced", "-f", "oneshot", "60");
        }
    }

    private static boolean play(Object vibration, Object useAttrs) {
        try {
            Class<?>[] ps = vibrate.getParameterTypes();
            Object[] args = new Object[ps.length];
            int ints = 0, strings = 0;
            for (int i = 0; i < ps.length; i++) {
                if (ps[i] == int.class) args[i] = ints++ == 0 ? android.os.Process.myUid() : 0;   // uid, then deviceId
                else if (ps[i] == String.class) args[i] = strings++ == 0 ? "com.android.shell" : "SolCube trigger";
                else if (ps[i] == IBinder.class) args[i] = new Binder();
                else if (ps[i].isInstance(vibration)) args[i] = vibration;
                else args[i] = useAttrs;
            }
            vibrate.invoke(service, args);
            return true;
        } catch (Throwable t) {
            L.e("haptics: play failed", t);
            return false;
        }
    }

    private Haptics() {}
}
