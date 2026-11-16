# Project brief: vivo GameCube FI + SR controller app (no root)

## Goal
Build an Android app for a **vivo X300 Ultra (OriginOS 7, non-rooted)** that forces vivo's built-in **frame interpolation (FI)** and **super resolution (SR)** on games vivo doesn't officially support, and keeps them on while the game runs. Primary games: Genshin Impact (global, `com.miHoYo.GenshinImpact`), Brawl Stars (`com.supercell.brawlstars`), Clash Royale (`com.supercell.clashroyale`). The user should be able to add any other game by package name.

Everything below has been tested by hand on the target phone through a shell script (`fi.sh`, included at the end). The app is a nicer front end plus a background service doing the same thing. Treat the script as the reference implementation.

## How it works
vivo's GameCube feature is driven by system settings. Writing the right values makes the GPU interpolate frames (e.g. 45 fps -> 90 fps) in any game. There is no root; the writes are made with **shell (adb-level) permission through Shizuku**.

### Settings (verified)
| Setting | On | Off |
|---|---|---|
| `system gamecube_frame_interpolation` | `1:3:<PID>:<SRC>:<DST>` | `0:-1:<PID>:0:0` |
| `global gpu_memc_frame_rate` | `<pkg>_0_<PID>_1_30_<SRC>_<DST>` | `<pkg>_0_<PID>_0_-1_-1_-1` |
| `system vivo_sr_status` | `2` | `0` |
| `global vsr_value_from_gamecube` | `1` | `0` |

- `<PID>` is the game's process ID. **It changes on every launch**, so look it up again each time (`pidof -s <pkg>`).
- FI uses both of the first two settings; SR uses the last two. They are independent, so FI and SR can be on together.
- Read the foreground game with `settings get secure current_game_package`.

### Writing: the part that bites
- On OriginOS 7, `settings put` and `cmd settings put` **silently do nothing**. The only method that works is:
  `content insert --uri content://settings/<system|global|secure> --bind name:s:<key> --bind value:s:<value>`
- Colons inside values must be escaped as `\:` (e.g. `1\:3\:12345\:45\:90`) or it fails with "Binding not well formed".
- Reading with `settings get` works normally.
- vivo's service **resets the FI setting roughly every 12 s**, so the app needs a loop (about every 0.5 s while a game runs) that reads the value and rewrites it when it differs.

## Frame-rate rules
- vivo only accepts whole-multiple ratios: **60->120** and **45->90** work. 60->144, 72->144, 45->144 and 48->144 are all rejected.
- 144 Hz is not reachable for normal apps without root (peak/min refresh rate settings and display-mode tricks are ignored).
- **Heat decides the ratio.** 60->120 adds 15-25 C to CPU/GPU. 45->90 runs about as cool as plain 60 fps (CPU/GPU 70-76 C in Genshin). FI at 60->120 plus SR hit 90-100 C. FI 45->90 plus SR is still being tested, so make SR optional per game and default it off unless the user turns it on.
- vivo throttles at about 45 C battery temperature, and FI drops to ~90 or less. Hence the pause rule below.

## Required behaviour
1. **Game table**: per game `package`, `SRC`, `DST`, `SR on/off`. Defaults: Genshin 45->90 (SR optional), Brawl Stars 60->120, Clash Royale 60->120. Add/remove games in the UI.
2. **Background service** (foreground service with a notification): while any listed game is running, keep FI (and SR if enabled) on. Prefer the game that is currently in the foreground.
3. **Game detection**: find the PID with `pidof -s`, re-detect when it dies ("game closed"), rewrite settings with the new PID on relaunch. When the game closes, turn SR off.
4. **Heat pause**: battery temperature (tenths of a degree, from `/sys/class/power_supply/battery/temp`, or `dumpsys battery` if unreadable). At **>= 45.0 C** turn **FI and SR both off** and keep checking; resume at **<= 42.0 C**. Make the thresholds configurable.
5. **Controls**: Start, Stop (stop also writes all four settings to their off values), Status (running?, current game, PID, SRC->DST, battery temp, live values of the four settings, recent log), and a manual SR on/off toggle.
6. **Log** the last events (game found/closed, rewrites, pause/resume, heartbeat) and show it in the app.
7. **Shizuku handling**: detect whether Shizuku is running and permission is granted, and show clear instructions if not. Shizuku does not survive a reboot, so the app must notice that and tell the user to restart it (Wireless debugging method).
8. Run the shell commands through a **Shizuku UserService** (not `Shizuku.newProcess`, which is private in recent API versions). Batch the commands so the loop does not spawn many processes. The reference script costs about four process spawns a second while a game runs; the app should do at least as well, and idle cheaply (poll every ~5 s) when no game is running.

