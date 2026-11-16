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
        versionCode = 26
        versionName = "2.1.5"   // 2026-11-16: find input devices by name via getevent (power button on other phones)
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
