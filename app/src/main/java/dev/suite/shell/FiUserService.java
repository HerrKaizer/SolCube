package dev.suite.shell;

import dev.suite.ISolCubeService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * vivo GameCube frame interpolation (FI) controller, run by Shizuku as a
 * UserService in its own shell-user process ("dev.suite:fi"). Mirrors the tested fi.sh loop from
 * the project brief. Settings go through {@link SysSettings} (in-process binder calls), the game
 * PID is found by scanning /proc and the battery temperature comes from sysfs, so the loop
 * normally spawns no processes at all.
 */
public class FiUserService extends ISolCubeService.Stub {

    static final class Game {
        final String pkg; final int src, dst;
        Game(String pkg, int src, int dst) { this.pkg = pkg; this.src = src; this.dst = dst; }
    }

    static final long CHECK = 500;   // ms between checks while a game runs
    static final long IDLE = 5000;   // ms between checks when idle or paused
    static final File TEMPF = new File("/sys/class/power_supply/battery/temp");

    private volatile List<Game> games = new ArrayList<>();
    private volatile int hot = 450, cool = 420;
    private volatile boolean stop;
    private volatile boolean reselect;
    private Thread loop;

    // loop state (also read by status())
    private volatile String pkg = "";
    private volatile int pid = 0, src, dst, temp;
    private volatile boolean paused;

    public FiUserService() {
        L.tag = "SolCube.fi";
        AppKeeper.watchInstalled(this::destroy);
    }

    // ------------------------------------------------------------------ binder

