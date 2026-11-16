package dev.suite.shell;

import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import java.lang.reflect.Method;

/**
 * Calls IVoiceInteractionManagerService.showSessionFromSession(null, bundle, 7, "hyperOS_home"),
 * the same call MiCTS makes, so Google's assistant opens Circle to Search ("omni").
 */
final class CtsTrigger {
    static Method show;

    static Object service() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "voiceinteraction");
        Class<?> stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService$Stub");
        Object vims = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        for (Method m : Class.forName("com.android.internal.app.IVoiceInteractionManagerService").getMethods()) {
            if (m.getName().equals("showSessionFromSession")) show = m;
        }
        return vims;
    }

    static boolean trigger(Object vims, int entry) throws Exception {
        Bundle args = new Bundle();
        args.putLong("invocation_time_ms", SystemClock.elapsedRealtime());
        args.putInt("omni.entry_point", entry);
        args.putBoolean("micts_trigger", true);
        Object r = show.getParameterCount() == 4
                ? show.invoke(vims, null, args, 7, "hyperOS_home")
                : show.invoke(vims, null, args, 7);
        return Boolean.TRUE.equals(r);
    }

    private CtsTrigger() {}
}
