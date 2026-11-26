package dev.suite.shell;

import android.os.Binder;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;

import dev.suite.ISolCubeService;

/**
 * Shell-side (UID 2000) daemon that hides the lock screen top bar ("Lock screen status bar")
 * using StatusBarManager.DISABLE_SYSTEM_INFO (0x00100000) while locked or screen-off.
 */
public class StatusBarUserService extends ISolCubeService.Stub {

    public static final int DISABLE_SYSTEM_INFO = 0x00100000;

    private static final String TAG_PKG = "com.android.shell";

    private final Binder token = new Binder();
    private final Object lock = new Object();

    private volatile boolean dead;
    private volatile boolean screenPushed;   // SolCube pushes screen on/off/unlock (see command)
    private volatile boolean hideLock;
    private volatile int lastFlags = -1;
    private volatile boolean lastLocked;
    private volatile Thread watcher;

    public StatusBarUserService() {
        L.tag = "SolCube.sb";
        AppKeeper.watchInstalled(this::destroy);
        L.i("started");
    }

    @Override
    public void configure(String json) {
        if (dead) return;
        try {
            JSONObject o = (json == null || json.isEmpty()) ? new JSONObject() : new JSONObject(json);
            hideLock = o.optBoolean("hideLock", true);
            synchronized (lock) {
                recomputeLocked(false);
                if (hideLock && !dead) {
                    if (watcher == null || !watcher.isAlive()) {
                        Thread t = new Thread(this::watchLoop, "sb-loop");
                        t.setDaemon(true);
                        watcher = t;
                        t.start();
                    }
                } else {
                    Thread t = watcher;
                    watcher = null;
                    if (t != null) t.interrupt();
                }
            }
        } catch (Throwable t) {
            L.e("configure", t);
        }
    }

    @Override
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", !dead);
            o.put("running", !dead);   // same key as the other services (Diagnostics)
            o.put("hideLock", hideLock);
            o.put("flags", lastFlags);
            o.put("locked", lastLocked);
            JSONArray log = new JSONArray();
            for (String line : L.recent(20)) log.put(line);
            o.put("log", log);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    @Override
    public String command(String cmd) {
        if (dead) return "";
        if (cmd.startsWith("screen ")) {   // pushed by SolCube on screen on/off/unlock
            screenPushed = true;
            synchronized (lock) {
                recomputeLocked(false);
            }
            Thread t = watcher;
            if (t != null) t.interrupt();   // wakes the loop so it picks its new pace
            return "ok";
        }
        if ("reapply".equals(cmd)) {
            synchronized (lock) {
                recomputeLocked(true);
            }
            return "ok";
        }
        return "";
    }

    @Override
    public void destroy() {
        if (dead) return;
        dead = true;
        Thread t;
        synchronized (lock) {
            t = watcher;
            watcher = null;
        }
        if (t != null) t.interrupt();
        try {
            applyFlags(0);
        } catch (Throwable e) {
            L.e("destroy applyFlags", e);
        }
        L.i("stopped");
        System.exit(0);
    }

    private void watchLoop() {
        while (!dead && hideLock && Thread.currentThread() == watcher) {
            boolean locked;
            boolean interactive;
            synchronized (lock) {
                locked = recomputeLocked(false);
                interactive = isInteractiveNow();
            }
            // Fast only while the lock screen is showing (unlock must bring the icons back at once);
            // screen off or unlocked only needs to catch the next lock, 1 s is plenty.
            // Once SolCube pushes unlock events, the lock screen needs only a slower safety poll.
            long delay = (interactive && locked) ? (screenPushed ? 600L : 150L) : 1000L;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ignored) {
                if (dead || Thread.currentThread() != watcher) break;
            }
        }
    }

    private boolean recomputeLocked(boolean force) {
        if (dead) return false;
        boolean locked = hideLock && (!isInteractiveNow() || isKeyguardLockedNow());
        lastLocked = locked;
        int flags = locked ? DISABLE_SYSTEM_INFO : 0;
        if (force || flags != lastFlags) {
            applyFlags(flags);
            lastFlags = flags;
            L.i("flags=0x" + Integer.toHexString(flags) + " locked=" + locked);
        }
        return locked;
    }

    private void applyFlags(int flags) {
        try {
            Object svc = getServiceInterface("statusbar", "com.android.internal.statusbar.IStatusBarService$Stub");
            if (svc != null) {
                try {
                    Method m = svc.getClass().getMethod("disable", int.class, IBinder.class, String.class);
                    m.invoke(svc, flags, token, TAG_PKG);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // Fall through to disableForUser
                }
                try {
                    Method m = svc.getClass().getMethod("disableForUser", int.class, IBinder.class, String.class, int.class);
                    m.invoke(svc, flags, token, TAG_PKG, 0);
                    return;
                } catch (NoSuchMethodException ignored) {
                    // Fall through to cmd statusbar
                }
            }
        } catch (Throwable t) {
            L.e("disable binder", t);
        }
        applyFlagsFallbackCmd(flags);
    }

    private static void applyFlagsFallbackCmd(int flags) {
        try {
            SysSettings.exec("cmd", "statusbar", "send-disable-flag",
                flags != 0 ? "system-icons" : "none");
        } catch (Throwable t) {
            L.e("disable cmd", t);
        }
    }

    private static boolean isKeyguardLockedNow() {
        try {
            Object wm = getServiceInterface("window", "android.view.IWindowManager$Stub");
            if (wm != null) {
                Method m = wm.getClass().getMethod("isKeyguardLocked");
                Object res = m.invoke(wm);
                if (res instanceof Boolean) return (Boolean) res;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isInteractiveNow() {
        try {
            Object pm = getServiceInterface("power", "android.os.IPowerManager$Stub");
            if (pm != null) {
                Method m = pm.getClass().getMethod("isInteractive");
                Object res = m.invoke(pm);
                if (res instanceof Boolean) return (Boolean) res;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static Object getServiceInterface(String serviceName, String stubClassName) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, serviceName);
        if (binder == null) return null;
        Class<?> stub = Class.forName(stubClassName);
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }
}
