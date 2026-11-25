package dev.suite.shell;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Power button: hold 0.5 s (screen on) -> Circle to Search with the ripple starting at the
 * power button. Everything else is passed through to the system unchanged.
 *
 * The listener takes the power key device exclusively (EVIOCGRAB), so the system only sees
 * what we inject:
 *  - screen off: presses are passed through immediately (wake-up is not delayed);
 *  - screen on, released before 0.5 s: a normal press is injected on release
 *    (Android turns the screen off on key-up anyway);
 *  - a volume key pressed while power is held: the press is passed through immediately
 *    so power+volume combos (screenshot, power off) keep working;
 *  - held 0.5 s: our gesture fires and the key never reaches the system.
 * If this process dies the kernel drops the grab and the power button works normally again.
 */
final class PowerKey {
    static final int EV_KEY = 1, KEY_POWER = 116, KEY_VOLUMEDOWN = 114, KEY_VOLUMEUP = 115;
    static final int EVIOCGRAB = 0x40044590;   // _IOW('E', 0x90, int)

    // where the ripple starts: power button on the right edge (natural portrait, 0..1)
    static float fx = 1.0f, fy = 0.37f;
    static volatile long holdMs = 350;      // set from SolCube
    static volatile boolean cts = true;     // hold = Circle to Search
    static volatile boolean act = false;    // hold = "Power button hold" card's action
    static volatile boolean routine = false; // hold = Routine trigger (priority when matched)
    static volatile boolean started;

    static final Object lock = new Object();
    static boolean down, pass, fired;
    // volume keys: currently held (0 = down, 1 = up) and when one was last pressed
    static final boolean[] volHeld = new boolean[2];
    static long volPressedAt = -1_000_000;
    static final long COMBO_WINDOW_MS = 250;
    static long downAt, downUptime;
    static CtsListener.Capture cap;

    static Object input, power;
    static Method inject, isInteractive;
    static final Map<Integer, Integer> scanToKey = new HashMap<>();

    static synchronized void start(String powerDev, String... volumeDevs) {
        start(powerDev, "", volumeDevs);
    }

    static synchronized void start(String powerDev, String powerDevName, String... volumeDevs) {
        if (started || powerDev == null || powerDev.isEmpty()) return;
        try {
            Class<?> img = Class.forName("android.hardware.input.InputManagerGlobal");
            input = img.getMethod("getInstance").invoke(null);
            inject = img.getMethod("injectInputEvent", android.view.InputEvent.class, int.class);
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "power");
            power = Class.forName("android.os.IPowerManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
            isInteractive = power.getClass().getMethod("isInteractive");
        } catch (Throwable t) {
            L.i("power key disabled (no inject/power service): " + t);
            return;
        }
        loadKeyLayout(powerDevName);
        started = true;
        Thread t = new Thread(() -> readPower(powerDev), "power");
        t.setDaemon(true); t.start();
        for (String v : volumeDevs) {
            Thread vt = new Thread(() -> readVolume(v), "volume");
            vt.setDaemon(true); vt.start();
        }
        Thread timer = new Thread(PowerKey::timer, "power-timer");
        timer.setDaemon(true); timer.start();
    }

