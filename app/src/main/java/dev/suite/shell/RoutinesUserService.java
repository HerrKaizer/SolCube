package dev.suite.shell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.lang.reflect.Method;

import dev.suite.ISuiteService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routines engine, run by Shizuku as a UserService ("dev.suite:routines"). The app sends the
 * routines as JSON (configure); this process watches the triggers and runs the actions as the
 * shell user.
 *
 * Routine: {id, name, on, trigger:{type, ssids[], minutes, at}, days:[1..7 Mon=1], from, to
 *           ("HH:MM", empty = any time), after: routine id (only if that one ran more recently,
 *           within 24 h), once: at most once a day, unless: routine id (skip for the rest of the
 *           day once that one ran today), actions:[{type, value, except[]}]}
 * Triggers: wifi_connect, wifi_gone, wifi_off (checked every N min), dnd_on, dnd_off, unlock,
 *           screen_on, time.
 * Actions:  data, wifi, airplane, saver, dnd (on/off/restore), ringer (normal/vibrate/silent/
 *           restore), media (0..100 % or restore), close_apps, lock, vibrate.
 *
 * "restore" puts back what the last change of that setting replaced. Run times and the values
 * to restore are kept in a global setting (no files), so they survive reboots.
 */
public class RoutinesUserService extends ISuiteService.Stub {
    private static final String STATE_KEY = "suite_routines_state";
    private static final long TICK = 30_000;
    private static final long DAY = 24 * 3600_000L;

    private Context ctx;
    private HandlerThread thread;
    private Handler h;
    private JSONArray routines = new JSONArray();
    private JSONObject state = new JSONObject();      // {lastRun:{id:ms}, saved:{type:{v,t}}}
    private final Map<String, Boolean> prevCond = new HashMap<>();
    private final Map<String, Long> goneSince = new HashMap<>();
    private final Map<String, Long> lastCheck = new HashMap<>();
    private long lastScanRequest;
    private volatile String error = "";
    private volatile boolean dndEvents, unlockEvents;

    public RoutinesUserService() { this(null); }

    public RoutinesUserService(Context given) {
        L.tag = "Suite.rt";
        thread = new HandlerThread("routines");
        thread.start();
        h = new Handler(thread.getLooper());
        loadState();
        try {
            ctx = MixUserService.shellContext(given);
            listen();
        } catch (Throwable t) {
            L.e("DND observer unavailable, checking it every 1.5 s instead", t);
        }
        h.post(tick);
        L.i("routines engine started");
    }

