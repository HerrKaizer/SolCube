# SolCube

**An all-in-one toolkit for vivo and iQOO phones.** Instant Circle to Search, gestures, frame generation for games, routines and more, in one app.

No root. No computer. No other apps to install.

**[⬇ Download the latest version](https://github.com/HerrKaizer/SolCube/releases/latest)** (file: `SolCube.apk`)

> Made and tested on a vivo X300 Ultra with OriginOS 7. Other vivo and iQOO phones on OriginOS should work, but some features may not.

## What SolCube can do

- **Circle to Search, anywhere.** Hold two fingers on the screen, hold the power button or hold the navigation bar, and Circle to Search opens straight away, with a small ripple and vibration.
- **Smoother games.** Frame generation for games vivo doesn't support (45→90 or 60→120 fps), with a live FPS counter. It pauses by itself when the phone gets hot.
- **Super resolution** for sharper games.
- **Gestures.** Draw a shape over any app to open an app or run an action.
- **Routines.** Let the phone do things for you: for example silent at bedtime, mobile data off on Wi-Fi, Do Not Disturb at work. Ready-made presets included.
- **Double-press the power button** to open an app, start Circle to Search or switch between ring, vibrate and silent.
- **Hold the power button** for something other than the assistant.
- **Play together:** let two apps play sound at the same time, plus volume in 1% steps.
- **Temperature overlay:** CPU, GPU and battery temperature in a small pill.
- **Stream to laptop** (beta): show your phone on a PC over a USB cable.

## What you need

- A **vivo or iQOO phone** with OriginOS.
- **Wi-Fi** for the one-minute setup.
- The **Google app**, for Circle to Search. SolCube makes it your default assistant for you.

## Install

1. **Download** `SolCube.apk` from the [latest release](https://github.com/HerrKaizer/SolCube/releases/latest).
2. **Open the file** and allow installing from this source when your phone asks. If vivo says the install was rejected, unlock the phone and try again with the screen on.
3. **Open SolCube** and follow the **one-minute unlock**. It has three short steps, and SolCube moves on by itself after each one:
   1. **Show hidden settings:** tap *Software version* 7 times. Android then says "You are now a developer". That's just the name of these settings.
   2. **Switch them on:** turn on *Developer options*.
   3. **Link SolCube:** Android shows a 6-digit code, and you type it into a SolCube notification. SolCube shows you how.
4. **Keep SolCube running:** when SolCube asks, allow it in vivo's battery settings. Otherwise vivo may pause it in the background to save battery.
5. **Turn on what you want.** Each feature has its own card. In Routines, **Add from presets** gives you ready-made routines.

You only do the unlock once. After that, SolCube starts by itself, also after a restart.

Don't want the unlock yet? Tap **Use basic features only** during setup. You can unlock everything later from any locked card.

## Updating

SolCube tells you when a new version is out. Download it and install it over the one you have; **don't uninstall first**.

## Questions

**Is the unlock safe?**
Yes. SolCube uses *Wireless debugging*, a switch built into Android. It only connects SolCube to your own phone, nothing goes over the internet, and your phone isn't rooted. To undo it, turn off *Developer options* in Settings.

**Something stopped working.**
Open SolCube. If its engine isn't running, the card at the top has a **Start** button. Settings → **Diagnostics** shows what's running and has a **Copy report** button for bug reports.

**Missing a feature?**
Tap **Can't find a feature?** at the bottom of the app, or message [u/HerrKaizer__](https://www.reddit.com/user/HerrKaizer__/) on Reddit.

## Privacy

SolCube collects no data: no accounts, analytics, ads or tracking. Everything runs on your phone. The only thing it does online is check GitHub for updates. Full details in the [Privacy Policy](PRIVACY.md).

## For techies

### Why SolCube needs SolEngine

Normal Android apps can't change most system settings, listen to the power button and raw touches, set default apps or control other apps. The **shell user** (uid 2000, what `adb shell` runs as) can. Root isn't needed, but something has to run as the shell user.

**SolEngine** is SolCube's own way to get there, built into the app:

1. During setup, SolCube pairs with the phone's **own** `adbd` through Wireless debugging (ADB over TLS). It finds the pairing and connect ports over mDNS and connects to `127.0.0.1`, so no computer is involved. The pairing key stays in SolCube's private storage and never leaves the phone.
2. Over that connection it starts `SolCubeDaemon` from SolCube's own APK with `app_process`, running as uid 2000.
3. The daemon starts one small process per feature (`dev.suite:cts`, `:routines`, `:fi` and so on), each a Binder service that only SolCube can talk to (HMAC handshake). Features you don't use don't run.
4. It sets `adb_allowed_connection_time` so Android doesn't drop the pairing after 7 days, and grants SolCube `WRITE_SECURE_SETTINGS`.

After a reboot, SolCube switches Wireless debugging back on through `WRITE_SECURE_SETTINGS` and starts the engine again as soon as the phone is on Wi-Fi. Android only allows Wireless debugging on Wi-Fi; once running, the engine keeps working on mobile data. USB debugging stays on because Android ties Wireless debugging to it. After an app update the engine notices the new APK and restarts itself.

Earlier versions (before 5.7) relied on a separate helper app for this; SolCube no longer needs it.

### How the features work

- **Circle to Search:** a listener reads the touch screen and power key input devices directly, and on a long press it calls `IVoiceInteractionManagerService.showSessionFromSession` the same way MiCTS does. It also makes Google the `ASSISTANT` role holder and switches on screen context, which Circle to Search needs.
- **Frame generation and super resolution:** vivo's GameCube is driven by system settings. SolCube writes them per game, watches the game's process and the battery temperature, and pauses when it gets hot.
- **Routines and gestures:** run in their own shell processes, so they keep working while SolCube's screen is closed. Screen on/off and unlock events come from SolCube's app process.
- **Double power press:** vivo sends an intent on a double press. SolCube switches on vivo's own setting and becomes the preferred handler for it.
- **Overlays:** the ripple, pills and temperature overlay are drawn by an accessibility service that is configured with no access to screen content (`canRetrieveWindowContent="false"`). It exists only so overlays can sit above the status bar.

### Source

Kotlin and Java. The ADB pairing and connection use [libadb-android](https://github.com/MuntashirAkon/libadb-android) and Conscrypt; everything else is SolCube's own code. App code is in [`app/src/main/java/dev/suite`](app/src/main/java/dev/suite), and everything that runs as the shell user is in [`.../shell`](app/src/main/java/dev/suite/shell). Build with Gradle (`assembleDebug`). The release APK is debug-signed. Changes per version are in [CHANGELOG.md](CHANGELOG.md).