## Things to avoid / known quirks
- Don't use `settings put`. Don't forget the colon escaping.
- Writing the settings directly from the app's own permissions has not been tested. Assume the shell route is required unless proven otherwise.
- `pkill -f` self-matches in Android shell; the script uses a pidfile. In the app, keep the loop in the service and don't shell out for process control.
- The user may have to re-run after every reboot. Do not promise persistence.
- Do not try to force 144 Hz.

## Out of scope
Root, Magisk, modifying system files, 144 Hz, per-app refresh-rate hacks.

## Reference implementation (`fi.sh`, tested on the phone)
Mirror its logic and settings exactly.

```sh
#!/system/bin/sh
# vivo GameCube: frame interpolation (FI) + super resolution (SR) controller.
#
#   sh /sdcard/Download/fi.sh start      start the background loop (restarts if already running)
#   sh /sdcard/Download/fi.sh stop       stop it, turn FI + SR off
#   sh /sdcard/Download/fi.sh status     running? + last log lines + current settings
#   sh /sdcard/Download/fi.sh sr on|off  switch SR by hand
#   sh /sdcard/Download/fi.sh check      print the raw settings values
#
# Games: package:SRC:DST:SR   (SR 1 = also force super resolution for that game)
# Ratios must be whole multiples (45:90, 60:120). 144 is rejected by vivo.

GAMES="com.miHoYo.GenshinImpact:45:90:1 com.supercell.brawlstars:60:120:0 com.supercell.clashroyale:60:120:0"
HOT=450     # pause FI+SR at this battery temp (tenths of a degree: 450 = 45.0 C)
COOL=420    # resume at this
CHECK=0.5   # seconds between checks while a game runs
IDLE=5      # seconds between checks when idle or paused

DIR=/sdcard/Download
LOG=$DIR/fi.log
PIDF=$DIR/fi.pid
SELF=$0

running() { P=$(cat "$PIDF" 2>/dev/null); [ -n "$P" ] && grep -q "fi.sh" "/proc/$P/cmdline" 2>/dev/null; }

sr_set() {  # $1 = 2 (on) or 0 (off), $2 = 1 or 0
  content insert --uri content://settings/system --bind name:s:vivo_sr_status --bind value:s:"$1"
  content insert --uri content://settings/global --bind name:s:vsr_value_from_gamecube --bind value:s:"$2"
}

fi_off_all() {
  content insert --uri content://settings/system --bind name:s:gamecube_frame_interpolation --bind value:s:"0\:-1\:0\:0\:0"
  content insert --uri content://settings/global --bind name:s:gpu_memc_frame_rate --bind value:s:"x_0_0_0_-1_-1_-1"
}

case "$1" in
  start)
    running && kill "$P" 2>/dev/null
    setsid sh "$SELF" run >/dev/null 2>&1 </dev/null &
    sleep 1; running && echo "started (pid $P)" || echo "failed to start"
    exit 0 ;;
  stop)
    if running; then kill "$P"; echo stopped; else echo "not running"; fi
    sleep 1; fi_off_all; sr_set 0 0; echo "FI + SR off"
    exit 0 ;;
  status)
    running && echo "running (pid $P)" || echo "stopped"
    tail -5 "$LOG" 2>/dev/null
    exec sh "$SELF" check ;;
  check)
    echo "fi  = $(settings get system gamecube_frame_interpolation)"
    echo "gpu = $(settings get global gpu_memc_frame_rate)"
    echo "sr  = $(settings get system vivo_sr_status) vsr = $(settings get global vsr_value_from_gamecube)"
    echo "game= $(settings get secure current_game_package)  battery=$(dumpsys battery | grep -m1 -i temperature)"
    exit 0 ;;
  sr)
    case "$2" in on) sr_set 2 1; echo "SR on";; off) sr_set 0 0; echo "SR off";; *) echo "usage: sr on|off";; esac
    exit 0 ;;
  run) ;;
  *) echo "usage: sh fi.sh start|stop|status|check|sr on|sr off"; exit 1 ;;
esac

# ---------------- daemon ----------------
running && [ "$P" != "$$" ] && kill "$P" 2>/dev/null
echo $$ > "$PIDF"
log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }
echo "$(date '+%H:%M:%S') started (pid $$)" > "$LOG"
trap 'log "stopped"; [ "$(cat "$PIDF" 2>/dev/null)" = "$$" ] && rm -f "$PIDF"; exit 0' TERM INT HUP

TEMPF=/sys/class/power_supply/battery/temp
if [ -r "$TEMPF" ]; then temp() { read T < "$TEMPF"; }
else temp() { T=$(dumpsys battery | grep -m1 -i temperature | grep -o '[0-9]*'); }; log "temp file unreadable, using dumpsys"; fi

# find a running listed game -> sets PKG SRC DST WSR
pick_game() {
  CUR=$(settings get secure current_game_package); PKG=""
  for PASS in 1 2; do
    for G in $GAMES; do
      p=${G%%:*}
      [ $PASS -eq 1 ] && [ "$p" != "$CUR" ] && continue
      if pidof -s "$p" >/dev/null; then
        R=${G#*:}; SRC=${R%%:*}; R=${R#*:}; DST=${R%%:*}; WSR=${R#*:}; PKG=$p; return
      fi
    done
  done
}

fi_on() {
  content insert --uri content://settings/system --bind name:s:gamecube_frame_interpolation --bind value:s:"1\:3\:$PID\:$SRC\:$DST"
  content insert --uri content://settings/global --bind name:s:gpu_memc_frame_rate --bind value:s:"${PKG}_0_${PID}_1_30_${SRC}_${DST}"
  [ "$WSR" = 1 ] && [ $SRON -eq 0 ] && { sr_set 2 1; SRON=1; }
}
fi_off() {
  content insert --uri content://settings/system --bind name:s:gamecube_frame_interpolation --bind value:s:"0\:-1\:$PID\:0\:0"
  content insert --uri content://settings/global --bind name:s:gpu_memc_frame_rate --bind value:s:"${PKG}_0_${PID}_0_-1_-1_-1"
  [ $SRON -eq 1 ] && { sr_set 0 0; SRON=0; }
}

PKG=""; PID=""; N=0; PAUSED=0; SRON=0; BEAT=$SECONDS
temp
while true; do
  if [ $((SECONDS - BEAT)) -ge 300 ]; then temp; log "alive, battery $T"; BEAT=$SECONDS; fi

  if [ -z "$PID" ] || [ ! -d "/proc/$PID" ]; then
    if [ -n "$PID" ]; then log "game closed"; [ $SRON -eq 1 ] && { sr_set 0 0; SRON=0; }; fi
    PID=""; pick_game
    if [ -z "$PKG" ]; then sleep $IDLE; continue; fi
    PID=$(pidof -s "$PKG"); N=0
    [ -z "$PID" ] || [ ! -d "/proc/$PID" ] && { PID=""; sleep $IDLE; continue; }
    log "now: $PKG pid $PID  $SRC->$DST  sr=$WSR"
  fi

  if [ $PAUSED -eq 1 ]; then
    temp
    if [ "$T" -le $COOL ]; then PAUSED=0; N=0; log "battery $T cooled, resuming"
    else sleep $IDLE; continue; fi
  fi

  N=$((N + 1))
  if [ $((N % 10)) -eq 1 ]; then
    temp
    if [ "$T" -ge $HOT ]; then PAUSED=1; fi_off; log "battery $T hot, FI+SR paused"; sleep $IDLE; continue; fi
    [ "$(pidof -s "$PKG")" != "$PID" ] && { PID=""; continue; }
    if [ "$WSR" = 1 ] && [ "$(settings get system vivo_sr_status)" != "2" ]; then SRON=0; log "sr reset -> rewrote"; fi
    if [ "$(settings get global gpu_memc_frame_rate)" != "${PKG}_0_${PID}_1_30_${SRC}_${DST}" ]; then fi_on; log "gpu value reset -> rewrote"; fi
  fi

  C=$(settings get system gamecube_frame_interpolation)
  if [ "$C" != "1:3:$PID:$SRC:$DST" ]; then fi_on; log "reset ($C) -> rewrote"; fi
  sleep $CHECK
done
```
