package dev.suite.shell;

import android.os.Binder;
import android.os.IBinder;
import java.lang.reflect.Method;

/**
 * Replays BlueLM's power-button trigger vibration exactly.
 *
 * BlueLM (com.vivo.ai.copilot) doesn't use a standard Android effect: it plays a vivo/RichTap
 * "PatternHe" haptic. Recorded from `dumpsys vibrator_manager` (Recent vibrations) on this phone:
 *   PatternHe { mLooper=1, mInterval=0, mAmplitude=255, mFreq=0, mPatternInfo=[...] }  ~277 ms
 * Decoded: one continuous 240 ms event, intensity 100, frequency 50, envelope
 * 0 ms: 0 -> 20 ms: 10 -> 140 ms: 30 -> 240 ms: 0 (frequency offset -15 throughout): a soft swell.
 * The 4th value is just a running serial number in BlueLM's calls.
 *
 * The effect is built with vivo's android.os.RichTapVibrationEffect.createPatternHeWithParam and
 * sent straight to the vibrator_manager service as the shell user (like `cmd vibrator_manager`).
 */
final class Haptics {
    // BlueLM's pattern stretched to 2x its length (user's request): event 240 -> 480 ms and the
    // envelope point times 0/20/140/240 -> 0/40/280/480 ms; intensities and frequency unchanged.
    // (17736 = 0x4548 = "HE" marker; 18 = number of ints in the event block.)
    static final int[] BLUELM_TRIGGER = {
        2, 2, 17736, 1, 65537, 0, 0, 1, 4096, 18, 1, 0, 100, 50, 480, 4,
        0, 0, -15, 40, 10, -15, 280, 30, -15, 480, 0, -15,
    };

    private static Object service, combined, popCombined, attrs;
    private static Method vibrate;
    private static boolean broken;

    private static synchronized boolean init() {
        if (vibrate != null) return true;
        if (broken) return false;
        try {
            Class<?> rt = Class.forName("android.os.RichTapVibrationEffect");
            Object effect = rt.getMethod("createPatternHeWithParam", int[].class, int.class, int.class, int.class, int.class)
                    .invoke(null, BLUELM_TRIGGER, 1, 0, 255, 0);
            Class<?> ve = Class.forName("android.os.VibrationEffect");
            Class<?> cv = Class.forName("android.os.CombinedVibration");
            combined = cv.getMethod("createParallel", ve).invoke(null, effect);
            // the pop: a 30 ms full-strength step, which vivo plays as CLICK(STRONG)
            Object pop = ve.getMethod("createOneShot", long.class, int.class).invoke(null, 30L, 255);
            popCombined = cv.getMethod("createParallel", ve).invoke(null, pop);

            Class<?> va = Class.forName("android.os.VibrationAttributes");
            Class<?> vab = Class.forName("android.os.VibrationAttributes$Builder");
            Object b = vab.getConstructor().newInstance();
            int usage = va.getField("USAGE_HARDWARE_FEEDBACK").getInt(null);
            vab.getMethod("setUsage", int.class).invoke(b, usage);
            try {   // like `cmd vibrator_manager -f`: don't let Do Not Disturb swallow it
                int f = va.getField("FLAG_BYPASS_INTERRUPTION_POLICY").getInt(null);
                vab.getMethod("setFlags", int.class, int.class).invoke(b, f, f);
            } catch (Throwable ignored) {}
            attrs = vab.getMethod("build").invoke(b);

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
            L.i("haptics: BlueLM pattern ready (" + vibrate.getParameterCount() + "-arg vibrate)");
            return true;
        } catch (Throwable t) {
            broken = true;
            L.e("haptics: BlueLM pattern unavailable, using the plain pop", t);
            return false;
        }
    }

    /** Builds the effect ahead of time so the first trigger isn't slowed by reflection. */
    static void warm() { init(); }

    /**
     * Trigger feedback: a pop, then BlueLM's swell right after it (vivo plays only the first part
     * of a chained effect, so they are two vibrations). Runs on its own thread so it never delays
     * the ripple. False if the BlueLM effect isn't available (caller falls back to the plain pop).
     */
    static boolean bluelmTrigger() {
        if (!init()) return false;
        Thread t = new Thread(() -> {
            play(popCombined);
            try { Thread.sleep(40); } catch (InterruptedException ignored) {}
            play(combined);
        }, "haptic");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * "Done" signal for routines: two full-strength buzzes (250 ms, gap 150 ms), long enough that
     * vivo plays them as a real buzz rather than a click, so it's heard on a table. Bypasses
     * Do Not Disturb.
     */
    static void done() {
        if (init()) {
            try {
                Class<?> ve = Class.forName("android.os.VibrationEffect");
                Object buzz = ve.getMethod("createWaveform", long[].class, int[].class, int.class)
                        .invoke(null, new long[]{0, 250, 150, 250}, new int[]{0, 255, 0, 255}, -1);
                Object c = Class.forName("android.os.CombinedVibration")
                        .getMethod("createParallel", ve).invoke(null, buzz);
                if (play(c)) return;
            } catch (Throwable t) {
                L.e("haptics: buzz", t);
            }
        }
        SysSettings.exec("cmd", "vibrator_manager", "synced", "-f", "waveform", "-a", "250", "255", "150", "0", "250", "255");
    }

    private static boolean play(Object vibration) {
        try {
            Class<?>[] ps = vibrate.getParameterTypes();
            Object[] args = new Object[ps.length];
            int ints = 0, strings = 0;
            for (int i = 0; i < ps.length; i++) {
                if (ps[i] == int.class) args[i] = ints++ == 0 ? android.os.Process.myUid() : 0;   // uid, then deviceId
                else if (ps[i] == String.class) args[i] = strings++ == 0 ? "com.android.shell" : "Suite trigger";
                else if (ps[i] == IBinder.class) args[i] = new Binder();
                else if (ps[i].isInstance(vibration)) args[i] = vibration;
                else args[i] = attrs;
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
