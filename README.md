# Suite

All-in-one Android app for vivo / OriginOS phones. Tested on a vivo X300 Ultra running OriginOS 7; other devices may not work.

**Download:** [latest release](https://github.com/HerrKaizer/Suite/releases/latest) (debug-signed APK)

## Features

- **Circle to Search** triggers: two-finger hold and power-button hold, with ripple and haptics
- **Frame interpolation** for games vivo doesn't support (per-game 45→90 or 60→120 fps, pauses when hot)
- **Super resolution** toggle
- **Temperature overlay** (CPU / GPU / battery)
- **Play together**: audio mixing (several apps play at once)
- **Routines** (beta): Wi-Fi, DND, unlock and time triggers with actions like data, ringer, airplane mode, close apps
- **Double power press**: open an app (optionally turning NFC on while it is open), or switch sound mode: Ring › Vibrate › Silent, silent on/off, vibrate on/off
- **Stream to laptop** (beta, scrcpy based)

## Requirements

- A vivo / iQOO phone on OriginOS (tested on OriginOS 7, Android 16)
- **[Shizuku](https://shizuku.rikka.app/)** installed and running (no root needed)
- **Developer options → Wireless debugging** (to start Shizuku; needs Wi-Fi once per boot)
- **Circle to Search triggers only:** the **Google app** set as your default digital assistant. Suite makes the same call as MiCTS, so MiCTS itself is not needed.
- **Overlay permission** ("Display over other apps") for the ripple, pill and temperature overlay
- **Accessibility service "Suite"**: only used to draw overlays above the status bar. It reads no screen content. Suite can enable it for you through Shizuku.
- Installing from unknown sources allowed (the APK is debug-signed)
- **Stream to laptop only:** a PC with adb, scrcpy and the helper script, plus a USB cable

## Setup guide

### 1. Install the APK
Download `Suite.apk` from the [release page](https://github.com/HerrKaizer/Suite/releases/latest), open it and allow installs from this source when asked. If vivo rejects the install ("User rejected permissions"), unlock the phone and try again with the screen on.

### 2. Enable Developer options and Wireless debugging
1. Settings → About phone → tap **Software version** (or Build number) 7 times.
2. Settings → System management → **Developer options**.
3. Turn on **Wireless debugging** (connect to a Wi-Fi network first).

### 3. Install and start Shizuku
1. Install Shizuku from the [Play Store or GitHub](https://shizuku.rikka.app/download/).
2. Open Shizuku → **Start via Wireless debugging**.
3. In Developer options → Wireless debugging → **Pair device with pairing code**. Pull down the notification Shizuku shows and enter the pairing code in its notification.
4. Back in Shizuku tap **Start**. It should say "Shizuku is running".

Shizuku stops when the phone reboots, so repeat step 3 (the pairing is remembered, only Start is needed) after each reboot.

### 4. Give Suite its permissions
1. Open Suite. When Shizuku asks, **allow** Suite.
2. Allow **Display over other apps** for Suite when prompted.
3. Turn on the features you want with each card's **Enable** button.

### 5. Circle to Search (optional)
1. Settings → Apps → Default apps → **Digital assistant app** → choose **Google**.
2. Make sure the Google app is up to date and Circle to Search is enabled in the Google app settings.
3. In Suite, enable the Circle to Search card, then hold two fingers on the screen or hold the power button.

### 6. Keep Suite alive (recommended)
vivo's background cleaner can stop apps. In Settings → Battery → Background power consumption (name varies), allow Suite to run in the background.

## Notes

- Everything shell-level runs as Shizuku UserServices. Source is in `app/src/main/java/dev/suite/shell`.
- Routines, double-press apps and the frame-interpolation game list start empty or generic. Set them up in the app.

## Updates and your settings

- Suite checks GitHub once a day and shows a pop-up with the release notes when a new version is out.
- Install the new APK over the old one. **Don't uninstall first**: updating keeps all settings and routines.
- Settings are also part of Android's Google backup, so they come back after a reinstall or on a new phone.
- Missing something? Tap **Can't find a feature?** in the app, or DM [u/HerrKaizer__](https://www.reddit.com/user/HerrKaizer__/) on Reddit.
