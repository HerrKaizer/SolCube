#!/system/bin/sh
# Keeps vivo GPU frame interpolation (60 -> 120) on for the games listed below,
# and pauses it while the battery is hot.
# Start / restart (aShell):  setsid sh /sdcard/Download/fi120.sh >/dev/null 2>&1 </dev/null &
#   (starting it again automatically stops the old copy)
# Stop:    P=$(cat /sdcard/Download/fi120.pid 2>/dev/null); grep -q fi120 /proc/$P/cmdline 2>/dev/null && kill $P && echo stopped || echo "not running"
# Status:  P=$(cat /sdcard/Download/fi120.pid 2>/dev/null); grep -q fi120 /proc/$P/cmdline 2>/dev/null && echo running || echo stopped
# Add a game: put its package name in PKGS, separated by spaces.

PKGS="com.miHoYo.GenshinImpact com.supercell.brawlstars com.supercell.clashroyale"
SRC=45
DST=90
HOT=450     # pause interpolation at this battery temperature (tenths of a degree: 450 = 45.0 C)
COOL=420    # resume once it has cooled to this (420 = 42.0 C)
CHECK=0.5   # seconds between checks while a listed game is running
IDLE=5      # seconds between checks when none is running, or while paused for heat
DIR=/sdcard/Download
LOG=$DIR/fi120.log
PIDF=$DIR/fi120.pid
TEMPF=/sys/class/power_supply/battery/temp

# stop an older copy if one is running, then record ours
# (only if that PID really is this script - after a reboot the number may belong to something else)
OLD=$(cat "$PIDF" 2>/dev/null)
[ -n "$OLD" ] && [ "$OLD" != "$$" ] && grep -q fi120 "/proc/$OLD/cmdline" 2>/dev/null && kill "$OLD" 2>/dev/null
echo $$ > "$PIDF"

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }
echo "$(date '+%H:%M:%S') started (pid $$), games: $PKGS" > "$LOG"
trap 'log "stopped by TERM/INT/HUP"; [ "$(cat "$PIDF" 2>/dev/null)" = "$$" ] && rm -f "$PIDF"; exit 0' TERM INT HUP

# battery temperature in tenths of a degree; reading the system file costs no extra process
if [ -r "$TEMPF" ]; then
  temp() { read T < "$TEMPF"; }
else
  temp() { T=$(dumpsys battery | grep -m1 -i temperature | grep -o '[0-9]*'); }
  log "battery temp file not readable, using dumpsys (slightly heavier)"
fi

pick_game() {
  CUR=$(settings get secure current_game_package)
  for P in $PKGS; do
    [ "$P" = "$CUR" ] && pidof -s "$P" >/dev/null && { echo "$P"; return; }
  done
  for P in $PKGS; do
    pidof -s "$P" >/dev/null && { echo "$P"; return; }
  done
}

write_on() {
  content insert --uri content://settings/system --bind name:s:gamecube_frame_interpolation --bind value:s:"1\:3\:$PID\:$SRC\:$DST"
  content insert --uri content://settings/global --bind name:s:gpu_memc_frame_rate --bind value:s:"${PKG}_0_${PID}_1_30_${SRC}_${DST}"
}
write_off() {
  content insert --uri content://settings/system --bind name:s:gamecube_frame_interpolation --bind value:s:"0\:-1\:$PID\:0\:0"
  content insert --uri content://settings/global --bind name:s:gpu_memc_frame_rate --bind value:s:"${PKG}_0_${PID}_0_-1_-1_-1"
}

PKG=""; PID=""; N=0; PAUSED=0; BEAT=$SECONDS
temp

while true; do
  if [ $((SECONDS - BEAT)) -ge 300 ]; then
    temp; log "alive, battery $T"; BEAT=$SECONDS
  fi

  # find the game (only when we don't have a live one)
  if [ -z "$PID" ] || [ ! -d "/proc/$PID" ]; then
    [ -n "$PID" ] && log "game closed"
    PID=""
    PKG=$(pick_game)
    if [ -z "$PKG" ]; then sleep $IDLE; continue; fi
    PID=$(pidof -s "$PKG"); N=0
    if [ -z "$PID" ] || [ ! -d "/proc/$PID" ]; then PID=""; sleep $IDLE; continue; fi
    log "now: $PKG (PID $PID)"
  fi

  # paused for heat: check every IDLE seconds whether it has cooled down
  if [ $PAUSED -eq 1 ]; then
    temp
    if [ "$T" -le $COOL ]; then
      PAUSED=0; N=0; log "battery $T -> cooled, resuming"
    else
      sleep $IDLE; continue
    fi
  fi

  # every ~5 s: battery temperature, confirm the game PID, check the second setting
  N=$((N + 1))
  if [ $((N % 10)) -eq 1 ]; then
    temp
    if [ "$T" -ge $HOT ]; then
      PAUSED=1; write_off; log "battery $T -> hot, interpolation paused"
      sleep $IDLE; continue
    fi
    [ "$(pidof -s "$PKG")" != "$PID" ] && { PID=""; continue; }
    if [ "$(settings get global gpu_memc_frame_rate)" != "${PKG}_0_${PID}_1_30_${SRC}_${DST}" ]; then
      write_on; log "gpu value reset -> rewrote"
    fi
  fi

  # the setting vivo keeps resetting: one cheap check each loop
  C=$(settings get system gamecube_frame_interpolation)
  if [ "$C" != "1:3:$PID:$SRC:$DST" ]; then
    write_on; log "reset ($C) -> rewrote"
  fi
  sleep $CHECK
done
