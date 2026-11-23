package dev.suite.shell;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Keeps SolCube's ripple overlay available while SolCube itself is closed.
 *
 * vivo's cleaner force-stops SolCube when it is swiped away / cleaned, which takes the ripple
 * overlay (in SolCube's own process) with it, while this listener (a Shizuku UserService) keeps
 * running. So the listener checks every few seconds that the ripple answers on its localhost
 * port and, if not, restarts SolCube's service as the shell user
 * (`am start-foreground-service`). SolCube has no notification permission, so nothing shows up
 * in the shade. A finger or power-button press also triggers an immediate check, so a hold
 * right after a clean can still get the animation.
 */
final class AppKeeper {
    static final int PORT = 47321;
    static final long CHECK_MS = 3000, MIN_RESTART_GAP_MS = 2000;
    static final String PKG = "dev.suite";
    static volatile Runnable onUninstall;
    private static volatile long lastRestart = -1_000_000;
    private static volatile boolean checking;
    private static int missingStreak;
    private static Object pm;
    private static java.lang.reflect.Method pmGetPkg;
    private static boolean pmLongFlags, pmBroken;

    static void start() {
        Thread t = new Thread(() -> {
            if (isAppInstalled()) ensureA11y();
            while (true) {
                try { Thread.sleep(CHECK_MS); } catch (InterruptedException e) { return; }
                ensure("watchdog");
            }
        }, "app-keeper");
        t.setDaemon(true);
        t.start();
    }

    /** True if SolCube (`dev.suite`) is currently installed (returns true on transient lookup errors). */
    static boolean isAppInstalled() {
        if (!pmBroken) {
            try {
                Object p;
                java.lang.reflect.Method m;
                boolean longFlags;
                synchronized (AppKeeper.class) {
                    if (pm == null && !pmBroken) {
                        android.os.IBinder b = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                                .getMethod("getService", String.class).invoke(null, "package");
                        if (b == null) return true;
                        Object iface = Class.forName("android.content.pm.IPackageManager$Stub")
                                .getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
                        java.lang.reflect.Method found = null;
                        boolean lf = false;
                        try {
                            found = iface.getClass().getMethod("getPackageInfo", String.class, long.class, int.class);
                            lf = true;
                        } catch (NoSuchMethodException e) {
                            found = iface.getClass().getMethod("getPackageInfo", String.class, int.class, int.class);
                        }
                        pm = iface;
                        pmGetPkg = found;
                        pmLongFlags = lf;
                    }
                    p = pm;
                    m = pmGetPkg;
                    longFlags = pmLongFlags;
                }
                if (p != null && m != null) {
                    Object info = longFlags ? m.invoke(p, PKG, 0L, 0) : m.invoke(p, PKG, 0, 0);
                    return info != null;
                }
            } catch (java.lang.reflect.InvocationTargetException ite) {
                if (ite.getCause() instanceof android.os.RemoteException) {
                    synchronized (AppKeeper.class) { pm = null; }
                    return true;
                }
                synchronized (AppKeeper.class) { pm = null; pmBroken = true; }
            } catch (Throwable t) {
                synchronized (AppKeeper.class) { pm = null; pmBroken = true; }
            }
        }
        try {
            Process p = new ProcessBuilder("pm", "path", PKG).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int rc = p.waitFor();
            if (out.contains("package:") || out.contains("Error") || out.contains("Exception")
                    || out.contains("Can't find")) return true;
            return rc == 0;
        } catch (Throwable t) {
            return true;
        }
    }

    static void watchInstalled(Runnable onRemoved) { watchInstalled(onRemoved, false); }

    /** Stops standalone shell daemons promptly if SolCube itself has been uninstalled. */
    static void watchInstalled(Runnable onRemoved, boolean keepA11y) {
        Thread t = new Thread(() -> {
            if (keepA11y && isAppInstalled()) ensureA11y();
            int missing = 0;
            while (true) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
                if (isAppInstalled()) {
                    missing = 0;
                    if (keepA11y) ensureA11y();
                } else if (++missing >= 2) {
                    L.i("SolCube uninstalled: stopping daemon");
                    if (keepA11y) disableA11y();
                    try { if (onRemoved != null) onRemoved.run(); } catch (Throwable ignored) {}
                    System.exit(0);
                }
            }
        }, "pkg-watch");
        t.setDaemon(true);
        t.start();
    }

    /** Non-blocking: called from the input threads when a finger / the power key goes down. */
    static void kick() {
        if (checking) return;
        Thread t = new Thread(() -> ensure("touch"), "app-kick");
        t.setDaemon(true);
        t.start();
    }

    static boolean rippleAlive() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 120);
            return true;   // the app reads an empty line and closes; nothing is played
        } catch (Exception e) {
            return false;
        }
    }

    static final String A11Y = "dev.suite/dev.suite.SolCubeA11y";

    /** The service's name before the rename (Suite, up to 3.6.7); still listed on updated phones. */
    static boolean oldA11y(String s) {
        return s.equalsIgnoreCase("dev.suite/dev.suite." + "Suite" + "A11y") || s.equalsIgnoreCase("dev.suite/." + "Suite" + "A11y");
    }

    /**
     * Keeps SolCube's accessibility service (used only for the ripple's above-the-status-bar
     * window) in the enabled list. Android drops it when the app is force-stopped.
     */
    static boolean ensureA11y() {
        try {
            boolean changed = false;
            String cur = SysSettings.get("secure", "enabled_accessibility_services");
            if (cur == null || "null".equals(cur)) cur = "";
            boolean listed = false;
            StringBuilder keep = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty() || oldA11y(s)) continue;   // drop the pre-rename entry
                if (s.equalsIgnoreCase(A11Y) || s.equalsIgnoreCase("dev.suite/.SolCubeA11y")) listed = true;
                if (keep.length() > 0) keep.append(':');
                keep.append(s);
            }
            if (!listed) {
                if (keep.length() > 0) keep.append(':');
                keep.append(A11Y);
            }
            if (!keep.toString().equals(cur)) {
                SysSettings.put("secure", "enabled_accessibility_services", keep.toString());
                L.i("accessibility service re-enabled");
                changed = true;
            }
            if (!"1".equals(SysSettings.get("secure", "accessibility_enabled"))) {
                SysSettings.put("secure", "accessibility_enabled", "1");
                changed = true;
            }
            return changed;
        } catch (Throwable t) {
            L.e("a11y enable failed", t);
            return false;
        }
    }

    /** Circle to Search turned off: take the service back out of the enabled list. */
    static void disableA11y() {
        try {
            String cur = SysSettings.get("secure", "enabled_accessibility_services");
            if (cur == null || "null".equals(cur)) return;
            StringBuilder keep = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty() || s.equalsIgnoreCase(A11Y) || s.equalsIgnoreCase("dev.suite/.SolCubeA11y") || oldA11y(s)) continue;
                if (keep.length() > 0) keep.append(':');
                keep.append(s);
            }
            if (!keep.toString().equals(cur)) SysSettings.put("secure", "enabled_accessibility_services", keep.toString());
        } catch (Throwable t) {
            L.e("a11y disable failed", t);
        }
    }

    private static void ensure(String why) {
        if (checking) return;
        checking = true;
        try {
            if (!isAppInstalled()) {
                if ("watchdog".equals(why) && ++missingStreak >= 2) {
                    L.i("SolCube uninstalled: cleaning up and exiting");
                    Runnable r = onUninstall;
                    if (r != null) r.run();
                    else disableA11y();
                    System.exit(0);
                }
                return;
            }
            missingStreak = 0;
            boolean reenabled = ensureA11y();
            if (!CtsListener.animate && !CtsListener.navEnabled) return;   // instant mode without nav hold needs no ripple server/overlay
            if (rippleAlive()) return;
            if (reenabled) {
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                if (rippleAlive()) return;
            }
            long now = CtsListener.now();
            if (now - lastRestart < MIN_RESTART_GAP_MS) return;
            lastRestart = now;
            L.i("ripple not answering (" + why + "), rebinding accessibility service");
            disableA11y();
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            ensureA11y();
        } catch (Exception e) {
            L.e("restart failed", e);
        } finally {
            checking = false;
        }
    }

    private AppKeeper() {}
}
