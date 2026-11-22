package dev.suite.shell;

import android.content.ComponentName;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared system actions, setting readers/writers, and window/process helpers used by both
 * RoutinesUserService ("dev.suite:routines") and ProfilesUserService ("dev.suite:profiles").
 */
public final class ShellActions {
    private ShellActions() {}

    public static final class TaskHit {
        public final String pkg;
        public final String cmp;
        public final int taskId;
        public TaskHit(String pkg, String cmp, int taskId) { this.pkg = pkg; this.cmp = cmp; this.taskId = taskId; }
    }

    private static Object atm, wm, pm;
    private static Method focusedTask, moveTask, setFocusedTask, keyguard, interactive;

    /** Runs a one-shot action that isn't read/restored as a setting. Returns false if stateful. */
    public static boolean runInstant(JSONObject a, TaskHit frontHit, String extraKeep) throws InterruptedException {
        String type = a.optString("type");
        switch (type) {
            case "vibrate":
                Haptics.done();
                return true;
            case "lock":
                SysSettings.exec("input", "keyevent", "223");
                L.i("screen locked");
                return true;
            case "wake_screen":
                SysSettings.exec("input", "keyevent", "224");
                L.i("screen woken");
                return true;
            case "go_home":
                SysSettings.exec("input", "keyevent", "3");
                L.i("went home");
                return true;
            case "screenshot":
                SysSettings.exec("input", "keyevent", "120");
                L.i("screenshot triggered");
                return true;
            case "media_control":
                mediaControl(a.optString("value", "play_pause"));
                return true;
            case "open_app": {
                String pkg = a.optString("pkg");
                if (pkg.isEmpty()) {
                    JSONArray apps = a.optJSONArray("apps");
                    if (apps != null && apps.length() > 0) pkg = apps.optString(0);
                }
                openApp(pkg);
                return true;
            }
            case "close_app":
                closeSpecificApps(a.optJSONArray("apps"));
                return true;
            case "close_apps":
                closeApps(a.optJSONArray("except"), extraKeep);
                return true;
            case "revive":
                revive(a.optJSONArray("apps"));
                return true;
            case "open_url": {
                String url = a.optString("url").trim();
                if (!url.isEmpty()) {
                    if (!url.contains("://")) url = "https://" + url;
                    SysSettings.exec("am", "start", "-a", "android.intent.action.VIEW", "-d", url);
                    L.i("open_url: " + url);
                }
                return true;
            }
            case "notice": {
                String text = a.optString("text").trim();
                if (!text.isEmpty()) SysSettings.notice(text, true);
                return true;
            }
            case "run_routine": {
                String id = a.optString("routineId").trim();
                if (!id.isEmpty()) {
                    SysSettings.exec("am", "broadcast", "-n", "dev.suite/.TestReceiver",
                            "-a", "dev.suite.RT_RUN", "--es", "id", id);
                }
                return true;
            }
            case "shell_cmd": {
                String cmd = a.optString("cmd").trim();
                if (!cmd.isEmpty()) {
                    String out = SysSettings.exec("/system/bin/sh", "-c", cmd);
                    L.i("shell_cmd: " + (out.isEmpty() ? "ok" : out.split("\n")[0]));
                }
                return true;
            }
            case "wait":
                Thread.sleep(Math.min(120, Math.max(0, a.optInt("value", 1))) * 1000L);
                return true;
            case "launch_bg":
                launchBackground(a.optJSONArray("apps"), frontHit != null ? frontHit : frontTask());
                return true;
            case "circle_to_search":
                triggerCircleToSearch();
                return true;
            case "gemini_cts":
                triggerGeminiOverlay();
                return true;
            case "gemini_live":
                triggerGeminiLive();
                return true;
            default:
                return false;
        }
    }

    /** Executes any action once (used by Double power press / Power hold custom actions). */
    public static void runAction(JSONObject a) { runAction(a, false); }

