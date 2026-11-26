package dev.suite;

// Built-in uid=2000 supervisor daemon for SolCube's shell-side services.
interface ISolCubeDaemon {
    int version() = 1;
    void spawnService(String className, String suffix) = 2;
    void cacheChild(String suffix, IBinder binder) = 3;
    IBinder getChild(String suffix) = 4;
    void stopService(String suffix) = 5;
    void shutdown() = 6;
}
