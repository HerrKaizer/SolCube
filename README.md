# SolCube

Formerly **Suite**. All-in-one Android app for vivo / OriginOS phones. Tested on a vivo X300 Ultra running OriginOS 7; other devices may not work.

**Download:** [latest release](https://github.com/HerrKaizer/SolCube/releases/latest) (debug-signed APK)

## Features

- **Circle to Search** triggers: two-finger hold, power-button hold and navigation-bar hold, with ripple and haptics
- **Frame interpolation** for games vivo doesn't support (per-game 45→90 or 60→120 fps, pauses when hot)
- **Super resolution** toggle
- **Temperature overlay** (CPU / GPU / battery)
- **Play together**: audio mixing (several apps play at once)
- **Routines** (beta): Wi-Fi, DND, unlock and time triggers with actions like data, ringer, airplane mode, close apps
- **Double power press**: open an app (optionally turning NFC on while it is open), Circle to Search, or switch sound mode: Ring › Vibrate › Silent, silent on/off, vibrate on/off. SolCube turns on vivo Wallet's double-press switch for you ("Set up double press")
- **Power button hold**: instead of Circle to Search, the hold can open an app or switch sound mode
- **Stream to laptop** (beta, scrcpy based)

## Requirements

- A vivo / iQOO phone on OriginOS (tested on OriginOS 7, Android 17)
- **Developer options → Wireless debugging** and Wi-Fi, for **SolEngine**, SolCube's own built-in shell engine (no root, no Shizuku). SolEngine leaves Wireless debugging on, together with USB debugging, which Android requires for it, so it can start by itself after a reboot.
- **Circle to Search triggers only:** the **Google app** set as your default digital assistant. SolCube makes the same call as MiCTS, so MiCTS itself is not needed.
- **Overlay permission** ("Display over other apps") for the ripple, pill and temperature overlay
- **Accessibility service "SolCube ripple"**: only used to draw overlays above the status bar. It reads no screen content. SolCube enables it for you through SolEngine.
- Installing from unknown sources allowed (the APK is debug-signed)
- **Stream to laptop only:** a PC with adb, scrcpy and the helper script, plus a USB cable

## Setup guide

### 1. Install the APK
Download `SolCube.apk` from the [release page](https://github.com/HerrKaizer/SolCube/releases/latest), open it and allow installs from this source when asked. If vivo rejects the install ("User rejected permissions"), unlock the phone and try again with the screen on.

### 2. Run SolCube's setup
Open SolCube. Its setup walks you through it and moves on by itself at each step:
1. Unhide **Developer options** (tap **Software version** 7 times; SolCube opens the page).
2. Turn Developer options on.
3. Connect: SolCube opens **Wireless debugging** → tap **Pair device with pairing code**, then type the code into SolCube's notification.

That's it: the pairing is kept, SolEngine starts by itself after a reboot (on Wi-Fi), restarts itself after an update, and grants SolCube's other permissions (overlay, notifications, Do Not Disturb, accessibility). If it ever isn't running, the card in SolCube has one **Start SolEngine** button.

Coming from Shizuku? Since 5.7.0 SolCube doesn't use Shizuku any more. Setup opens by itself after the update; pair once and you can uninstall Shizuku if nothing else needs it.

### 3. Turn on features
Turn on the features you want with each card's **Enable** button. Routines → **Add from presets** has ready-made routines (they start switched off).

### 4. Circle to Search (optional)
1. Settings → Apps → Default apps → **Digital assistant app** → choose **Google**.
2. Make sure the Google app is up to date and Circle to Search is enabled in the Google app settings.
3. In SolCube, enable the Circle to Search card, then hold two fingers on the screen, hold the power button, or hold the navigation bar at the bottom.

### 5. Keep SolCube alive (recommended)
vivo's background cleaner can stop apps. In Settings → Battery → Background power consumption (name varies), allow SolCube to run in the background.

## Notes

- Everything shell-level runs in SolEngine (`shell/SolCubeDaemon.java`, started over this phone's own Wireless debugging), one process per feature. Source is in `app/src/main/java/dev/suite/shell`.
- Routines start with switched-off presets; double-press apps and the frame-interpolation game list start empty. Set them up in the app.

## Updates and your settings

- SolCube checks GitHub twice a day. While a new version is out, a pop-up with the release notes opens every time you open SolCube. "Check for updates" at the bottom of the app checks right away.
- Install the new APK over the old one. **Don't uninstall first**: updating keeps all settings and routines.
- Settings are also part of Android's Google backup, so they come back after a reinstall or on a new phone.
- Missing something? Tap **Can't find a feature?** in the app, or DM [u/HerrKaizer__](https://www.reddit.com/user/HerrKaizer__/) on Reddit.

## Privacy

SolCube collects no data: no accounts, analytics, ads or tracking. Everything runs on your phone; the only network request is the update check to GitHub. Full details in the [Privacy Policy](PRIVACY.md).
