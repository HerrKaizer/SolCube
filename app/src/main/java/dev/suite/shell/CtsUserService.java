package dev.suite.shell;

import dev.suite.ISuiteService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Circle to Search listener, run by Shizuku as a UserService in its own shell-user process
 * ("dev.suite:cts"). Two triggers, each switchable from Suite:
 *   touch  - two-finger hold on the touchscreen
 *   button - power-button hold (takes the power key exclusively while enabled)
 * Hold times can change live. Turning the button trigger OFF exits the process, which is the
 * only clean way to release the power-key grab; Suite restarts it with the new settings.
 */
public class CtsUserService extends ISuiteService.Stub {
    private static volatile boolean listening;
    private static volatile boolean configured;
    private static volatile boolean button;

    public CtsUserService() {
        L.tag = "Suite.cts";
        CtsListener.touchEnabled = false;   // until Suite sends the settings
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
        // if Suite never sends settings (shouldn't happen), fall back to both triggers on
        Thread fallback = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            if (!configured) {
                L.i("no settings from Suite, using defaults");
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
            apply(o.optBoolean("touch", true), o.optBoolean("button", true),
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
            L.i("button trigger off: exiting to release the power key (Suite restarts the listener)");
            System.exit(0);
        }
        button = btn;
        L.i("triggers: touch=" + touch + " (" + CtsListener.holdMs + " ms), button=" + btn + " (" + PowerKey.holdMs + " ms)");
    }

    /** /dev/input/eventN of the input device with this name (from /proc/bus/input/devices). */
    static String device(String name, String fallback) {
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
            o.put("button", listening && PowerKey.started);
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** "test <freezeMs>": run the whole screenshot -> ripple -> Circle to Search sequence. */
    @Override public String command(String cmd) {
        if (cmd != null && cmd.startsWith("test ")) {
            long ms = Long.parseLong(cmd.substring(5).trim());
            new Thread(() -> CtsListener.test(ms), "cts-test").start();
            return "ok";
        }
        return "";
    }
}
