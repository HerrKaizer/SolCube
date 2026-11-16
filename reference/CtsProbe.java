import java.lang.reflect.Method;

/** Test: trigger Circle to Search directly from the shell user, as MiCTS does. */
public class CtsProbe {
    public static void main(String[] args) throws Exception {
        int entry = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        long t0 = System.nanoTime();
        Object vims = CtsTrigger.service();
        long t1 = System.nanoTime();
        boolean ok = CtsTrigger.trigger(vims, entry);
        long t2 = System.nanoTime();
        System.out.println("ok=" + ok + " getService=" + (t1 - t0) / 1000000 + "ms call=" + (t2 - t1) / 1000000 + "ms");
    }
}
