package dev.suite.shell;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.Display;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

import dev.suite.ISolCubeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routines engine, run by Shizuku as a UserService ("dev.suite:routines"). The app sends the
 * routines as JSON (configure); this process watches the triggers and runs the actions as the
 * shell user.
 */
public class RoutinesUserService extends ISolCubeService.Stub {
    private static final String STATE_KEY = "suite_routines_state";
    private static final long TICK = 30_000;
    private static final long DAY = 24 * 3600_000L;

    private Context ctx;
    private HandlerThread thread;
    private Handler h;
    private JSONArray routines = new JSONArray();
    private JSONObject state = new JSONObject();      // {lastRun:{id:ms}, saved:{type:{v,t}}}
    private final Map<String, Boolean> prevCond = new HashMap<>();
    private final Map<String, Boolean> prevRaw = new HashMap<>();
    private final Map<String, String> prevWifiSsid = new HashMap<>();
    private final Map<String, Set<String>> prevBtDevs = new HashMap<>();
    private final Map<String, Long> goneSince = new HashMap<>();
    private final Map<String, Long> lastCheck = new HashMap<>();
    private final Set<String> inRange = new HashSet<>();   // wifi_gone routines whose network was seen since they last ran
    private long lastScanRequest;
    private volatile String error = "";
    private volatile boolean dndEvents, unlockEvents;

    public RoutinesUserService() { this(null); }

