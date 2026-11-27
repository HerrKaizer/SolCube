# SolCube changelog

Newest first. Every build bumps `versionCode`/`versionName` in `app/build.gradle.kts`; release tags are `v<versionName>`.

## 5.7.14
- Setup step 3 fits on the screen again: the Wi-Fi note is one short line (and only shows off Wi-Fi, also in preview), the intro sentence is gone since the picture shows it, and a step that still has to scroll stays inside its card with faded edges instead of sliding over the logo.

## 5.7.13
- Old Shizuku wording removed from code comments and unused translations.

## 5.7.12
- After setup and the tour, SolCube asks once to "Keep SolCube running" and opens vivo's background power page (vivo freezes apps that aren't allowed there, and SolEngine can't change it). Only marked as asked once a button is tapped.
- Setup step 3: a small looping picture shows the pairing code, pulling down the notification shade, tapping "Enter code" and typing the digits.
- Double power press without SolEngine: the card explains how to pick SolCube yourself ("Always" in the chooser, vivo Wallet switch) instead of "Requires setup".
- "Requires setup" is now "Needs the one-minute unlock"; step 3 no longer repeats "Last step:" under the "Final step" badge.
- 5.7.9–5.7.11 were test builds of the above.

## 5.7.8
- Setup wording rewritten to be less technical (all 9 languages): "One-minute unlock" intro with an "Is this safe?" explainer (the debugging terms moved there), "Undo anytime" replaces the outdated "Resets on restart" chip, step 1 prepares for "You are now a developer", step 3 explains the code first, a calm gold Wi-Fi note instead of a red warning, friendlier pairing status lines, "Skip for now?" instead of "Use basic mode?".
- Setup preview (Diagnostics → Open setup): tap the step badge to see the next screen.
- 5.7.5–5.7.7 were test builds of the above.