    public static void runAction(JSONObject a, boolean syncNotice) {
        if (a == null) return;
        String type = a.optString("type");
        if (type.isEmpty()) return;
        try {
            if (runInstant(a, null, null)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        String current = read(type);
        if (current == null) {
            L.i("run_action: unknown " + type);
            return;
        }
        String rawVal = a.optString("value");
        String target;
        String displayVal;
        if ("toggle".equals(rawVal)) {
            boolean curOn;
            if ("stream_laptop".equals(type)) {
                curOn = "on".equals(current) || "wide".equals(current);
            } else if ("keep_awake".equals(type)) {
                long ms = 60_000L;
                try { ms = Long.parseLong(current); } catch (NumberFormatException ignored) {}
                curOn = ms >= 600_000L;
            } else if ("dark_mode".equals(type) && "auto".equals(current)) {
                curOn = SysSettings.exec("dumpsys", "uimode").contains("mComputedNightMode=true");
            } else {
                curOn = "on".equals(current);
            }
            displayVal = curOn ? "off" : "on";
            if ("keep_awake".equals(type)) {
                target = curOn ? "60000" : "1800000";
            } else {
                target = displayVal;
            }
        } else {
            displayVal = rawVal;
            target = targetValue(type, a);
        }
        if (target == null) return;
        write(type, target);
        L.i("run_action " + type + ": " + current + " → " + target);
        String msg = actionNotice(type, displayVal);
        if (msg != null && !msg.isEmpty()) {
            if (syncNotice) {
                AppKeeper.ensureA11y();
                SysSettings.exec("am", "broadcast", "-n", "dev.suite/.IslandReceiver",
                        "--es", "text", msg, "--ez", "ok", "true");
            } else {
                SysSettings.notice(msg, true);
            }
        }
    }

    private static String actionNotice(String type, String v) {
        switch (type) {
            case "wifi": return "Wi-Fi " + v;
            case "data": return "Data " + v;
            case "bluetooth": return "Bluetooth " + v;
            case "nfc": return "NFC " + v;
            case "airplane": return "Airplane " + v;
            case "location": return "Location " + v;
            case "auto_sync": return "Auto-sync " + v;
            case "dnd": return "DND " + v;
            case "ringer": return "silent".equals(v) ? "Silent" : "vibrate".equals(v) ? "Vibrate" : "Ring";
            case "media": return "Media " + v + "%";
            case "ring_vol": return "Ring " + v + "%";
            case "notif_vol": return "Notif " + v + "%";
            case "alarm_vol": return "Alarm " + v + "%";
            case "brightness": return "Brightness " + v + "%";
            case "auto_brightness": return "Auto-bright " + v;
            case "dark_mode": return "Dark mode " + v;
            case "rotation": return "auto".equals(v) ? "Auto-rotate" : "landscape".equals(v) ? "Landscape" : "Portrait lock";
            case "screen_timeout":
                try {
                    long s = Long.parseLong(v) / 1000L;
                    return s >= 60 ? "Timeout " + (s / 60) + " min" : "Timeout " + s + " s";
                } catch (NumberFormatException ignored) {
                    return "Timeout";
                }
            case "keep_awake": return "off".equals(v) ? "Normal timeout" : "Keep screen on";
            case "extra_dim": return "Extra dim " + v;
            case "grayscale": return "Grayscale " + v;
            case "saver": return "Saver " + v;
            case "boost": return "Boost " + v;
            case "temp_pill": return "Temp pill " + v;
            case "fps_counter": return "FPS " + v;
            case "frame_interp": return "FI " + v;
            case "super_res": return "SR " + v;
            case "play_together": return "Mixing " + v;
            case "stream_laptop": return "Laptop " + ("wide".equals(v) ? "16:10" : v);
            case "gestures": return "Gestures " + v;
            case "cts_touch": return "Touch CTS " + v;
            case "cts_button": return "Power CTS " + v;
            case "cts_nav": return "Nav CTS " + v;
            case "power_hold": return "Power hold " + v;
            default: return null;
        }
    }

    /** Maps a configured action value to the raw value passed to write() / compared with read(). */
    public static String targetValue(String type, JSONObject a) {
        String v = a.optString("value");
        switch (type) {
            case "rotation":
                if ("auto".equals(v)) return "1:0";
                if ("landscape".equals(v)) return "0:1";
                return "0:0";
            case "keep_awake":
                return "off".equals(v) ? "60000" : "1800000";
            case "screen_timeout":
                return v.isEmpty() ? "60000" : v;
            case "brightness": {
                int pct = Math.max(1, Math.min(100, a.optInt("value", 50)));
                return String.format(Locale.US, "m:0:f:%.3f", pct / 100f);
            }
            case "ring_vol":
                return String.valueOf(percentToIndex(2, a.optInt("value", 50)));
            case "media":
                return String.valueOf(percentToIndex(3, a.optInt("value", 0)));
            case "alarm_vol":
                return String.valueOf(percentToIndex(4, a.optInt("value", 50)));
            case "notif_vol":
                return String.valueOf(percentToIndex(5, a.optInt("value", 50)));
            default:
                return v;
        }
    }

    /** Waits up to ~4 s for a setting to read back as the value just written. */
    public static boolean settled(String type, String target) throws InterruptedException {
        if ("ring_vol".equals(type) || "notif_vol".equals(type)) {
            if (!"normal".equals(read("ringer"))) return true;
        }
        for (int i = 0; i < 8; i++) {
            if (matchesValue(type, target, read(type))) return true;
            Thread.sleep(500);
        }
        return matchesValue(type, target, read(type));
    }

    private static boolean matchesValue(String type, String target, String actual) {
        if (target.equals(actual)) return true;
        if ("brightness".equals(type) && actual != null && target.startsWith("m:") && actual.startsWith("m:")) {
            String[] t = target.split(":");
            String[] c = actual.split(":");
            if (t.length >= 4 && c.length >= 4 && t[1].equals(c[1]) && "f".equals(t[2]) && "f".equals(c[2])) {
                try {
                    return Math.abs(Float.parseFloat(t[3]) - Float.parseFloat(c[3])) < 0.06f;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    /** Current value in the same vocabulary write() takes, or null if type isn't a stateful setting. */
    public static String read(String type) {
        switch (type) {
            case "nfc":
                for (String l : SysSettings.exec("dumpsys", "nfc").split("\n")) {
                    String t = l.trim();
                    if (t.startsWith("mState=")) return (t.equals("mState=on") || t.equals("mState=turning on")) ? "on" : "off";
                }
                return "off";
            case "bluetooth": {
                String b = SysSettings.get("global", "bluetooth_on");
                return ("1".equals(b) || "2".equals(b)) ? "on" : "off";
            }
            case "location": {
                String m = SysSettings.get("secure", "location_mode");
                return (m == null || "0".equals(m) || "null".equals(m)) ? "off" : "on";
            }
            case "auto_sync":
                return readAutoSync() ? "on" : "off";
            case "rotation": {
                String acc = SysSettings.get("system", "accelerometer_rotation");
                String rot = SysSettings.get("system", "user_rotation");
                return ("1".equals(acc) ? "1" : "0") + ":" + (rot != null && rot.matches("\\d+") ? rot : "0");
            }
            case "keep_awake":
            case "screen_timeout": {
                String ms = SysSettings.get("system", "screen_off_timeout");
                return ms != null && ms.matches("\\d+") ? ms : "60000";
            }
            case "auto_brightness":
                return "1".equals(SysSettings.get("system", "screen_brightness_mode")) ? "on" : "off";
            case "brightness": {
                String mode = "1".equals(SysSettings.get("system", "screen_brightness_mode")) ? "1" : "0";
                String fl = SysSettings.get("system", "screen_brightness_float");
                if (fl != null) {
                    try {
                        float f = Float.parseFloat(fl);
                        if (f >= 0f && f <= 1f) return String.format(Locale.US, "m:%s:f:%.3f", mode, f);
                    } catch (NumberFormatException ignored) {}
                }
                String iv = SysSettings.get("system", "screen_brightness");
                return "m:" + mode + ":i:" + (iv != null && iv.matches("\\d+") ? iv : "128");
            }
            case "dark_mode": {
                String m = SysSettings.get("secure", "ui_night_mode");
                if ("2".equals(m)) return "on";
                if ("1".equals(m)) return "off";
                if ("0".equals(m)) return "auto";
                String out = SysSettings.exec("cmd", "uimode", "night");
                if (out.contains("yes")) return "on";
                if (out.contains("no")) return "off";
                return "auto";
            }
            case "extra_dim":
                return "1".equals(SysSettings.get("secure", "reduce_bright_colors_activated")) ? "on" : "off";
            case "grayscale":
                return ("1".equals(SysSettings.get("secure", "accessibility_display_daltonizer_enabled"))
                        && "0".equals(SysSettings.get("secure", "accessibility_display_daltonizer"))) ? "on" : "off";
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
            case "airplane":
                return "1".equals(SysSettings.get("global", "airplane_mode_on")) ? "on" : "off";
            case "saver": {
                // vivo's own power saving mode (what its switch shows): 1 balanced, 2 power saving, 6 boost
                String t = SysSettings.get("system", "power_save_type");
                if (t != null && t.matches("\\d+")) return "2".equals(t) ? "on" : "off";
                return "1".equals(SysSettings.get("global", "low_power")) ? "on" : "off";
            }
            case "boost": {
                String t = SysSettings.get("system", "power_save_type");
                return "6".equals(t) ? "on" : "off";
            }
            case "dnd":
                return "0".equals(SysSettings.get("global", "zen_mode")) ? "off" : "on";
            case "ringer": {
                String m = SysSettings.get("global", "mode_ringer");
                return "0".equals(m) ? "silent" : "1".equals(m) ? "vibrate" : "normal";
            }
            case "ring_vol":
                return String.valueOf(streamIndex(2));
            case "media":
                return String.valueOf(streamIndex(3));
            case "alarm_vol":
                return String.valueOf(streamIndex(4));
            case "notif_vol":
                return String.valueOf(streamIndex(5));
            case "play_together":
                return pidof("dev.suite:mix") > 0 ? "on" : "off";
            case "super_res":
                return (pidof("dev.suite:sr") > 0 || SrUserService.isOn()) ? "on" : "off";
            case "frame_interp":
                return running("dev.suite:fi") ? "on" : "off";
            case "temp_pill":
                return getFeature("temp");
            case "fps_counter":
                return getFeature("fps");
            case "stream_laptop":
                return getFeature("stream");
            case "gestures":
                return getFeature("gestures");
            case "cts_touch":
                return getFeature("cts_touch");
            case "cts_button":
                return getFeature("cts_button");
            case "cts_nav":
                return getFeature("cts_nav");
            case "power_hold":
                return getFeature("power_hold");
        }
        return null;
    }

    public static void write(String type, String v) {
        boolean on = "on".equals(v);
        switch (type) {
            case "nfc":
                setNfc(on);
                break;
            case "bluetooth": {
                String out = SysSettings.exec("cmd", "bluetooth_manager", on ? "enable" : "disable");
                if (out.contains("Unknown") || out.contains("Exception")) SysSettings.exec("svc", "bluetooth", on ? "enable" : "disable");
                break;
            }
            case "location": {
                String out = SysSettings.exec("cmd", "location", "set-location-enabled", on ? "true" : "false");
                if (out.contains("Unknown") || out.contains("Exception")) SysSettings.put("secure", "location_mode", on ? "3" : "0");
                break;
            }
            case "auto_sync":
                writeAutoSync(on);
                break;
            case "rotation": {
                String[] parts = v.split(":");
                SysSettings.put("system", "accelerometer_rotation", parts.length > 0 ? parts[0] : "0");
                SysSettings.put("system", "user_rotation", parts.length > 1 ? parts[1] : "0");
                break;
            }
            case "keep_awake":
            case "screen_timeout":
                SysSettings.put("system", "screen_off_timeout", v);
                break;
            case "auto_brightness":
                SysSettings.put("system", "screen_brightness_mode", on ? "1" : "0");
                break;
            case "brightness":
                writeBrightness(v);
                break;
            case "dark_mode": {
                String mode = "on".equals(v) ? "yes" : "off".equals(v) ? "no" : "auto";
                SysSettings.exec("cmd", "uimode", "night", mode);
                break;
            }
            case "extra_dim":
                SysSettings.put("secure", "reduce_bright_colors_activated", on ? "1" : "0");
                break;
            case "grayscale":
                if (on) {
                    SysSettings.put("secure", "accessibility_display_daltonizer", "0");
                    SysSettings.put("secure", "accessibility_display_daltonizer_enabled", "1");
                } else {
                    SysSettings.put("secure", "accessibility_display_daltonizer_enabled", "0");
                }
                break;
            case "data":
                SysSettings.exec("svc", "data", on ? "enable" : "disable");
                break;
            case "wifi":
                SysSettings.exec("cmd", "wifi", "set-wifi-enabled", on ? "enabled" : "disabled");
                break;
            case "airplane":
                SysSettings.exec("cmd", "connectivity", "airplane-mode", on ? "enable" : "disable");
                break;
            case "saver":
                setSaver(on);
                break;
            case "boost":
                setBoost(on);
                break;
            case "dnd":
                SysSettings.exec("cmd", "notification", "set_dnd", on ? "on" : "off");
                break;
            case "ringer":
                SysSettings.exec("cmd", "audio", "set-ringer-mode", v.toUpperCase());
                break;
            case "ring_vol":
                SysSettings.exec("cmd", "audio", "set-volume", "2", v);
                break;
            case "media":
                SysSettings.exec("cmd", "audio", "set-volume", "3", v);
                break;
            case "alarm_vol":
                SysSettings.exec("cmd", "audio", "set-volume", "4", v);
                break;
            case "notif_vol":
                SysSettings.exec("cmd", "audio", "set-volume", "5", v);
                break;
            case "play_together":
                setFeature("mix", on ? "on" : "off");
                break;
            case "super_res":
                setFeature("sr", on ? "on" : "off");
                break;
            case "frame_interp":
                setFeature("fi", on ? "on" : "off");
                break;
            case "temp_pill":
                setFeature("temp", on ? "on" : "off");
                break;
            case "fps_counter":
                setFeature("fps", on ? "on" : "off");
                break;
            case "stream_laptop":
                setFeature("stream", v);
                break;
            case "gestures":
                setFeature("gestures", on ? "on" : "off");
                break;
            case "cts_touch":
                setFeature("cts_touch", on ? "on" : "off");
                break;
            case "cts_button":
                setFeature("cts_button", on ? "on" : "off");
                break;
            case "cts_nav":
                setFeature("cts_nav", on ? "on" : "off");
                break;
            case "power_hold":
                setFeature("power_hold", on ? "on" : "off");
                break;
        }
    }

    private static String getFeature(String feature) {
        String out = SysSettings.exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.GET_FEATURE",
                "--es", "feature", feature);
        Matcher m = Pattern.compile("data=\"([^\"]*)\"").matcher(out);
        if (m.find()) {
            String val = m.group(1);
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    private static void setFeature(String feature, String value) {
        SysSettings.exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.SET_FEATURE",
                "--es", "feature", feature, "--es", "value", value,
                "--ez", "on", String.valueOf("on".equals(value)));
    }

    private static void triggerCircleToSearch() {
        String out = SysSettings.exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.TRIGGER_CTS");
        if (out.contains("data=\"ok\"")) {
            L.i("circle_to_search: triggered with ripple");
            return;
        }
        CtsListener.launch();
    }

    public static void triggerGeminiLive() {
        // Explicit -p com.google.android.googlequicksearchbox avoids Chrome opening on ROMs where
        // bard.google.com App Links verification is disabled.
        String out = SysSettings.exec("am", "start", "-f", "0x10000000",
                "-a", "android.intent.action.VIEW",
                "-d", "https://bard.google.com/android?source=bardshell&feature=liveconv",
                "-p", "com.google.android.googlequicksearchbox");
        if (!out.contains("Error:") && !out.contains("Exception")) {
            L.i("gemini_live: started bardshell liveconv");
            return;
        }
        out = SysSettings.exec("am", "start", "-f", "0x10000000",
                "-n", "com.google.android.apps.bard/.widget.RobinWidgetEntryPointActivity",
                "--es", "feature", "liveconv");
        if (!out.contains("Error:") && !out.contains("Exception")) {
            L.i("gemini_live: started widget entrypoint");
            return;
        }
        SysSettings.exec("am", "start", "-f", "0x10000000",
                "-a", "android.intent.action.VOICE_COMMAND",
                "-p", "com.google.android.googlequicksearchbox");
        L.i("gemini_live: fallback VOICE_COMMAND");
    }

    public static void triggerGeminiOverlay() {
        try {
            if (CtsTrigger.triggerGeminiOverlay(CtsTrigger.service())) {
                L.i("gemini_cts: triggered overlay via VoiceInteraction");
                return;
            }
        } catch (Throwable t) {
            L.e("gemini_cts VoiceInteraction", t);
        }
        String out = SysSettings.exec("cmd", "voiceinteraction", "show");
        if (!out.contains("Error:") && !out.contains("Exception") && !out.contains("Unknown")) {
            L.i("gemini_cts: triggered via cmd voiceinteraction show");
            return;
        }
        SysSettings.exec("am", "start", "-f", "0x10000000",
                "-a", "android.intent.action.ASSIST",
                "-p", "com.google.android.googlequicksearchbox");
        L.i("gemini_cts: fallback ACTION_ASSIST");
    }

    private static void mediaControl(String action) {
        String key;
        switch (action) {
            case "play": key = "126"; break;
            case "pause": key = "127"; break;
            case "next": key = "87"; break;
            case "prev": key = "88"; break;
            case "stop": key = "86"; break;
            default: key = "85"; break;
        }
        SysSettings.exec("input", "keyevent", key);
        L.i("media_control: " + action);
    }

    private static void writeBrightness(String v) {
        String[] parts = v.split(":");
        String mode = parts.length > 1 ? parts[1] : "0";
        SysSettings.put("system", "screen_brightness_mode", mode);
        if (parts.length >= 4) {
            if ("f".equals(parts[2])) {
                try {
                    float f = Math.max(0.01f, Math.min(1f, Float.parseFloat(parts[3])));
                    if (setDisplayBrightness(f)) return;
                    SysSettings.put("system", "screen_brightness_float", String.format(Locale.US, "%.3f", f));
                    SysSettings.put("system", "screen_brightness", String.valueOf(Math.max(1, Math.min(255, Math.round(f * 255f)))));
                } catch (NumberFormatException ignored) {}
            } else {
                SysSettings.put("system", "screen_brightness", parts[3]);
            }
        }
    }

    private static boolean setDisplayBrightness(float value) {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "display");
            Object dm = Class.forName("android.hardware.display.IDisplayManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            dm.getClass().getMethod("setBrightness", int.class, float.class).invoke(dm, 0, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readAutoSync() {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "content");
            Object cs = Class.forName("android.content.IContentService$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            try {
                return (boolean) cs.getClass().getMethod("getMasterSyncAutomaticallyAsUser", int.class).invoke(cs, 0);
            } catch (NoSuchMethodException e) {
                return (boolean) cs.getClass().getMethod("getMasterSyncAutomatically").invoke(cs);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeAutoSync(boolean on) {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "content");
            Object cs = Class.forName("android.content.IContentService$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            try {
                cs.getClass().getMethod("setMasterSyncAutomaticallyAsUser", boolean.class, int.class).invoke(cs, on, 0);
            } catch (NoSuchMethodException e) {
                cs.getClass().getMethod("setMasterSyncAutomatically", boolean.class).invoke(cs, on);
            }
        } catch (Throwable t) {
            L.e("auto_sync write", t);
        }
    }

    private static void openApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
            Object ipm = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            ipm.getClass().getMethod("setPackageStoppedState", String.class, boolean.class, int.class).invoke(ipm, pkg, false, 0);
        } catch (Throwable ignored) {}
        String cmp = SysSettings.exec("cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg);
        String targetCmp = lastComponentLine(cmp, pkg);
        if (targetCmp != null) {
            SysSettings.exec("am", "start", "-n", targetCmp, "-f", "0x10200000");
            L.i("open_app: " + targetCmp);
        } else {
            SysSettings.exec("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
            L.i("open_app (monkey): " + pkg);
        }
    }

    public static void closeSpecificApps(JSONArray apps) {
        if (apps == null || apps.length() == 0) return;
        Set<String> targets = new LinkedHashSet<>();
        for (int i = 0; i < apps.length(); i++) {
            String pkg = apps.optString(i);
            if (!pkg.isEmpty() && !"dev.suite".equals(pkg) && !"moe.shizuku.privileged.api".equals(pkg)) targets.add(pkg);
        }
        if (targets.isEmpty()) return;
        try {
            Object atmSvc = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Method remove = atmSvc.getClass().getMethod("removeTask", int.class);
            Pattern p = Pattern.compile("Recent #\\d+: Task\\{\\S+ #(\\d+) type=standard (?:A=\\d+:|I=)([\\w.]+)");
            for (String l : SysSettings.exec("dumpsys", "activity", "recents").split("\n")) {
                Matcher m = p.matcher(l);
                if (m.find() && targets.contains(m.group(2))) {
                    try { remove.invoke(atmSvc, Integer.parseInt(m.group(1))); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        for (String pkg : targets) SysSettings.exec("am", "force-stop", pkg);
        L.i("close_app: stopped " + String.join(", ", targets));
    }

    /**
     * Battery mode via vivo's app function ("battery_mode": 1 balanced, 2 power saving, 6 boost).
     */
    private static boolean setBatteryMode(String mode) {
        String out = SysSettings.exec("cmd", "app_function", "execute-app-function",
                "--package", "com.iqoo.powersaving",
                "--function", "com.iqoo.powersaving.appfunctions.PowerSavingFunctions#setDeviceStateItem",
                "--parameters", "{\"setDeviceStateItemParams\":{\"key\":\"battery_mode\",\"value\":\"" + mode
                        + "\",\"requestInitiatedWhileUnlocked\":true}}");
        if (out != null && out.replaceAll("\\s+", "").contains("\"isSuccessful\":[true]")) return true;
        L.i("battery_mode " + mode + ": vivo power mode unavailable (" + (out == null ? "" : out.trim().replaceAll("\\s+", " ")) + ")");
        return false;
    }

    private static void setSaver(boolean on) {
        if (setBatteryMode(on ? "2" : "1")) return;
        SysSettings.exec("cmd", "power", "set-mode", on ? "1" : "0");
    }

    private static void setBoost(boolean on) {
        if (setBatteryMode(on ? "6" : "1")) return;
        SysSettings.put("system", "power_save_type", on ? "6" : "1");
    }

    /**
     * Toggles NFC via Settings' AppFunction ("vivo_nfc_settings_screen/nfc_toggle") so OriginOS
     * sees com.android.settings as the caller (clearing its is_recovery_nfc_state flag instead of
     * auto-reenabling NFC during the 2 AM vddpa_start job). Falls back to `svc nfc`.
     */
    public static void setNfc(boolean on) {
        String want = on ? "on" : "off";
        // Settings answers "done" even when NFC is still turning on/off and then drops the
        // request (seen when Mir Pay closes within a second): wait for NFC to settle, check, retry.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (want.equals(nfcSettled(5000))) return;
            if (!setVivoNfc(on)) break;
            if (want.equals(nfcSettled(5000))) return;
            L.i("nfc " + want + ": didn't stick, retrying (" + (attempt + 1) + ")");
        }
        // Last resort. OriginOS treats this as a third-party switch: after an "off" here it
        // re-enables NFC at ~2 AM (the profiles engine's screen-off guard turns it back off).
        L.i("nfc " + want + ": using svc");
        SysSettings.exec("svc", "nfc", on ? "enable" : "disable");
    }

    /** NFC state as dumpsys shows it: "on", "off", "turning on", "turning off" (or "" if unknown). */
    public static String nfcRawState() {
        for (String l : SysSettings.exec("dumpsys", "nfc").split("\n")) {
            String t = l.trim();
            if (t.startsWith("mState=")) return t.substring(7).trim();
        }
        return "";
    }

    /** Waits (up to maxMs) until NFC is fully on or off and returns that state. */
    private static String nfcSettled(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (true) {
            String s = nfcRawState();
            if ("on".equals(s) || "off".equals(s) || System.currentTimeMillis() > end) return s;
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return s; }
        }
    }

    private static boolean setVivoNfc(boolean on) {
        String out = SysSettings.exec("cmd", "app_function", "execute-app-function",
                "--package", "com.android.settings",
                "--function", "setDeviceStateItem",
                "--parameters", "{\"setDeviceStateItemParams\":{\"key\":\"vivo_nfc_settings_screen/nfc_toggle\",\"value\":\""
                        + on + "\",\"requestInitiatedWhileUnlocked\":true}}");
        if (out != null && out.replaceAll("\\s+", "").contains("\"isSuccessful\":[true]")) return true;
        L.i("nfc " + on + ": settings app_function unavailable ("
                + (out == null ? "" : out.trim().replaceAll("\\s+", " ")) + ")");
        return false;
    }

    private static int lastInt(String s) {
        Matcher m = Pattern.compile("(-?\\d+)\\s*$").matcher(s.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int streamIndex(int stream) {
        return lastInt(SysSettings.exec("cmd", "audio", "get-stream-volume", String.valueOf(stream)));
    }

    private static int percentToIndex(int stream, int pct) {
        int max = lastInt(SysSettings.exec("cmd", "audio", "get-max-volume", String.valueOf(stream)));
        if (max <= 0) max = (stream == 3) ? 15 : 7;
        return Math.round(Math.max(0, Math.min(100, pct)) * max / 100f);
    }

    /**
     * Closes everything: clears recents (like swiping every card away), then force-stops every
     * user-installed app still running, background ones included (KWGT, VPNs, messengers…).
     * Keeps the launcher, the keyboard, SolCube, Shizuku (SolCube runs on it), except, and extraKeep.
     */
    public static void closeApps(JSONArray except, String extraKeep) {
        Set<String> keep = new HashSet<>();
        keep.add("dev.suite");
        keep.add("moe.shizuku.privileged.api");
        String ime = SysSettings.get("secure", "default_input_method");
        if (ime != null && ime.contains("/")) keep.add(ime.substring(0, ime.indexOf('/')));
        for (String l : SysSettings.exec("cmd", "package", "resolve-activity", "--brief",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").split("\n"))
            if (l.contains("/")) keep.add(l.trim().substring(0, l.trim().indexOf('/')));
        if (except != null) for (int i = 0; i < except.length(); i++) keep.add(except.optString(i));
        if (extraKeep != null && !extraKeep.isEmpty()) keep.add(extraKeep);

        Method remove;
        Object atmSvc;
        try {
            atmSvc = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            remove = atmSvc.getClass().getMethod("removeTask", int.class);
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
                if ((boolean) remove.invoke(atmSvc, Integer.parseInt(m.group(1)))) n++;
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
    public static void revive(JSONArray stopped) {
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

    /** Wakes companion apps in the background (audio session broadcast first, then activity + restore front task). */
    public static void launchBackground(JSONArray apps, TaskHit frontHit) {
        if (apps == null || apps.length() == 0) return;
        if (frontHit == null) frontHit = frontTask();
        Object ipm = null;
        Method unstop = null;
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
            ipm = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
            unstop = ipm.getClass().getMethod("setPackageStoppedState", String.class, boolean.class, int.class);
        } catch (Throwable ignored) {}

        boolean launchedActivity = false;
        for (int i = 0; i < apps.length(); i++) {
            String cp = apps.optString(i);
            if (cp == null || cp.isEmpty()) continue;
            try { if (unstop != null) unstop.invoke(ipm, cp, false, 0); } catch (Throwable ignored) {}
            if (running(cp)) {
                L.i("launch_bg: " + cp + " already running");
                continue;
            }
            String receiver = findAudioEffectReceiver(cp);
            if (receiver != null) {
                SysSettings.exec("am", "broadcast", "-f", "0x01000020", "-n", receiver,
                        "-a", "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION",
                        "--ei", "android.media.extra.AUDIO_SESSION", "0",
                        "--es", "android.media.extra.PACKAGE_NAME", frontHit.pkg);
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                if (pidof(cp) > 0) {
                    L.i("launch_bg: woke " + cp + " via session broadcast");
                    continue;
                }
            }
            String cmp = SysSettings.exec("cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", cp);
            String targetCmp = lastComponentLine(cmp, cp);
            if (targetCmp != null) {
                SysSettings.exec("am", "start", "--activity-no-animation", "-n", targetCmp);
                launchedActivity = true;
                try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                L.i("launch_bg: started " + targetCmp);
            }
        }
        if (launchedActivity) restoreFrontTask(frontHit);
    }

    private static String findAudioEffectReceiver(String pkg) {
        String dump = SysSettings.exec("dumpsys", "package", pkg);
        boolean inSection = false;
        Pattern p = Pattern.compile("\\S+\\s+(" + Pattern.quote(pkg) + "/\\S+)");
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.equals("android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION:")) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (t.isEmpty() || (t.endsWith(":") && !t.contains(pkg))) {
                    inSection = false;
                    continue;
                }
                Matcher m = p.matcher(t);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private static String lastComponentLine(String out, String pkg) {
        String found = null;
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.startsWith(pkg + "/")) found = t;
        }
        return found;
    }

    private static void restoreFrontTask(TaskHit hit) {
        try {
            initAtm();
            if (hit.taskId > 0) {
                if (moveTask != null) {
                    Class<?>[] pts = moveTask.getParameterTypes();
                    Object[] args = new Object[pts.length];
                    for (int i = 0; i < pts.length; i++) {
                        if (pts[i] == String.class) args[i] = "com.android.shell";
                        else if (pts[i] == int.class) args[i] = (i == 2 ? hit.taskId : 0);
                        else args[i] = null;
                    }
                    moveTask.invoke(atm, args);
                    return;
                }
                if (setFocusedTask != null) {
                    setFocusedTask.invoke(atm, hit.taskId);
                    return;
                }
            }
        } catch (Throwable t) {
            L.e("moveTaskToFront", t);
        }
        if (!hit.cmp.isEmpty()) {
            SysSettings.exec("am", "start", "--activity-no-animation", "--activity-reorder-to-front", "-n", hit.cmp);
        }
    }

    /** True if any process of the app (main or ":sub" process) is alive. */
    public static boolean running(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        File[] files = new File("/proc").listFiles();
        if (files == null) return false;
        byte[] buf = new byte[160];
        for (File f : files) {
            if (!f.getName().matches("\\d+")) continue;
            try (FileInputStream in = new FileInputStream(new File(f, "cmdline"))) {
                int n = in.read(buf);
                if (n <= 0) continue;
                int end = 0;
                while (end < n && buf[end] != 0) end++;
                String name = new String(buf, 0, end);
                if (name.equals(pkg) || name.startsWith(pkg + ":")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean musicPlaying() {
        return SysSettings.exec("dumpsys", "audio").contains("state:started");
    }

    /**
     * For "Only if not already done": true when this action would change nothing
     * (setting already at its value, app already running/closed, music already playing/paused).
     */
    public static boolean alreadyDone(JSONObject a) {
        String type = a.optString("type");
        try {
            switch (type) {
                case "launch_bg": {
                    JSONArray apps = a.optJSONArray("apps");
                    if (apps == null || apps.length() == 0) return true;
                    for (int i = 0; i < apps.length(); i++) if (!running(apps.optString(i))) return false;
                    return true;
                }
                case "open_app": {
                    String pkg = a.optString("pkg");
                    if (pkg.isEmpty()) {
                        JSONArray apps = a.optJSONArray("apps");
                        if (apps != null && apps.length() > 0) pkg = apps.optString(0);
                    }
                    return running(pkg);
                }
                case "close_app": {
                    JSONArray apps = a.optJSONArray("apps");
                    if (apps == null) return true;
                    for (int i = 0; i < apps.length(); i++) if (running(apps.optString(i))) return false;
                    return true;
                }
                case "media_control": {
                    String v = a.optString("value", "play_pause");
                    if ("play".equals(v)) return musicPlaying();
                    if ("pause".equals(v) || "stop".equals(v)) return !musicPlaying();
                    return false;
                }
                default: {
                    String cur = read(type);
                    String target = targetValue(type, a);
                    return cur != null && target != null && cur.equals(target);
                }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public static int pidof(String pkg) {
        File[] files = new File("/proc").listFiles();
        if (files == null) return 0;
        byte[] buf = new byte[128];
        for (File f : files) {
            String name = f.getName();
            if (!name.matches("\\d+")) continue;
            try (FileInputStream in = new FileInputStream(new File(f, "cmdline"))) {
                int n = in.read(buf);
                if (n <= 0) continue;
                int end = 0;
                while (end < n && buf[end] != 0) end++;
                if (end == pkg.length() && new String(buf, 0, end).equals(pkg)) return Integer.parseInt(name);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static void initAtm() throws Exception {
        if (atm != null) return;
        atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        focusedTask = atm.getClass().getMethod("getFocusedRootTaskInfo");
        for (Method m : atm.getClass().getMethods()) {
            if (m.getName().equals("moveTaskToFront") && m.getParameterCount() >= 4) { moveTask = m; break; }
            if (m.getName().equals("setFocusedRootTask") && m.getParameterCount() == 1) setFocusedTask = m;
        }
    }

    public static TaskHit frontTask() {
        try {
            initAtm();
            Object info = focusedTask.invoke(atm);
            if (info == null) return new TaskHit("", "", -1);
            ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
            int id = info.getClass().getField("taskId").getInt(info);
            if (top == null) return new TaskHit("", "", -1);
            return new TaskHit(top.getPackageName(), top.flattenToShortString(), id);
        } catch (Throwable t) {
            return new TaskHit("", "", -1);
        }
    }

    private static void initWmPm() throws Exception {
        if (wm != null && pm != null) return;
        Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
        wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, get.invoke(null, "window"));
        pm = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, get.invoke(null, "power"));
        keyguard = wm.getClass().getMethod("isKeyguardLocked");
        interactive = pm.getClass().getMethod("isInteractive");
    }

    public static boolean interactive() {
        try {
            initWmPm();
            return (boolean) interactive.invoke(pm);
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean locked() {
        try {
            initWmPm();
            return (boolean) keyguard.invoke(wm);
        } catch (Throwable t) {
            return false;
        }
    }
}
