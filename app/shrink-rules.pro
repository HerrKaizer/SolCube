# Only shrink BouncyCastle (libadb uses a handful of its lightweight crypto classes directly, no
# JCA provider lookups by name). Everything else -- SolCube's own code (reflection, app_process
# entry points, AIDL), libadb, spake2, Conscrypt (JNI) -- is kept exactly as compiled.
-dontobfuscate
-dontoptimize
-keep class !org.bouncycastle.**, ** { *; }
-keepattributes *
-dontwarn **
-ignorewarnings