## 5.7.4
- Double power press sets itself up (vivo's switch on + SolCube as the handler) as soon as an action is picked, a double-press routine is saved, or SolEngine starts while one is wanted. If the double press opens Circle to Search, Google is made the default assistant too.

## 5.7.3
- Circle to Search: SolEngine makes Google the default digital assistant and turns on "Use text from screen" and "Use screenshot" (when a CTS trigger is switched on, and at each engine start while one is on). vivo ships its own assistant there and hides the page, which left users stuck.

## 5.7.2
- Diagnostics: an app event repeated within 10 s is logged once, even with other lines in between (5.7.1 only caught back-to-back repeats).

## 5.7.1
- Diagnostics: the same app event reported twice in a row within 10 s is logged once.

## 5.7.0
- SolEngine only: Shizuku support removed (library, provider, fallback). Former Shizuku users are sent to SolEngine pairing (setup opens by itself, card says why).
- Routines: "Add from presets" adds the shipped presets (off) for existing users; "Game over" brings "Game on" along.
- Diagnostics: in-app event log (SolEngine starts and why they failed, setup, presets) under Recent events.
- One-off shell jobs share one helper that stays up 30 s and is only stopped when idle (no more callers stopping each other's command).
- Screen on/off/unlock pushed from the app to the routines and lock-screen status bar services; their polling slowed down.
- Font stored compressed in the APK, copied once to app storage (APK about 2 MB smaller).
- Setup mentions that SolEngine also turns on USB debugging; pairing notifications get their own icon; "Frame gen" wording in setup; tour says SolEngine.
- Changelog moved here from the versionName comment.

## 5.6.4
- presets: Good morning renamed Wake up, Work preset back (off, fires only on the picked Wi-Fi)

## 5.6.3
- light-mode tour demos use the light gold, lock screen status bar reports "running"

## 5.6.2
- Diagnostics in plain words (SolEngine Running, per-feature status with warnings), new translated routine presets shipped off, light-mode tour demos

## 5.6.1
- tour demo backgrounds no longer go see-through, phone outline visible

## 5.6.0
- feature tour = live animated demos drawn in code (TourDemo), reconnect screen = one Start button (Wireless debugging only as fallback), setup no longer auto-opens once connected before, APK shrunk (BouncyCastle via R8, phone ABIs only, tour JPGs removed)

## 5.5.1
- pairing-never-expires applied on every engine attach

## 5.5.0
- SolEngine reworked after Shizuku: own-phone mDNS + 127.0.0.1 pair/connect, adb_allowed_connection_time=0 (pairing never expires), start on boot, Wireless debugging left on, stale engine processes killed by name, inotify APK-change restart, Doze temp-whitelist before binder handoff

## 5.4.10
- never toggle Wireless debugging when it is already on (just connect with the saved key)

## 5.4.9
- a removed pairing (key rejected) sends the user to pairing instead of "turn on Wireless debugging"

## 5.4.8
- SolEngine (renamed) one-tap start like Shizuku (toggles Wireless debugging off/on itself, reasons shown), auto-start when Wi-Fi connects / on unlock / app open with 10 min back-off, Start buttons in card, pill and reconnect screen

## 5.4.7
- engine restarts itself from the new APK after an update (version = APK path fingerprint, UPGRADE command; older engines keep running until reconnect)

## 5.4.6
- Setup auto-advances on vivo unhide (vivo_development_show) and enable, no manual buttons or in-app code box, Open Wireless debugging opens the real page (QS tile gateway), one-time grant of notifications/overlay/DND after connecting, carousel hides slide descriptions before shrinking, 10 min pairing timeout

## 5.4.5
- Feature tour opens after pairing (from MainActivity), Shizuku death no longer drops the built-in engine, engine children exit when the engine dies, attach race, Pair again keeps auto-reconnect, preview-safe setup, non-English setup/tour layout (wrapping pills, auto-fit buttons, scale-to-fit slides, uncropped art), translated pairing errors + lock-screen card, lighter status bar polling

## 5.4.4
- Replace cropped screenshots in FeatureIntroActivity with bespoke obsidian-and-gold studio artworks, flip description card above artwork card, and refresh stale apkPath in SolCubeDaemon after APK updates

## 5.4.3
- Upgrade SetupActivity to GlassBackdrop + LiquidGlass with unclipped glowing gold buttons, add post-setup FeatureIntroActivity with fresh 5.4 screenshots and Skip button

## 5.4.2
- Add EngineProvider.reset_engine + AdbEngine.resetIdentity for full setup/engine reset

## 5.4.1
- Fix uid=2000 EngineBridge ContentProvider handoff via AttributionSource, suppress all Island pill notifications during setup/pairing, prevent pairing notification tap from minimizing Settings pairing dialog, add first-page Language switcher, and clarify Wireless debugging tap hint

## 5.4.0
- Built-in Wireless ADB setup & daemon engine (no third-party Shizuku required)

## 5.3.7
- Add Settings Lock screen status bar card to hide the lock screen top bar while locked

## 5.3.6
- Fix OriginOS 7 / Android 17 nav-bar Circle to Search app minimization by starting the 800 ms ripple immediately while settling BBKLauncher2's speculative startExistingRecents on finger lift and launching CTS once the clean frame is presented

## 5.3.5
- Fix temp pill blinking on FPS refresh & jittering pill widths, auto-select safe OriginOS 6 lift-wait vs OriginOS 7 instant nav-bar CTS, and improve MediaTek/Vivo X200 Pro Mini volume-down forwarding + hold-to-repeat

## 5.3.4
- Test build — revert nav-bar lift-wait to instant trigger with strict ~15 px navTol below launcher mTouchSlop

## 5.3.3
- Revert broken nav-bar static armed glow hack (keep fast screenshot pre-decode + 100 ms stationary settle cleanly) and read live Resources.getSystem().configuration.locales with onResume auto-recreate when language follows System (defaulting unsupported locales to English)

## 5.3.2
- Fix nav-bar Circle to Search lag safely on OriginOS 6 (show an instant armed thumb halo while waiting for lift, adapt post-lift settle to 100 ms on stationary holds vs 260 ms on drift, then clear the halo for 2 frames before CTS screenshots and the full wave launches)

## 5.3.1
- Fix nav-bar CTS ripple capture by pre-decoding during lift/settle and attaching at t=0, play App UI haptics via COMMUNICATION_REQUEST so they work when Android touch intensity is off, add Reset to defaults in Haptics, and post a standard update notification with Remind me later and Download now

## 5.3.0
- Lock Onest Cyrillic fallback font, add Settings Haptics card (master toggle, per-action vibration presets with live preview, and App UI feedback switch), and eliminate the 1-2 s nav-bar Circle to Search trigger delay by starting the ripple immediately on hold

## 5.2.2
- Bundle Onest & Wix Madefor Cyrillic fallback fonts with live A/B toggle under Russian, auto-fit single-line pill buttons and prevent BETA badge wrapping, shorten cramped stream/button strings, switch Shortcuts->Быстрые действия and Frame interpolation->Frame gen terminology

## 5.2.1
- Overhaul all 8 non-English localizations (official Android/vivo terminology, consistent verb forms & present tense, Russian/Polish pluralization, native quotes & CJK punctuation, German verb-final action phrasing & capitalized nouns, plus 38 newly localized strings across UI, Quick Settings tiles, and Stream overlay)

## 5.2.0
- Add Polish, Spanish, German, French, Italian, and Chinese Simplified translations across UI, routine summaries, and accessibility strings; move non-English dictionaries to lazy JSON assets under assets/i18n/

## 5.1.0
- Add Language section in Settings (System / English / Russian / Portuguese + Request a language via GitHub/Reddit), full RU/PT localization across UI & accessibility strings, and reuse Settings tab vector icon on gesture cards

## 5.0.3
- Replace gesture ··· chip with vector settings gear icon and add live plain-English summary banner at the top of editRoutine

## 5.0.2
- Fix gesture recognition vibration (60 ms pulse + COMMUNICATION_REQUEST bypass), move Draw a custom shape to top of Add gesture without ellipsis, redesign Paused in apps with 1-tap unpause chips + async searchable ListView picker + appName cache, and smooth-scroll down when uncollapsing cards

## 5.0.1
- Cache BottomNavPill live backdrop RenderNode between scroll/tab updates, remove legacy single-page EdgeNavRail layout, separate Gestures from Automation (counts, list, and non-gesture trigger picker), merge Assign/Edit and keep user drawings on Delete in gesture ··· menu, add 1-tap Assign/Edit button in Test Pad, and lower wallpaper (OFF_DP 65 -> 40)

## 5.0.0
- Fix gesture card disappearing immediately on delete (routine & custom shape), pause live background gestures while Test Pad or gesture recorder is open, crisp geometric vector glyphs for all gesture thumbnails, and 3D tactile metallic Volume knob

## 3.12.19
- Floating LiquidGlass bottom tab bar with live behind-bar card blur, redesigned visual Gestures tab, glowing right-aligned tab headers with smooth pill expansion, and raised wallpaper

## 3.12.9
- Fix navigation-bar Circle to Search leaving a stuck blurred screen on OriginOS 6 (wait for real finger lift + 350 ms launcher settle instead of synthetic a11y touch cancel)

## 3.12.8
- Refine landscape pill size to 28.5 dp tall (~16.8 dp text)

## 3.12.7
- Restore pre-3.10.0 portrait pill & text size (keeping auto-placing) and enlarge landscape pills

## 3.12.6
- Allow portrait status pill to expand across the clock to the left edge margin

## 3.12.5
- Route TestReceiver shell IPC commands (features, CTS ripple, routine triggers) over authenticated NoticeHost (47322); non-overlapping landscape multi-pill row in Island

## 3.12.4
- Fix landscape pill geometry on rotation; fix launch_bg waking companion apps quietly without stealing focus; add per-profile status pill switch (profile name vs actions); Deliver shell pill notices via authenticated localhost IPC (NoticeHost on 127.0.0.1:47322) when OriginOS drops shell am broadcasts; Show "NFC on" / "NFC off" pill instead of app/profile name for NFC profiles and power-button launches watched by a profile; Fix Circle to Search minimizing apps on navigation-bar hold (cancel active nav touch via AccessibilityServiceInfo motionEventSources pulse + 160 ms settle with finger-lift fallback, tighten navTol, keep RippleHost active for nav hold); Remove "running in the background" & "displaying over other apps" system notifications (host runtime inside SolCubeA11y & use TYPE_ACCESSIBILITY_OVERLAY with FGS fallback); add "Instant trigger (no animation)" switch to Circle to Search card

## 3.11.1
- fix volume down key broken when power-button hold is enabled (capability-based input device discovery for MediaTek mtk-pmic-keys/mtk-kpd + Qualcomm; forward non-power keys on shared grabbed devices; stop orphaned shell daemons and release EVIOCGRAB automatically after uninstall)

## 3.11.0
- Gemini CTS & Gemini Live actions; Two-finger hold, Power button hold, Nav bar hold & Double power press routine triggers (routine priority + Shortcuts card fallback); amber "Other" button on Double power press & Power button hold cards to pick any action

## 3.10.1
- status pill Y fix: always align to cutout bottom - holeR instead of falling back to statusBarH * 0.5 when statusBarH is close to cutout bottom

## 3.10.0
- status pill auto-scales/centers per device via DisplayCutout + WindowMetrics; all SolCube features added to Routine/Profile actions (temp pill, FPS counter, Stream to laptop, Gestures, Circle to Search & CTS triggers, Power hold); collapsible One/Two finger sections under Gestures

## 3.9.4
- load pill: same width as the temperature pill in portrait (no longer stretched by the FPS pill), same height in landscape

## 3.9.3
- FPS pill keeps its border; Gaming summary says "FPS counter"

## 3.9.2
- FPS counter: its own card (above the temperature overlay) and pill, left of the temperatures in landscape, on top in portrait; three digits fit (102 showed as "10"); frames of the app in front from SurfaceFlinger timestats

## 3.9.1
- FPS pill (switch in the temp card)

## 3.9.0
- Quick Settings tiles for Frame interpolation, Super resolution, Play together (+ "Add tile" on their cards); "Shizuku stopped · tap to start" pill; new routine triggers (any game opened/closed, media starts/stops, call starts/ends, landscape/portrait, Bluetooth/Wi-Fi/mobile data switched, sound mode, dark mode, battery cooled down, screen off for N min)

## 3.8.2
- FI fi/gpu + log, SR values and last gesture moved to Diagnostics → Live values; Gaming summary says "Frame interpolation"; Gestures card line fits ("Off")

## 3.8.1
- taller wallpapers (1152x3712, ribbon continues down) so the scroll parallax works again

## 3.8.0
- new wallpapers (grass + satin ribbon, dark + light) fitted to the screen, 20 dp up, 110 % zoom, no sway; temp editors removed; glow no longer clipped square after expanding a card

## 3.7.10
- wallpaper: past the photo's edges fade to its backdrop colour (no smeared edge rows); position label says up/down

## 3.7.9
- TEMP wallpaper card: zoom ±1/5 % around the screen middle (wall_dark_zoom)

## 3.7.8
- TEMP Settings card: dark wallpaper position (↑/↓ 5/20 dp, live, saved as wall_dark_off)

## 3.7.7
- new dark wallpaper (grass + satin ribbon "C"), fitted to the screen, 40 dp down; wallpaper sway/drift removed; temp switch gone

## 3.7.5
- wallpaper 90 dp lower (OFF_DP 185 → 95; a bigger OFF_DP moves it UP)

## 3.7.2
- link text more yellow (dark FFCE4A, light 9C7406)

## 3.7.1
- profiles: Boost/Battery saver in a Game Cube game is set after Game Cube applies its remembered mode (~3.5 s), so the game panel follows

## 3.7.0
- Audio card (Play together + Fine volume knob/slider, 1 % steps on vivo's 150-step media stream); landscape pill drops in at the top centre (no hole circle); Gestures card face as short as the groups

## 3.6.11
- "Frame interpolation" action for Profiles + Routines

## 3.6.10
- NFC: wait for NFC to settle + verify/retry the Settings route (it drops "off" while turning on), screen-off guard against vivo's 2 AM auto re-enable

## 3.6.9
- profiles: no "<name> off" pill unless leaving restores something; "Only if not already done" toggle

## 3.6.8
- code renamed Suite→SolCube (classes, tags, comments), old a11y entry cleaned up; update check uses github.com/HerrKaizer/SolCube; Gestures card same height as the groups; Gestures card styled like the group cards; flat gold on switches + theme picker, greener "running"; glow on every gold control, halo no longer clipped square; glowing gold matched to the icon

## 2026-11-21
- renamed to SolCube (visible name only), palette matched to the new icon (amber gold on black)

