package dev.suite.shell;

import dev.suite.ISuiteService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stream to laptop, run by Shizuku as a UserService ("dev.suite:stream").
 *
 *  - "Wide" mode: the phone's display is resized to the laptop's shape (e.g. 1200x1920 for a
 *    16:10 laptop) so the game fills the laptop screen; the phone keeps showing the game and is
 *    used as the touch controller.
 *  - Talks to the laptop helper (GenshinMode\helper.ps1) over USB: the helper runs
 *    `adb reverse tcp:47420 tcp:47420`, so 127.0.0.1:47420 here reaches the laptop.
 *    Line protocol: "status" -> "ok <streaming 0|1> <screenW> <screenH>", "start", "stop".
 *  - Safety: if the laptop can't be reached for 15 s while wide (cable pulled, laptop asleep),
 *    the display is put back to normal. Stopping this service also puts it back.
 */
public class StreamUserService extends ISuiteService.Stub {
    static final int PORT = 47420;
    static volatile String token = "";   // from the laptop helper via Suite (see dev.suite.Ipc)
    static final long CHECK = 3000, LOST = 15000;
    private volatile int longSide = 1920;   // from the helper's Resolution menu
    private volatile int appliedLong;

    private volatile boolean stop;
    private volatile boolean wide, helperOk, streaming;
    private volatile long lastHelper = System.currentTimeMillis();
    private volatile int pcW = 16, pcH = 10;
    private final Thread loop;

    public StreamUserService() {
        L.tag = "Suite.stream";
        wide = readWide();
        loop = new Thread(this::run, "stream-loop");
        loop.start();
    }

    // ------------------------------------------------------------------ laptop helper

    /** One request/response to the laptop helper; null if it isn't reachable. */
    static String ask(String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 600);
            s.setSoTimeout(4000);
            OutputStream out = s.getOutputStream();
            // the helper only obeys requests carrying its token (other phone apps can reach this port too)
            out.write((token + " " + cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void poll() {
        String r = ask("status");
        if (r != null && r.startsWith("ok")) {
            String[] f = r.split("\\s+");
            helperOk = true;
            lastHelper = System.currentTimeMillis();
            streaming = f.length > 1 && f[1].equals("1");
            if (f.length > 3) {
                try {
                    int w = Integer.parseInt(f[2]), h = Integer.parseInt(f[3]);
                    if (w > 0 && h > 0) { pcW = Math.max(w, h); pcH = Math.min(w, h); }
                    if (f.length > 4) longSide = Math.max(1280, Math.min(3168, Integer.parseInt(f[4])));
                } catch (NumberFormatException ignored) {}
            }
        } else {
            helperOk = false;
            streaming = false;
        }
    }

    // ------------------------------------------------------------------ display shape

    private static final Pattern SIZE = Pattern.compile("Physical size: (\\d+)x(\\d+)");
    private static final Pattern DENSITY = Pattern.compile("Physical density: (\\d+)");

    static boolean readWide() {
        return SysSettings.exec("wm", "size").contains("Override size");
    }

    private synchronized void setWide(boolean on) {
        if (!on) {
            SysSettings.exec("wm", "size", "reset");
            SysSettings.exec("wm", "density", "reset");
            wide = false;
            L.i("screen back to normal");
            return;
        }
        Matcher m = SIZE.matcher(SysSettings.exec("wm", "size"));
        Matcher d = DENSITY.matcher(SysSettings.exec("wm", "density"));
        int physShort = m.find() ? Math.min(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) : 1440;
        int physDensity = d.find() ? Integer.parseInt(d.group(1)) : 640;
        // long side 1920, short side from the laptop's aspect ratio (16:10 -> 1200)
        int lng = longSide;
        int shortSide = Math.round(lng * pcH / (float) pcW) & ~1;
        int density = Math.round(physDensity * shortSide / (float) physShort);
        SysSettings.exec("wm", "size", shortSide + "x" + lng);
        SysSettings.exec("wm", "density", String.valueOf(density));
        wide = true;
        appliedLong = lng;
        lastHelper = System.currentTimeMillis();
        L.i("screen " + lng + "x" + shortSide + " (laptop " + pcW + ":" + pcH + "), density " + density);
    }

    private void run() {
        try {
            while (!stop) {
                poll();
                wide = readWide();
                if (wide && helperOk && appliedLong != 0 && appliedLong != longSide) {
                    L.i("resolution changed on the laptop");
                    setWide(true);
                }
                if (wide && System.currentTimeMillis() - lastHelper > LOST) {
                    L.i("laptop not reachable for " + LOST / 1000 + " s");
                    setWide(false);
                }
                Thread.sleep(CHECK);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            L.e("loop crashed", t);
        }
    }

    // ------------------------------------------------------------------ ISuiteService

    /** "wide on|off", "stream on|off", "exit" (stream off + normal screen). */
    @Override public synchronized String command(String cmd) {
        if (cmd == null) return "";
        if (cmd.matches("key \\d{1,3}")) {   // Back (4) / Home (3) from the bubble
            SysSettings.exec("input", "keyevent", cmd.substring(4));
            return "ok";
        }
        switch (cmd.trim()) {
            case "wide on":
                poll();
                if (!helperOk) { L.i("16:10 refused: laptop helper not found"); return "no-helper"; }
                setWide(true);
                return "ok";
            case "wide off":
            case "exit":
                if (streaming) ask("stop");
                streaming = false;
                setWide(false);
                return "ok";
            case "stream on": {
                poll();
                if (!helperOk) return "no-helper";
                if (!wide) setWide(true);
                String r = ask("start");
                L.i("stream start -> " + r);
                poll();
                return r == null ? "no-helper" : r;
            }
            case "stream off": {
                String r = ask("stop");
                L.i("stream stop -> " + r);
                poll();
                return r == null ? "no-helper" : r;
            }
        }
        return "";
    }

    @Override public void destroy() {
        stop = true;
        loop.interrupt();
        try { loop.join(1500); } catch (InterruptedException ignored) {}
        if (streaming) ask("stop");
        if (readWide()) setWide(false);
        L.i("stopped");
        System.exit(0);
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", loop.isAlive());
            o.put("wide", wide);
            o.put("helper", helperOk);
            o.put("streaming", streaming);
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** {"token": …}: the laptop helper's token (it turns away requests without it). */
    @Override public void configure(String json) {
        try { token = new JSONObject(json).optString("token", ""); } catch (Exception e) { L.e("bad config", e); }
    }
}
