package dev.suite.shell;

import dev.suite.ISuiteService;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell-side CPU & GPU temp and load reader ("dev.suite:temp"). On Android 17 / OriginOS 7,
 * /proc/stat and /sys/class/thermal are blocked by SELinux for regular apps, while shell can
 * read /proc/stat, /sys/class/kgsl/kgsl-3d0/gpubusy, and `dumpsys thermalservice`.
 */
public class TempUserService extends ISuiteService.Stub {
    private static final Pattern HAL_TEMP = Pattern.compile("mValue=([0-9.]+),\\s*mType=(\\d+)");

    private long prevTotal = -1, prevIdle = -1;
    private int lastCpu = -1, lastGpu = -1;
    private int gpuEmptyStreak = 0;
    private int lastCpuTemp = -1, lastGpuTemp = -1;

    public TempUserService() {
        L.tag = "Suite.temp";
        readCpu();   // seed baseline jiffies for the first delta
    }

    private int readCpu() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null) return lastCpu;
            String[] p = line.trim().split("\\s+");
            if (p.length < 5 || !"cpu".equals(p[0])) return lastCpu;
            long user = Long.parseLong(p[1]);
            long nice = Long.parseLong(p[2]);
            long sys = Long.parseLong(p[3]);
            long idle = Long.parseLong(p[4]);
            long iowait = p.length > 5 ? Long.parseLong(p[5]) : 0;
            long irq = p.length > 6 ? Long.parseLong(p[6]) : 0;
            long softirq = p.length > 7 ? Long.parseLong(p[7]) : 0;
            long steal = p.length > 8 ? Long.parseLong(p[8]) : 0;
            // guest & guest_nice (9..10) are already included in user/nice on Linux
            long idleAll = idle + iowait;
            long total = user + nice + sys + idleAll + irq + softirq + steal;
            if (prevTotal < 0) {
                prevTotal = total;
                prevIdle = idleAll;
                return -1;
            }
            long dt = total - prevTotal;
            long di = idleAll - prevIdle;
            if (dt <= 0) return lastCpu;
            prevTotal = total;
            prevIdle = idleAll;
            long busy = Math.max(0L, dt - Math.max(0L, di));
            int pct = (int) Math.round(busy * 100.0 / dt);
            lastCpu = Math.max(0, Math.min(100, pct));
            return lastCpu;
        } catch (Throwable t) {
            return -1;
        }
    }

    private int readGpu() {
        String s = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (s != null) {
            String[] p = s.trim().split("\\s+");
            if (p.length >= 2) {
                try {
                    long busy = Long.parseLong(p[0]);
                    long total = Long.parseLong(p[1]);
                    if (total > 0) {
                        gpuEmptyStreak = 0;
                        int pct = (int) Math.round(busy * 100.0 / total);
                        lastGpu = Math.max(0, Math.min(100, pct));
                        return lastGpu;
                    }
                    // KGSL resets gpubusy on every read; another reader (e.g. vivo GameCube) can leave
                    // a transient "0 0" window between polls. Hold the last non-zero reading briefly.
                    gpuEmptyStreak++;
                    if (lastGpu > 0 && gpuEmptyStreak <= 2) return lastGpu;
                    lastGpu = 0;
                    return 0;
                } catch (NumberFormatException ignored) {}
            }
        }
        s = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (s != null) {
            String digits = s.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    lastGpu = Math.max(0, Math.min(100, Integer.parseInt(digits)));
                    return lastGpu;
                } catch (NumberFormatException ignored) {}
            }
        }
        s = readFirstLine("/sys/kernel/ged/hal/gpu_utilization");
        if (s != null) {
            String[] p = s.trim().split("\\s+");
            if (p.length >= 1) {
                try {
                    lastGpu = Math.max(0, Math.min(100, Integer.parseInt(p[0])));
                    return lastGpu;
                } catch (NumberFormatException ignored) {}
            }
        }
        lastGpu = -1;
        return -1;
    }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int[] readTemps() {
        int cpuMax = -1;
        int gpuMax = -1;
        boolean inHal = false;
        String dump = SysSettings.exec("dumpsys", "thermalservice");
        for (String line : dump.split("\n")) {
            if (line.contains("Current temperatures from HAL:")) {
                inHal = true;
                continue;
            }
            if (!inHal) continue;
            if (!line.startsWith("\t") && !line.startsWith(" ")) break;
            Matcher m = HAL_TEMP.matcher(line);
            if (!m.find()) continue;
            try {
                int deg = Math.round(Float.parseFloat(m.group(1)));
                int type = Integer.parseInt(m.group(2));
                if (deg < 1 || deg > 150) continue;
                if (type == 0) cpuMax = Math.max(cpuMax, deg);
                else if (type == 1) gpuMax = Math.max(gpuMax, deg);
            } catch (NumberFormatException ignored) {}
        }
        if (cpuMax > 0) lastCpuTemp = cpuMax;
        if (gpuMax > 0) lastGpuTemp = gpuMax;
        return new int[]{cpuMax, gpuMax};
    }

    private synchronized String sample() {
        int c = readCpu();
        int g = readGpu();
        return c + " " + g;
    }

    @Override public synchronized String command(String cmd) {
        if ("load".equals(cmd)) return sample();
        if ("temps".equals(cmd)) {
            int[] t = readTemps();
            return t[0] + " " + t[1];
        }
        if ("all".equals(cmd)) {
            int[] t = readTemps();
            return t[0] + " " + t[1] + " " + readCpu() + " " + readGpu();
        }
        return "";
    }

    @Override public String status() {
        try {
            return new JSONObject()
                    .put("running", true)
                    .put("cpu", lastCpu)
                    .put("gpu", lastGpu)
                    .put("cpuTemp", lastCpuTemp)
                    .put("gpuTemp", lastGpuTemp)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override public void configure(String json) {}

    @Override public void destroy() {
        System.exit(0);
    }
}
