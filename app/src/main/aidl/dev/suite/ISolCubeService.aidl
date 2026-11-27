package dev.suite;

// Shell-side half of a SolCube section, run by SolEngine as a feature process (adb shell user).
interface ISolCubeService {
    // Reserved destroy transaction: clean up and exit the process.
    void destroy() = 16777114;

    // JSON: state + recent log lines.
    String status() = 1;

    // JSON config (frame interpolation: games and heat thresholds).
    void configure(String json) = 2;

    // Small commands, e.g. "sr on" / "sr off".
    String command(String cmd) = 3;
}
