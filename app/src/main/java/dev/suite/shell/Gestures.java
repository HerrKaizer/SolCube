package dev.suite.shell;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawn-gesture recognizer shared by Suite (test pad, recorder) and the routines engine (live
 * two-finger drawing). Plain Java, no Android classes.
 *
 * Shapes use the $P point-cloud method (Vatavu, Anthony, Wobbrock 2012): the drawing is resampled
 * to N points, scaled into a unit box and centred, then greedily matched against each template's
 * cloud. Stroke order and direction don't matter, so a circle drawn either way round matches.
 * Straight two-finger swipes are picked out before that by plain geometry.
 *
 * A stroke is float[] {x0, y0, x1, y1, ...} in any units with square pixels (y grows downward).
 */
public final class Gestures {
    public static final int N = 32;

    /** Ids and names of the built-in gestures, by group. */
    public static final String[][] SWIPES = {
            {"swipe_up", "Swipe up"}, {"swipe_down", "Swipe down"},
            {"swipe_left", "Swipe left"}, {"swipe_right", "Swipe right"},
    };
    public static final String[][] SHAPES = {
            {"circle", "Circle"}, {"square", "Square"}, {"triangle", "Triangle"}, {"x", "Cross (X)"},
            {"check", "Check mark ✓"}, {"caret_up", "Arrow up ^"}, {"caret_down", "Arrow down v"},
            {"lt", "Arrow left <"}, {"gt", "Arrow right >"},
    };
    public static final String[][] DIGITS = {
            {"d2", "2"}, {"d3", "3"}, {"d4", "4"}, {"d5", "5"}, {"d6", "6"}, {"d7", "7"}, {"d8", "8"}, {"d9", "9"},
    };

    public static String builtinName(String id) {
        for (String[][] g : new String[][][]{SWIPES, SHAPES, DIGITS})
            for (String[] e : g) if (e[0].equals(id)) return e[1];
        return null;
    }

    public static boolean isSwipe(String id) { return id != null && id.startsWith("swipe_"); }

    /**
     * One-finger drawing happens on top of normal use (Suite can't hold the touch back), so only
     * closed shapes are allowed: a scroll, fling or drag never ends where it began.
     */
    public static final java.util.Set<String> ONE_FINGER_OK =
            new java.util.HashSet<>(java.util.Arrays.asList("circle", "square", "triangle", "d8"));

    /** Why a built-in can't be drawn with one finger (null = it can). */
    public static String oneFingerWhy(String id) {
        if (id == null || ONE_FINGER_OK.contains(id)) return null;
        switch (id) {
            case "x":
                return "A cross is two quick diagonal strokes, which is exactly what two flicks look like while scrolling a feed or moving a map. With one finger it would go off by itself.";
            case "check": case "caret_up": case "caret_down": case "lt": case "gt":
                return "This is two straight lines with one corner. Ordinary drags, swipes between photos and the back gesture draw that shape all the time, so with one finger it would go off by itself.";
            case "d6": case "d9":
                return "Part of this digit is an open line, and a scroll that curls at the end can look just like it. With one finger it would go off by itself.";
            default:
                if (isSwipe(id)) return "A one-finger swipe is how every app scrolls.";
                return "This digit is an open line that doesn't end where it starts. A curved scroll or swipe in an app can draw the same shape, so with one finger it would go off by itself.";
        }
    }

    /** A stored sample (normalized, N points) drawn in one stroke that ends near where it began. */
    public static boolean closedSample(float[] pts, int strokes) {
        if (strokes != 1 || pts == null || pts.length < 2 * N) return false;
        float gap = (float) Math.hypot(pts[2 * N - 2] - pts[0], pts[2 * N - 1] - pts[1]);
        return gap <= 0.34f;   // the cloud is scaled so its longest side is 1
    }

    /** A normalized sample: N points (x, y interleaved) plus how many strokes it was drawn in. */
    public static final class Template {
        public final String id;
        public final float[] pts;
        public final int strokes;
        public Template(String id, float[] pts, int strokes) { this.id = id; this.pts = pts; this.strokes = strokes; }
    }

    public static final class Result {
        public String id;          // the match, or null when nothing is close enough / clear enough
        public String closest;     // best-scoring gesture, even when rejected
        public float score;        // closest's score, 0..1 (1 = perfect)
        public String runnerUp;    // best match among the other gestures
        public float runnerScore;
        @Override public String toString() {
            return (id != null ? id : "none, closest " + closest) + " " + Math.round(score * 100) + "%"
                    + (runnerUp != null ? " (next " + runnerUp + " " + Math.round(runnerScore * 100) + "%)" : "");
        }
    }

