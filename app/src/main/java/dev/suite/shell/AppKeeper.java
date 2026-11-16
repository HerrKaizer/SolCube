package dev.suite.shell;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Keeps Suite's ripple overlay available while Suite itself is closed.
 *
 * vivo's cleaner force-stops Suite when it is swiped away / cleaned, which takes the ripple
 * overlay (in Suite's own process) with it, while this listener (a Shizuku UserService) keeps
 * running. So the listener checks every few seconds that the ripple answers on its localhost
 * port and, if not, restarts Suite's service as the shell user
 * (`am start-foreground-service`). Suite has no notification permission, so nothing shows up
 * in the shade. A finger or power-button press also triggers an immediate check, so a hold
 * right after a clean can still get the animation.
 */
final class AppKeeper {
    static final int PORT = 47321;
    static final long CHECK_MS = 3000, MIN_RESTART_GAP_MS = 2000;
    private static volatile long lastRestart = -1_000_000;
    private static volatile boolean checking;

    static void start() {
        Thread t = new Thread(() -> {
            ensureA11y();
            while (true) {
                try { Thread.sleep(CHECK_MS); } catch (InterruptedException e) { return; }
                ensure("watchdog");
            }
        }, "app-keeper");
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

    static final String A11Y = "dev.suite/dev.suite.SuiteA11y";

    /**
     * Keeps Suite's accessibility service (used only for the ripple's above-the-status-bar
     * window) in the enabled list. Android drops it when the app is force-stopped.
     */
    static void ensureA11y() {
        try {
            String cur = SysSettings.get("secure", "enabled_accessibility_services");
            if (cur == null || "null".equals(cur)) cur = "";
            boolean listed = false;
            for (String s : cur.split(":")) {
                if (s.equalsIgnoreCase(A11Y) || s.equalsIgnoreCase("dev.suite/.SuiteA11y")) listed = true;
            }
            if (!listed) {
                SysSettings.put("secure", "enabled_accessibility_services", cur.isEmpty() ? A11Y : cur + ":" + A11Y);
                L.i("accessibility service re-enabled");
            }
            if (!"1".equals(SysSettings.get("secure", "accessibility_enabled"))) {
                SysSettings.put("secure", "accessibility_enabled", "1");
            }
        } catch (Throwable t) {
            L.e("a11y enable failed", t);
        }
    }

    /** Circle to Search turned off: take the service back out of the enabled list. */
    static void disableA11y() {
        try {
            String cur = SysSettings.get("secure", "enabled_accessibility_services");
            if (cur == null || "null".equals(cur)) return;
            StringBuilder keep = new StringBuilder();
            for (String s : cur.split(":")) {
                if (s.isEmpty() || s.equalsIgnoreCase(A11Y) || s.equalsIgnoreCase("dev.suite/.SuiteA11y")) continue;
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
            ensureA11y();
            if (rippleAlive()) return;
            long now = CtsListener.now();
            if (now - lastRestart < MIN_RESTART_GAP_MS) return;
            lastRestart = now;
            L.i("ripple not answering (" + why + "), restarting Suite's service");
            Process p = new ProcessBuilder("am", "start-foreground-service", "-n", "dev.suite/.SuiteService")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (out.contains("Error") || out.contains("Exception")) L.i("restart: " + out);
        } catch (Exception e) {
            L.e("restart failed", e);
        } finally {
            checking = false;
        }
    }

    private AppKeeper() {}
}
