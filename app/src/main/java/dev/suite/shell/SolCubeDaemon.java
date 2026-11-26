package dev.suite.shell;

import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import dev.suite.ISolCubeDaemon;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in uid=2000 supervisor daemon ("solcube_engine"), launched by SolCube over Wireless ADB.
 * Survives app restarts (its process name does not start with "dev.suite:") and spawns/caches the
 * existing *UserService child processes cleanly.
 */
public final class SolCubeDaemon extends ISolCubeDaemon.Stub {
    public static final int PORT = 47323;
    private static final int DESTROY_TX = 16777114;

    private final String secret;
    private final int apkVersion;
    private volatile String apkPath;
    private final String startApk;   // the APK this engine was started from (apkPath may be refreshed)
    private final ConcurrentHashMap<String, IBinder> children = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> procs = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile boolean shuttingDown;

    private SolCubeDaemon(String secret, int apkVersion, String apkPath) {
        this.secret = secret;
        this.apkVersion = apkVersion;
        this.apkPath = apkPath;
        this.startApk = apkPath;
    }

    public static void main(String[] args) {
        if (args.length < 2) System.exit(2);
        String secret = args[0];

        L.tag = "SolCube.eng";
        Looper.prepareMainLooper();

        // Version = fingerprint of the APK path, which changes on every install (the app computes the same).
        String apkPath = resolveApkPath();
        if (apkPath.isEmpty()) {
            L.i("apk path not found");
            System.exit(4);
            return;
        }
        int apkVersion = versionOf(apkPath);

        // Reuse or replace any already-running SolCubeDaemon on 127.0.0.1:47323.
        String ping = askExisting(secret, "PING");
        if (ping != null && ping.startsWith("ok ")) {
            int runningVer = 0;
            try { runningVer = Integer.parseInt(ping.substring(3).trim()); } catch (NumberFormatException ignored) {}
            if (runningVer == apkVersion) {
                askExisting(secret, "ATTACH");
                L.i("existing daemon v" + runningVer + " re-attached");
                System.exit(0);
                return;
            }
            L.i("replacing daemon v" + runningVer + " with v" + apkVersion);
            askExisting(secret, "SHUTDOWN");
            long waitUntil = System.currentTimeMillis() + 2500L;
            while (System.currentTimeMillis() < waitUntil) {
                if (askExisting(secret, "PING") == null) break;
                try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
            }
        }

        // Like Shizuku's starter: anything left from an older engine (a hung engine, or feature
        // processes orphaned by one that crashed) is killed by name before we take over.
        killStale();

        SolCubeDaemon daemon = new SolCubeDaemon(secret, apkVersion, apkPath);
        daemon.startServer();
        grantWriteSecureSettings();
        AppKeeper.watchInstalled(daemon::shutdown);
        daemon.watchUpdates();

        if (!EngineBridge.attachDaemon(secret, daemon, apkVersion, daemon.children)) {
            L.i("initial attach failed; waiting for app ATTACH");
        } else {
            L.i("daemon v" + apkVersion + " attached");
        }

        Looper.loop();
    }

    /** Must match Shell.engineVersion() in the app. */
    static int versionOf(String apkPath) {
        return apkPath.hashCode() & 0x7fffffff;
    }

    private volatile boolean respawning;

    /** SolCube was updated: start an engine from the new APK; it asks us to SHUTDOWN and takes over. */
    private android.os.FileObserver apkObserver;   // field: a collected FileObserver stops watching

