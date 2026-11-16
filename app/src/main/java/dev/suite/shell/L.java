package dev.suite.shell;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shell-side log: kept in memory (shown in the app) and mirrored to logcat. No files.
 * `adb logcat -s Suite.cts Suite.fi`
 */
public final class L {
    static String tag = "Suite";
    private static final int MAX = 60;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static void i(String msg) {
        android.util.Log.i(tag, msg);
        synchronized (lines) {
            lines.addLast(fmt.format(new Date()) + " " + msg);
            while (lines.size() > MAX) lines.removeFirst();
        }
    }

    static void e(String msg, Throwable t) {
        android.util.Log.w(tag, msg, t);
        i(msg + ": " + t);
    }

    static List<String> recent(int n) {
        synchronized (lines) {
            List<String> all = new ArrayList<>(lines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    private L() {}
}
