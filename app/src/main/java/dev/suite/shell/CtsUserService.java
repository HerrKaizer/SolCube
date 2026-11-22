package dev.suite.shell;

import dev.suite.ISolCubeService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Circle to Search listener, run by Shizuku as a UserService in its own shell-user process
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

    public CtsUserService() {
        L.tag = "SolCube.cts";
        CtsListener.touchEnabled = false;   // until SolCube sends the settings
        Thread t = new Thread(() -> {
            try {
                listening = true;
                CtsListener.main(new String[]{device("vivo_ts", "/dev/input/event7"), String.valueOf(CtsListener.holdMs), "400"});
            } catch (Throwable e) {
                L.e("listener stopped", e);
            } finally {
                listening = false;
            }
        }, "cts-listener");
        t.start();
        // if SolCube never sends settings (shouldn't happen), fall back to both triggers on
        Thread fallback = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            if (!configured) {
                L.i("no settings from SolCube, using defaults");
                apply(true, true, CtsListener.holdMs, PowerKey.holdMs);
            }
        }, "cts-defaults");
        fallback.setDaemon(true);
        fallback.start();
    }

    @Override public synchronized void configure(String json) {
        try {
            JSONObject o = new JSONObject(json);
            CtsListener.freezeMs = Math.max(0, Math.min(600, o.optLong("freeze", 0)));
            String sec = o.optString("secret", "");
            if (!sec.isEmpty()) CtsListener.secret = sec;
            boolean touch = o.optBoolean("touch", true);
            CtsListener.touchCts = o.optBoolean("touchCts", touch);
            CtsListener.touchRoutine = o.optBoolean("touchRoutine", false);
            boolean nav = o.optBoolean("nav", false);
            CtsListener.navCts = o.optBoolean("navCts", nav);
            CtsListener.navRoutine = o.optBoolean("navRoutine", false);
            CtsListener.navHoldMs = clamp(o.optLong("navHold", 450));
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
            // event numbers change between boots (e.g. pmic_resin moved event3 -> event2 and the
            // fingerprint sensor took event3, which broke volume+power screenshots): look them up by name
            String pwr = device("pmic_pwrkey", "/dev/input/event1");
            String volUp = device("gpio-keys", "/dev/input/event0");
            String volDown = device("pmic_resin", "/dev/input/event2");
            L.i("keys: power " + pwr + ", volume " + volUp + " + " + volDown);
            PowerKey.start(pwr, volUp, volDown);
        } else if (!btn && PowerKey.started) {
            L.i("button trigger off: exiting to release the power key (SolCube restarts the listener)");
            System.exit(0);
        }
        button = btn;
        L.i("triggers: touch=" + touch + " (" + CtsListener.holdMs + " ms), button=" + btn + " (" + PowerKey.holdMs + " ms)");
    }

    /** /dev/input/eventN of the input device with this name (from /proc/bus/input/devices). */
    static String device(String name, String fallback) {
        // vivo's SELinux policy denies shell both /proc/bus/input/devices and /sys/class/input/*/name,
        // so ask getevent (it reads each device's name with an ioctl). Before this, the lookup
        // always failed and fell back to fixed numbers, which only match some phones' boot order.
        String byIoctl = deviceFromGetevent(name);
        if (byIoctl != null) return byIoctl;
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

    private static String getevent;   // `getevent -p` output, read once per process

    /** "add device 9: /dev/input/event1" followed by `name: "pmic_pwrkey"` in getevent -p's listing. */
    static synchronized String deviceFromGetevent(String name) {
        try {
            if (getevent == null) getevent = SysSettings.exec("getevent", "-p");
            String dev = null;
            for (String line : getevent.split("\n")) {
                line = line.trim();
                if (line.startsWith("add device")) dev = line.substring(line.indexOf("/dev/input/")).trim();
                else if (dev != null && line.startsWith("name:") && line.endsWith("\"" + name + "\"")) {
                    L.i("found " + name + " at " + dev);
                    return dev;
                }
            }
        } catch (Exception e) {
            L.e("getevent", e);
        }
        return null;
    }

    private static long clamp(long ms) { return Math.max(200, Math.min(1000, ms)); }

    @Override public void destroy() {
        L.i("stopping");
        AppKeeper.disableA11y();
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
                CtsListener.Capture c = new CtsListener.Capture();
                c.start();
                float fx = PowerKey.fx, fy = PowerKey.fy;
                CtsListener.fire(fx, fy, new float[]{fx, fy, fx, fy}, c);
            }, "power-cts").start();
            return "ok";
        }
        return "";
    }
}
