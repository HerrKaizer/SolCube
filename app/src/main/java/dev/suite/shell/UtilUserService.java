package dev.suite.shell;

import android.content.ComponentName;
import android.content.IntentFilter;
import android.os.IBinder;

import dev.suite.ISuiteService;

import java.lang.reflect.Method;

/**
 * One-off shell-user jobs ("dev.suite:util"), started when needed and stopped right after.
 *   prefer-double <component> <set,comma,separated>
 *       makes <component> the "always" handler of vivo's double-power-press intent
 *       (vivo.intent.action.NFC_BUS_SWINGSPLASH), like picking it with "Always" in the chooser.
 */
public class UtilUserService extends ISuiteService.Stub {
    public UtilUserService() { L.tag = "Suite.util"; }

    private static volatile Thread session;

    @Override public String command(String cmd) {
        try {
            if (cmd.startsWith("put ")) {   // put <table> <key> <value>
                String[] p = cmd.split(" ");
                SysSettings.put(p[1], p[2], p[3]);
                L.i("put " + p[1] + " " + p[2] + " = " + p[3]);
                return "ok";
            }
            if (cmd.equals("cleanup-old-backup")) {
                // 2.1.1–2.1.2 saved all settings (routines, Wi-Fi names, apps) to public Downloads
                java.io.File f = new java.io.File("/sdcard/Download/Suite/suite-backup.json");
                if (f.exists() && !f.delete()) return "failed";
                java.io.File dir = f.getParentFile();
                String[] left = dir == null ? null : dir.list();
                if (left != null && left.length == 0) dir.delete();
                // drop the media index entry too, so it doesn't linger in file pickers
                SysSettings.exec("content", "delete", "--uri", "content://media/external/file",
                        "--where", "_display_name='suite-backup.json'");
                L.i("old public backup removed");
                return "ok";
            }
            if (cmd.equals("cts")) {   // Circle to Search straight away (no ripple: the listener isn't running)
                return CtsTrigger.trigger(CtsTrigger.service(), 1) ? "ok" : "failed";
            }
            if (cmd.startsWith("ringer ")) {   // ringer normal|vibrate|silent
                String m = cmd.substring(7).trim().toUpperCase();
                SysSettings.exec("cmd", "audio", "set-ringer-mode", m);
                L.i("ringer " + m);
                return "ok";
            }
            if (cmd.startsWith("allow-dnd ")) {   // Do Not Disturb access for Suite (instant ring changes)
                SysSettings.exec("cmd", "notification", "allow_dnd", cmd.substring(10).trim());
                return "ok";
            }
            if (cmd.startsWith("nfc-session ")) {
                String pkg = cmd.substring(12).trim();
                if (session != null && session.isAlive()) return "ok";   // already watching
                session = new Thread(() -> nfcSession(pkg), "nfc-session");
                session.start();
                return "ok";
            }
            if (cmd.startsWith("prefer-double ")) {
                String[] p = cmd.split(" ");
                ComponentName me = ComponentName.unflattenFromString(p[1]);
                String[] parts = p[2].split(",");
                ComponentName[] set = new ComponentName[parts.length];
                for (int i = 0; i < parts.length; i++) set[i] = ComponentName.unflattenFromString(parts[i]);

                IntentFilter f = new IntentFilter("vivo.intent.action.NFC_BUS_SWINGSPLASH");
                f.addCategory("android.intent.category.DEFAULT");
                int match = IntentFilter.MATCH_CATEGORY_EMPTY | IntentFilter.MATCH_ADJUSTMENT_NORMAL;

                IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "package");
                Object ipm = Class.forName("android.content.pm.IPackageManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
                // drop older "always" choices (e.g. the Double Power Key app), then set ours
                for (ComponentName c : set) {
                    if (c.equals(me)) continue;
                    try {
                        ipm.getClass().getMethod("clearPackagePreferredActivities", String.class).invoke(ipm, c.getPackageName());
                    } catch (Throwable t) {
                        L.e("clear " + c.getPackageName(), t);
                    }
                }
                Method replace = null;
                for (Method m : ipm.getClass().getMethods()) if (m.getName().equals("replacePreferredActivity")) replace = m;
                if (replace == null) return "no replacePreferredActivity";
                replace.invoke(ipm, f, match, set, me, 0);
                L.i("double press → " + me.flattenToShortString());
                return "ok";
            }
        } catch (Throwable t) {
            L.e(cmd, t);
            return String.valueOf(t.getCause() != null ? t.getCause() : t);
        }
        return "";
    }