    private static void loadKeyLayout(String devName) {
        scanToKey.clear();
        // Standard Linux input-event-codes fallbacks for hardware keys sharing a PMIC/kpd node
        scanToKey.put(114, KeyEvent.KEYCODE_VOLUME_DOWN);
        scanToKey.put(115, KeyEvent.KEYCODE_VOLUME_UP);
        scanToKey.put(113, KeyEvent.KEYCODE_VOLUME_MUTE);
        scanToKey.put(102, KeyEvent.KEYCODE_HOME);
        scanToKey.put(172, KeyEvent.KEYCODE_HOME);
        scanToKey.put(158, KeyEvent.KEYCODE_BACK);
        scanToKey.put(139, KeyEvent.KEYCODE_MENU);
        scanToKey.put(127, KeyEvent.KEYCODE_MENU);
        scanToKey.put(212, KeyEvent.KEYCODE_CAMERA);
        scanToKey.put(528, KeyEvent.KEYCODE_FOCUS);
        scanToKey.put(217, KeyEvent.KEYCODE_SEARCH);
        scanToKey.put(226, KeyEvent.KEYCODE_HEADSETHOOK);
        scanToKey.put(164, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        scanToKey.put(163, KeyEvent.KEYCODE_MEDIA_NEXT);
        scanToKey.put(165, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        scanToKey.put(166, KeyEvent.KEYCODE_MEDIA_STOP);
        scanToKey.put(143, KeyEvent.KEYCODE_WAKEUP);
        scanToKey.put(142, KeyEvent.KEYCODE_SLEEP);
        scanToKey.put(582, KeyEvent.KEYCODE_VOICE_ASSIST);
        scanToKey.put(583, KeyEvent.KEYCODE_ASSIST);
        parseKlFile("/system/usr/keylayout/Generic.kl");
        if (devName != null && !devName.isEmpty()) {
            String kl = devName.replaceAll("[^A-Za-z0-9_-]", "_") + ".kl";
            parseKlFile("/system/usr/keylayout/" + kl);
            parseKlFile("/system_ext/usr/keylayout/" + kl);
            parseKlFile("/product/usr/keylayout/" + kl);
            parseKlFile("/vendor/usr/keylayout/" + kl);
            parseKlFile("/odm/usr/keylayout/" + kl);
        }
    }

    private static void parseKlFile(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length >= 3 && "key".equals(parts[0])) {
                    try {
                        int scan = Integer.decode(parts[1]);
                        int kc = KeyEvent.keyCodeFromString("KEYCODE_" + parts[2]);
                        if (kc != KeyEvent.KEYCODE_UNKNOWN) scanToKey.put(scan, kc);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean interactive() {
        try { return (Boolean) isInteractive.invoke(power); } catch (Throwable t) { return true; }
    }

    static void injectKeyEvent(int action, int keyCode, long downTime, long eventTime, int scanCode) {
        try {
            KeyEvent e = new KeyEvent(downTime, eventTime, action, keyCode, 0, 0,
                    -1 /* virtual keyboard */, scanCode, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
            inject.invoke(input, e, 0 /* async */);
        } catch (Throwable t) {
            L.i("inject failed: " + t);
        }
    }

    static void injectKey(int action, long downTime) {
        injectKeyEvent(action, KeyEvent.KEYCODE_POWER, downTime, SystemClock.uptimeMillis(), 0);
    }

    static void readPower(String dev) {
        FileDescriptor fd = null;
        Map<Integer, Long> otherDown = new HashMap<>();
        try {
            fd = android.system.Os.open(dev, android.system.OsConstants.O_RDONLY, 0);
            Method ioctl = android.system.Os.class.getMethod("ioctlInt", FileDescriptor.class, int.class);
            ioctl.invoke(null, fd, EVIOCGRAB);
            L.i("power key grabbed: " + dev);
            FileInputStream in = new FileInputStream(fd);
            byte[] b = new byte[24 * 16];
            while (true) {
                int n = in.read(b);
                if (n <= 0) break;
                for (int o = 0; o + 24 <= n; o += 24) {
                    int type = (b[o + 16] & 0xff) | (b[o + 17] & 0xff) << 8;
                    int code = (b[o + 18] & 0xff) | (b[o + 19] & 0xff) << 8;
                    int val = (b[o + 20] & 0xff) | (b[o + 21] & 0xff) << 8 | (b[o + 22] & 0xff) << 16 | (b[o + 23]) << 24;
                    if (type == EV_KEY) {
                        if (code == KEY_POWER) onPower(val);
                        else onOtherKey(code, val, otherDown);
                    }
                }
            }
        } catch (Throwable t) {
            L.i("power key reader stopped: " + t);
        } finally {
            long now = SystemClock.uptimeMillis();
            for (Map.Entry<Integer, Long> e : otherDown.entrySet()) {
                Integer mapped = scanToKey.get(e.getKey());
                int kc = mapped != null ? mapped : KeyEvent.KEYCODE_UNKNOWN;
                injectKeyEvent(KeyEvent.ACTION_UP, kc, e.getValue(), now, e.getKey());
            }
            // closing the fd releases the grab -> the system gets the button back
            try { if (fd != null) android.system.Os.close(fd); } catch (Throwable ignored) {}
            started = false;
        }
    }

    static void onOtherKey(int scanCode, int val, Map<Integer, Long> otherDown) {
        if (val != 0 && val != 1) return;   // ignore autorepeat; InputDispatcher synthesizes repeats natively
        Integer mapped = scanToKey.get(scanCode);
        int keyCode = mapped != null ? mapped : KeyEvent.KEYCODE_UNKNOWN;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            onVolume(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ? 0 : 1, val == 1);
        }
        long now = SystemClock.uptimeMillis();
        long dt;
        if (val == 1) {
            otherDown.put(scanCode, now);
            dt = now;
        } else {
            Long prev = otherDown.remove(scanCode);
            dt = prev != null ? prev : now;
        }
        injectKeyEvent(val == 1 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, dt, now, scanCode);
    }

    static void onVolume(int idx, boolean pressed) {
        synchronized (lock) {
            volHeld[idx] = pressed;
            if (pressed) {
                volPressedAt = CtsListener.now();
                if (down && !pass && !fired) {             // combo: hand power to the system now
                    pass = true;
                    injectKey(KeyEvent.ACTION_DOWN, downUptime);
                }
            }
        }
    }

    static void onPower(int val) {
        if (val == 2) return;   // autorepeat
        synchronized (lock) {
            if (val == 1) {
                down = true; fired = false; cap = null;
                AppKeeper.kick();
                downAt = CtsListener.now(); downUptime = SystemClock.uptimeMillis();
                // a volume key already down (or pressed a moment ago): it's a combo
                // (screenshot / power menu), so give power to the system right away
                boolean combo = volHeld[0] || volHeld[1] || downAt - volPressedAt < COMBO_WINDOW_MS;
                pass = combo || !interactive();
                if (combo) L.i("power+volume combo (volume first)");
                if (pass) injectKey(KeyEvent.ACTION_DOWN, downUptime);   // screen off: wake now
                lock.notifyAll();                                        // wake the hold timer
            } else {
                down = false;
                if (pass) injectKey(KeyEvent.ACTION_UP, downUptime);
                else if (!fired) {                                       // short press
                    injectKey(KeyEvent.ACTION_DOWN, downUptime);
                    injectKey(KeyEvent.ACTION_UP, downUptime);
                }
                cap = null;
            }
        }
    }

    static void readVolume(String dev) {
        try (FileInputStream in = new FileInputStream(dev)) {
            byte[] b = new byte[24 * 16];
            while (true) {
                int n = in.read(b);
                if (n <= 0) break;
                for (int o = 0; o + 24 <= n; o += 24) {
                    int type = (b[o + 16] & 0xff) | (b[o + 17] & 0xff) << 8;
                    int code = (b[o + 18] & 0xff) | (b[o + 19] & 0xff) << 8;
                    int val = (b[o + 20] & 0xff) | (b[o + 21] & 0xff) << 8 | (b[o + 22] & 0xff) << 16 | (b[o + 23]) << 24;
                    if (type != EV_KEY || (code != KEY_VOLUMEDOWN && code != KEY_VOLUMEUP)) continue;
                    if (val != 0 && val != 1) continue;
                    onVolume(code == KEY_VOLUMEDOWN ? 0 : 1, val == 1);
                }
            }
        } catch (Throwable t) {
            L.i("volume reader " + dev + " stopped: " + t);
        }
    }

    static void timer() {
        while (true) {
            // sleep until the power key is held with the screen on (onPower notifies)
            synchronized (lock) {
                while (!(down && !pass && !fired)) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
            }
            try { Thread.sleep(15); } catch (InterruptedException e) { return; }
            CtsListener.Capture c = null;
            boolean fire = false;
            synchronized (lock) {
                if (down && !pass && !fired) {
                    long held = CtsListener.now() - downAt;
                    if (cts && CtsListener.animate && cap == null && held >= CtsListener.captureAfter(holdMs)) {
                        cap = new CtsListener.Capture();
                        cap.start();
                    }
                    if (held >= holdMs) { fired = true; fire = true; c = cap; }
                }
            }
            if (fire) {
                if (routine && CtsListener.triggerRoutine("power_hold")) {
                    L.i("power hold → routine");
                    Haptics.powerHold();
                } else if (cts) {
                    L.i("power hold");
                    CtsListener.fire(fx, fy, new float[]{fx, fy, fx, fy}, c);
                } else if (act) {
                    L.i("power hold → SolCube action");
                    // the "Power button hold" card: SolCube's invisible activity opens the app / switches the sound mode / runs action
                    Haptics.powerHold();
                    SysSettings.exec("am", "start", "-n", "dev.suite/.HoldActivity",
                            "-f", "0x10800000");   // NEW_TASK | NO_ANIMATION
                } else {
                    L.i("power hold → no matching routine");
                }
            }
        }
    }
}

