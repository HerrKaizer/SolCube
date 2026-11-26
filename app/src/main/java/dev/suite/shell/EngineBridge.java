package dev.suite.shell;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.Map;

/** Pushes daemon and child ISolCubeService binders to dev.suite's EngineProvider. */
final class EngineBridge {
    private static final String AUTHORITY = "dev.suite.engine";

    private EngineBridge() {}

    static boolean attachDaemon(String secret, IBinder daemon, int apkVersion, Map<String, IBinder> children) {
        try {
            Bundle b = new Bundle();
            b.putString("secret", secret);
            b.putInt("version", apkVersion);
            b.putBinder("daemon", daemon);
            if (children != null && !children.isEmpty()) {
                Bundle cb = new Bundle();
                for (Map.Entry<String, IBinder> e : children.entrySet()) {
                    IBinder child = e.getValue();
                    if (child != null && child.pingBinder()) cb.putBinder(e.getKey(), child);
                }
                b.putBundle("children", cb);
            }
            Bundle res = callProvider("attach_daemon", null, b);
            return res != null && res.getBoolean("ok", false);
        } catch (Throwable t) {
            L.e("attachDaemon failed", t);
            return false;
        }
    }

    static boolean attachChild(String secret, String suffix, IBinder child, int apkVersion) {
        try {
            Bundle b = new Bundle();
            b.putString("secret", secret);
            b.putInt("version", apkVersion);
            b.putBinder("binder", child);
            Bundle res = callProvider("attach_child", suffix, b);
            return res != null && res.getBoolean("ok", false);
        } catch (Throwable t) {
            L.e("attachChild failed (" + suffix + ")", t);
            return false;
        }
    }

    private static Bundle callProvider(String method, String arg, Bundle extras) throws Exception {
        // Like Shizuku's BinderSender: briefly exempt SolCube from Doze / background limits so the provider
        // call can start or wake its process (after a reboot, or when the app was killed).
        SysSettings.exec("cmd", "deviceidle", "tempwhitelist", "-d", "30000", "dev.suite");
        Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Method get = null;
        for (Method m : am.getClass().getMethods()) {
            if (m.getName().equals("getContentProviderExternal") && m.getParameterCount() == 4) {
                get = m;
                break;
            }
        }
        if (get == null) throw new NoSuchMethodException("getContentProviderExternal");
        IBinder token = new Binder();
        Object holder = get.invoke(am, AUTHORITY, 0, token, "*solcube*");
        if (holder == null) return null;
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            if (provider == null) return null;
            AttributionSource attr = new AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell")
                    .build();
            Method call = null;
            for (Method m : provider.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("call") && p.length == 5 && p[0] == AttributionSource.class) {
                    call = m;
                    break;
                }
            }
            if (call == null) throw new NoSuchMethodException("IContentProvider.call");
            return (Bundle) call.invoke(provider, attr, AUTHORITY, method, arg, extras);
        } finally {
            releaseProvider(am, token);
        }
    }

    private static void releaseProvider(Object am, IBinder token) {
        try {
            for (Method m : am.getClass().getMethods()) {
                if (m.getName().equals("removeContentProviderExternalAsUser") && m.getParameterCount() == 3) {
                    m.invoke(am, AUTHORITY, token, 0);
                    return;
                }
            }
            for (Method m : am.getClass().getMethods()) {
                if (m.getName().equals("removeContentProviderExternal") && m.getParameterCount() == 2) {
                    m.invoke(am, AUTHORITY, token);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
