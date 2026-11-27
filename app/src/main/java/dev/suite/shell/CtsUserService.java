package dev.suite.shell;

import dev.suite.ISolCubeService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Circle to Search listener, run by SolEngine in its own shell-user process
 * ("dev.suite:cts"). Two triggers, each switchable from SolCube:
 *   touch  - two-finger hold on the touchscreen
 *   button - power-button hold (takes the power key exclusively while enabled)
 * Hold times can change live. Turning the button trigger OFF exits the process, which is the
 * only clean way to release the power-key grab; SolCube restarts it with the new settings.
 */
public class CtsUserService extends ISolCubeService.Stub {
    private static volatile boolean listening;
    private static volatile boolean configured;
    private static volatile boolean button;

    static final class InputDev {
        final String path;
        String name = "";
        final java.util.Set<Integer> keys = new java.util.LinkedHashSet<>();
        boolean hasAbsMt, hasSw, hasRel;
        int maxX, maxY;
        InputDev(String path) { this.path = path; }
    }

    public CtsUserService() {
        L.tag = "SolCube.cts";
        AppKeeper.onUninstall = this::destroy;
        CtsListener.touchEnabled = false;   // until SolCube sends the settings
        Thread t = new Thread(() -> {
            try {
                listening = true;
                InputDev td = findTouchDevice(listInputDevices());
                String[] args;
                if (td != null && td.maxX > 0 && td.maxY > 0) {
                    int tol = Math.max(30, td.maxX * 400 / 14400);
                    args = new String[]{td.path, String.valueOf(CtsListener.holdMs),
                            String.valueOf(tol), String.valueOf(td.maxX), String.valueOf(td.maxY)};
                } else {
                    args = new String[]{device("vivo_ts", "/dev/input/event7"),
                            String.valueOf(CtsListener.holdMs), "400"};
                }
                CtsListener.main(args);
            } catch (Throwable e) {
                L.e("listener stopped", e);
            } finally {
                listening = false;
            }
        }, "cts-listener");
        t.start();
        // if SolCube never sends settings (shouldn't happen), fall back to touch trigger only (never grab power key blindly)
        Thread fallback = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            if (!configured) {
                L.i("no settings from SolCube, using touch-only defaults");
                apply(true, false, CtsListener.holdMs, PowerKey.holdMs);
            }
        }, "cts-defaults");
        fallback.setDaemon(true);
        fallback.start();
    }

    @Override public synchronized void configure(String json) {
        try {
            JSONObject o = new JSONObject(json);
            CtsListener.freezeMs = Math.max(0, Math.min(600, o.optLong("freeze", 0)));
            CtsListener.animate = o.optBoolean("animate", true);
            Haptics.configure(o.optJSONObject("haptics"));
            String sec = o.optString("secret", "");
            if (!sec.isEmpty()) {
                CtsListener.secret = sec;
                SysSettings.setNoticeSecret(sec);
            }
            boolean touch = o.optBoolean("touch", true);
            CtsListener.touchCts = o.optBoolean("touchCts", touch);
            CtsListener.touchRoutine = o.optBoolean("touchRoutine", false);
            boolean nav = o.optBoolean("nav", false);
            CtsListener.navCts = o.optBoolean("navCts", nav);
            CtsListener.navRoutine = o.optBoolean("navRoutine", false);
            CtsListener.navHoldMs = clamp(o.optLong("navHold", 350));
            CtsListener.navEnabled = nav;
            boolean btnCts = o.optBoolean("buttonCts", true);
            PowerKey.cts = btnCts;
            PowerKey.act = o.optBoolean("buttonAct", !btnCts);
            PowerKey.routine = o.optBoolean("buttonRoutine", false);
            apply(touch, o.optBoolean("button", true),
                    o.optLong("touchHold", 400), o.optLong("buttonHold", 350));
        } catch (Exception e) {
            L.e("bad config", e);
        }
    }

    private static synchronized void apply(boolean touch, boolean btn, long touchHold, long buttonHold) {
        configured = true;
        CtsListener.holdMs = clamp(touchHold);
        PowerKey.holdMs = clamp(buttonHold);
        CtsListener.touchEnabled = touch;
        if (btn && !PowerKey.started) {
            // Event numbers and driver names vary across chipsets (Qualcomm: pmic_pwrkey/gpio-keys/pmic_resin;
            // MediaTek: mtk-pmic-keys/mtk-kpd) and boots: pick by advertised capabilities rather than fixed names.
            java.util.List<InputDev> devs = listInputDevices();
            InputDev pwr = findPowerDevice(devs);
            if (pwr == null) {
                StringBuilder sb = new StringBuilder();
                for (InputDev d : devs) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(d.path).append('(').append(d.name).append(",k=").append(d.keys.size()).append(')');
                }
                L.i("power key not found; button hold disabled. devices=[" + sb + "]");
            } else {
                java.util.List<InputDev> vols = findVolumeDevices(devs, pwr);
                String[] volPaths = new String[vols.size()];
                StringBuilder vs = new StringBuilder();
                for (int i = 0; i < vols.size(); i++) {
                    InputDev vd = vols.get(i);
                    volPaths[i] = vd.path;
                    if (vs.length() > 0) vs.append(", ");
                    vs.append(vd.path).append(" (").append(vd.name).append(')');
                }
                L.i("keys: power " + pwr.path + " (" + pwr.name + ", keys=" + pwr.keys + "), volume [" + vs + "]");
                PowerKey.start(pwr.path, pwr.name, volPaths);
            }
        } else if (!btn && PowerKey.started) {
            L.i("button trigger off: exiting to release the power key (SolCube restarts the listener)");
            System.exit(0);
        }
        button = btn;
        L.i("triggers: touch=" + touch + " (" + CtsListener.holdMs + " ms), nav=" + CtsListener.navEnabled
                + " (" + CtsListener.navHoldMs + " ms), button=" + btn + " (" + PowerKey.holdMs + " ms)");
    }

    /** /dev/input/eventN of the input device with this name (from `getevent -p`, else `/proc/bus/input/devices`). */
    static String device(String name, String fallback) {
        if (name != null) {
            for (InputDev d : listInputDevices()) {
                if (name.equals(d.name)) return d.path;
            }
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/bus/input/devices"))) {
            String line;
            boolean match = false;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) match = line.equals("N: Name=\"" + name + "\"");
                else if (match && line.startsWith("H: Handlers=")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("event(\\d+)").matcher(line);
                    if (m.find()) return "/dev/input/event" + m.group(1);
                }
            }
        } catch (Exception e) {
            L.e("input devices", e);
        }
        return fallback;
    }

    private static String getevent;   // `getevent -p` output, cached once non-empty per process
    private static java.util.List<InputDev> cachedDevs;
    private static final java.util.regex.Pattern SEC_HDR =
            java.util.regex.Pattern.compile("^([A-Z0-9_]+)\\s*\\(([0-9a-fA-F]{4})\\):(.*)$");
    private static final java.util.regex.Pattern ABS_X = java.util.regex.Pattern.compile("(^|\\s)0035\\s*:");
    private static final java.util.regex.Pattern ABS_Y = java.util.regex.Pattern.compile("(^|\\s)0036\\s*:");
    private static final java.util.regex.Pattern ABS_MAX = java.util.regex.Pattern.compile("max\\s+(\\d+)");

    static synchronized java.util.List<InputDev> listInputDevices() {
        if (cachedDevs != null) return cachedDevs;
        java.util.List<InputDev> devs = new java.util.ArrayList<>();
        try {
            if (getevent == null || getevent.isEmpty()) getevent = SysSettings.exec("getevent", "-p");
            if (getevent == null || getevent.isEmpty()) return devs;
            InputDev cur = null;
            int sec = 0;   // 1=KEY, 3=ABS, 9=PROPS
            for (String raw : getevent.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("add device") && line.contains("/dev/input/")) {
                    cur = new InputDev(line.substring(line.indexOf("/dev/input/")).trim());
                    devs.add(cur);
                    sec = 0;
                    continue;
                }
                if (cur == null) continue;
                if (line.startsWith("name:")) {
                    String n = line.substring(5).trim();
                    if (n.startsWith("\"") && n.endsWith("\"") && n.length() >= 2) n = n.substring(1, n.length() - 1);
                    cur.name = n;
                    sec = 0;
                    continue;
                }
                if (line.startsWith("events:")) { sec = 0; continue; }
                if (line.startsWith("input props:")) {
                    sec = 9;
                    if (line.contains("INPUT_PROP_DIRECT")) cur.hasAbsMt = true;
                    continue;
                }
                java.util.regex.Matcher sm = SEC_HDR.matcher(line);
                if (sm.matches()) {
                    String code = sm.group(2);
                    String rest = sm.group(3);
                    if ("0001".equals(code)) {
                        sec = 1;
                        parseKeyCodes(cur, rest);
                    } else if ("0003".equals(code)) {
                        sec = 3;
                        parseAbsLine(cur, rest);
                    } else if ("0005".equals(code)) {
                        cur.hasSw = true;
                        sec = 0;
                    } else if ("0002".equals(code)) {
                        cur.hasRel = true;
                        sec = 0;
                    } else {
                        sec = 0;
                    }
                    continue;
                }
                if (sec == 1) parseKeyCodes(cur, line);
                else if (sec == 3) parseAbsLine(cur, line);
                else if (sec == 9 && line.contains("INPUT_PROP_DIRECT")) cur.hasAbsMt = true;
            }
            if (!devs.isEmpty()) cachedDevs = java.util.Collections.unmodifiableList(devs);
        } catch (Exception e) {
            L.e("getevent", e);
        }
        return cachedDevs != null ? cachedDevs : devs;
    }

    private static void parseKeyCodes(InputDev d, String s) {
        if (s == null) return;
        for (String tok : s.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            if (tok.endsWith("*")) tok = tok.substring(0, tok.length() - 1);
            if (tok.length() >= 1 && tok.length() <= 4 && tok.matches("[0-9a-fA-F]+")) {
                d.keys.add(Integer.parseInt(tok, 16));
            }
        }
    }

    private static void parseAbsLine(InputDev d, String s) {
        if (s == null) return;
        if (ABS_X.matcher(s).find()) {
            java.util.regex.Matcher m = ABS_MAX.matcher(s);
            if (m.find()) { d.hasAbsMt = true; d.maxX = Integer.parseInt(m.group(1)) + 1; }
        } else if (ABS_Y.matcher(s).find()) {
            java.util.regex.Matcher m = ABS_MAX.matcher(s);
            if (m.find()) { d.hasAbsMt = true; d.maxY = Integer.parseInt(m.group(1)) + 1; }
        }
    }

    private static final String[] EXCLUDED_KEY_NAMES = {
            "touch", "_ts", "ts_", "finger", "_fp", "fp_", "fpc", "goodix", "silead", "focal", "synaptics",
            "uinput", "virtual", "headset", "jack", "avrcp", "bluetooth", "bt_", "hid", "mouse",
            "hall", "lid", "sensor", "vibrat", "haptic", "stylus", "pen", "qwerty", "keyboard"
    };

    static boolean isExcludedKeyDev(InputDev d) {
        if (d.hasAbsMt || d.hasRel || d.hasSw || d.keys.isEmpty() || d.keys.size() > 48) return true;
        if (d.keys.contains(30) && d.keys.contains(57)) return true;   // KEY_A + KEY_SPACE -> QWERTY keyboard
        String low = d.name.toLowerCase(java.util.Locale.US);
        for (String kw : EXCLUDED_KEY_NAMES) {
            if (low.contains(kw)) return true;
        }
        return false;
    }

    static InputDev findPowerDevice(java.util.List<InputDev> devs) {
        InputDev best = null;
        int bestScore = Integer.MIN_VALUE;
        for (InputDev d : devs) {
            if (isExcludedKeyDev(d) || !d.keys.contains(PowerKey.KEY_POWER)) continue;
            int score = 0;
            String low = d.name.toLowerCase(java.util.Locale.US);
            if ("pmic_pwrkey".equals(low)) score += 100;
            else if (low.contains("pwrkey") || low.contains("power")
                    || "mtk-pmic-keys".equals(low) || "pmic-keys".equals(low) || "qpnp_pon".equals(low)) score += 80;
            else if ("mtk-kpd".equals(low) || "gpio-keys".equals(low) || "gpio_keys".equals(low)
                    || low.contains("pmic") || low.contains("kpd") || low.contains("key")) score += 50;
            if (d.keys.size() == 1) score += 40;
            else score -= d.keys.size();
            if (score > bestScore) { bestScore = score; best = d; }
        }
        return best;
    }

    static java.util.List<InputDev> findVolumeDevices(java.util.List<InputDev> devs, InputDev powerDev) {
        java.util.List<InputDev> out = new java.util.ArrayList<>();
        for (InputDev d : devs) {
            if (powerDev != null && d.path.equals(powerDev.path)) continue;
            if (isExcludedKeyDev(d)) continue;
            if (d.keys.contains(PowerKey.KEY_VOLUMEDOWN) || d.keys.contains(PowerKey.KEY_VOLUMEUP)) out.add(d);
        }
        return out;
    }

    static InputDev findTouchDevice(java.util.List<InputDev> devs) {
        InputDev fallback = null;
        for (InputDev d : devs) {
            if (d.maxX <= 0 || d.maxY <= 0) continue;
            if ("vivo_ts".equals(d.name)) return d;
            if (fallback == null) fallback = d;
        }
        return fallback;
    }

    private static long clamp(long ms) { return Math.max(200, Math.min(1000, ms)); }

    @Override public void destroy() {
        L.i("stopping");
        if (!AppKeeper.isAppInstalled()) AppKeeper.disableA11y();
        System.exit(0);   // closing the input fds releases the power-key grab
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", listening);
            o.put("touch", listening && CtsListener.touchEnabled);
            o.put("touchRoutine", listening && CtsListener.touchRoutine);
            o.put("button", listening && PowerKey.started);
            o.put("buttonRoutine", listening && PowerKey.routine);
            o.put("nav", listening && CtsListener.navEnabled);
            o.put("navRoutine", listening && CtsListener.navRoutine);
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** "test <freezeMs>": run the whole screenshot -> ripple -> Circle to Search sequence. */
    @Override public String command(String cmd) {
        if (cmd != null && cmd.startsWith("run_action ")) {
            try {
                JSONObject a = new JSONObject(cmd.substring(11).trim());
                Thread t = new Thread(() -> ShellActions.runAction(a), "cts-action");
                t.setDaemon(true);
                t.start();
                return "ok";
            } catch (Exception e) {
                L.e("run_action", e);
                return "err";
            }
        }
        if (cmd != null && cmd.startsWith("test ")) {
            long ms = Long.parseLong(cmd.substring(5).trim());
            new Thread(() -> CtsListener.test(ms), "cts-test").start();
            return "ok";
        }
        if ("power-cts".equals(cmd)) {   // double power press set to Circle to Search: ripple from the button
            new Thread(() -> {
                CtsListener.Capture c = null;
                if (CtsListener.animate) {
                    c = new CtsListener.Capture();
                    c.start();
                }
                float fx = PowerKey.fx, fy = PowerKey.fy;
                CtsListener.fire(fx, fy, new float[]{fx, fy, fx, fy}, c);
            }, "power-cts").start();
            return "ok";
        }
        return "";
    }
}
