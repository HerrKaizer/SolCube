package dev.suite.shell;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Runs as the adb shell user (via app_process). Reads the raw touchscreen and,
 * on a two-finger tap-and-hold, plays the CtsRipple overlay and then opens
 * Circle to Search.
 *
 * While the fingers are held it takes a raw screenshot (shell may run screencap),
 * which is sent to the ripple app so the ripple can warp the real screen contents.
 *
 * args: [device=/dev/input/event7] [holdMs=500] [moveTolerance=400 raw units] [maxX maxY]
 */
public class CtsListener {
    static final int EV_SYN = 0, EV_ABS = 3;
    static final int SYN_REPORT = 0;
    static final int ABS_MT_SLOT = 0x2f, ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39;
    static final int SLOTS = 16;

    static final int[] id = new int[SLOTS], x = new int[SLOTS], y = new int[SLOTS];
    static final int[] sx = new int[SLOTS], sy = new int[SLOTS];
    static int slot = 0;

    static volatile long holdMs = 400;            // two-finger hold (set from SolCube)
    static final long maxGapMs = 300;
    static volatile boolean touchEnabled = true;  // two-finger trigger on/off (set from SolCube)
    static volatile long freezeMs = 0;            // overlay freeze after the CTS call (set from SolCube)
    static volatile String secret;                // shared with SolCube for the ripple-port handshake (set from SolCube)
    // Navigation-bar hold: one finger that lands on the gesture bar (bottom strip, middle of the
    // width) and stays still. Moving it (swipe up = home/recents, sideways = switch apps) cancels.
    static volatile boolean navEnabled = false;
    static volatile long navHoldMs = 450;
    static final float NAV_STRIP = 0.035f;          // bottom 3.5 % of the height (~110 px; vivo's bar window is 72 px)
    static final float NAV_SIDE = 0.2f;             // not the outer 20 % on each side (games' corner buttons)

    /** End-to-end test (shell-only hook): fresh screenshot, then ripple + CTS with the given freeze. */
    static void test(long freeze) {
        long old = freezeMs;
        try {
            freezeMs = freeze;
            Capture c = new Capture();
            c.start();
            try { c.join(1000); } catch (InterruptedException ignored) {}
            L.i("test: freeze " + freeze + " ms");
            fire(0.5f, 0.45f, new float[]{0.42f, 0.45f, 0.58f, 0.45f}, c);
        } finally {
            freezeMs = old;
        }
    }

    /** Start the screenshot early enough to be ready when the hold completes (it takes ~150-250 ms). */
    static long captureAfter(long hold) { return Math.max(60, hold - 260); }
    static int tol = 400;
    static int maxX = 14400, maxY = 31680;   // vivo_ts ABS_MT_POSITION_X/Y range (+1)

    // gesture state (guarded by lock)
    static final Object lock = new Object();
    static long firstDown = 0;      // time first finger of this touch went down
    static long twoSince = 0;       // time count became 2 (0 = not armed)
    static long navSince = 0;       // time a single finger went down on the gesture bar (0 = not armed)
    static int navSlot = -1, nsx, nsy;
    static boolean spent = false;   // fired or cancelled; reset when all fingers lift
    static Capture capture = null;  // screenshot for the current hold

