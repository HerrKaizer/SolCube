package dev.suite.shell;

import dev.suite.ISuiteService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-app Profiles engine ("dev.suite:profiles"). Watches the focused task in-process via
 * ActivityTaskManager, applies actions when a matched app enters the foreground, and restores
 * modified settings when leaving (or after 3 min locked for NFC).
 */
public class ProfilesUserService extends ISuiteService.Stub {
    public ProfilesUserService() { L.tag = "Suite.prof"; }

    private static final long POLL_ACTIVE_MS = 250;
    private static final long POLL_LOCKED_MS = 500;
    private static final long POLL_IDLE_MS = 1500;
    private static final long LEAVE_DEBOUNCE_MS = 300;
    private static final long LOCK_NFC_MS = 3 * 60_000L;

    private volatile JSONArray profiles = new JSONArray();
    private volatile boolean stop;
    private Thread loop;

    private volatile String activeId = "";
    private volatile String activeName = "";
    private volatile String activeApp = "";
    private volatile boolean activeRestore = true;
    private final Map<String, String> saved = new LinkedHashMap<>();
    private long goneSince;
    private long lockedSince;
    private volatile long suppressLeaveUntil;

    @Override public synchronized void configure(String json) {
        try {
            JSONArray a = new JSONObject(json).optJSONArray("profiles");
            profiles = a == null ? new JSONArray() : a;
            L.i("loaded " + profiles.length() + " profiles");
            if (loop == null) {
                loop = new Thread(this::run, "profiles-loop");
                loop.start();
            }
        } catch (Throwable t) {
            L.e("configure", t);
        }
    }

    @Override public void destroy() {
        stop = true;
        Thread t = loop;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
        synchronized (this) {
            exitActive("stopped");
        }
        L.i("profiles engine stopped");
        System.exit(0);
    }

    @Override public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", loop != null && loop.isAlive());
            o.put("activeId", activeId);
            o.put("activeName", activeName);
            o.put("activeApp", activeApp);
            o.put("log", new JSONArray(L.recent(20)));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override public String command(String cmd) { return ""; }

    // ------------------------------------------------------------------ watcher loop

    private void run() {
        L.i("started");
        try {
            while (!stop) {
                boolean on = ShellActions.interactive();
                boolean lock = !on || ShellActions.locked();
                long now = System.currentTimeMillis();

                synchronized (this) {
                    if (stop) break;
                    if (!activeId.isEmpty()) {
                        JSONObject cur = findById(activeId);
                        if (cur == null || !cur.optBoolean("on", true)) {
                            exitActive("disabled");
                        } else {
                            ShellActions.TaskHit hit = on ? ShellActions.frontTask() : new ShellActions.TaskHit("", "", -1);
                            if (now < suppressLeaveUntil) {
                                goneSince = 0;
                            } else if (containsApp(cur, hit.pkg)) {
                                goneSince = 0;
                                activeApp = hit.pkg;
                                if (lock && "off".equals(saved.get("nfc"))) {
                                    if (lockedSince == 0) lockedSince = now;
                                    else if (now - lockedSince >= LOCK_NFC_MS) exitActive("locked 3 min");
                                } else {
                                    lockedSince = 0;
                                }
                            } else {
                                if (lock && "off".equals(saved.get("nfc"))) {
                                    if (lockedSince == 0) lockedSince = now;
                                    else if (now - lockedSince >= LOCK_NFC_MS) exitActive("locked 3 min");
                                } else if (!lock) {
                                    if (goneSince == 0) goneSince = now;
                                    else if (now - goneSince >= LEAVE_DEBOUNCE_MS) exitActive("left " + activeApp);
                                }
                            }
                        }
                    }

                    if (activeId.isEmpty() && !lock) {
                        ShellActions.TaskHit hit = ShellActions.frontTask();
                        if (!hit.pkg.isEmpty()) {
                            JSONObject match = findForApp(hit.pkg);
                            if (match != null) enterProfile(match, hit);
                        }
                    }
                }

                long delay = !activeId.isEmpty()
                        ? (lock ? POLL_LOCKED_MS : POLL_ACTIVE_MS)
                        : (lock ? POLL_IDLE_MS : POLL_ACTIVE_MS);
                Thread.sleep(delay);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            L.e("profiles loop crashed", t);
        }
    }

    private void enterProfile(JSONObject p, ShellActions.TaskHit hit) {
        activeId = p.optString("id");
        activeName = p.optString("name");
        activeApp = hit.pkg;
        activeRestore = p.optBoolean("restoreOnExit", true);
        saved.clear();
        goneSince = 0;
        lockedSince = 0;
        L.i("▶ " + activeName + " (" + hit.pkg + ")");
        if (!activeName.isEmpty()) SysSettings.notice(activeName, true);

        JSONArray actions = p.optJSONArray("actions");
        if (actions == null) return;
        for (int i = 0; i < actions.length(); i++) {
            if (stop) break;
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            String type = a.optString("type");
            if ("launch_bg".equals(type)) {
                JSONArray apps = a.optJSONArray("apps");
                if (apps != null && apps.length() > 0) {
                    suppressLeaveUntil = System.currentTimeMillis() + 1800;
                    new Thread(() -> {
                        try { ShellActions.launchBackground(apps, hit); }
                        finally { suppressLeaveUntil = System.currentTimeMillis() + 300; }
                    }, "prof-bg").start();
                }
                continue;
            }
            try {
                if (ShellActions.runInstant(a, hit, hit.pkg)) continue;
                String cur = ShellActions.read(type);
                String target = ShellActions.targetValue(type, a);
                if (cur == null || target == null) continue;
                if (!cur.equals(target)) {
                    if (activeRestore && !saved.containsKey(type)) saved.put(type, cur);
                    ShellActions.write(type, target);
                    if ("nfc".equals(type) && "on".equals(target)) SysSettings.notice("NFC on", true);
                    L.i(type + ": " + cur + " → " + target);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                L.e("enter " + type, t);
            }
        }
    }

    private void exitActive(String why) {
        if (activeId.isEmpty()) return;
        L.i("■ " + activeName + " (" + why + ")");
        if (!activeName.isEmpty()) SysSettings.notice(activeName + " off", true);
        if (activeRestore && !saved.isEmpty()) {
            java.util.List<Map.Entry<String, String>> entries = new java.util.ArrayList<>(saved.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<String, String> e = entries.get(i);
                try {
                    ShellActions.write(e.getKey(), e.getValue());
                    if ("nfc".equals(e.getKey()) && "off".equals(e.getValue())) SysSettings.notice("NFC off", true);
                    L.i("restored " + e.getKey() + " → " + e.getValue());
                } catch (Throwable t) {
                    L.e("restore " + e.getKey(), t);
                }
            }
        }
        saved.clear();
        activeId = "";
        activeName = "";
        activeApp = "";
        goneSince = 0;
        lockedSince = 0;
    }

    // ------------------------------------------------------------------ profile lookup

    private JSONObject findById(String id) {
        JSONArray a = profiles;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private JSONObject findForApp(String pkg) {
        JSONArray a = profiles;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optBoolean("on", true) && containsApp(o, pkg)) return o;
        }
        return null;
    }

    private static boolean containsApp(JSONObject p, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        JSONArray a = p.optJSONArray("apps");
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) {
            if (pkg.equals(a.optString(i))) return true;
        }
        return false;
    }
}