    // ================================================================== events

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            evaluate(null);
            h.postDelayed(this, TICK);
        }
    };

    /**
     * Broadcast receivers can't be registered from a shell process (Android finds no app record
     * for it: "Failed to find PID"), so: Do Not Disturb via a settings observer, and unlock /
     * screen-on by checking the lock state every 1.5 s, but only while a routine is waiting for it
     * (its time window, days and "only after" all pass). Those checks don't wake the phone; they
     * run when it's awake anyway. Wi-Fi is checked on the 30 s tick, again only inside windows.
     */
    private void listen() {
        ContentObserver o = new ContentObserver(h) {
            @Override public void onChange(boolean self) { dndEvents = true; evaluate("dnd"); }
        };
        ctx.getContentResolver().registerContentObserver(Uri.parse("content://settings/global/zen_mode"), false, o);
        observing = true;
        L.i("watching Do Not Disturb");
    }

    private volatile boolean observing;
    private boolean armed;                 // a routine is waiting for unlock / screen on (or DND without observer)
    private Boolean wasLocked, wasInteractive;
    private String lastZen;
    private Object wm, pm;
    private Method isLocked, isInteractive;

    private final Runnable fast = new Runnable() {
        @Override public void run() {
            if (!armed) { wasLocked = null; wasInteractive = null; return; }
            try {
                if (wm == null) {
                    Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
                    wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class)
                            .invoke(null, get.invoke(null, "window"));
                    pm = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class)
                            .invoke(null, get.invoke(null, "power"));
                    isLocked = wm.getClass().getMethod("isKeyguardLocked");
                    isInteractive = pm.getClass().getMethod("isInteractive");
                }
                boolean locked = (boolean) isLocked.invoke(wm);
                boolean on = (boolean) isInteractive.invoke(pm);
                if (wasInteractive != null && !wasInteractive && on) evaluate("screen_on");
                if (wasLocked != null && wasLocked && !locked) { unlockEvents = true; evaluate("unlock"); }
                wasLocked = locked;
                wasInteractive = on;
                if (!observing) {
                    String z = SysSettings.get("global", "zen_mode");
                    if (lastZen != null && !lastZen.equals(z)) evaluate("dnd");
                    lastZen = z;
                }
            } catch (Throwable t) {
                L.e("lock-state check", t);
                armed = false;
                return;
            }
            h.postDelayed(this, 1500);
        }
    };

    private void setArmed(boolean a) {
        if (a == armed) return;
        armed = a;
        if (a) { h.removeCallbacks(fast); h.post(fast); }
    }

    // ================================================================== evaluation (handler thread)

    /** Per-pass cache so Wi-Fi is queried at most once per evaluation. */
    private final class Snapshot {
        String connected;            // SSID or ""
        Set<String> nearby;          // SSIDs seen in the last 3 minutes
        String connected() {
            if (connected == null) {
                Matcher m = Pattern.compile("connected to \"(.*)\"").matcher(SysSettings.exec("cmd", "wifi", "status"));
                connected = m.find() ? m.group(1) : "";
            }
            return connected;
        }
        Set<String> nearby() {
            if (nearby == null) {
                nearby = new HashSet<>();
                Pattern p = Pattern.compile("^\\s*[0-9a-fA-F:]{17}\\s+\\d+\\s+\\S+\\s+([\\d.]+)\\s+(.*?)\\s+\\[");
                for (String line : SysSettings.exec("cmd", "wifi", "list-scan-results").split("\n")) {
                    Matcher m = p.matcher(line);
                    if (m.find() && Double.parseDouble(m.group(1)) < 180) nearby.add(m.group(2));
                }
            }
            return nearby;
        }
    }

    private synchronized void evaluate(String event) {
        if (Thread.currentThread() != thread) { h.post(() -> evaluate(event)); return; }
        try {
            long now = System.currentTimeMillis();
            Snapshot snap = new Snapshot();
            boolean arm = false;
            for (int i = 0; i < routines.length(); i++) {
                JSONObject r = routines.getJSONObject(i);
                if (!r.optBoolean("on", true)) continue;
                String id = r.getString("id");
                JSONObject t = r.optJSONObject("trigger");
                if (t == null) continue;
                String type = t.optString("type");
                boolean gate = gate(r, now);
                if (gate && (type.equals("unlock") || type.equals("screen_on"))) arm = true;
                if (!observing && type.startsWith("dnd_")) arm = true;
                wifiGuard(r, id, now);

                switch (type) {
                    case "unlock":
                    case "screen_on":
                        if (type.equals(event) && gate) run(r, type);
                        break;
                    case "time": {
                        int at = minutes(t.optString("at"));
                        if (at < 0 || !gate) break;
                        LocalDateTime n = LocalDateTime.now();
                        long todayAt = n.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + at * 60_000L;
                        if (now >= todayAt && now - todayAt < 15 * 60_000L && lastRun(id) < todayAt) run(r, "time");
                        break;
                    }
                    case "wifi_off": {   // checked every N minutes inside the conditions
                        if (!gate) break;
                        long every = Math.max(1, t.optInt("minutes", 3)) * 60_000L;
                        Long last = lastCheck.get(id);
                        if (last != null && now - last < every - 5_000) break;
                        lastCheck.put(id, now);
                        if ("off".equals(read("wifi"))) run(r, "Wi-Fi was off");
                        break;
                    }
                    default: {   // state triggers: fire when (state && conditions) becomes true
                        boolean cond = gate && state(r, t, type, snap, now);
                        Boolean prev = prevCond.put(id, cond);
                        if (prev != null && cond && !prev) run(r, type);
                    }
                }
            }
            setArmed(arm);
        } catch (Throwable t) {
            L.e("evaluate", t);
        }
    }

    private boolean state(JSONObject r, JSONObject t, String type, Snapshot snap, long now) {
        String id = r.optString("id");
        switch (type) {
            case "dnd_on": return !"0".equals(SysSettings.get("global", "zen_mode"));
            case "dnd_off": return "0".equals(SysSettings.get("global", "zen_mode"));
            case "wifi_connect": return ssids(t).contains(snap.connected());
            case "wifi_gone": {
                Set<String> want = ssids(t);
                if (now - lastScanRequest > 120_000) {   // screen-off scans are rare: ask for one
                    lastScanRequest = now;
                    SysSettings.exec("cmd", "wifi", "start-scan");
                }
                boolean seen = want.contains(snap.connected());
                if (!seen) for (String s : snap.nearby()) if (want.contains(s)) { seen = true; break; }
                if (seen) { goneSince.remove(id); return false; }
                Long since = goneSince.get(id);
                if (since == null) { goneSince.put(id, now); L.i(r.optString("name") + ": office networks not in range, waiting"); return false; }
                return now - since >= Math.max(1, t.optInt("minutes", 10)) * 60_000L;
            }
        }
        return false;
    }

    /**
     * A routine's "keep Wi-Fi on before it runs" part: {from, to, minutes}. On the routine's days,
     * inside that window and until the routine has run today, every N minutes: if Wi-Fi is off,
     * turn it on and buzz.
     */
    private void wifiGuard(JSONObject r, String id, long now) {
        JSONObject g = r.optJSONObject("wifiGuard");
        if (g == null || !g.optBoolean("on", true)) return;
        LocalDateTime n = LocalDateTime.now();
        JSONArray days = r.optJSONArray("days");
        if (days != null && days.length() > 0) {
            boolean ok = false;
            for (int i = 0; i < days.length(); i++) if (days.optInt(i) == n.getDayOfWeek().getValue()) ok = true;
            if (!ok) return;
        }
        int from = minutes(g.optString("from")), to = minutes(g.optString("to"));
        int m = n.getHour() * 60 + n.getMinute();
        if (from < 0 || to < 0 || !(from < to ? (m >= from && m < to) : (m >= from || m < to))) return;
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (lastRun(id) >= today) return;   // the routine ran: stop checking for today
        long every = Math.max(1, g.optInt("minutes", 3)) * 60_000L;
        Long last = lastCheck.get(id);
        if (last != null && now - last < every - 5_000) return;
        lastCheck.put(id, now);
        if ("off".equals(read("wifi"))) {
            write("wifi", "on");
            L.i(r.optString("name") + ": Wi-Fi was off → turned on");
            SysSettings.notice("Wi-Fi on", true);
            Haptics.done();
        }
    }

    private static Set<String> ssids(JSONObject t) {
        Set<String> s = new LinkedHashSet<>();
        JSONArray a = t.optJSONArray("ssids");
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    /** Days, time window, "only after", once a day. */
    private boolean gate(JSONObject r, long now) {
        LocalDateTime n = LocalDateTime.now();
        JSONArray days = r.optJSONArray("days");
        if (days != null && days.length() > 0) {
            boolean ok = false;
            for (int i = 0; i < days.length(); i++) if (days.optInt(i) == n.getDayOfWeek().getValue()) ok = true;
            if (!ok) return false;
        }
        int from = minutes(r.optString("from")), to = minutes(r.optString("to"));
        if (from >= 0 && to >= 0 && from != to) {
            int m = n.getHour() * 60 + n.getMinute();
            boolean in = from < to ? (m >= from && m < to) : (m >= from || m < to);
            if (!in) return false;
        }
        String after = r.optString("after");
        String id = r.optString("id");
        if (!after.isEmpty()) {
            long a = lastRun(after);
            if (a <= lastRun(id) || now - a > DAY) return false;
        }
        long today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (r.optBoolean("once") && lastRun(id) >= today) return false;
        String unless = r.optString("unless");   // stop for the day once that routine has run today
        if (!unless.isEmpty() && lastRun(unless) >= today) return false;
        return true;
    }

    private static int minutes(String hhmm) {
        try {
            String[] p = hhmm.trim().split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    // ================================================================== running

    private void run(JSONObject r, String why) {
        String id = r.optString("id");
        L.i("▶ " + r.optString("name") + " (" + why + ")");
        try {
            state.optJSONObject("lastRun").put(id, System.currentTimeMillis());
            saveState();
        } catch (Exception ignored) {}
        JSONArray actions = r.optJSONArray("actions");
        int failed = 0;
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) continue;
                try { act(a); } catch (Throwable t) { failed++; L.e("action " + a.optString("type"), t); }
            }
        }
        saveState();
        prevCond.clear();   // re-baseline: our own changes (e.g. DND off) shouldn't chain-fire on stale edges
        L.i("✓ " + r.optString("name") + " done");
        // no pill for routines that lock the screen (Sleep): nobody's looking
        if (!hasAction(actions, "lock"))
            SysSettings.notice(failed == 0 ? r.optString("name") : r.optString("name") + ": " + failed + " failed", failed == 0);
    }

    private static boolean hasAction(JSONArray actions, String type) {
        if (actions == null) return false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a != null && type.equals(a.optString("type"))) return true;
        }
        return false;
    }

    private void act(JSONObject a) throws Exception {
        String type = a.optString("type");
        String v = a.optString("value");
        switch (type) {
            case "vibrate": Haptics.done(); return;
            case "lock": SysSettings.exec("input", "keyevent", "223"); L.i("screen locked"); return;
            case "close_apps": closeApps(a.optJSONArray("except")); return;
            case "revive": revive(a.optJSONArray("apps")); return;
            case "wait": Thread.sleep(Math.min(60, Math.max(0, a.optInt("value", 1))) * 1000L); return;
        }
        String current = read(type);
        if (current == null) { L.i("unknown action " + type); return; }
        JSONObject saved = state.optJSONObject("saved");
        if (v.equals("restore")) {
            JSONObject s = saved.optJSONObject(type);
            if (s == null) { L.i(type + ": nothing to restore"); return; }
            saved.remove(type);
            write(type, s.optString("v"));
            L.i(type + ": restored " + s.optString("v"));
            return;
        }
        String target = type.equals("media") ? String.valueOf(percentToIndex(a.optInt("value", 0))) : v;
        JSONObject s = saved.optJSONObject(type);
        if (s != null && s.optString("v").equals(target)) {
            saved.remove(type);   // going back to the remembered value: nothing left to restore
        } else if (s == null || System.currentTimeMillis() - s.optLong("t") > 20 * 3600_000L) {
            saved.put(type, new JSONObject().put("v", current).put("t", System.currentTimeMillis()));
        }
        write(type, target);
        L.i(type + ": " + current + " → " + target);
    }

    /** Current value in the same vocabulary write() takes. */
    private String read(String type) {
        switch (type) {
            case "data": {
                // per-SIM keys can be stale for the SIM that isn't used for data: read the data SIM's
                String sub = SysSettings.get("global", "multi_sim_data_call");
                String v = sub != null && sub.matches("\\d+") ? SysSettings.get("global", "mobile_data" + sub) : null;
                if (v == null || v.equals("null")) v = SysSettings.get("global", "mobile_data");
                return "1".equals(v) ? "on" : "off";
            }
            case "wifi": {   // wifi_on: 1 on, 2 on in airplane mode, 0 off, 3 off by airplane mode
                String w = SysSettings.get("global", "wifi_on");
                return "1".equals(w) || "2".equals(w) ? "on" : "off";
            }
            case "airplane": return "1".equals(SysSettings.get("global", "airplane_mode_on")) ? "on" : "off";
            case "saver": return "1".equals(SysSettings.get("global", "low_power")) ? "on" : "off";
            case "dnd": return "0".equals(SysSettings.get("global", "zen_mode")) ? "off" : "on";
            case "ringer": {
                String m = SysSettings.get("global", "mode_ringer");
                return "0".equals(m) ? "silent" : "1".equals(m) ? "vibrate" : "normal";
            }
            case "media": return String.valueOf(mediaIndex());
        }
        return null;
    }

    private void write(String type, String v) {
        boolean on = v.equals("on");
        switch (type) {
            case "data": SysSettings.exec("svc", "data", on ? "enable" : "disable"); break;
            case "wifi": SysSettings.exec("cmd", "wifi", "set-wifi-enabled", on ? "enabled" : "disabled"); break;
            case "airplane": SysSettings.exec("cmd", "connectivity", "airplane-mode", on ? "enable" : "disable"); break;
            case "saver": SysSettings.exec("cmd", "power", "set-mode", on ? "1" : "0"); break;
            case "dnd": SysSettings.exec("cmd", "notification", "set_dnd", on ? "on" : "off"); break;
            case "ringer": SysSettings.exec("cmd", "audio", "set-ringer-mode", v.toUpperCase()); break;
            case "media": SysSettings.exec("cmd", "audio", "set-volume", "3", v); break;
        }
    }

    private static int lastInt(String s) {
        Matcher m = Pattern.compile("(-?\\d+)\\s*$").matcher(s.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int mediaIndex() { return lastInt(SysSettings.exec("cmd", "audio", "get-stream-volume", "3")); }

    private int percentToIndex(int pct) {
        int max = lastInt(SysSettings.exec("cmd", "audio", "get-max-volume", "3"));
        if (max <= 0) max = 15;
        return Math.round(Math.max(0, Math.min(100, pct)) * max / 100f);
    }

    /**
     * Closes everything: clears recents (like swiping every card away), then force-stops every
     * user-installed app still running, background ones included (KWGT, VPNs, messengers…).
     * Keeps the launcher, the keyboard, Suite and Shizuku (Suite runs on it). The background ones
     * are logged; which apps come back is the "revive" action's own list.
     */
    private void closeApps(JSONArray except) {
        Set<String> keep = new HashSet<>();
        keep.add("dev.suite");
        keep.add("moe.shizuku.privileged.api");
        String ime = SysSettings.get("secure", "default_input_method");
        if (ime != null && ime.contains("/")) keep.add(ime.substring(0, ime.indexOf('/')));
        for (String l : SysSettings.exec("cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").split("\n"))
            if (l.contains("/")) keep.add(l.trim().substring(0, l.trim().indexOf('/')));
        if (except != null) for (int i = 0; i < except.length(); i++) keep.add(except.optString(i));

        Method remove;
        Object atm;
        try {
            atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            remove = atm.getClass().getMethod("removeTask", int.class);
        } catch (Throwable t) {
            L.e("close apps: task service unavailable", t);
            return;
        }
        Pattern p = Pattern.compile("Recent #\\d+: Task\\{\\S+ #(\\d+) type=standard (?:A=\\d+:|I=)([\\w.]+)");
        int n = 0;
        Set<String> inRecents = new HashSet<>();
        for (String l : SysSettings.exec("dumpsys", "activity", "recents").split("\n")) {
            Matcher m = p.matcher(l);
            if (!m.find() || keep.contains(m.group(2))) continue;
            inRecents.add(m.group(2));
            try {
                if ((boolean) remove.invoke(atm, Integer.parseInt(m.group(1)))) n++;
            } catch (Throwable t) {
                L.e("close " + m.group(2), t);
            }
        }

        // then every user app that still has a process
        Set<String> third = new HashSet<>();
        for (String l : SysSettings.exec("pm", "list", "packages", "-3").split("\n"))
            if (l.startsWith("package:")) third.add(l.substring(8).trim());
        Set<String> running = new LinkedHashSet<>();
        for (String l : SysSettings.exec("ps", "-A", "-o", "NAME=").split("\n")) {
            String pkg = l.trim();
            int c = pkg.indexOf(':');
            if (c > 0) pkg = pkg.substring(0, c);
            if (third.contains(pkg) && !keep.contains(pkg)) running.add(pkg);
        }
        List<String> bg = new ArrayList<>();
        for (String pkg : running) {
            SysSettings.exec("am", "force-stop", pkg);
            if (!inRecents.contains(pkg)) bg.add(pkg);
        }
        L.i("closed " + n + " from recents, force-stopped " + running.size() + " (background: " + String.join(", ", bg) + ")");
    }

    /**
     * Revives the chosen apps after Close all apps: takes them out of Android's "stopped" state
     * (so push messages and alarms reach them again; nothing is opened) and refreshes their
     * home-screen widgets, which starts e.g. KWGT again.
     */
    private void revive(JSONArray stopped) {
        if (stopped == null || stopped.length() == 0) { L.i("revive: no apps chosen"); return; }
        Object ipm = null;
        Method unstop = null;
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
            ipm = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            unstop = ipm.getClass().getMethod("setPackageStoppedState", String.class, boolean.class, int.class);
        } catch (Throwable t) {
            L.e("revive: package service", t);
        }
        // widget ids per provider, from `dumpsys appwidget`
        Map<String, List<String>> widgets = new HashMap<>();   // "pkg/cls" -> ids
        String id = null;
        Pattern pid = Pattern.compile("^\\s*\\[\\d+\\] id=(\\d+)");
        Pattern pcmp = Pattern.compile("provider=.*cmp:ComponentInfo\\{([^}]+)\\}");   // ICU needs the \\}
        for (String l : SysSettings.exec("dumpsys", "appwidget").split("\n")) {
            Matcher a = pid.matcher(l);
            if (a.find()) { id = a.group(1); continue; }
            Matcher c = pcmp.matcher(l);
            if (c.find() && id != null) { widgets.computeIfAbsent(c.group(1), k -> new ArrayList<>()).add(id); id = null; }
        }
        int un = 0, w = 0;
        for (int i = 0; i < stopped.length(); i++) {
            String pkg = stopped.optString(i);
            try { if (unstop != null) { unstop.invoke(ipm, pkg, false, 0); un++; } }
            catch (Throwable t) { L.e("revive " + pkg, t); }
            for (Map.Entry<String, List<String>> e : widgets.entrySet()) {
                if (!e.getKey().startsWith(pkg + "/")) continue;
                SysSettings.exec("am", "broadcast", "-f", "0x20", "-n", e.getKey(),
                        "-a", "android.appwidget.action.APPWIDGET_UPDATE", "--eia", "appWidgetIds", String.join(",", e.getValue()));
                w++;
            }
        }
        L.i("revived " + un + " apps, refreshed " + w + " widget providers");
    }

    // ================================================================== state

    private void loadState() {
        try {
            String s = SysSettings.get("global", STATE_KEY);
            if (s != null && s.startsWith("{")) state = new JSONObject(s);
        } catch (Exception ignored) {}
        try {
            if (state.optJSONObject("lastRun") == null) state.put("lastRun", new JSONObject());
            if (state.optJSONObject("saved") == null) state.put("saved", new JSONObject());
        } catch (Exception ignored) {}
    }

    private void saveState() { SysSettings.put("global", STATE_KEY, state.toString()); }

    private long lastRun(String id) { return state.optJSONObject("lastRun").optLong(id, 0); }

    // ================================================================== binder

    @Override public void destroy() {
        L.i("routines engine stopped");
        System.exit(0);
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", true);
            o.put("error", error);
            o.put("dndEvents", dndEvents);
            o.put("observing", observing);
            o.put("armed", armed);
            o.put("unlockEvents", unlockEvents);
            o.put("lastRun", state.optJSONObject("lastRun"));
            o.put("log", new JSONArray(L.recent(30)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override public void configure(String json) {
        h.post(() -> {
            try {
                routines = new JSONObject(json).optJSONArray("routines");
                if (routines == null) routines = new JSONArray();
                prevCond.clear();
                goneSince.clear();
                L.i("loaded " + routines.length() + " routines");
                evaluate(null);
            } catch (Exception e) {
                L.e("configure", e);
            }
        });
    }

    @Override public String command(String cmd) {
        if (cmd.startsWith("run ")) {
            String id = cmd.substring(4).trim();
            h.post(() -> {
                for (int i = 0; i < routines.length(); i++) {
                    JSONObject r = routines.optJSONObject(i);
                    if (r != null && id.equals(r.optString("id"))) { run(r, "run now"); return; }
                }
                L.i("no routine " + id);
            });
            return "ok";
        }
        if (cmd.equals("close_apps")) { h.post(() -> closeApps(null)); return "ok"; }   // test hooks
        if (cmd.startsWith("revive ")) {
            JSONArray apps = new JSONArray();
            for (String s : cmd.substring(7).split(",")) if (!s.trim().isEmpty()) apps.put(s.trim());
            h.post(() -> revive(apps));
            return "ok";
        }
        if (cmd.equals("networks")) {
            Set<String> names = new LinkedHashSet<>();
            for (String l : SysSettings.exec("cmd", "wifi", "list-networks").split("\n")) {
                Matcher m = Pattern.compile("^\\s*\\d+\\s+(.*?)\\s{2,}\\S+\\^?\\s*$").matcher(l);
                if (m.find()) names.add(m.group(1).trim());
            }
            return new JSONArray(names).toString();
        }
        return "";
    }
}
