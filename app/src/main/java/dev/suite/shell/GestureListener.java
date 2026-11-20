package dev.suite.shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Two-finger drawn gestures, run inside the routines engine (shell user). Reads the raw
 * touchscreen like the Circle to Search listener does; it only watches, so the app under the
 * fingers sees the touches too. That's why gestures are drawn with two fingers held together:
 * two fingers that keep their spacing (not a pinch) are rare in normal use.
 *
 * A stroke = both fingers down (the second within 350 ms of the first) until either lifts. The
 * path is the point between the two fingers. Strokes that follow within GAP_MS belong to the
 * same gesture (a cross is two strokes).
 */
final class GestureListener {
    interface Sink { void onGesture(String id, int fingers, Gestures.Result r); }

    static final int SLOTS = 16;
    static final long SECOND_FINGER_MS = 350, GAP_MS = 450, MAX_STROKE_MS = 4000;
    static final int ABS_MT_SLOT = 0x2f, ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39;

    // settings (from configure)
    private static volatile boolean on;
    private static volatile List<Gestures.Template> templates = Gestures.builtins();
    private static volatile Set<String> used = new HashSet<>();    // two-finger gestures with actions
    private static volatile Set<String> used1 = new HashSet<>();   // one-finger gestures with actions
    private static volatile Set<String> ignore = new HashSet<>();
    private static volatile Set<String> multi = new HashSet<>();   // gestures with a template drawn in 2+ strokes
    private static volatile float minScore = Gestures.minScore(1);
    private static volatile boolean vibrate = true;

    // status
    static volatile boolean running;
    static volatile String last = "";
    static volatile String device = "";

