/**
 * Probe: can the shell user EVIOCGRAB the power key (read-only fd)?
 * args: <holdMs>  — grabs, holds for holdMs, then exits (which releases the grab).
 */
public class GrabProbe {
    static final int EVIOCGRAB = 0x40044590;   // _IOW('E', 0x90, int)

    public static void main(String[] args) throws Exception {
        long hold = args.length > 0 ? Long.parseLong(args[0]) : 0;
        java.io.FileDescriptor fd = android.system.Os.open("/dev/input/event1", android.system.OsConstants.O_RDONLY, 0);
        try {
            java.lang.reflect.Method m = android.system.Os.class.getMethod("ioctlInt", java.io.FileDescriptor.class, int.class);
            int r = (Integer) m.invoke(null, fd, EVIOCGRAB);
            System.out.println("grab ok r=" + r);
            Thread.sleep(hold);
        } catch (Throwable t) {
            System.out.println("grab failed: " + t);
        } finally {
            android.system.Os.close(fd);
        }
    }
}
