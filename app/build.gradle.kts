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
        versionCode = 125
        versionName = "3.10.1"  // status pill Y fix: always align to cutout bottom - holeR instead of falling back to statusBarH * 0.5 when statusBarH is close to cutout bottom; 3.10.0: status pill auto-scales/centers per device via DisplayCutout + WindowMetrics; all SolCube features added to Routine/Profile actions (temp pill, FPS counter, Stream to laptop, Gestures, Circle to Search & CTS triggers, Power hold); collapsible One/Two finger sections under Gestures; 3.9.4: load pill: same width as the temperature pill in portrait (no longer stretched by the FPS pill), same height in landscape; 3.9.3: FPS pill keeps its border; Gaming summary says "FPS counter"; 3.9.2: FPS counter: its own card (above the temperature overlay) and pill, left of the temperatures in landscape, on top in portrait; three digits fit (102 showed as "10"); frames of the app in front from SurfaceFlinger timestats; 3.9.1: FPS pill (switch in the temp card); 3.9.0: Quick Settings tiles for Frame interpolation, Super resolution, Play together (+ "Add tile" on their cards); "Shizuku stopped · tap to start" pill; new routine triggers (any game opened/closed, media starts/stops, call starts/ends, landscape/portrait, Bluetooth/Wi-Fi/mobile data switched, sound mode, dark mode, battery cooled down, screen off for N min); 3.8.2: FI fi/gpu + log, SR values and last gesture moved to Diagnostics → Live values; Gaming summary says "Frame interpolation"; Gestures card line fits ("Off"); 3.8.1: taller wallpapers (1152x3712, ribbon continues down) so the scroll parallax works again; 3.8.0: new wallpapers (grass + satin ribbon, dark + light) fitted to the screen, 20 dp up, 110 % zoom, no sway; temp editors removed; glow no longer clipped square after expanding a card; 3.7.10: wallpaper: past the photo's edges fade to its backdrop colour (no smeared edge rows); position label says up/down; 3.7.9: TEMP wallpaper card: zoom ±1/5 % around the screen middle (wall_dark_zoom); 3.7.8: TEMP Settings card: dark wallpaper position (↑/↓ 5/20 dp, live, saved as wall_dark_off); 3.7.7: new dark wallpaper (grass + satin ribbon "C"), fitted to the screen, 40 dp down; wallpaper sway/drift removed; temp switch gone; 3.7.5: wallpaper 90 dp lower (OFF_DP 185 → 95; a bigger OFF_DP moves it UP); 3.7.2: link text more yellow (dark FFCE4A, light 9C7406); 3.7.1: profiles: Boost/Battery saver in a Game Cube game is set after Game Cube applies its remembered mode (~3.5 s), so the game panel follows; 3.7.0: Audio card (Play together + Fine volume knob/slider, 1 % steps on vivo's 150-step media stream); landscape pill drops in at the top centre (no hole circle); Gestures card face as short as the groups; 3.6.11: "Frame interpolation" action for Profiles + Routines; 3.6.10: NFC: wait for NFC to settle + verify/retry the Settings route (it drops "off" while turning on), screen-off guard against vivo's 2 AM auto re-enable; 3.6.9: profiles: no "<name> off" pill unless leaving restores something; "Only if not already done" toggle; 3.6.8: code renamed Suite→SolCube (classes, tags, comments), old a11y entry cleaned up; update check uses github.com/HerrKaizer/SolCube; Gestures card same height as the groups; Gestures card styled like the group cards; flat gold on switches + theme picker, greener "running"; glow on every gold control, halo no longer clipped square; glowing gold matched to the icon; 2026-11-21: renamed to SolCube (visible name only), palette matched to the new icon (amber gold on black)
    }

    buildTypes {
        // The published APK is this build, signed with the same (debug) key as every release so
        // updates install over older versions. Not debuggable: no `run-as` or debugger access to
        // SolCube's settings / Shizuku binder for anyone with adb.
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