    private static Sink sink;
    private static Thread reader;
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gesture-timer");
        t.setDaemon(true);
        return t;
    });

    // touch state (reader thread, and the timer under `this` lock)
    private static final int[] id = new int[SLOTS], x = new int[SLOTS], y = new int[SLOTS];
    private static int slot;
    private static int maxX = 14400, maxY = 31680;
    private static long firstDown;
    private static boolean spent;           // this touch can't start a stroke (until all fingers lift)
    private static boolean drawing;         // a stroke is in progress
    private static int curKey;              // which slots were down last frame (bit mask)
    private static float penX, penY, offX, offY;   // pen position (raw) and its offset from the fingers
    private static float d0;
    private static long strokeStart;
    private static float[] buf = new float[512];
    private static int bufN;
    private static int rotation;
    private static final List<float[]> strokes = new ArrayList<>();
    private static int gen;                 // bumps when a new stroke starts (cancels a pending decision)

    /** {on, sens, vibrate, ignore:[pkg], samples:[{id, s, p:[..]}]}, plus the routines (to know which gestures are used). */
    static synchronized void configure(JSONObject g, JSONArray routines, Sink s) {
        sink = s;
        Set<String> u = new HashSet<>(), u1 = new HashSet<>();
        if (routines != null) for (int i = 0; i < routines.length(); i++) {
            JSONObject r = routines.optJSONObject(i);
            if (r == null || !r.optBoolean("on", true)) continue;
            JSONObject t = r.optJSONObject("trigger");
            if (t != null && "gesture".equals(t.optString("type")) && !t.optString("gesture").isEmpty())
                (t.optInt("fingers", 2) == 1 ? u1 : u).add(t.optString("gesture"));
        }
        List<Gestures.Template> tpl = new ArrayList<>(Gestures.builtins());
        Set<String> ig = new HashSet<>();
        boolean enabled = false;
        int sens = 1;
        boolean vib = true;
        if (g != null) {
            enabled = g.optBoolean("on", false);
            sens = g.optInt("sens", 1);
            vib = g.optBoolean("vibrate", true);
            JSONArray a = g.optJSONArray("ignore");
            if (a != null) for (int i = 0; i < a.length(); i++) ig.add(a.optString(i));
            JSONArray sm = g.optJSONArray("samples");
            if (sm != null) for (int i = 0; i < sm.length(); i++) {
                JSONObject o = sm.optJSONObject(i);
                JSONArray p = o != null ? o.optJSONArray("p") : null;
                if (p == null || p.length() != 2 * Gestures.N) continue;
                float[] pts = new float[p.length()];
                for (int k = 0; k < pts.length; k++) pts[k] = (float) p.optDouble(k);
                tpl.add(new Gestures.Template(o.optString("id"), pts, Math.max(1, o.optInt("s", 1))));
            }
        }
        Set<String> m = new HashSet<>();
        for (Gestures.Template t : tpl) if (t.strokes > 1) m.add(t.id);
        templates = tpl;
        used = u;
        used1 = u1;
        ignore = ig;
        multi = m;
        minScore = Gestures.minScore(sens);
        vibrate = vib;
        on = enabled && !(u.isEmpty() && u1.isEmpty());
        L.i("gestures: " + (on ? "listening for 2 fingers " + u + ", 1 finger " + u1 : enabled ? "on, but no routine uses one" : "off"));
        if (on && reader == null) start();
    }

    static boolean listening() { return on && running; }

    private static void start() {
        reader = new Thread(() -> {
            try {
                findDevice();
                for (int i = 0; i < SLOTS; i++) id[i] = -1;
                running = true;
                L.i("gestures: reading " + device + " (" + maxX + "×" + maxY + ")");
                byte[] b = new byte[24 * 64];
                try (FileInputStream in = new FileInputStream(device)) {
                    while (true) {
                        int n = in.read(b);
                        if (n <= 0) break;
                        for (int o = 0; o + 24 <= n; o += 24) {
                            int type = (b[o + 16] & 0xff) | (b[o + 17] & 0xff) << 8;
                            int code = (b[o + 18] & 0xff) | (b[o + 19] & 0xff) << 8;
                            int val = (b[o + 20] & 0xff) | (b[o + 21] & 0xff) << 8 | (b[o + 22] & 0xff) << 16 | (b[o + 23]) << 24;
                            if (type == 3) {
                                if (code == ABS_MT_SLOT) slot = (val >= 0 && val < SLOTS) ? val : 0;
                                else if (code == ABS_MT_TRACKING_ID) id[slot] = val;
                                else if (code == ABS_MT_POSITION_X) x[slot] = val;
                                else if (code == ABS_MT_POSITION_Y) y[slot] = val;
                            } else if (type == 0 && code == 0 && on) {
                                frame();
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                L.e("gestures: listener stopped", t);
            } finally {
                running = false;
                synchronized (GestureListener.class) { reader = null; }
            }
        }, "gestures");
        reader.setDaemon(true);
        reader.start();
    }

    /** The touchscreen: vivo's "vivo_ts", else the first device reporting multi-touch X and Y. Reads its X/Y range. */
    private static void findDevice() {
        String out = SysSettings.exec("getevent", "-p");
        List<String[]> devs = new ArrayList<>();   // {path, name, maxX, maxY}
        String[] cur = null;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("add device") && line.contains("/dev/input/")) {
                cur = new String[]{line.substring(line.indexOf("/dev/input/")).trim(), "", "0", "0"};
                devs.add(cur);
            } else if (cur == null) {
                continue;
            } else if (line.startsWith("name:")) {
                cur[1] = line.substring(5).trim().replace("\"", "");
            } else if (AX.matcher(line).find()) {
                cur[2] = String.valueOf(maxOf(line));
            } else if (AY.matcher(line).find()) {
                cur[3] = String.valueOf(maxOf(line));
            }
        }
        String[] pick = null;
        for (String[] d : devs) {
            if ("0".equals(d[2]) || "0".equals(d[3])) continue;
            if ("vivo_ts".equals(d[1])) { pick = d; break; }
            if (pick == null) pick = d;
        }
        if (pick != null) {
            device = pick[0];
            maxX = Integer.parseInt(pick[2]) + 1;
            maxY = Integer.parseInt(pick[3]) + 1;
        } else {
            device = CtsUserService.device("vivo_ts", "/dev/input/event7");
        }
    }

    // getevent -p lists axes as "0035  : value 0, min 0, max 14399, ..." (the first after "ABS (0003): ")
    private static final java.util.regex.Pattern AX = java.util.regex.Pattern.compile("(^|\\s)0035\\s*:");
    private static final java.util.regex.Pattern AY = java.util.regex.Pattern.compile("(^|\\s)0036\\s*:");

    private static int maxOf(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("max (\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static long now() { return System.nanoTime() / 1_000_000; }

    private static synchronized void frame() {
        int count = 0, a = -1, b = -1, key = 0;
        for (int i = 0; i < SLOTS; i++) if (id[i] >= 0) { if (a < 0) a = i; else if (b < 0) b = i; count++; key |= 1 << i; }
        long t = now();
        if (count == 0) {
            if (solo) endSolo();
            if (drawing) endStroke(t);
            firstDown = 0; spent = false;
            return;
        }
        if (firstDown == 0) {
            firstDown = t;
            if (count == 1 && !used1.isEmpty()) startSolo(a, t);
        } else if (solo) {
            // still the same single finger, not too slow: keep the path; anything else isn't a one-finger drawing
            if (count != 1 || a != soloSlot || t - soloStart > MAX_SOLO_MS) solo = false;
            else soloAdd(x[a], y[a]);
        }
        if (count > 2) {   // three fingers: screenshot gesture etc. Drop the whole drawing.
            if (drawing || !strokes.isEmpty()) { drawing = false; strokes.clear(); gen++; L.i("gesture dropped: 3 fingers"); }
            spent = true;
            return;
        }
        float cx = count == 2 ? (x[a] + x[b]) / 2f : x[a];
        float cy = count == 2 ? (y[a] + y[b]) / 2f : y[a];
        if (drawing) {
            if (t - strokeStart > MAX_STROKE_MS) { drop("took over " + MAX_STROKE_MS / 1000 + " s"); return; }
            if (key != curKey) {
                // fingers merged / split / one lifted: carry on from where the pen was, no jump
                offX = penX - cx; offY = penY - cy;
                curKey = key;
                if (count == 2) d0 = Math.max(1f, dist(a, b));
            } else if (count == 2) {
                float d = dist(a, b);
                if (d < 0.4f * d0 - 400 || d > 2.2f * d0 + 400) { drop("pinch (spacing " + Math.round(d0) + " → " + Math.round(d) + ")"); return; }
            }
            pen(cx + offX, cy + offY);
            return;
        }
        if (spent) return;
        if (count == 2) {
            if (t - firstDown > SECOND_FINGER_MS) { spent = true; return; }   // one finger first, then another: a pinch
            drawing = true;
            curKey = key;
            d0 = Math.max(1f, dist(a, b));
            offX = offY = 0;
            strokeStart = t;
            bufN = 0;
            if (strokes.isEmpty()) rotation = CtsListener.rotation();
            gen++;   // a stroke after a short gap continues the gesture
            pen(cx, cy);
            // pulling the shade down / swiping up from the bar with two fingers isn't a gesture
            float[] p = screen(cx, cy);
            float h = screenH();
            if (p[1] < 0.05f * h || p[1] > 0.965f * h) { drop("started at the screen edge"); }
        }
    }

    // ---------------------------------------------------------------- one finger

    static final long MAX_SOLO_MS = 3000;
    private static boolean solo;
    private static int soloSlot, soloN;
    private static long soloStart;
    private static float[] soloBuf = new float[512];
    private static int soloRot;

    private static void startSolo(int s, long t) {
        soloRot = CtsListener.rotation();
        int saved = rotation;
        rotation = soloRot;
        float[] p = screen(x[s], y[s]);
        float w = (soloRot == 1 || soloRot == 3) ? maxY : maxX, h = screenH();
        rotation = saved;
        // not from an edge: that's the back gesture, the shade or the navigation bar
        if (p[0] < 0.05f * w || p[0] > 0.95f * w || p[1] < 0.05f * h || p[1] > 0.96f * h) return;
        solo = true;
        soloSlot = s;
        soloStart = t;
        soloN = 0;
        soloAdd(x[s], y[s]);
    }

    private static void soloAdd(float rx, float ry) {
        if (soloN + 2 > soloBuf.length) {
            if (soloBuf.length >= 8192) return;
            soloBuf = java.util.Arrays.copyOf(soloBuf, soloBuf.length * 2);
        }
        int saved = rotation;
        rotation = soloRot;
        float[] p = screen(rx, ry);
        rotation = saved;
        soloBuf[soloN++] = p[0];
        soloBuf[soloN++] = p[1];
    }

    private static void endSolo() {
        solo = false;
        if (soloN < 8) return;
        final List<float[]> list = new ArrayList<>();
        list.add(java.util.Arrays.copyOf(soloBuf, soloN));
        timer.execute(() -> decideSolo(list));
    }

    /** Most one-finger touches are taps and scrolls: drop those quietly. Only closed shapes go on. */
    private static void decideSolo(List<float[]> list) {
        float ss = shortSide();
        if (Gestures.extent(list) < 0.15f * ss || !Gestures.closed(list)) return;
        lastDrawing = describe(list);
        String time = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
        Gestures.Result r = Gestures.recognize(list, templates, minScore);
        if (r.id == null || !used1.contains(r.id)) {
            if (r.id == null) { L.i("one-finger shape not recognised: " + r); last = "? " + r.closest + " " + Math.round(r.score * 100) + "% " + time; }
            return;
        }
        fire(r, 1, time);
    }

    private static void fire(Gestures.Result r, int fingers, String time) {
        if (ShellActions.locked()) { L.i("gesture " + r.id + " ignored: locked"); return; }
        String front = ShellActions.frontTask().pkg;
        if (ignore.contains(front)) { L.i("gesture " + r.id + " ignored in " + front); return; }
        L.i("gesture (" + fingers + " finger" + (fingers == 1 ? "" : "s") + ") " + r);
        last = r.id + " " + Math.round(r.score * 100) + "% " + time;
        if (vibrate) Haptics.pop();
        Sink s = sink;
        if (s != null) s.onGesture(r.id, fingers, r);
    }

    private static void drop(String why) {
        drawing = false; strokes.clear(); gen++; spent = true;
        L.i("gesture dropped: " + why);
        last = "! " + why;
    }

    private static float dist(int a, int b) { return (float) Math.hypot(x[a] - x[b], y[a] - y[b]); }

    private static void pen(float rx, float ry) {
        penX = rx; penY = ry;
        if (bufN + 2 > buf.length) {
            if (buf.length >= 8192) return;
            buf = java.util.Arrays.copyOf(buf, buf.length * 2);
        }
        float[] p = screen(rx, ry);
        buf[bufN++] = p[0];
        buf[bufN++] = p[1];
    }

    /** Raw (natural orientation) → the screen as it's turned now, same units. */
    private static float[] screen(float rx, float ry) {
        switch (rotation) {
            case 1: return new float[]{ry, maxX - rx};
            case 2: return new float[]{maxX - rx, maxY - ry};
            case 3: return new float[]{maxY - ry, rx};
            default: return new float[]{rx, ry};
        }
    }

    private static float screenH() { return (rotation == 1 || rotation == 3) ? maxX : maxY; }
    private static float shortSide() { return Math.min(maxX, maxY); }

    private static void endStroke(long t) {
        if (!drawing) return;
        drawing = false;
        if (bufN >= 4) strokes.add(java.util.Arrays.copyOf(buf, bufN));
        bufN = 0;
        if (strokes.isEmpty()) return;
        final int g = gen;
        // decide now when nothing drawn in several strokes is in use, else give a second stroke a moment
        boolean wait = strokes.size() < 3 && usesMulti();
        if (wait) {
            Gestures.Result r = match(new ArrayList<>(strokes));
            if (r != null && r.id != null && r.score >= minScore + 0.12f && !multi.contains(r.id)) wait = false;
        }
        timer.schedule(() -> decide(g), wait ? GAP_MS : 0, TimeUnit.MILLISECONDS);
    }

    private static boolean usesMulti() {
        for (String u : used) if (multi.contains(u)) return true;
        return false;
    }

    private static void decide(int g) {
        List<float[]> list;
        synchronized (GestureListener.class) {
            if (g != gen || drawing) return;   // another stroke started
            list = new ArrayList<>(strokes);
            strokes.clear();
        }
        if (list.isEmpty()) return;
        lastDrawing = describe(list);
        String time = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
        float ss = shortSide();
        if (Gestures.extent(list) < 0.12f * ss) {
            L.i("gesture dropped: too small (" + Math.round(100 * Gestures.extent(list) / ss) + "% of the screen width)");
            last = "! too small " + time;
            return;
        }
        Gestures.Result r = match(list);
        if (r == null) { L.i("gesture dropped: a straight line (scroll)"); last = "! straight line " + time; return; }
        if (r.id == null) {
            L.i("gesture not recognised: " + r);
            last = "? " + r.closest + " " + Math.round(r.score * 100) + "% " + time;
            return;
        }
        if (!used.contains(r.id)) {
            L.i("gesture " + r + ": nothing assigned to it");
            last = r.id + " " + Math.round(r.score * 100) + "% " + time;
            return;
        }
        fire(r, 2, time);
    }

    /** The last drawing, resampled small, for the status (lets a bad match be looked at). */
    static volatile String lastDrawing = "";

    private static String describe(List<float[]> list) {
        StringBuilder sb = new StringBuilder();
        for (float[] s : list) {
            if (sb.length() > 0) sb.append(" | ");
            int n = s.length / 2, step = Math.max(1, n / 40);
            for (int i = 0; i < n; i += step) sb.append(Math.round(s[2 * i] / 10)).append(',').append(Math.round(s[2 * i + 1] / 10)).append(' ');
        }
        return sb.toString().trim();
    }

    /** Swipe, shape, or null for drawings that are too small / plain scrolls. */
    private static Gestures.Result match(List<float[]> list) {
        float ss = shortSide();
        if (Gestures.extent(list) < 0.12f * ss) return null;            // tiny: a nudge, not a drawing
        String sw = Gestures.swipe(list, 0.2f * ss);
        if (sw != null) {
            Gestures.Result r = new Gestures.Result();
            r.id = r.closest = sw;
            r.score = 1f;
            return r;
        }
        if (Gestures.straight(list)) return null;                        // diagonal line: a scroll
        return Gestures.recognize(list, templates, minScore);
    }

    private GestureListener() {}
}