    /**
     * SolCube updated or uninstalled. Like Shizuku's ApkChangedObservers: inotify on the APK's FOLDER
     * (the file itself stays open by running processes, so DELETE_SELF wouldn't fire) for "base.apk"
     * being deleted; a slow poll stays as a safety net.
     */
    private void watchUpdates() {
        File dir = new File(startApk).getParentFile();
        if (dir != null) {
            apkObserver = new android.os.FileObserver(dir, android.os.FileObserver.DELETE) {
                @Override public void onEvent(int event, String path) {
                    if ((event & 0x00008000 /* IN_IGNORED */) != 0 || !"base.apk".equals(path)) return;
                    stopWatching();
                    new Thread(SolCubeDaemon.this::onApkChanged, "apk-changed").start();
                }
            };
            apkObserver.startWatching();
        }
        Thread t = new Thread(() -> {
            while (!shuttingDown) {
                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                String now = resolveApkPath();
                if (!now.equals(startApk)) { onApkChanged(); return; }
            }
        }, "update-watch");
        t.setDaemon(true);
        t.start();
    }

    private void onApkChanged() {
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}   // let the install finish
        String now = resolveApkPath();
        if (now.isEmpty()) return;   // uninstalled: AppKeeper.watchInstalled shuts the engine down
        if (now.equals(startApk)) return;
        L.i("SolCube updated: restarting the engine from the new APK");
        respawn();
    }

    /** Kills uid-2000 processes named solcube_engine (not us) or dev.suite:* by scanning /proc. */
    private static void killStale() {
        int self = android.os.Process.myPid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            String n = p.getName();
            if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(n); } catch (NumberFormatException e) { continue; }
            if (pid == self) continue;
            String name = readCmdName(new File(p, "cmdline"));
            if (name == null) continue;
            if (!name.equals("solcube_engine") && !name.startsWith("dev.suite:")) continue;
            if (readUid(new File(p, "status")) != android.os.Process.myUid()) continue;
            android.os.Process.killProcess(pid);
            L.i("killed stale " + name + " (" + pid + ")");
        }
    }

    private static int readUid(File status) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(status), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String readCmdName(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[128];
            int len = in.read(buf);
            if (len <= 0) return null;
            int end = 0;
            while (end < len && buf[end] != 0) end++;
            return new String(buf, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private void respawn() {
        if (respawning || shuttingDown) return;
        respawning = true;
        String now = resolveApkPath();
        if (now.isEmpty()) { respawning = false; return; }
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                    "CLASSPATH=\"$SC_APK\" setsid app_process /system/bin --nice-name=solcube_engine "
                            + "dev.suite.shell.SolCubeDaemon \"$SC_SECRET\" 0 </dev/null >/dev/null 2>&1 &");
            pb.environment().put("SC_APK", now);
            pb.environment().put("SC_SECRET", secret);
            File devNull = new File("/dev/null");
            pb.redirectInput(devNull);
            pb.redirectOutput(devNull);
            pb.redirectError(devNull);
            pb.start().waitFor();
        } catch (Throwable t) {
            respawning = false;
            L.e("respawn", t);
        }
    }

    private static String resolveApkPath() {
        String out = SysSettings.exec("pm", "path", "dev.suite");
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) return t.substring("package:".length()).trim();
        }
        return "";
    }

    private static void grantWriteSecureSettings() {
        new Thread(() -> {
            String out = SysSettings.exec("pm", "grant", "dev.suite", "android.permission.WRITE_SECURE_SETTINGS");
            if (!out.isEmpty()) L.i("pm grant WRITE_SECURE_SETTINGS: " + out.trim());
        }, "grant-wss").start();
    }

    private static String askExisting(String secret, String cmd) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            s.setSoTimeout(1500);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            String nonce = in.readLine();
            if (nonce == null || nonce.isEmpty()) return null;
            String mac = IpcAuth.mac(secret, nonce + " " + cmd);
            out.println(cmd + " " + mac);
            return in.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void startServer() {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
                server = ss;
                while (!shuttingDown) {
                    try (Socket s = ss.accept()) {
                        handleClient(s);
                    } catch (Throwable e) {
                        if (!shuttingDown) L.e("ipc client", e);
                    }
                }
            } catch (Throwable e) {
                if (!shuttingDown) L.e("ipc server failed", e);
            }
        }, "engine-ipc");
        t.setDaemon(true);
        t.start();
    }

    private void handleClient(Socket s) throws Exception {
        s.setSoTimeout(2000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        String nonce = IpcAuth.nonce();
        out.println(nonce);
        String line = in.readLine();
        if (line == null) return;
        int sp = line.indexOf(' ');
        if (sp <= 0) { out.println("err"); return; }
        String cmd = line.substring(0, sp);
        String mac = line.substring(sp + 1).trim();
        String expected = IpcAuth.mac(secret, nonce + " " + cmd);
        if (!IpcAuth.same(mac, expected)) { out.println("err"); return; }

        switch (cmd) {
            case "PING":
                out.println("ok " + apkVersion);
                break;
            case "ATTACH":
                boolean ok = EngineBridge.attachDaemon(secret, this, apkVersion, children);
                out.println(ok ? "ok " + apkVersion : "err");
                break;
            case "UPGRADE":
                out.println("ok");
                new Thread(this::respawn, "engine-upgrade").start();
                break;
            case "SHUTDOWN":
                out.println("ok");
                new Thread(this::shutdown, "engine-stop").start();
                break;
            default:
                out.println("err");
                break;
        }
    }

    // ------------------------------------------------------------------ ISolCubeDaemon

    @Override public int version() {
        return apkVersion;
    }

    @Override public synchronized void spawnService(String className, String suffix) {
        if (shuttingDown) return;
        IBinder existing = getChild(suffix);
        if (existing != null) {
            EngineBridge.attachChild(secret, suffix, existing, apkVersion);
            return;
        }
        Process old = procs.remove(suffix);
        if (old != null && old.isAlive()) old.destroyForcibly();

        try {
            String curApk = apkPath;
            if (curApk == null || curApk.isEmpty() || !new File(curApk).exists()) {
                String fresh = resolveApkPath();
                if (!fresh.isEmpty()) {
                    apkPath = fresh;
                    curApk = fresh;
                }
            }
            ProcessBuilder pb = new ProcessBuilder(
                    "app_process", "/system/bin",
                    "--nice-name=dev.suite:" + suffix,
                    "dev.suite.shell.SolCubeChild",
                    className, suffix, secret, String.valueOf(apkVersion));
            pb.environment().put("CLASSPATH", curApk);
            File devNull = new File("/dev/null");
            pb.redirectInput(devNull);
            pb.redirectOutput(devNull);
            pb.redirectError(devNull);
            Process p = pb.start();
            procs.put(suffix, p);
            L.i("spawned " + suffix + " (" + className + ")");
        } catch (Throwable t) {
            L.e("spawnService " + suffix, t);
        }
    }

    @Override public void cacheChild(String suffix, IBinder binder) {
        if (suffix == null || binder == null || !binder.pingBinder()) return;
        children.put(suffix, binder);
        try {
            binder.linkToDeath(() -> {
                children.remove(suffix, binder);
                procs.remove(suffix);
            }, 0);
        } catch (Throwable ignored) {
            children.remove(suffix, binder);
        }
    }

    @Override public IBinder getChild(String suffix) {
        IBinder b = suffix == null ? null : children.get(suffix);
        if (b == null) return null;
        if (b.pingBinder()) return b;
        children.remove(suffix, b);
        return null;
    }

    @Override public void stopService(String suffix) {
        if (suffix == null) return;
        IBinder b = children.remove(suffix);
        if (b != null && b.pingBinder()) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("dev.suite.ISolCubeService");
                b.transact(DESTROY_TX, data, reply, IBinder.FLAG_ONEWAY);
            } catch (Throwable ignored) {
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        Process p = procs.remove(suffix);
        if (p != null && p.isAlive()) p.destroyForcibly();
    }

    @Override public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        List<String> keys = new ArrayList<>(children.keySet());
        for (String k : keys) stopService(k);
        for (Map.Entry<String, Process> e : procs.entrySet()) {
            Process p = e.getValue();
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
        procs.clear();
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        L.i("daemon shutdown");
        System.exit(0);
    }
}