    /** Raw screencap output (16-byte header w,h,format,colorspace + RGBA pixels). */
    static class Capture extends Thread {
        volatile byte[] data;
        Capture() { super("capture"); setDaemon(true); }
        @Override public void run() {
            long t0 = System.nanoTime();
            try {
                Process p = new ProcessBuilder("screencap").start();
                InputStream in = p.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1440 * 3168 * 4 + 16);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                p.waitFor();
                data = bos.toByteArray();
                L.i("capture " + data.length + "B " + (System.nanoTime() - t0) / 1000000 + "ms");
            } catch (Exception e) {
                L.e("error", e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        L.tag = "SolCube.cts";
        String dev = args.length > 0 ? args[0] : "/dev/input/event7";
        if (args.length > 1) holdMs = Long.parseLong(args[1]);
        if (args.length > 2) tol = Integer.parseInt(args[2]);
        if (args.length > 4) { maxX = Integer.parseInt(args[3]); maxY = Integer.parseInt(args[4]); }
        for (int i = 0; i < SLOTS; i++) id[i] = -1;

        Thread timer = new Thread(() -> {
            while (true) {
                // sleep until two fingers are down (frame() notifies), instead of polling
                synchronized (lock) {
                    while ((twoSince == 0 && navSince == 0) || spent) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                }
                try { Thread.sleep(15); } catch (InterruptedException e) { return; }
                boolean fire = false;
                float fx = 0.5f, fy = 0.5f;
                float[] fingers = new float[4];
                Capture cap = null;
                synchronized (lock) {
                    if (navSince != 0 && !spent) {
                        long held = now() - navSince;
                        if (capture == null && held >= captureAfter(navHoldMs)) {
                            capture = new Capture();
                            capture.start();
                        }
                        if (held >= navHoldMs) {
                            spent = true; navSince = 0; fire = true;
                            cap = capture;
                            int s = navSlot >= 0 ? navSlot : 0;
                            fx = (float) x[s] / maxX; fy = (float) y[s] / maxY;
                            fingers[0] = fingers[2] = fx; fingers[1] = fingers[3] = fy;
                            L.i("navigation bar hold");
                        }
                    } else if (twoSince != 0 && !spent) {
                        long held = now() - twoSince;
                        if (capture == null && held >= captureAfter(holdMs)) {
                            capture = new Capture();
                            capture.start();
                        }
                        if (held >= holdMs) {
                            spent = true; twoSince = 0; fire = true;
                            cap = capture;
                            int n = 0; long ax = 0, ay = 0;
                            for (int i = 0; i < SLOTS; i++) if (id[i] >= 0) {
                                if (n < 2) { fingers[n * 2] = (float) x[i] / maxX; fingers[n * 2 + 1] = (float) y[i] / maxY; }
                                ax += x[i]; ay += y[i]; n++;
                            }
                            if (n > 0) { fx = (float) ax / n / maxX; fy = (float) ay / n / maxY; }
                            if (n < 2) { fingers[2] = fingers[0]; fingers[3] = fingers[1]; }
                        }
                    }
                }
                if (fire) fire(fx, fy, fingers, cap);
            }
        });
        timer.setDaemon(true);
        timer.start();

        try { vims = CtsTrigger.service(); } catch (Throwable e) { L.e("error", e); }
        AppKeeper.start();   // (the power-key trigger is started by CtsUserService when enabled)
        Haptics.warm();
        L.i("listening on " + dev + " hold=" + holdMs + "ms tol=" + tol);
        byte[] b = new byte[24 * 64];
        try (FileInputStream in = new FileInputStream(dev)) {
            while (true) {
                int n = in.read(b);
                if (n <= 0) break;
                for (int o = 0; o + 24 <= n; o += 24) {
                    int type = (b[o + 16] & 0xff) | (b[o + 17] & 0xff) << 8;
                    int code = (b[o + 18] & 0xff) | (b[o + 19] & 0xff) << 8;
                    int val = (b[o + 20] & 0xff) | (b[o + 21] & 0xff) << 8 | (b[o + 22] & 0xff) << 16 | (b[o + 23]) << 24;
                    handle(type, code, val);
                }
            }
        }
    }

    static long now() { return System.nanoTime() / 1_000_000; }

    static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();

    /** Ripple from (fx, fy) over the captured frame, then Circle to Search. Used by both gestures. */
    static void fire(float fx, float fy, float[] fingers, Capture cap) {
        if (!busy.compareAndSet(false, true)) return;   // one at a time
        // instant feedback: the hold was recognised (BlueLM's own trigger vibration)
        if (!Haptics.bluelmTrigger()) pop();
        try {
            byte[] frame = null;
            if (cap != null) {
                try { cap.join(400); } catch (InterruptedException ignored) {}
                frame = cap.data;
            }
            if (!ripple(fx, fy, fingers, frame)) launch();
        } finally {
            busy.set(false);
        }
    }

    static void handle(int type, int code, int val) {
        if (type == EV_ABS) {
            if (code == ABS_MT_SLOT) slot = (val >= 0 && val < SLOTS) ? val : 0;
            else if (code == ABS_MT_TRACKING_ID) id[slot] = val;
            else if (code == ABS_MT_POSITION_X) x[slot] = val;
            else if (code == ABS_MT_POSITION_Y) y[slot] = val;
        } else if (type == EV_SYN && code == SYN_REPORT) {
            frame();
        }
    }

    static void frame() {
        int count = 0;
        for (int i = 0; i < SLOTS; i++) if (id[i] >= 0) count++;
        long t = now();
        synchronized (lock) {
            if (count == 0 || (!touchEnabled && !navEnabled)) {
                firstDown = 0; twoSince = 0; navSince = 0; navSlot = -1; spent = false; capture = null; return;
            }
            boolean first = firstDown == 0;
            if (first) { firstDown = t; AppKeeper.kick(); }
            if (spent) return;

            // navigation-bar hold: armed only by a touch that STARTS on the bar with one finger
            if (navEnabled) {
                if (first && count == 1) {
                    for (int i = 0; i < SLOTS; i++) if (id[i] >= 0) {
                        if (onNavBar((float) x[i] / maxX, (float) y[i] / maxY)) {
                            navSince = t; navSlot = i; nsx = x[i]; nsy = y[i];
                            capture = null;
                            lock.notifyAll();   // wake the hold timer
                        }
                        break;
                    }
                } else if (navSince != 0) {
                    if (count != 1 || id[navSlot] < 0
                            || Math.abs(x[navSlot] - nsx) > tol || Math.abs(y[navSlot] - nsy) > tol) {
                        navSince = 0;   // second finger, lifted, or moved (a swipe): not a hold
                    } else {
                        return;         // still holding on the bar
                    }
                }
            }
            if (!touchEnabled) return;
            if (count == 2) {
                if (twoSince == 0) {
                    if (t - firstDown > maxGapMs) { spent = true; return; } // second finger came too late
                    twoSince = t;
                    capture = null;
                    lock.notifyAll();   // wake the hold timer
                    for (int i = 0; i < SLOTS; i++) { sx[i] = x[i]; sy[i] = y[i]; }
                } else {
                    for (int i = 0; i < SLOTS; i++) {
                        if (id[i] >= 0 && (Math.abs(x[i] - sx[i]) > tol || Math.abs(y[i] - sy[i]) > tol)) {
                            spent = true; twoSince = 0; return; // moved: pinch/scroll, not a hold
                        }
                    }
                }
            } else if (count > 2 || twoSince != 0) {
                spent = true; twoSince = 0; // third finger, or one of the two lifted early
            }
        }
    }

    static Object wms;
    static java.lang.reflect.Method rotationM;

    /** Current screen rotation (Surface.ROTATION_*), 0 if it can't be read. */
    static int rotation() {
        try {
            if (rotationM == null) {
                android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "window");
                wms = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
                rotationM = wms.getClass().getMethod("getDefaultDisplayRotation");
            }
            return (Integer) rotationM.invoke(wms);
        } catch (Throwable e) {
            rotationM = null;
            return 0;
        }
    }