    /**
     * NFC on now; off again as soon as <pkg> stops being the app in front (~0.4 s; still in
     * recents doesn't count; it gets 10 s to show up first), or once the phone has been locked
     * over it for 3 min. No limit while it's on screen and unlocked. Left alone if NFC was
     * already on. Exits the process when done.
     */
    private static void nfcSession(String pkg) {
        boolean wasOn = nfcOn();
        if (!wasOn) { SysSettings.exec("svc", "nfc", "enable"); L.i("NFC on for " + pkg); SysSettings.notice("NFC on", true); }
        else L.i("NFC was already on");
        try {
            long start = System.currentTimeMillis(), goneSince = 0, lockedSince = 0;
            boolean seen = false;
            while (true) {
                // checked 5x a second so NFC goes off right when the app is left (user: "instant")
                Thread.sleep(200);
                String fp = frontPackage();
                boolean front = pkg.equals(fp);
                long now = System.currentTimeMillis();
                if (front) {
                    seen = true;
                    goneSince = 0;
                    // on screen and unlocked: no limit; locked (screen off / lock screen) over it: 3 min
                    if (!locked()) { lockedSince = 0; continue; }
                    if (lockedSince == 0) lockedSince = now;
                    if (now - lockedSince >= 3 * 60_000L) { L.i("locked for 3 min"); break; }
                    continue;
                }
                if (!seen) { if (now - start > 10_000) break; continue; }
                // two misses in a row (~0.4 s) so a one-frame focus blip doesn't cut NFC mid-payment
                if (goneSince == 0) goneSince = now;
                else if (now - goneSince >= 200) break;
            }
        } catch (InterruptedException ignored) {}
        if (!wasOn) {
            SysSettings.exec("svc", "nfc", "disable");
            L.i("NFC off (" + pkg + " closed)");
            // notice() runs on its own thread: send it here, before the process exits
            SysSettings.exec("am", "broadcast", "-n", "dev.suite/.IslandReceiver", "--es", "text", "NFC off", "--ez", "ok", "true");
        }
        System.exit(0);
    }

    private static boolean nfcOn() {
        for (String l : SysSettings.exec("dumpsys", "nfc").split("\n")) {
            String t = l.trim();
            if (t.startsWith("mState=")) return t.equals("mState=on") || t.equals("mState=turning on");
        }
        return false;
    }

    private static Object wm, pm;
    private static Method keyguard, interactive;

    /** Screen off or lock screen showing. */
    private static boolean locked() {
        try {
            if (wm == null) {
                Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
                wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, get.invoke(null, "window"));
                pm = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, get.invoke(null, "power"));
                keyguard = wm.getClass().getMethod("isKeyguardLocked");
                interactive = pm.getClass().getMethod("isInteractive");
            }
            return (boolean) keyguard.invoke(wm) || !(boolean) interactive.invoke(pm);
        } catch (Throwable t) {
            L.e("lock state", t);
            return false;
        }
    }

    private static Object atm;
    private static Method focused;

    /** Package of the focused task's top activity. */
    private static String frontPackage() {
        try {
            if (atm == null) {
                atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                focused = atm.getClass().getMethod("getFocusedRootTaskInfo");
            }
            Object info = focused.invoke(atm);
            if (info == null) return "";
            ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
            return top == null ? "" : top.getPackageName();
        } catch (Throwable t) {
            L.e("front app", t);
            return "";
        }
    }

    @Override public void destroy() { System.exit(0); }
    @Override public String status() { return "{\"running\":true}"; }
    @Override public void configure(String json) {}
}