    // ================================================================== swipes

    /**
     * "swipe_up" / … when the drawing is one straight stroke along an axis at least minLen long,
     * else null.
     */
    public static String swipe(List<float[]> strokes, float minLen) {
        if (strokes.size() != 1) return null;
        float[] s = strokes.get(0);
        int n = s.length / 2;
        if (n < 2) return null;
        float dx = s[2 * n - 2] - s[0], dy = s[2 * n - 1] - s[1];
        float chord = (float) Math.hypot(dx, dy);
        if (chord < minLen) return null;
        if (chord < 0.9f * pathLength(s)) return null;                 // curved: not a swipe
        // largest sideways wander from the straight line
        float maxOff = 0;
        for (int i = 0; i < n; i++) {
            float off = Math.abs((s[2 * i] - s[0]) * dy - (s[2 * i + 1] - s[1]) * dx) / chord;
            maxOff = Math.max(maxOff, off);
        }
        if (maxOff > 0.2f * chord) return null;
        double ang = Math.toDegrees(Math.atan2(dy, dx));               // 0 = right, 90 = down
        if (Math.abs(ang) <= 25) return "swipe_right";
        if (Math.abs(ang) >= 155) return "swipe_left";
        if (Math.abs(ang - 90) <= 25) return "swipe_down";
        if (Math.abs(ang + 90) <= 25) return "swipe_up";
        return null;
    }

    /** Looks like a scroll: one nearly straight stroke (in any direction). */
    public static boolean straight(List<float[]> strokes) {
        if (strokes.size() != 1) return false;
        float[] s = strokes.get(0);
        int n = s.length / 2;
        if (n < 2) return true;
        float chord = (float) Math.hypot(s[2 * n - 2] - s[0], s[2 * n - 1] - s[1]);
        return chord >= 0.9f * pathLength(s);
    }

