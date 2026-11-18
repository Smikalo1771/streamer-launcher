#!/system/bin/sh
# Run by the launcher (TvGuard) as the shell user through Shizuku, as soon as Shizuku is up after boot and then
# every 10 min. Each step is cheap when there is nothing to do.
# Install: adb push tv/tvguard.sh tv/castwatch.sh /data/local/tmp/   (or ./deploy.sh)
# What it did is logged in /data/local/tmp/tvguard.log (last 300 lines): adb shell cat /data/local/tmp/tvguard.log
D=/data/local/tmp
L=$D/tvguard.log
B=$(cat /proc/sys/kernel/random/boot_id)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> $L; }
awake() { dumpsys power 2>/dev/null | grep -qE 'mWakefulness=(Awake|Dreaming)'; }

# Keep the log short: it lives on the TV's data partition.
if [ -f $L ] && [ "$(wc -l < $L)" -gt 300 ]; then
  tail -n 200 $L > $L.tmp && mv $L.tmp $L
fi

# First run of this boot: note it, with the uptime, so the log shows when Shizuku and the launcher came up.
if [ "$(cat $D/tvguard.boot 2>/dev/null)" != "$B" ]; then
  echo "$B" > $D/tvguard.boot
  log "boot: tvguard first run, up $(cut -d. -f1 /proc/uptime) s"
fi

# Nightly restart: clears the RAM and swap that build up over days of standby. Once a night between 04:00 and
# 05:59, only in standby and after 3 h up. HDMI-CEC is off across the restart, or the boot would wake the TV at
# 4 am; the first run after boot puts the Streamer back to standby, skips this boot's One Touch Play (so the morning
# is like any other) and turns CEC on again. HomeKeyService turns CEC back on too, should this script not run.
if [ -f $D/nightly.pending ]; then
  rm -f $D/nightly.pending
  echo "$B" > $D/hdmi.boot
  awake && input keyevent KEYCODE_SLEEP
  sleep 5
  settings put global hdmi_control_enabled 1
  log "nightly: back in standby after the restart, HDMI-CEC on again"
fi
h=$(date +%H)
if { [ "$h" = 04 ] || [ "$h" = 05 ]; } && ! awake && [ "$(cut -d. -f1 /proc/uptime)" -gt 10800 ]     && [ "$(cat $D/nightly.last 2>/dev/null)" != "$(date +%Y-%m-%d)" ]; then
  date +%Y-%m-%d > $D/nightly.last
  touch $D/nightly.pending
  log "nightly: restarting (up $(cut -d. -f1 /proc/uptime) s, $(grep SwapFree /proc/meminfo | tr -s ' '))"
  settings put global hdmi_control_enabled 0
  sleep 3
  reboot
fi

# Remote buttons: HomeKeyService (YouTube, Netflix and star buttons, Home) must be on the accessibility list.
# Android drops it after a force stop, and the launcher puts it back only when Home is created anew, so a dropped
# helper could stay off for days. Only while the screen is on: with guard_off_in_standby the helper steps out on
# purpose in standby and comes back on SCREEN_ON by itself. Clear and write the list again, so a helper Android
# marked as crashed is bound again (see ServiceRepair).
P=$(pm list packages com.filip.tvhome 2>/dev/null | grep -qx package:com.filip.tvhome && echo com.filip.tvhome || echo com.gathin.tvhome)
SVC=$P/com.gathin.tvhome.HomeKeyService
list=$(settings get secure enabled_accessibility_services)
case "$list" in
  *"$SVC"*) ;;
  *)
    if awake; then
      case "$list" in null|"") new=$SVC ;; *) new="$list:$SVC" ;; esac
      settings put secure enabled_accessibility_services ""
      settings put secure accessibility_enabled 0
      sleep 1
      settings put secure enabled_accessibility_services "$new"
      settings put secure accessibility_enabled 1
      log "buttons: HomeKeyService was off (list: $list), turned it on again"
    fi
    ;;
esac

# HDMI: after boot the Streamer does not claim the TV's input by itself. Once per boot, while it is awake, send
# One Touch Play so the TV switches to its HDMI input. Retried on the next run when it was asleep or the send failed.
if [ "$(cat $D/hdmi.boot 2>/dev/null)" != "$B" ] && awake \
    && cmd hdmi_control onetouchplay 2>/dev/null | grep -q Success; then
  echo "$B" > $D/hdmi.boot
  log "hdmi: One Touch Play sent, TV switched to this input"
fi

# Cast: reload the receiver's device-auth keys after boot or a receiver restart (castwatch.sh).
out=$(sh $D/castwatch.sh)
[ -n "$out" ] && log "cast: $out"

# Screen saver: Google's own Ambient mode (dreamx Backdrop) since 2026-11-18. The launcher's photo screensaver
# (ClockDream) froze on a white picture on the OLED, so it is no longer forced back here.

# Screen saver after 2 min: Ambient mode (dreamx) puts 10 min back when it updates.
t=$(settings get system screen_off_timeout)
if [ "$t" != 120000 ]; then
  settings put system screen_off_timeout 120000
  log "screen saver: timeout was $t ms, set to 120000"
fi
