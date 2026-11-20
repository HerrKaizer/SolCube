# Privacy Policy

**Effective:** 20 November 2026 · Applies to the Suite Android app (`dev.suite`)

**Short version:** Suite collects no data. There are no accounts, no analytics, no ads, no tracking and no servers. Everything Suite does happens on your phone.

## What Suite does not do

- It does not collect, store, sell or share personal data.
- It has no analytics, crash reporting, advertising or tracking SDKs.
- It does not send your settings, routines, app lists or usage anywhere.
- It does not read what is on your screen (see "Accessibility service" below).

## The one network request

Suite checks for updates about twice a day by asking GitHub's public API for the latest release:
`https://api.github.com/repos/HerrKaizer/Suite/releases/latest`

This request contains no personal data. As with any website, GitHub sees your IP address when it answers. Suite only reads the version number and release notes, and doesn't send anything back. See [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

Links you choose to tap (the release page, "Can't find a feature?" which opens Reddit, or an "Open link" routine action you set up) open in your browser or the Reddit app and follow their own privacy policies.

## Data kept on your phone

Your settings, routines, profiles, gestures and app choices are stored in Suite's private storage on your phone. Suite doesn't upload them.

They are included in **Android's own backup** (Google backup and phone-to-phone transfer) so they come back after a reinstall or on a new phone. That backup is made by Android under your Google account, not by Suite, and you can turn it off in your phone's settings. A private key used only for Suite's internal communication is excluded from backup.

Suite keeps a short log of recent events in memory for the in-app Diagnostics. It is never sent anywhere. "Copy report" only copies it to your clipboard when you tap it.

## Permissions and why Suite needs them

- **Shizuku (shell access):** to change system settings and listen for the power button, touches and gestures. All of this runs on your phone.
- **Accessibility service:** only used to draw Suite's overlays above the status bar. It is set up with no access to screen content (`canRetrieveWindowContent="false"`) and doesn't read what you type or see.
- **Display over other apps:** the temperature pill, notice pill and Circle to Search ripple.
- **Do Not Disturb access:** so Ring / Vibrate / Silent can be switched directly.
- **Internet:** only for the update check above.
- **Vibrate, run at startup, foreground service:** haptics and keeping Suite's features running.

Some features read local information to work, such as which app is in the foreground (Profiles, frame interpolation), Wi-Fi network names (Routines) and device temperatures (the overlay). This information stays on your phone and is used only for the feature you turned on.

**Stream to laptop** sends your screen to your own computer over the USB cable through scrcpy. Nothing leaves your phone and your computer.

## Children

Suite is not directed at children and collects no data from anyone.

## Changes

If this policy changes, the new version will be published here with a new date.

## Contact

Questions: open an issue on [GitHub](https://github.com/HerrKaizer/Suite/issues) or DM [u/HerrKaizer__](https://www.reddit.com/user/HerrKaizer__/) on Reddit.
