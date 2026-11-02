#!/system/bin/sh
# Cast receiver watchdog, run as the shell user through Shizuku by tv/tvguard.sh (the launcher's TvGuard calls it
# right after boot and every 10 min). It can also be run by hand: adb shell sh /data/local/tmp/castwatch.sh
# With setupwraith disabled, the receiver loses its Cast device-auth keys whenever its process restarts, and
# Chrome then refuses the TV. Repair: restart the receiver while setupwraith is enabled for a few seconds.
# Install: adb push tv/castwatch.sh /data/local/tmp/ ; it remembers the repaired receiver next to itself, as
# "<pid> <boot id>", so a pid left from the previous boot never passes for the current receiver.
P=com.google.android.apps.mediashell
W=com.google.android.tungsten.setupwraith
S=/data/local/tmp/castwatch.pid
B=$(cat /proc/sys/kernel/random/boot_id)

# The receiver can also stop listening without its process restarting: after a standby right after boot it logs
# "Cast receiver stopped." and does not start again on wake. Only judged while the TV is awake or in the screen
# saver, because in standby a stopped receiver is normal. 1F49 = port 8009, 0A = LISTEN.
listening() { grep -qiE '^ *[0-9]+: [0-9A-F]+:1F49 [0-9A-F]+:0000 0A ' /proc/net/tcp6 /proc/net/tcp 2>/dev/null; }
awake() { dumpsys power 2>/dev/null | grep -E 'mWakefulness=(Awake|Dreaming)' >/dev/null; }

pid=$(pidof $P)
if [ -n "$pid" ] && [ "$pid $B" = "$(cat $S 2>/dev/null)" ]; then
  listening || ! awake || { sleep 15; listening || ! awake; } && exit 0
fi

am force-stop $P
pm enable --user 0 $W >/dev/null
sleep 8
pm disable-user --user 0 $W >/dev/null
sleep 3
echo "$(pidof $P) $B" > $S
echo "cast repaired"