    /**
     * Is this touch (raw 0..1, portrait/natural axes) on the gesture bar as the screen is turned
     * right now: the bottom strip of the visible screen, away from the sides.
     */
    static boolean onNavBar(float ax, float ay) {
        // most touches are nowhere near an edge: skip the rotation lookup for them
        float sx0 = NAV_STRIP * maxY / maxX;
        if (ax > sx0 && ax < 1f - sx0 && ay > NAV_STRIP && ay < 1f - NAV_STRIP) return false;
        int r = rotation();
        float sx, sy, strip;
        switch (r) {
            case 1: sx = ay; sy = 1f - ax; break;          // ROTATION_90
            case 2: sx = 1f - ax; sy = 1f - ay; break;     // ROTATION_180
            case 3: sx = 1f - ay; sy = ax; break;          // ROTATION_270
            default: sx = ax; sy = ay;
        }
        // same height in pixels whichever way round: in landscape the short side is the height
        strip = (r == 1 || r == 3) ? NAV_STRIP * maxY / maxX : NAV_STRIP;
        return sy >= 1f - strip && sx >= NAV_SIDE && sx <= 1f - NAV_SIDE;
    }

    static Object vims;

    /**
     * Ask the CtsRipple app (localhost:47321) to play the ripple at fx,fy (0..1, natural
     * orientation) over the captured frame, and vibrate as it starts.
     * Protocol: "ripple fx fy len f1x f1y f2x f2y\n" -> "ok\n", then len bytes of raw
     * screencap; the app replies "cts\n" partway through (we open Circle to Search then —
     * its screenshot doesn't include our overlay window) and "done\n" at the end.
     * Returns true if Circle to Search was already launched.
     */
    static boolean ripple(float fx, float fy, float[] fingers, byte[] frame) {
        long t0 = System.nanoTime();
        int len = frame == null ? 0 : frame.length;
        boolean launched = false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.setTcpNoDelay(true);
            s.setSendBufferSize(1 << 22);
            String key = secret;
            if (key == null || key.isEmpty()) { L.i("ripple skipped: no secret from SolCube yet"); return false; }
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 47321), 80);
            s.setSoTimeout(150);
            OutputStream out = s.getOutputStream();
            InputStream in0 = s.getInputStream();
            // Make sure the other end is SolCube before the screenshot leaves this process: another app
            // could be holding the port while vivo has SolCube stopped (see RippleHost / dev.suite.Ipc).
            String hello = readLine(in0);
            if (hello == null || !hello.startsWith("hello ")) return false;
            String ns = hello.substring(6).trim(), nc = IpcAuth.nonce();
            out.write(("auth " + nc + " " + IpcAuth.mac(key, "client|" + ns + "|" + nc) + "\n").getBytes());
            out.flush();
            String resp = readLine(in0);
            if (resp == null || !resp.startsWith("auth ")
                    || !IpcAuth.same(resp.substring(5).trim(), IpcAuth.mac(key, "server|" + nc + "|" + ns))) {
                L.i("ripple port is held by something that isn't SolCube: no screenshot sent");
                return false;
            }
            // 9th field: how long the overlay stays frozen after Circle to Search is called,
            // so CTS's own screenshot doesn't catch the moving ripple
            out.write(String.format(java.util.Locale.US, "ripple %.4f %.4f %d %.4f %.4f %.4f %.4f %d\n",
                    fx, fy, len, fingers[0], fingers[1], fingers[2], fingers[3], freezeMs).getBytes());
            out.flush();
            InputStream in = s.getInputStream();
            if (!"ok".equals(readLine(in))) return false;   // app frozen/not answering: skip the ripple
            if (len > 0) out.write(frame);
            out.flush();
            s.setSoTimeout(3000);
            String line;
            while ((line = readLine(in)) != null) {
                if ("cts".equals(line) && !launched) {
                    launch(); launched = true;
                    L.i("cts at " + (System.nanoTime() - t0) / 1000000 + "ms");
                } else if ("done".equals(line)) {
                    pop();                                   // CTS is revealed now
                    break;
                }
            }
            L.i("ripple " + (System.nanoTime() - t0) / 1000000 + "ms frame=" + len);
        } catch (Exception e) {
            L.i("ripple skipped: " + e);
        }
        return launched;
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') sb.append((char) c);
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    static boolean vibrated;

    // Ripple runs 1050 ms after `start`; second pop comes at `done` (~1080 ms).
    /**
     * Strong, crisp pop: a 30 ms full-power step, which vivo plays as CLICK(STRONG).
     * (Plays when the ripple starts moving and again when CTS is revealed.
     * Note: vivo plays chained effects only up to the first one; a plain waveform plays in full.)
     */
    static void pop() {
        vibrated = true;
        run(java.util.Arrays.asList("cmd", "vibrator_manager", "synced", "-f", "-d", "SolCube",
                "waveform", "-a", "30", "255"));
    }

    static void run(java.util.List<String> cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(new java.io.File("/dev/null")).start();
        } catch (IOException e) {
            L.e("error", e);
        }
    }

    static void launch() {
        long t0 = System.nanoTime();
        try {
            if (vims == null) vims = CtsTrigger.service();
            if (CtsTrigger.trigger(vims, 1)) {
                L.i("fired direct " + (System.nanoTime() - t0) / 1000000 + "ms");
                return;
            }
        } catch (Throwable e) {
            vims = null; // service may have restarted; re-fetch next time
            L.e("error", e);
        }
        try { // fallback: MiCTS app
            L.i("direct call failed, falling back to MiCTS");
            new ProcessBuilder("am", "start", "-n", "com.parallelc.micts/.ui.activity.MainActivity")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            L.e("error", e);
        }
    }
}

