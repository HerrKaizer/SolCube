package dev.suite.shell;

import dev.suite.ISuiteService;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * vivo super resolution (SR), run by Shizuku as a UserService ("dev.suite:sr"). Turns SR on,
 * rewrites it if vivo resets it (checked every 5 s), and turns it off when stopped.
 */
public class SrUserService extends ISuiteService.Stub {
    static final long CHECK = 5000;
    private volatile boolean stop;
    private final Thread loop;

    public SrUserService() {
        L.tag = "Suite.sr";
        loop = new Thread(this::run, "sr-loop");
        loop.start();
    }

    static boolean isOn() {
        return "2".equals(SysSettings.get("system", "vivo_sr_status"))
                && "1".equals(SysSettings.get("global", "vsr_value_from_gamecube"));
    }

    static void set(boolean on) {
        SysSettings.put("system", "vivo_sr_status", on ? "2" : "0");
        SysSettings.put("global", "vsr_value_from_gamecube", on ? "1" : "0");
    }

    private void run() {
        set(true);
        L.i("SR on");
        try {
            while (!stop) {
                Thread.sleep(CHECK);
                if (!stop && !isOn()) { set(true); L.i("sr reset -> rewrote"); }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            L.e("loop crashed", t);
        }
    }

    @Override public void destroy() {
        stop = true;
        loop.interrupt();
        try { loop.join(2000); } catch (InterruptedException ignored) {}
        set(false);
        L.i("SR off");
        System.exit(0);
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", loop.isAlive());
            o.put("sr", SysSettings.get("system", "vivo_sr_status"));
            o.put("vsr", SysSettings.get("global", "vsr_value_from_gamecube"));
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override public void configure(String json) {}

    @Override public String command(String cmd) { return ""; }
}
