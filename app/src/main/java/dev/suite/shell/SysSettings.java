package dev.suite.shell;

import android.content.AttributionSource;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * Reads and writes system/global/secure settings as the shell user, in-process: the same
 * binder calls `settings get` and `content insert` make, without spawning a process each time.
 * Writes use insert (like `content insert`), because on OriginOS 7 `settings put` silently
 * does nothing. Falls back to running the commands if the in-process route fails.
 */
final class SysSettings {
    private static Object provider;
    private static Object attr;
    private static Method call, insert;
    private static boolean broken;

    private static synchronized boolean init() {
        if (provider != null) return true;
        if (broken) return false;
        try {
            Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Method get = null;
            for (Method m : am.getClass().getMethods()) {
                if (m.getName().equals("getContentProviderExternal") && m.getParameterCount() == 4) get = m;
            }
            Object holder = get.invoke(am, "settings", 0, new Binder(), "*cmd*");
            provider = holder.getClass().getField("provider").get(holder);
            attr = new AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell").build();
            for (Method m : provider.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("call") && p.length == 5 && p[0] == AttributionSource.class) call = m;
                if (m.getName().equals("insert") && p.length == 4 && p[0] == AttributionSource.class) insert = m;
            }
            if (call == null || insert == null) throw new NoSuchMethodException("call/insert");
            L.i("settings: in-process provider ready");
            return true;
        } catch (Throwable t) {
            broken = true;
            provider = null;
            L.e("settings: in-process route unavailable, using commands", t);
            return false;
        }
    }

    /** `settings get <table> <key>` */
    static String get(String table, String key) {
        if (init()) {
            try {
                Bundle args = new Bundle();
                args.putInt("_user", 0);
                Bundle b = (Bundle) call.invoke(provider, attr, "settings", "GET_" + table, key, args);
                return b == null ? "null" : String.valueOf(b.getString("value"));
            } catch (Throwable t) {
                L.e("settings get failed", t);
            }
        }
        return exec("settings", "get", table, key);
    }

    /** `content insert --uri content://settings/<table> --bind name:s:<key> --bind value:s:<value>` */
    static void put(String table, String key, String value) {
        if (init()) {
            try {
                ContentValues cv = new ContentValues();
                cv.put("name", key);
                cv.put("value", value);
                insert.invoke(provider, attr, Uri.parse("content://settings/" + table), cv, null);
                return;
            } catch (Throwable t) {
                L.e("settings insert failed", t);
            }
        }
        exec("content", "insert", "--uri", "content://settings/" + table,
                "--bind", "name:s:" + key, "--bind", "value:s:" + value.replace(":", "\\:"));
    }

    static final int NOTICE_PORT = 47322;
    private static final ExecutorService NOTICE_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notice");
        t.setDaemon(true);
        return t;
    });
    private static volatile String noticeSecret = "";

    static void setNoticeSecret(String s) {
        if (s != null && !s.isEmpty()) noticeSecret = s;
    }

    private static String sendIpcOnce(JSONObject payload, int readTimeoutMs) {
        String key = noticeSecret;
        if (key == null || key.isEmpty()) return null;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", NOTICE_PORT), 200);
            s.setSoTimeout(readTimeoutMs);
            s.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            String hello = in.readLine();
            if (hello == null || !hello.startsWith("hello ")) return null;
            String ns = hello.substring(6).trim();
            String nc = IpcAuth.nonce();
            out.write(("auth " + nc + " " + IpcAuth.mac(key, "client|" + ns + "|" + nc) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp = in.readLine();
            if (resp == null || !resp.startsWith("auth ")
                    || !IpcAuth.same(resp.substring(5).trim(), IpcAuth.mac(key, "server|" + nc + "|" + ns))) {
                L.i("notice port rejected handshake");
                return null;
            }
            out.write((payload.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return in.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sendIpcWithWake(JSONObject payload, int readTimeoutMs) {
        String resp = sendIpcOnce(payload, readTimeoutMs);
        if (resp != null) return resp;
        String key = noticeSecret;
        if (key == null || key.isEmpty()) return null;
        boolean reenabled = AppKeeper.ensureA11y();
        int retries = reenabled ? 3 : 1;
        for (int i = 0; i < retries; i++) {
            try { Thread.sleep(350); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            resp = sendIpcOnce(payload, readTimeoutMs);
            if (resp != null) return resp;
        }
        return null;
    }

    private static boolean sendNoticeSocket(String text, boolean ok) {
        try {
            return "ok".equals(sendIpcOnce(new JSONObject().put("text", text).put("ok", ok), 1500));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sends OPEN_AUDIO_EFFECT_CONTROL_SESSION via SolCube's app context (or am broadcast fallback). */
    static boolean sendAudioEffectBroadcast(String component, String frontPkg) {
        try {
            JSONObject payload = new JSONObject()
                    .put("cmd", "broadcast")
                    .put("component", component)
                    .put("action", "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION")
                    .put("pkg", frontPkg == null ? "" : frontPkg)
                    .put("audioSession", 0);
            if ("ok".equals(sendIpcWithWake(payload, 1500))) return true;
        } catch (Throwable ignored) {}
        String out = exec("am", "broadcast", "-f", "0x01000020", "-n", component,
                "-a", "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION",
                "--ei", "android.media.extra.AUDIO_SESSION", "0",
                "--es", "android.media.extra.PACKAGE_NAME", frontPkg == null ? "" : frontPkg);
        return !out.contains("skip Broadcasting");
    }

    static String getFeature(String feature) {
        try {
            String resp = sendIpcWithWake(
                    new JSONObject().put("cmd", "get_feature").put("feature", feature), 2000);
            if (resp != null && resp.startsWith("ok:")) {
                String val = resp.substring(3);
                return val.isEmpty() ? null : val;
            }
        } catch (Throwable ignored) {}
        String out = exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.GET_FEATURE",
                "--es", "feature", feature);
        Matcher m = Pattern.compile("data=\"([^\"]*)\"").matcher(out);
        if (m.find()) {
            String val = m.group(1);
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    static void setFeature(String feature, String value) {
        boolean on = "on".equals(value);
        try {
            String resp = sendIpcWithWake(new JSONObject()
                    .put("cmd", "set_feature")
                    .put("feature", feature)
                    .put("value", value)
                    .put("on", on), 5000);
            if ("ok".equals(resp)) return;
        } catch (Throwable ignored) {}
        exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.SET_FEATURE",
                "--es", "feature", feature, "--es", "value", value,
                "--ez", "on", String.valueOf(on));
    }

    static boolean triggerCts() {
        try {
            String resp = sendIpcWithWake(new JSONObject().put("cmd", "trigger_cts"), 2500);
            if (resp != null && resp.startsWith("ok:")) return "ok:ok".equals(resp);
        } catch (Throwable ignored) {}
        String out = exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.TRIGGER_CTS");
        return out.contains("data=\"ok\"");
    }

    static void runRoutine(String id) {
        try {
            String resp = sendIpcWithWake(new JSONObject().put("cmd", "rt_run").put("id", id), 2000);
            if ("ok".equals(resp)) return;
        } catch (Throwable ignored) {}
        exec("am", "broadcast", "-n", "dev.suite/.TestReceiver",
                "-a", "dev.suite.RT_RUN", "--es", "id", id);
    }

    static boolean triggerRoutine(String triggerType) {
        try {
            String resp = sendIpcWithWake(
                    new JSONObject().put("cmd", "rt_trigger").put("trigger", triggerType), 5000);
            if (resp != null && resp.startsWith("ok:")) return "ok:matched".equals(resp);
        } catch (Throwable ignored) {}
        String out = exec("am", "broadcast", "-f", "0x11000020",
                "-n", "dev.suite/.TestReceiver", "-a", "dev.suite.RT_TRIGGER",
                "--es", "trigger", triggerType);
        return out != null && out.contains("data=\"matched\"");
    }

    /** Delivers a status pill synchronously via authenticated localhost IPC, with broadcast fallback. */
    static boolean sendNoticeNow(String text, boolean ok) {
        boolean reenabled = AppKeeper.ensureA11y();
        if (sendNoticeSocket(text, ok)) return true;
        String key = noticeSecret;
        if (key != null && !key.isEmpty()) {
            int retries = reenabled ? 3 : 1;
            for (int i = 0; i < retries; i++) {
                try { Thread.sleep(350); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (sendNoticeSocket(text, ok)) return true;
            }
        }
        String out = exec("am", "broadcast", "-n", "dev.suite/.IslandReceiver",
                "--es", "text", text, "--ez", "ok", String.valueOf(ok));
        if (out.contains("skip Broadcasting") || out.contains("Exception") || out.contains("Error")) {
            L.i("notice fallback failed (" + text + "): " + out.replace('\n', ' ').trim());
            return false;
        }
        return true;
    }

    /** Shows SolCube's island pill with this text. Preserves call order without blocking. */
    static void notice(String text, boolean ok) {
        NOTICE_EXEC.execute(() -> sendNoticeNow(text, ok));
    }

    static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            L.e("exec failed: " + String.join(" ", cmd), e);
            return "";
        }
    }

    private SysSettings() {}
}
