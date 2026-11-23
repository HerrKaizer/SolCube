package dev.suite.shell;

import dev.suite.ISolCubeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-app Profiles engine ("dev.suite:profiles"). Watches the focused task in-process via
 * ActivityTaskManager, applies actions when a matched app enters the foreground, and restores
 * modified settings when leaving (or after 3 min locked for NFC).
 */
public class ProfilesUserService extends ISolCubeService.Stub {
    public ProfilesUserService() {
        L.tag = "SolCube.prof";
        AppKeeper.watchInstalled(this::destroy);
    }

    private static final long POLL_ACTIVE_MS = 250;
    private static final long POLL_LOCKED_MS = 500;
    private static final long POLL_IDLE_MS = 1500;
    private static final long LEAVE_DEBOUNCE_MS = 300;
    private static final long LOCK_NFC_MS = 3 * 60_000L;
    /** Actions that only accompany others; they never count as "something to do". */
    private static final java.util.Set<String> COSMETIC =
            new java.util.HashSet<>(java.util.Arrays.asList("vibrate", "notice", "wait"));

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
    private static final long NFC_GUARD_MS = 30_000;
    private String nfcAtScreenOff;
    private long nextNfcCheck;

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
                    nfcGuard(on, now);
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

    /**
     * OriginOS (CN) re-enables NFC by itself at ~2 AM after a non-system app turned it off. If NFC
     * was off when the screen went off and comes on while the screen is still off (nobody can
     * have done that by hand), turn it back off.
     */
    private void nfcGuard(boolean screenOn, long now) {
        if (screenOn) { nfcAtScreenOff = null; return; }
        if (nfcAtScreenOff == null) {
            nfcAtScreenOff = ShellActions.nfcRawState();
            nextNfcCheck = now + NFC_GUARD_MS;
            return;
        }
        if (now < nextNfcCheck) return;
        nextNfcCheck = now + NFC_GUARD_MS;
        if ("off".equals(nfcAtScreenOff) && activeId.isEmpty() && "on".equals(ShellActions.nfcRawState())) {
            L.i("NFC came on by itself with the screen off: turning it back off");
            ShellActions.setNfc(false);
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
        JSONArray actions = p.optJSONArray("actions");
        boolean onlyIfNeeded = p.optBoolean("onlyIfNeeded", false);
        boolean[] skip = new boolean[actions == null ? 0 : actions.length()];
        if (onlyIfNeeded && actions != null) {
            boolean anyReal = false, anyNeeded = false;
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) { skip[i] = true; continue; }
                String t = a.optString("type");
                if (COSMETIC.contains(t)) continue;
                anyReal = true;
                skip[i] = ShellActions.alreadyDone(a);
                if (!skip[i]) anyNeeded = true;
            }
            if (anyReal && !anyNeeded) {
                // Everything is already in place: stay silent, and leaving has nothing to undo.
                L.i("▶ " + activeName + " (" + hit.pkg + ") — already done, skipped");
                return;
            }
        }
        L.i("▶ " + activeName + " (" + hit.pkg + ")");
        if (!activeName.isEmpty()) SysSettings.notice(activeName, true);

        if (actions == null) return;
        for (int i = 0; i < actions.length(); i++) {
            if (stop) break;
            if (skip[i]) continue;
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
            if (("boost".equals(type) || "saver".equals(type)) && isGame(hit.pkg)) {
                powerModeAfterGameCube(a, type, activeId);
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

    /** vivo Game Cube treats the app as a game (it sets current_game_package as the game opens). */
    private static boolean isGame(String pkg) {
        for (int i = 0; i < 8; i++) {   // Game Cube marks it a moment after the app comes to the front
            if (pkg.equals(SysSettings.get("secure", "current_game_package"))) return true;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    /**
     * In a game, vivo Game Cube applies the mode it remembers for that game ~3.5 s after it opens
     * and ignores (or overwrites) changes made before that, so its panel kept showing the old mode
     * while the system said Boost. Switch once Game Cube is done: the panel then follows, as it
     * does for a change from Settings, and Game Cube remembers it for the game (and switches back
     * by itself on exit). Checks again a few seconds later in case Game Cube was slow.
     */
    private void powerModeAfterGameCube(JSONObject a, String type, String profileId) {
        String target = ShellActions.targetValue(type, a);
        if (target == null) return;
        new Thread(() -> {
            try {
                Thread.sleep(4500);
                for (int i = 0; i < 2 && !stop; i++) {
                    synchronized (ProfilesUserService.this) {
                        if (!profileId.equals(activeId)) return;   // left the game meanwhile
                        String cur = ShellActions.read(type);
                        if (cur != null && !cur.equals(target)) {
                            if (activeRestore && !saved.containsKey(type)) saved.put(type, cur);
                            ShellActions.write(type, target);
                            L.i(type + ": " + cur + " → " + target + " (after Game Cube)");
                        }
                    }
                    Thread.sleep(3000);
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable t) {
                L.e("power mode in game", t);
            }
        }, "prof-power").start();
    }

    private void exitActive(String why) {
        if (activeId.isEmpty()) return;
        L.i("■ " + activeName + " (" + why + ")");
        // Only say "<name> off" when leaving actually puts something back.
        if (activeRestore && !saved.isEmpty()) {
            if (!activeName.isEmpty()) SysSettings.notice(activeName + " off", true);
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