    /** Longest side of the bounding box. */
    public static float extent(List<float[]> strokes) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] s : strokes) for (int i = 0; i + 1 < s.length; i += 2) {
            minX = Math.min(minX, s[i]); maxX = Math.max(maxX, s[i]);
            minY = Math.min(minY, s[i + 1]); maxY = Math.max(maxY, s[i + 1]);
        }
        return minX > maxX ? 0 : Math.max(maxX - minX, maxY - minY);
    }

    static float pathLength(float[] s) {
        float d = 0;
        for (int i = 2; i + 1 < s.length; i += 2) d += (float) Math.hypot(s[i] - s[i - 2], s[i + 1] - s[i - 1]);
        return d;
    }

    // ================================================================== $P

    /** Resample to N points over all strokes, scale into a unit box (keeping the shape), centre. Null if degenerate. */
    public static float[] normalize(List<float[]> strokes) {
        float total = 0;
        int pts = 0;
        for (float[] s : strokes) { total += pathLength(s); pts += s.length / 2; }
        if (pts < 2 || total <= 0) return null;
        float step = total / (N - 1);
        float[] out = new float[2 * N];
        int k = 0;
        float carried = 0;
        for (float[] s : strokes) {
            int n = s.length / 2;
            if (n == 0) continue;
            float px = s[0], py = s[1];
            if (k == 0) { out[0] = px; out[1] = py; k = 1; }
            for (int i = 1; i < n && k < N; i++) {
                float qx = s[2 * i], qy = s[2 * i + 1];
                float d = (float) Math.hypot(qx - px, qy - py);
                while (d > 0 && carried + d >= step && k < N) {
                    float t = (step - carried) / d;
                    px = px + t * (qx - px);
                    py = py + t * (qy - py);
                    out[2 * k] = px; out[2 * k + 1] = py; k++;
                    d = (float) Math.hypot(qx - px, qy - py);
                    carried = 0;
                }
                carried += d;
                px = qx; py = qy;
            }
        }
        // rounding can leave the last point(s) unset: repeat the drawing's final point
        float[] last = strokes.get(strokes.size() - 1);
        while (k < N) { out[2 * k] = last[last.length - 2]; out[2 * k + 1] = last[last.length - 1]; k++; }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            minX = Math.min(minX, out[2 * i]); maxX = Math.max(maxX, out[2 * i]);
            minY = Math.min(minY, out[2 * i + 1]); maxY = Math.max(maxY, out[2 * i + 1]);
        }
        float size = Math.max(maxX - minX, maxY - minY);
        if (size <= 0) return null;
        float cx = 0, cy = 0;
        for (int i = 0; i < N; i++) {
            out[2 * i] = (out[2 * i] - minX) / size;
            out[2 * i + 1] = (out[2 * i + 1] - minY) / size;
            cx += out[2 * i]; cy += out[2 * i + 1];
        }
        cx /= N; cy /= N;
        for (int i = 0; i < N; i++) { out[2 * i] -= cx; out[2 * i + 1] -= cy; }
        return out;
    }

    private static float cloudDistance(float[] a, float[] b, int start) {
        boolean[] used = new boolean[N];
        float sum = 0;
        int i = start;
        do {
            float min = Float.MAX_VALUE;
            int idx = -1;
            for (int j = 0; j < N; j++) {
                if (used[j]) continue;
                float dx = a[2 * i] - b[2 * j], dy = a[2 * i + 1] - b[2 * j + 1];
                float d = dx * dx + dy * dy;
                if (d < min) { min = d; idx = j; }
            }
            used[idx] = true;
            float weight = 1f - ((i - start + N) % N) / (float) N;
            sum += weight * (float) Math.sqrt(min);
            i = (i + 1) % N;
        } while (i != start);
        return sum;
    }

    /** $P greedy-5 distance between two normalized clouds. */
    public static float distance(float[] a, float[] b) {
        int step = (int) Math.floor(Math.pow(N, 0.5));
        float min = Float.MAX_VALUE;
        for (int i = 0; i < N; i += step) {
            min = Math.min(min, Math.min(cloudDistance(a, b, i), cloudDistance(b, a, i)));
        }
        return min;
    }

    /** Sum of the weights in cloudDistance: turns a distance into a mean per-point distance. */
    private static final float WEIGHTS = (N + 1) / 2f;

    /** Mean per-point distance (unit box) at which the score reaches 0. */
    static final float ZERO_AT = 0.16f;

    public static float score(float dist) {
        return Math.max(0f, 1f - (dist / WEIGHTS) / ZERO_AT);
    }

    /**
     * Best match among the templates. id is null when the best score is below minScore or the
     * runner-up (a different gesture) is too close behind it.
     */
    public static Result recognize(List<float[]> strokes, List<Template> templates, float minScore) {
        Result r = new Result();
        float[] cloud = normalize(strokes);
        if (cloud == null) return r;
        float[] loop = loop(strokes);
        boolean closed = loop != null;
        // a loop drawn with a lead-in or an overshoot: also compare just the loop, so the tail
        // doesn't drag the score down
        List<float[]> loopStrokes = closed ? java.util.Collections.singletonList(loop) : strokes;
        float[] loopCloud = closed ? normalize(loopStrokes) : null;
        // the more tail was cut off, the less the loop-only match counts (a 6 or 9 is a loop with a long stem)
        float keep = closed ? Math.min(1f, pathLength(loop) / Math.max(1f, pathLength(strokes.get(0)))) : 0f;
        float loopFactor = closed ? Math.max(0f, 1f - 1.6f * (1f - keep)) : 0f;
        int corners = -1;
        java.util.Map<String, Float> best = new java.util.HashMap<>();
        for (Template t : templates) {
            if (CLOSED.contains(t.id) && !closed) continue;   // an arc isn't a circle, ^ isn't a triangle
            float s = score(distance(cloud, t.pts));
            if (loopCloud != null && t.strokes == 1) s = Math.max(s, loopFactor * score(distance(loopCloud, t.pts)));
            // $P can't tell a quick square from a circle: count the corners
            if (t.id.equals("circle") || t.id.equals("square")) {
                if (corners < 0) corners = corners(loopStrokes);
                if (t.id.equals("circle") && corners >= 3) s *= 0.75f;
                if (t.id.equals("square") && corners <= 1) s *= 0.75f;
                if (t.id.equals("square") && corners >= 3) s = Math.min(1f, s * 1.12f);
            }
            Float prev = best.get(t.id);
            if (prev == null || s > prev) best.put(t.id, s);
        }
        String top = null, second = null;
        float ts = -1, ss = -1;
        for (java.util.Map.Entry<String, Float> e : best.entrySet()) {
            float s = e.getValue();
            if (s > ts) { second = top; ss = ts; top = e.getKey(); ts = s; }
            else if (s > ss) { second = e.getKey(); ss = s; }
        }
        r.closest = top;
        r.score = Math.max(0, ts);
        r.runnerUp = second;
        r.runnerScore = Math.max(0, ss);
        if (top != null && ts >= minScore && ts - r.runnerScore >= MARGIN) r.id = top;
        return r;
    }

    /** Built-ins that end where they started. */
    static final java.util.Set<String> CLOSED = new java.util.HashSet<>(java.util.Arrays.asList("circle", "square", "triangle"));

    /**
     * One stroke that comes back around to itself: a loop. People overshoot or start with a little
     * lead-in, so it isn't the two ends that have to meet: some point near the start and some point
     * near the end do. Not a squashed loop (a scroll down and back up comes back to its start too).
     */
    public static boolean closed(List<float[]> strokes) { return loop(strokes) != null; }

    /** The loop part of a single stroke (lead-in / overshoot cut off), or null if it isn't a loop. */
    public static float[] loop(List<float[]> strokes) {
        if (strokes.size() != 1) return null;
        float[] s = strokes.get(0);
        if (s.length < 6) return null;
        int m = 64;
        float[] r = resample(s, m);
        if (r == null) return null;
        float ext = extent(strokes);
        if (ext <= 0) return null;
        // closest pair: i in the first 40 %, j in the last 40 %
        int bi = 0, bj = m - 1;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < m * 2 / 5; i++)
            for (int j = m - m * 2 / 5; j < m; j++) {
                float d = (float) Math.hypot(r[2 * i] - r[2 * j], r[2 * i + 1] - r[2 * j + 1]);
                if (d < best) { best = d; bi = i; bj = j; }
            }
        if (best > 0.3f * ext) return null;
        if ((bi + (m - 1 - bj)) > 0.35f * m) return null;   // long tail: a 6 / 9 / spiral, not a loop
        float[] l = new float[2 * (bj - bi + 2)];
        System.arraycopy(r, 2 * bi, l, 0, 2 * (bj - bi + 1));
        l[l.length - 2] = r[2 * bi]; l[l.length - 1] = r[2 * bi + 1];   // close it
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < l.length; i += 2) {
            minX = Math.min(minX, l[i]); maxX = Math.max(maxX, l[i]);
            minY = Math.min(minY, l[i + 1]); maxY = Math.max(maxY, l[i + 1]);
        }
        float w = maxX - minX, h = maxY - minY;
        if (Math.min(w, h) < 0.25f * Math.max(w, h)) return null;    // flat: a back-and-forth line
        return l;
    }

    /** m evenly spaced points along a stroke. */
    static float[] resample(float[] s, int m) {
        float len = pathLength(s);
        if (len <= 0) return null;
        float[] r = new float[2 * m];
        float step = len / (m - 1), carried = 0;
        float px = s[0], py = s[1];
        r[0] = px; r[1] = py;
        int k = 1;
        for (int i = 2; i + 1 < s.length && k < m; i += 2) {
            float qx = s[i], qy = s[i + 1];
            float d = (float) Math.hypot(qx - px, qy - py);
            while (d > 0 && carried + d >= step && k < m) {
                float t = (step - carried) / d;
                px += t * (qx - px); py += t * (qy - py);
                r[2 * k] = px; r[2 * k + 1] = py; k++;
                d = (float) Math.hypot(qx - px, qy - py);
                carried = 0;
            }
            carried += d;
            px = qx; py = qy;
        }
        for (; k < m; k++) { r[2 * k] = s[s.length - 2]; r[2 * k + 1] = s[s.length - 1]; }
        return r;
    }

    /** Sharp turns (over ~60°) in a single stroke, counted on a smoothed 64-point resample. */
    static int corners(List<float[]> strokes) {
        if (strokes.size() != 1) return 0;
        float[] s = strokes.get(0);
        float len = pathLength(s);
        int m = 64;
        if (len <= 0) return 0;
        float[] r = new float[2 * m];
        float step = len / (m - 1), carried = 0;
        float px = s[0], py = s[1];
        r[0] = px; r[1] = py;
        int k = 1;
        for (int i = 2; i + 1 < s.length && k < m; i += 2) {
            float qx = s[i], qy = s[i + 1];
            float d = (float) Math.hypot(qx - px, qy - py);
            while (d > 0 && carried + d >= step && k < m) {
                float t = (step - carried) / d;
                px += t * (qx - px); py += t * (qy - py);
                r[2 * k] = px; r[2 * k + 1] = py; k++;
                d = (float) Math.hypot(qx - px, qy - py);
                carried = 0;
            }
            carried += d;
            px = qx; py = qy;
        }
        for (; k < m; k++) { r[2 * k] = s[s.length - 2]; r[2 * k + 1] = s[s.length - 1]; }
        int w = 3, count = 0, last = -100;
        boolean loop = closed(strokes);
        for (int i = 0; i < m; i++) {
            int a = i - w, b = i + w;
            if (!loop && (a < 0 || b >= m)) continue;
            a = (a + m) % m; b = b % m;
            double ang1 = Math.atan2(r[2 * i + 1] - r[2 * a + 1], r[2 * i] - r[2 * a]);
            double ang2 = Math.atan2(r[2 * b + 1] - r[2 * i + 1], r[2 * b] - r[2 * i]);
            double turn = Math.abs(Math.toDegrees(ang2 - ang1));
            if (turn > 180) turn = 360 - turn;
            if (turn > 60) {
                if (i - last > w) count++;
                last = i;
            }
        }
        return count;
    }

    /** How far ahead of the runner-up the best match must be. */
    static final float MARGIN = 0.06f;

    /** Score needed for each sensitivity step (0 = strict, 1 = normal, 2 = loose). */
    public static float minScore(int sensitivity) {
        switch (sensitivity) {
            case 0: return 0.55f;
            case 2: return 0.32f;
            default: return 0.42f;
        }
    }

    // ================================================================== built-in templates

    private static List<Template> builtins;

    public static synchronized List<Template> builtins() {
        if (builtins != null) return builtins;
        List<Template> l = new ArrayList<>();
        // circle: round and a bit squashed either way, and with the usual overshoot
        l.add(t("circle", ellipse(1f, 1f, 1f)));
        l.add(t("circle", ellipse(1f, 0.72f, 1f)));
        l.add(t("circle", ellipse(0.72f, 1f, 1f)));
        l.add(t("circle", ellipse(1f, 1f, 1.15f)));
        l.add(t("square", poly(0, 0, 1, 0, 1, 1, 0, 1, 0, 0)));
        l.add(t("square", poly(0, 0, 1, 0, 1, 0.75f, 0, 0.75f, 0, 0)));
        l.add(t("square", poly(0, 0, 0.75f, 0, 0.75f, 1, 0, 1, 0, 0)));
        l.add(t("triangle", poly(0.5f, 0, 1, 0.9f, 0, 0.9f, 0.5f, 0)));
        l.add(t("triangle", poly(0.5f, 0, 1, 1, 0, 1, 0.5f, 0)));
        l.add(t("x", poly(0, 0, 1, 1), poly(1, 0, 0, 1)));
        l.add(t("x", poly(0, 0, 0.8f, 1), poly(0.8f, 0, 0, 1)));
        l.add(t("check", poly(0, 0.55f, 0.32f, 1, 1, 0)));
        l.add(t("check", poly(0, 0.65f, 0.25f, 1, 0.8f, 0)));
        l.add(t("caret_up", poly(0, 1, 0.5f, 0, 1, 1)));
        l.add(t("caret_up", poly(0, 0.7f, 0.5f, 0, 1, 0.7f)));
        l.add(t("caret_down", poly(0, 0, 0.5f, 1, 1, 0)));
        l.add(t("caret_down", poly(0, 0, 0.5f, 0.7f, 1, 0)));
        l.add(t("lt", poly(1, 0, 0, 0.5f, 1, 1)));
        l.add(t("lt", poly(0.7f, 0, 0, 0.5f, 0.7f, 1)));
        l.add(t("gt", poly(0, 0, 1, 0.5f, 0, 1)));
        l.add(t("gt", poly(0, 0, 0.7f, 0.5f, 0, 1)));

        l.add(t("d2", smooth(poly(0.05f, 0.22f, 0.25f, 0.02f, 0.5f, 0.05f, 0.58f, 0.25f, 0.4f, 0.55f, 0.02f, 1f, 0.62f, 1f))));
        l.add(t("d2", smooth(poly(0.05f, 0.2f, 0.3f, 0, 0.55f, 0.15f, 0.45f, 0.45f, 0.02f, 0.98f, 0.2f, 0.92f, 0.62f, 0.98f))));
        l.add(t("d3", smooth(poly(0.05f, 0.1f, 0.35f, 0, 0.55f, 0.15f, 0.5f, 0.35f, 0.22f, 0.48f, 0.5f, 0.58f, 0.6f, 0.8f, 0.4f, 1f, 0.05f, 0.92f))));
        l.add(t("d4", poly(0.42f, 1f, 0.42f, 0, 0, 0.68f, 0.62f, 0.68f)));
        l.add(t("d4", poly(0.12f, 0, 0, 0.65f, 0.6f, 0.65f), poly(0.45f, 0.25f, 0.45f, 1f)));
        l.add(t("d5", poly(0.6f, 0, 0.1f, 0), smooth(poly(0.1f, 0, 0.05f, 0.45f, 0.35f, 0.38f, 0.58f, 0.55f, 0.56f, 0.85f, 0.3f, 1f, 0, 0.9f))));
        l.add(t("d5", smooth(poly(0.6f, 0, 0.1f, 0, 0.05f, 0.45f, 0.35f, 0.38f, 0.58f, 0.55f, 0.56f, 0.85f, 0.3f, 1f, 0, 0.9f))));
        l.add(t("d6", smooth(poly(0.5f, 0, 0.2f, 0.25f, 0.02f, 0.6f, 0.1f, 0.9f, 0.3f, 1f, 0.52f, 0.88f, 0.55f, 0.65f, 0.3f, 0.52f, 0.05f, 0.65f))));
        l.add(t("d7", poly(0, 0, 0.6f, 0, 0.2f, 1f)));
        l.add(t("d7", poly(0, 0, 0.6f, 0, 0.35f, 1f)));
        l.add(t("d8", eight()));
        l.add(t("d9", smooth(poly(0.55f, 0.25f, 0.4f, 0.03f, 0.15f, 0.04f, 0.02f, 0.22f, 0.18f, 0.42f, 0.48f, 0.36f, 0.56f, 0.1f, 0.55f, 0.5f, 0.5f, 1f))));
        l.add(t("d9", smooth(poly(0.55f, 0.15f, 0.3f, 0, 0.05f, 0.12f, 0.08f, 0.38f, 0.35f, 0.42f, 0.55f, 0.2f, 0.55f, 0.6f, 0.52f, 1f))));
        builtins = l;
        return l;
    }

    /** The built-ins' drawings before normalizing (for tests). */
    static final List<Object[]> RAW = new ArrayList<>();

    private static Template t(String id, float[]... strokes) {
        List<float[]> l = new ArrayList<>();
        for (float[] s : strokes) l.add(s);
        RAW.add(new Object[]{id, l});
        return new Template(id, normalize(l), strokes.length);
    }

    /** A polyline from x, y pairs, with points filled in along each segment. */
    static float[] poly(float... xy) {
        List<Float> out = new ArrayList<>();
        for (int i = 0; i + 3 < xy.length; i += 2) {
            for (int k = 0; k < 16; k++) {
                float t = k / 16f;
                out.add(xy[i] + t * (xy[i + 2] - xy[i]));
                out.add(xy[i + 1] + t * (xy[i + 3] - xy[i + 1]));
            }
        }
        out.add(xy[xy.length - 2]);
        out.add(xy[xy.length - 1]);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Rounds the corners of a polyline (moving average), as handwriting does. */
    static float[] smooth(float[] s) {
        int n = s.length / 2;
        float[] r = new float[s.length];
        int w = 6;
        for (int i = 0; i < n; i++) {
            float sx = 0, sy = 0;
            int c = 0;
            for (int j = Math.max(0, i - w); j <= Math.min(n - 1, i + w); j++) { sx += s[2 * j]; sy += s[2 * j + 1]; c++; }
            r[2 * i] = sx / c; r[2 * i + 1] = sy / c;
        }
        r[0] = s[0]; r[1] = s[1]; r[2 * n - 2] = s[2 * n - 2]; r[2 * n - 1] = s[2 * n - 1];
        return r;
    }

    static float[] ellipse(float w, float h, float turns) {
        int n = 64;
        float[] r = new float[2 * (n + 1)];
        for (int i = 0; i <= n; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * turns * i / n;
            r[2 * i] = (float) (w / 2 * Math.cos(a));
            r[2 * i + 1] = (float) (h / 2 * Math.sin(a));
        }
        return r;
    }

    /** Figure eight (Gerono lemniscate, upright). */
    static float[] eight() {
        int n = 80;
        float[] r = new float[2 * (n + 1)];
        for (int i = 0; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            r[2 * i] = (float) (0.3 + 0.3 * Math.sin(2 * a));
            r[2 * i + 1] = (float) (0.5 - 0.5 * Math.sin(a));
        }
        return r;
    }

    private Gestures() {}
}