    public RoutinesUserService(Context given) {
        L.tag = "SolCube.rt";
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

    private boolean observe(String table, String key, String event) {
        try {
            ContentObserver o = new ContentObserver(h) {
                @Override public void onChange(boolean self) {
                    if ("dnd".equals(event)) dndEvents = true;
                    evaluate(event);
                }
            };
            ctx.getContentResolver().registerContentObserver(Uri.parse("content://settings/" + table + "/" + key), false, o);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void listen() {
        if (!observe("global", "zen_mode", "dnd")) {
            observing = false;
            L.i("settings observer unavailable, using in-process polling");
            return;
        }
        observing = true;
        observe("global", "airplane_mode_on", "airplane");
        observe("system", "power_save_type", "saver");
        observe("global", "low_power", "saver");
        observe("global", "wifi_on", "wifi");
        observe("global", "bluetooth_on", "bt");
        L.i("watching system settings");
    }

    private volatile boolean observing;
    private boolean armed, needLock, needApp, needCharge, needAudioBt, needDnd, needAirplane, needPowerMode;
    private int fastCycle;
    private Boolean wasLocked, wasInteractive, wasCharging, wasHeadphones;
    private String lastZen, lastAir, lastPowerMode, lastFrontPkg = "";
    private Set<String> lastBtSet;
    private Object wm, pm;
    private Method isLocked, isInteractive;

    private final Runnable fast = new Runnable() {
        @Override public void run() {
            if (!armed) {
                wasLocked = null; wasInteractive = null;
                wasCharging = null; wasHeadphones = null; lastBtSet = null;
                lastZen = null; lastAir = null; lastPowerMode = null;
                return;
            }
            try {
                fastCycle++;
                boolean interactive = true;
                boolean locked = false;
                if (needLock || needApp) {
                    if (wm == null) {
                        Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
                        wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class)
                                .invoke(null, get.invoke(null, "window"));
                        pm = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class)
                                .invoke(null, get.invoke(null, "power"));
                        isLocked = wm.getClass().getMethod("isKeyguardLocked");
                        isInteractive = pm.getClass().getMethod("isInteractive");
                    }
                    locked = (boolean) isLocked.invoke(wm);
                    interactive = (boolean) isInteractive.invoke(pm);
                    if (needLock) {
                        if (wasInteractive != null && !wasInteractive && interactive) evaluate("screen_on");
                        if (wasInteractive != null && wasInteractive && !interactive) evaluate("screen_off");
                        if (wasLocked != null && wasLocked && !locked) { unlockEvents = true; evaluate("unlock"); }
                        if (wasLocked != null && !wasLocked && locked) evaluate("lock");
                    }
                    wasLocked = locked;
                    wasInteractive = interactive;
                }
                if (needApp && interactive && !locked) {
                    String fp = ShellActions.frontTask().pkg;
                    if (!fp.equals(lastFrontPkg)) {
                        lastFrontPkg = fp;
                        evaluate("app");
                    }
                }
                if (needCharge) {
                    Boolean ch = quickCharging();
                    if (ch == null && fastCycle % 3 == 0) {
                        BatteryInfo bi = readBattery();
                        ch = bi != null ? bi.charging : null;
                    }
                    if (ch != null) {
                        if (wasCharging != null && !wasCharging.equals(ch)) evaluate("battery");
                        wasCharging = ch;
                    }
                }
                if (needAudioBt && fastCycle % 2 == 0) {
                    boolean hp = queryHeadphones();
                    Set<String> bt = queryConnectedBt();
                    boolean changed = (wasHeadphones != null && wasHeadphones != hp)
                            || (lastBtSet != null && !lastBtSet.equals(bt));
                    wasHeadphones = hp;
                    lastBtSet = bt;
                    if (changed) evaluate("bt_audio");
                }
                if (needDnd) {
                    String z = SysSettings.get("global", "zen_mode");
                    if (lastZen != null && !lastZen.equals(z)) evaluate("dnd");
                    lastZen = z;
                }
                if (needAirplane) {
                    String a = SysSettings.get("global", "airplane_mode_on");
                    if (lastAir != null && !lastAir.equals(a)) evaluate("airplane");
                    lastAir = a;
                }
                if (needPowerMode) {
                    String pmVal = SysSettings.get("system", "power_save_type") + ":" + SysSettings.get("global", "low_power");
                    if (lastPowerMode != null && !lastPowerMode.equals(pmVal)) evaluate("saver");
                    lastPowerMode = pmVal;
                }
            } catch (Throwable t) {
                L.e("fast check", t);
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

    private static Boolean quickCharging() {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/power_supply/battery/status"))) {
            String s = r.readLine();
            if (s == null) return null;
            s = s.trim();
            if ("Charging".equalsIgnoreCase(s) || "Full".equalsIgnoreCase(s)) return true;
            if ("Discharging".equalsIgnoreCase(s) || "Not charging".equalsIgnoreCase(s)) return false;
        } catch (Throwable ignored) {}
        return null;
    }

    // ================================================================== evaluation (handler thread)

    private static final class BatteryInfo {
        int level = -1;
        int tempTenths = 0;
        boolean ac, usb, wireless, charging;
    }

    private BatteryInfo readBattery() {
        BatteryInfo b = new BatteryInfo();
        for (String l : SysSettings.exec("dumpsys", "battery").split("\n")) {
            String t = l.trim();
            if (t.startsWith("AC powered:")) b.ac = t.endsWith("true");
            else if (t.startsWith("USB powered:")) b.usb = t.endsWith("true");
            else if (t.startsWith("Wireless powered:")) b.wireless = t.endsWith("true");
            else if (t.startsWith("level:")) { try { b.level = Integer.parseInt(t.substring(6).trim()); } catch (Exception ignored) {} }
            else if (t.startsWith("temperature:")) { try { b.tempTenths = Integer.parseInt(t.substring(12).trim()); } catch (Exception ignored) {} }
        }
        b.charging = b.ac || b.usb || b.wireless;
        return b;
    }

    private Set<String> queryConnectedBt() {
        Set<String> res = new LinkedHashSet<>();
        try {
            BluetoothManager bm = ctx != null ? ctx.getSystemService(BluetoothManager.class) : null;
            BluetoothAdapter ba = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
            if (ba != null && ba.isEnabled()) {
                Method isConn = BluetoothDevice.class.getMethod("isConnected");
                Set<BluetoothDevice> bonded = ba.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        if ((boolean) isConn.invoke(d)) {
                            String n = d.getName();
                            res.add(n != null && !n.isEmpty() ? n : d.getAddress());
                        }
                    }
                }
                return res;
            }
        } catch (Throwable ignored) {}
        if (!"on".equals(read("bluetooth"))) return res;
        boolean inBonded = false;
        for (String l : SysSettings.exec("dumpsys", "bluetooth_manager").split("\n")) {
            String t = l.trim();
            if (t.startsWith("Bonded devices:")) { inBonded = true; continue; }
            if (inBonded) {
                if (t.isEmpty() || (!t.contains(":") && !t.startsWith("["))) { inBonded = false; continue; }
                if (t.contains("Connected") || t.contains("connected=true")) {
                    Matcher m = Pattern.compile("\\[([^]]+)\\]").matcher(t);
                    if (m.find()) res.add(m.group(1).trim());
                }
            }
        }
        return res;
    }

    private boolean queryHeadphones() {
        try {
            AudioManager am = ctx != null ? ctx.getSystemService(AudioManager.class) : null;
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int type = d.getType();
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || type == AudioDeviceInfo.TYPE_USB_HEADSET
                            || type == AudioDeviceInfo.TYPE_USB_DEVICE
                            || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || type == AudioDeviceInfo.TYPE_HEARING_AID) {
                        return true;
                    }
                }
                return false;
            }
        } catch (Throwable ignored) {}
        return !queryConnectedBt().isEmpty();
    }

    /** Per-pass cache so system state is queried at most once per evaluation. */
    private final class Snapshot {
        String connected;
        Set<String> nearby;
        BatteryInfo battery;
        Set<String> connectedBt;
        Boolean headphones, mediaPlaying, inCall, darkMode, landscape, interactive, locked;
        String frontApp;

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

        BatteryInfo battery() {
            if (battery == null) battery = readBattery();
            return battery;
        }

        Set<String> connectedBt() {
            if (connectedBt == null) connectedBt = queryConnectedBt();
            return connectedBt;
        }

        boolean headphones() {
            if (headphones == null) headphones = queryHeadphones();
            return headphones;
        }

        boolean mediaPlaying() {
            if (mediaPlaying == null) {
                try {
                    AudioManager am = ctx != null ? ctx.getSystemService(AudioManager.class) : null;
                    mediaPlaying = am != null && am.isMusicActive();
                } catch (Throwable t) {
                    mediaPlaying = false;
                }
            }
            return mediaPlaying;
        }

        boolean inCall() {
            if (inCall == null) {
                try {
                    AudioManager am = ctx != null ? ctx.getSystemService(AudioManager.class) : null;
                    int m = am != null ? am.getMode() : AudioManager.MODE_NORMAL;
                    inCall = m == AudioManager.MODE_IN_CALL
                            || m == AudioManager.MODE_IN_COMMUNICATION
                            || m == AudioManager.MODE_RINGTONE;
                } catch (Throwable t) {
                    inCall = false;
                }
            }
            return inCall;
        }

        boolean darkMode() {
            if (darkMode == null) {
                String out = SysSettings.exec("dumpsys", "uimode");
                if (out.contains("mComputedNightMode=true")) darkMode = true;
                else if (out.contains("mComputedNightMode=false")) darkMode = false;
                else darkMode = "2".equals(SysSettings.get("secure", "ui_night_mode"));
            }
            return darkMode;
        }

        boolean landscape() {
            if (landscape == null) {
                try {
                    DisplayManager dm = ctx != null ? ctx.getSystemService(DisplayManager.class) : null;
                    Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
                    if (d != null) {
                        int rot = d.getRotation();
                        landscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
                    } else {
                        Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
                        Object iwm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class)
                                .invoke(null, get.invoke(null, "window"));
                        int rot = (int) iwm.getClass().getMethod("getDefaultDisplayRotation").invoke(iwm);
                        landscape = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270;
                    }
                } catch (Throwable t) {
                    landscape = false;
                }
            }
            return landscape;
        }

        String frontApp() {
            if (frontApp == null) frontApp = interactive() && !locked() ? ShellActions.frontTask().pkg : "";
            return frontApp;
        }

        boolean interactive() {
            if (interactive == null) interactive = ShellActions.interactive();
            return interactive;
        }

        boolean locked() {
            if (locked == null) locked = ShellActions.locked();
            return locked;
        }
    }

    private synchronized void evaluate(String event) {
        if (Thread.currentThread() != thread) { h.post(() -> evaluate(event)); return; }
        try {
            long now = System.currentTimeMillis();
            Snapshot snap = new Snapshot();
            boolean nLock = false, nApp = false, nCharge = false, nAudioBt = false, nDnd = false, nAir = false, nPwr = false;
            for (int i = 0; i < routines.length(); i++) {
                JSONObject r = routines.getJSONObject(i);
                if (!r.optBoolean("on", true)) continue;
                String id = r.getString("id");
                JSONObject t = r.optJSONObject("trigger");
                if (t == null) continue;
                String type = t.optString("type");
                boolean sched = scheduleGate(r, now);
                if (sched) {
                    if (type.equals("unlock") || type.equals("lock") || type.equals("screen_on") || type.equals("screen_off")) nLock = true;
                    if (type.equals("app_open") || type.equals("app_close")) nApp = true;
                    if (type.equals("charging_on") || type.equals("charging_off")) nCharge = true;
                    if (type.equals("bt_connect") || type.equals("bt_disconnect")
                            || type.equals("headphones_connect") || type.equals("headphones_disconnect")) nAudioBt = true;
                    if (!observing) {
                        if (type.startsWith("dnd_")) nDnd = true;
                        if (type.startsWith("airplane_")) nAir = true;
                        if (type.startsWith("saver_") || type.startsWith("boost_")) nPwr = true;
                    }
                }
                wifiGuard(r, id, now);

                boolean gate = sched && conditionsGate(r, snap);
                switch (type) {
                    case "unlock":
                    case "lock":
                    case "screen_on":
                    case "screen_off":
                        if (type.equals(event) && gate) run(r, type, 0);
                        break;
                    case "gesture":   // event-only: GestureListener sends "gesture:<fingers>:<id>"
                        if (event != null && event.equals("gesture:" + t.optInt("fingers", 2) + ":" + t.optString("gesture")) && gate) run(r, "gesture", 0);
                        break;
                    case "time": {
                        int at = minutes(t.optString("at"));
                        if (at < 0 || !gate) break;
                        LocalDateTime n = LocalDateTime.now();
                        long todayAt = n.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + at * 60_000L;
                        if (now >= todayAt && now - todayAt < 15 * 60_000L && lastRun(id) < todayAt) run(r, "time", 0);
                        break;
                    }
                    case "wifi_off": {   // checked every N minutes inside the conditions
                        if (!gate) break;
                        long every = Math.max(1, t.optInt("minutes", 3)) * 60_000L;
                        Long last = lastCheck.get(id);
                        if (last != null && now - last < every - 5_000) break;
                        lastCheck.put(id, now);
                        if ("off".equals(read("wifi"))) run(r, "Wi-Fi was off", 0);
                        break;
                    }
                    case "wifi_disconnect": {
                        String cur = snap.connected();
                        String prev = prevWifiSsid.put(id, cur);
                        if (prev != null && !prev.isEmpty() && !prev.equals(cur)) {
                            Set<String> want = ssids(t);
                            if (gate && (want.isEmpty() || want.contains(prev))) run(r, "wifi_disconnect", 0);
                        }
                        break;
                    }
                    case "bt_disconnect": {
                        Set<String> cur = snap.connectedBt();
                        Set<String> prev = prevBtDevs.put(id, new LinkedHashSet<>(cur));
                        if (prev != null && !prev.isEmpty()) {
                            Set<String> lost = new HashSet<>(prev);
                            lost.removeAll(cur);
                            if (!lost.isEmpty() && gate) {
                                Set<String> want = deviceSet(t);
                                boolean match = want.isEmpty();
                                if (!match) for (String d : lost) if (containsIgnoreCase(want, d)) { match = true; break; }
                                if (match) run(r, "bt_disconnect", 0);
                            }
                        }
                        break;
                    }
                    case "charging_off":
                    case "headphones_disconnect":
                    case "app_close": {
                        boolean raw = rawActive(t, type, snap);
                        Boolean prev = prevRaw.put(id, raw);
                        if (prev != null && prev && !raw && gate) run(r, type, 0);
                        break;
                    }
                    default: {   // state triggers: fire when (state && gate) becomes true
                        boolean cond = gate && state(r, t, type, snap, now);
                        Boolean prev = prevCond.put(id, cond);
                        if (prev != null && cond && !prev) run(r, type, 0);
                    }
                }
            }
            needLock = nLock;
            needApp = nApp;
            needCharge = nCharge;
            needAudioBt = nAudioBt;
            needDnd = nDnd;
            needAirplane = nAir;
            needPowerMode = nPwr;
            setArmed(nLock || nApp || nCharge || nAudioBt || nDnd || nAir || nPwr);
        } catch (Throwable t) {
            L.e("evaluate", t);
        }
    }

    private boolean rawActive(JSONObject t, String type, Snapshot snap) {
        switch (type) {
            case "charging_off":
                return matchesCharger(t.optString("mode", "any"), snap.battery());
            case "headphones_disconnect":
                return snap.headphones();
            case "app_close": {
                Set<String> apps = strSet(t.optJSONArray("apps"));
                return !apps.isEmpty() && apps.contains(snap.frontApp());
            }
        }
        return false;
    }

    private boolean state(JSONObject r, JSONObject t, String type, Snapshot snap, long now) {
        String id = r.optString("id");
        switch (type) {
            case "dnd_on": return !"0".equals(SysSettings.get("global", "zen_mode"));
            case "dnd_off": return "0".equals(SysSettings.get("global", "zen_mode"));
            case "airplane_on": return "on".equals(read("airplane"));
            case "airplane_off": return "off".equals(read("airplane"));
            case "saver_on": return "on".equals(read("saver"));
            case "saver_off": return "off".equals(read("saver"));
            case "boost_on": return "on".equals(read("boost"));
            case "boost_off": return "off".equals(read("boost"));
            case "charging_on": return matchesCharger(t.optString("mode", "any"), snap.battery());
            case "battery_below": {
                int lvl = snap.battery().level;
                return lvl >= 0 && lvl <= t.optInt("percent", 20);
            }
            case "battery_above": {
                int lvl = snap.battery().level;
                return lvl >= 0 && lvl >= t.optInt("percent", 80);
            }
            case "temp_above":
                return snap.battery().tempTenths >= t.optInt("temp", 42) * 10;
            case "bt_connect": {
                Set<String> cur = snap.connectedBt();
                if (cur.isEmpty()) return false;
                Set<String> want = deviceSet(t);
                if (want.isEmpty()) return true;
                for (String d : cur) if (containsIgnoreCase(want, d)) return true;
                return false;
            }
            case "headphones_connect":
                return snap.headphones();
            case "app_open": {
                Set<String> apps = strSet(t.optJSONArray("apps"));
                return !apps.isEmpty() && apps.contains(snap.frontApp());
            }
            case "wifi_connect": {
                String cur = snap.connected();
                if (cur.isEmpty()) return false;
                if (t.optBoolean("any")) return true;
                return ssids(t).contains(cur);
            }
            case "wifi_gone": {
                if (!"on".equals(read("wifi"))) { goneSince.remove(id); return false; }
                long waitMs = wifiGoneWaitMs(t);
                Set<String> want = ssids(t);
                long scanEvery = Math.min(120_000L, waitMs);
                if (now - lastScanRequest >= scanEvery) {
                    lastScanRequest = now;
                    SysSettings.exec("cmd", "wifi", "start-scan");
                }
                boolean seen = want.contains(snap.connected());
                if (!seen) for (String s : snap.nearby()) if (want.contains(s)) { seen = true; break; }
                if (seen) { goneSince.remove(id); inRange.add(id); return false; }
                if (!inRange.contains(id)) return false;
                Long since = goneSince.get(id);
                if (since == null) {
                    goneSince.put(id, now);
                    L.i(r.optString("name") + ": " + String.join(", ", want) + " not in range, waiting");
                    if (waitMs < 60_000L) h.postDelayed(() -> evaluate("wifi_gone"), waitMs + 500L);
                    return false;
                }
                return now - since >= waitMs;
            }
        }
        return false;
    }

    private static long wifiGoneWaitMs(JSONObject t) {
        if (t.has("seconds")) return Math.max(30, t.optInt("seconds", 600)) * 1000L;
        return Math.max(1, t.optInt("minutes", 10)) * 60_000L;
    }

    private static boolean matchesCharger(String mode, BatteryInfo b) {
        if (!b.charging) return false;
        if ("ac".equals(mode)) return b.ac;
        if ("usb".equals(mode)) return b.usb;
        if ("wireless".equals(mode)) return b.wireless;
        return true;
    }

    private boolean conditionsGate(JSONObject r, Snapshot snap) {
        JSONArray cs = r.optJSONArray("conditions");
        if (cs == null || cs.length() == 0) return true;
        boolean any = "any".equals(r.optString("conditionsMode"));
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            boolean ok = evalCondition(c, snap);
            if (any && ok) return true;
            if (!any && !ok) return false;
        }
        return !any;
    }

    private boolean evalCondition(JSONObject c, Snapshot snap) {
        String type = c.optString("type");
        String v = c.optString("value");
        switch (type) {
            case "battery": {
                int lvl = snap.battery().level;
                if (lvl < 0) return false;
                int target = c.optInt("value", 25);
                return "above".equals(c.optString("op")) ? lvl >= target : lvl <= target;
            }
            case "charging": {
                boolean ch = matchesCharger(c.optString("mode", "any"), snap.battery());
                return "not_charging".equals(v) ? !snap.battery().charging : ch;
            }
            case "wifi_state":
                return v.equals(read("wifi"));
            case "wifi_network": {
                String cur = snap.connected();
                Set<String> want = strSet(c.optJSONArray("ssids"));
                boolean match = !cur.isEmpty() && (want.isEmpty() || want.contains(cur));
                return "not_connected".equals(c.optString("op")) ? !match : match;
            }
            case "bt_state":
                return v.equals(read("bluetooth"));
            case "bt_device": {
                Set<String> cur = snap.connectedBt();
                Set<String> want = strSet(c.optJSONArray("devices"));
                boolean match;
                if (want.isEmpty()) match = !cur.isEmpty();
                else {
                    match = false;
                    for (String d : cur) if (containsIgnoreCase(want, d)) { match = true; break; }
                }
                return "not_connected".equals(c.optString("op")) ? !match : match;
            }
            case "headphones":
                return "disconnected".equals(v) ? !snap.headphones() : snap.headphones();
            case "data":
                return v.equals(read("data"));
            case "airplane":
                return v.equals(read("airplane"));
            case "dnd":
                return v.equals(read("dnd"));
            case "saver":
                return v.equals(read("saver"));
            case "boost":
                return v.equals(read("boost"));
            case "ringer":
                return v.equals(read("ringer"));
            case "screen":
                switch (v) {
                    case "unlocked": return snap.interactive() && !snap.locked();
                    case "locked": return !snap.interactive() || snap.locked();
                    case "screen_on": return snap.interactive();
                    case "screen_off": return !snap.interactive();
                    default: return true;
                }
            case "dark_mode":
                return "off".equals(v) ? !snap.darkMode() : snap.darkMode();
            case "orientation":
                return "portrait".equals(v) ? !snap.landscape() : snap.landscape();
            case "media_playing":
                return "not_playing".equals(v) ? !snap.mediaPlaying() : snap.mediaPlaying();
            case "in_call":
                return "not_in_call".equals(v) ? !snap.inCall() : snap.inCall();
            case "app": {
                Set<String> apps = strSet(c.optJSONArray("apps"));
                boolean match = !apps.isEmpty() && apps.contains(snap.frontApp());
                return "not_in".equals(c.optString("op")) ? !match : match;
            }
            case "temp": {
                int tTenths = snap.battery().tempTenths;
                int target = c.optInt("value", 40) * 10;
                return "below".equals(c.optString("op")) ? tTenths <= target : tTenths >= target;
            }
        }
        return true;
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

    private static Set<String> ssids(JSONObject t) { return strSet(t.optJSONArray("ssids")); }

    private static Set<String> deviceSet(JSONObject t) { return strSet(t.optJSONArray("devices")); }

    private static Set<String> strSet(JSONArray a) {
        Set<String> s = new LinkedHashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i);
            if (!v.isEmpty()) s.add(v);
        }
        return s;
    }

    private static boolean containsIgnoreCase(Set<String> set, String target) {
        if (target == null) return false;
        for (String s : set) if (s.equalsIgnoreCase(target)) return true;
        return false;
    }

    /** Days, time window, "only after", once a day. */
    private boolean scheduleGate(JSONObject r, long now) {
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
        // {start, end}: only while start has run more recently than end (e.g. after Morning,
        // until Sleep). Can run any number of times inside that stretch.
        JSONObject span = r.optJSONObject("between");
        if (span != null) {
            String s = span.optString("start"), e = span.optString("end");
            if (!s.isEmpty() && lastRun(s) == 0) return false;
            if (!s.isEmpty() && !e.isEmpty() && lastRun(s) <= lastRun(e)) return false;
            if (s.isEmpty() && !e.isEmpty() && lastRun(e) >= today) return false;
        }
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

    private void run(JSONObject r, String why, int depth) {
        String id = r.optString("id");
        L.i("▶ " + r.optString("name") + " (" + why + ")");
        inRange.remove(id);
        goneSince.remove(id);
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
                try { act(a, id, depth); } catch (Throwable t) { failed++; L.e("action " + a.optString("type"), t); }
            }
        }
        saveState();
        if (depth == 0) prevCond.clear();   // re-baseline at end of top-level run
        L.i("✓ " + r.optString("name") + " done");
        if (depth == 0 && !hasAction(actions, "lock") && !hasAction(actions, "notice"))
            SysSettings.notice(failed == 0 ? r.optString("name") : r.optString("name") + ": " + failed + " failed", failed == 0);
        if (depth == 0) h.post(() -> evaluate("post_run"));
    }

    private static boolean hasAction(JSONArray actions, String type) {
        if (actions == null) return false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a != null && type.equals(a.optString("type"))) return true;
        }
        return false;
    }

    private void act(JSONObject a, String selfId, int depth) throws Exception {
        String type = a.optString("type");
        if ("run_routine".equals(type)) {
            if (depth >= 4) { L.i("run_routine: max chain depth reached"); return; }
            String targetId = a.optString("routineId").trim();
            if (targetId.isEmpty() || targetId.equals(selfId)) return;
            for (int i = 0; i < routines.length(); i++) {
                JSONObject target = routines.optJSONObject(i);
                if (target != null && targetId.equals(target.optString("id"))) {
                    run(target, "chained", depth + 1);
                    return;
                }
            }
            L.i("run_routine: unknown " + targetId);
            return;
        }
        if (ShellActions.runInstant(a, null, null)) return;
        String v = a.optString("value");
        String current = ShellActions.read(type);
        if (current == null) { L.i("unknown action " + type); return; }
        JSONObject saved = state.optJSONObject("saved");
        if (v.equals("restore")) {
            JSONObject s = saved.optJSONObject(type);
            if (s == null) { L.i(type + ": nothing to restore"); return; }
            saved.remove(type);
            ShellActions.write(type, s.optString("v"));
            L.i(type + ": restored " + s.optString("v"));
            return;
        }
        String target = ShellActions.targetValue(type, a);
        if (target == null) { L.i("unknown action " + type); return; }
        JSONObject s = saved.optJSONObject(type);
        if (s != null && s.optString("v").equals(target)) {
            saved.remove(type);
        } else if (s == null || System.currentTimeMillis() - s.optLong("t") > 20 * 3600_000L) {
            saved.put(type, new JSONObject().put("v", current).put("t", System.currentTimeMillis()));
        }
        ShellActions.write(type, target);
        if (!target.equals(current) && !ShellActions.settled(type, target)) {
            ShellActions.write(type, target);
            if (!ShellActions.settled(type, target))
                throw new IllegalStateException(type + " still " + ShellActions.read(type) + " after setting " + target);
        }
        L.i(type + ": " + current + " → " + target);
    }

    private static String read(String type) { return ShellActions.read(type); }
    private static void write(String type, String v) { ShellActions.write(type, v); }

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
            o.put("gestures", GestureListener.listening());
            o.put("gestureDevice", GestureListener.device);
            o.put("lastGesture", GestureListener.last);
            o.put("lastDrawing", GestureListener.lastDrawing);
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
                prevRaw.clear();
                prevWifiSsid.clear();
                prevBtDevs.clear();
                goneSince.clear();
                inRange.clear();
                L.i("loaded " + routines.length() + " routines");
                GestureListener.configure(new JSONObject(json).optJSONObject("gestures"), routines,
                        (gid, fingers, res) -> evaluate("gesture:" + fingers + ":" + gid));
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
                    if (r != null && id.equals(r.optString("id"))) { run(r, "run now", 0); return; }
                }
                L.i("no routine " + id);
            });
            return "ok";
        }
        if (cmd.equals("close_apps")) { h.post(() -> ShellActions.closeApps(null, null)); return "ok"; }
        if (cmd.startsWith("revive ")) {
            JSONArray apps = new JSONArray();
            for (String s : cmd.substring(7).split(",")) if (!s.trim().isEmpty()) apps.put(s.trim());
            h.post(() -> ShellActions.revive(apps));
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
        if (cmd.equals("bt_devices")) {
            Set<String> names = new LinkedHashSet<>();
            try {
                BluetoothManager bm = ctx != null ? ctx.getSystemService(BluetoothManager.class) : null;
                BluetoothAdapter ba = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
                if (ba != null && ba.getBondedDevices() != null) {
                    for (BluetoothDevice d : ba.getBondedDevices()) {
                        String n = d.getName();
                        if (n != null && !n.trim().isEmpty()) names.add(n.trim());
                    }
                }
            } catch (Throwable ignored) {}
            if (names.isEmpty()) {
                boolean inBonded = false;
                for (String l : SysSettings.exec("dumpsys", "bluetooth_manager").split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("Bonded devices:")) { inBonded = true; continue; }
                    if (inBonded) {
                        if (t.isEmpty() || (!t.contains(":") && !t.startsWith("["))) { inBonded = false; continue; }
                        Matcher m = Pattern.compile("\\[([^]]+)\\]").matcher(t);
                        if (m.find()) names.add(m.group(1).trim());
                    }
                }
            }
            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            return new JSONArray(sorted).toString();
        }
        return "";
    }
}