    @Override public synchronized void configure(String json) {
        try {
            JSONObject o = new JSONObject(json);
            List<Game> g = new ArrayList<>();
            JSONArray a = o.getJSONArray("games");
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                g.add(new Game(e.getString("pkg"), e.getInt("src"), e.getInt("dst")));
            }
            games = g;
            hot = o.optInt("hot", 450);
            cool = o.optInt("cool", 420);
            reselect = true;   // pick the game again with the new values
            if (loop == null) {
                loop = new Thread(this::run, "fi-loop");
                loop.start();
            } else {
                L.i("settings changed");
            }
        } catch (Exception e) {
            L.e("bad config", e);
        }
    }

    @Override public void destroy() {
        stop = true;
        if (loop != null) {
            loop.interrupt();
            try { loop.join(2000); } catch (InterruptedException ignored) {}
        }
        offAll();
        L.i("stopped, FI off");
        System.exit(0);
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", loop != null && loop.isAlive());
            o.put("game", pkg);
            o.put("pid", pid);
            o.put("src", src);
            o.put("dst", dst);
            o.put("paused", paused);
            o.put("battery", temp);
            o.put("fi", SysSettings.get("system", "gamecube_frame_interpolation"));
            o.put("gpu", SysSettings.get("global", "gpu_memc_frame_rate"));
            o.put("current", SysSettings.get("secure", "current_game_package"));
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override public String command(String cmd) { return ""; }

    // ------------------------------------------------------------------ settings
    // (super resolution is its own section now: SrUserService)

    static void offAll() {
        SysSettings.put("system", "gamecube_frame_interpolation", "0:-1:0:0:0");
        SysSettings.put("global", "gpu_memc_frame_rate", "x_0_0_0_-1_-1_-1");
    }

    private String fiValue() { return "1:3:" + pid + ":" + src + ":" + dst; }
    private String gpuValue() { return pkg + "_0_" + pid + "_1_30_" + src + "_" + dst; }

    private void fiOn() {
        SysSettings.put("system", "gamecube_frame_interpolation", fiValue());
        SysSettings.put("global", "gpu_memc_frame_rate", gpuValue());
    }

    private void fiOff() {
        SysSettings.put("system", "gamecube_frame_interpolation", "0:-1:" + pid + ":0:0");
        SysSettings.put("global", "gpu_memc_frame_rate", pkg + "_0_" + pid + "_0_-1_-1_-1");
    }

    // ------------------------------------------------------------------ loop (fi.sh "run")

    private void run() {
        L.i("started");
        temp = readTemp();
        long beat = now();
        int n = 0;
        try {
            while (!stop) {
                if (now() - beat >= 300_000) { temp = readTemp(); L.i("alive, battery " + temp); beat = now(); }

                if (reselect && pid != 0) pid = 0;   // games/thresholds edited in the app
                reselect = false;

                if (pid == 0 || !alive(pid)) {
                    if (pid != 0) L.i("game closed");
                    pid = 0;
                    Game g = pickGame();
                    if (g == null) { pkg = ""; Thread.sleep(IDLE); continue; }
                    pkg = g.pkg; src = g.src; dst = g.dst;
                    pid = pidof(pkg); n = 0;
                    if (pid == 0 || !alive(pid)) { pid = 0; Thread.sleep(IDLE); continue; }
                    L.i("now: " + pkg + " pid " + pid + "  " + src + "->" + dst);
                    SysSettings.notice("FI " + src + "→" + dst, true);
                }

                if (paused) {
                    temp = readTemp();
                    if (temp <= cool) { paused = false; n = 0; L.i("battery " + temp + " cooled, resuming"); SysSettings.notice("FI resumed", true); }
                    else { Thread.sleep(IDLE); continue; }
                }

                n++;
                if (n % 10 == 1) {
                    temp = readTemp();
                    if (temp >= hot) {
                        paused = true; fiOff(); L.i("battery " + temp + " hot, FI paused");
                        SysSettings.notice("FI paused, hot", false);
                        Thread.sleep(IDLE); continue;
                    }
                    if (pidof(pkg) != pid) { pid = 0; continue; }
                    if (!gpuValue().equals(SysSettings.get("global", "gpu_memc_frame_rate"))) { fiOn(); L.i("gpu value reset -> rewrote"); }
                }

                String c = SysSettings.get("system", "gamecube_frame_interpolation");
                if (!fiValue().equals(c)) { fiOn(); L.i("reset (" + c + ") -> rewrote"); }
                Thread.sleep(CHECK);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            L.e("loop crashed", t);
        }
    }

    /** Prefers the game in the foreground, then the first running one in list order. */
    private Game pickGame() {
        List<Game> list = games;
        if (list.isEmpty()) return null;
        Map<String, Integer> pids = pids(list);
        String cur = SysSettings.get("secure", "current_game_package");
        for (Game g : list) if (g.pkg.equals(cur) && pids.containsKey(g.pkg)) return g;
        for (Game g : list) if (pids.containsKey(g.pkg)) return g;
        return null;
    }

    static boolean alive(int p) { return new File("/proc/" + p).isDirectory(); }

    static int pidof(String name) {
        List<Game> one = new ArrayList<>();
        one.add(new Game(name, 0, 0));
        Integer p = pids(one).get(name);
        return p == null ? 0 : p;
    }

    /** Like `pidof -s` for several names at once: one pass over /proc. */
    static Map<String, Integer> pids(List<Game> list) {
        Map<String, Integer> out = new HashMap<>();
        String[] dirs = new File("/proc").list();
        if (dirs == null) return out;
        byte[] buf = new byte[256];
        for (String d : dirs) {
            char c0 = d.charAt(0);
            if (c0 < '0' || c0 > '9') continue;
            try (FileInputStream in = new FileInputStream("/proc/" + d + "/cmdline")) {
                int len = in.read(buf);
                if (len <= 0) continue;
                int end = 0;
                while (end < len && buf[end] != 0) end++;
                String name = new String(buf, 0, end);
                for (Game g : list) {
                    if (g.pkg.equals(name) && !out.containsKey(name)) out.put(name, Integer.parseInt(d));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Battery temperature in tenths of a degree. */
    static int readTemp() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(TEMPF)))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) {
            String out = SysSettings.exec("sh", "-c", "dumpsys battery | grep -m1 -i temperature | grep -o '[0-9]*'");
            try { return Integer.parseInt(out.trim()); } catch (Exception e2) { return 0; }
        }
    }

    static long now() { return System.nanoTime() / 1_000_000; }
}
