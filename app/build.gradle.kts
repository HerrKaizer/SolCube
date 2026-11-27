plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.suite"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.suite"
        minSdk = 33
        targetSdk = 34
        // Conscrypt's native TLS library: phone CPUs only (x86 builds are for emulators/PCs).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 196
        versionName = "5.7.14"   // changes: CHANGELOG.md
    }

    buildTypes {
        // The published APK is this build, signed with the same (debug) key as every release so
        // updates install over older versions. Not debuggable: no `run-as` or debugger access to
        // SolCube's settings / engine binder for anyone with adb.
        getByName("debug") {
            isDebuggable = false
            // Size: libadb pulls in all of BouncyCastle (~7 MB) for a few algorithms. R8 only shrinks
            // BouncyCastle; everything else is kept as-is (see shrink-rules.pro).
            isMinifyEnabled = true
            proguardFiles("shrink-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { aidl = true }
    // BouncyCastle's data files (post-quantum tables, message bundles) aren't used by libadb
    packaging { resources.excludes += listOf("org/bouncycastle/**", "META-INF/versions/**/OSGI-INF/**") }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    compileOnly(project(":stubs"))   // hidden AudioPolicy classes for MixUserService
}
