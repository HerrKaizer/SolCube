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
        versionCode = 100
        versionName = "3.6.7"  // update check uses github.com/HerrKaizer/SolCube; Gestures card same height as the groups; Gestures card styled like the group cards; flat gold on switches + theme picker, greener "running"; glow on every gold control, halo no longer clipped square; glowing gold matched to the icon; 2026-11-21: renamed to SolCube (visible name only), palette matched to the new icon (amber gold on black)
    }

    buildTypes {
        // The published APK is this build, signed with the same (debug) key as every release so
        // updates install over older versions. Not debuggable: no `run-as` or debugger access to
        // Suite's settings / Shizuku binder for anyone with adb.
        getByName("debug") { isDebuggable = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { aidl = true }
    // the font is already compressed; keep it uncompressed so Typeface can map it
    androidResources { noCompress += "ttf" }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    compileOnly(project(":stubs"))   // hidden AudioPolicy classes for MixUserService
}
