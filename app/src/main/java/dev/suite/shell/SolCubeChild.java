package dev.suite.shell;

import android.content.Context;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Constructor;

/**
 * Child app_process host for one *UserService when running under SolCube's built-in engine.
 * Instantiates the service, pushes its binder to dev.suite's EngineProvider, and loops until
 * destroy() calls System.exit(0).
 */
public final class SolCubeChild {
    private SolCubeChild() {}

    public static void main(String[] args) {
        if (args.length < 4) System.exit(2);
        String className = args[0];
        String suffix = args[1];
        String secret = args[2];
        int apkVersion;
        try {
            apkVersion = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            apkVersion = 0;
        }

        L.tag = "SolCube." + suffix;
        Looper.prepareMainLooper();

        Context ctx;
        try {
            ctx = MixUserService.shellContext(null);
        } catch (Throwable t) {
            L.e("shellContext failed", t);
            System.exit(3);
            return;
        }

        IBinder service;
        try {
            Class<?> cls = Class.forName(className);
            Object instance;
            try {
                Constructor<?> ctor = cls.getConstructor(Context.class);
                instance = ctor.newInstance(ctx);
            } catch (NoSuchMethodException ignored) {
                instance = cls.getConstructor().newInstance();
            }
            service = (IBinder) instance;
        } catch (Throwable t) {
            L.e("instantiate " + className + " failed", t);
            System.exit(4);
            return;
        }

        if (!EngineBridge.attachChild(secret, suffix, service, apkVersion)) {
            L.i("attachChild failed for " + suffix);
            System.exit(5);
            return;
        }

        watchParent(service);
        Looper.loop();
    }

    /**
     * The engine (our parent) doesn't take us down if it crashes or is killed, and a new engine
     * would start a second copy of this service (two power-key grabs, double triggers). So when we
     * get re-parented, clean up like a normal Disable and exit; SolCube restarts us under the new engine.
     */
    private static void watchParent(IBinder service) {
        final int parent = android.system.Os.getppid();
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                if (android.system.Os.getppid() != parent) {
                    L.i("engine gone, stopping");
                    try {
                        ((dev.suite.ISolCubeService) service).destroy();
                    } catch (Throwable ignored) {
                    }
                    System.exit(0);
                }
            }
        }, "parent-watch");
        t.setDaemon(true);
        t.start();
    }
}
