package dev.suite.shell;

import android.content.AttributionSource;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

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

    /** Shows SolCube's island pill with this text (SolCube's IslandReceiver draws it). Doesn't block. */
    static void notice(String text, boolean ok) {
        new Thread(() -> {
            // the pill draws in SolCube's accessibility overlay (above the status bar): make sure that's on
            AppKeeper.ensureA11y();
            exec("am", "broadcast", "-n", "dev.suite/.IslandReceiver",
                "--es", "text", text, "--ez", "ok", String.valueOf(ok));
        }, "notice").start();
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
