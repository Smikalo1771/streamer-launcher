#!/system/bin/sh
# Cast receiver watchdog. MacroDroid ("Cast watchdog") runs it through Shizuku, as the shell user, every 15 min.
# With setupwraith disabled, the receiver loses its Cast device-auth keys whenever its process restarts, and
# Chrome then refuses the TV. Repair: restart the receiver while setupwraith is enabled for a few seconds.
# Install: adb push tv/castwatch.sh /data/local/tmp/ ; it remembers the repaired receiver's pid next to itself.
P=com.google.android.apps.mediashell
W=com.google.android.tungsten.setupwraith
S=/data/local/tmp/castwatch.pid

pid=$(pidof $P)
[ -n "$pid" ] && [ "$pid" = "$(cat $S 2>/dev/null)" ] && exit 0

# A receiver started 40-180 s after boot was restarted (and repaired) by the "Cast after boot" macro.
if [ -n "$pid" ]; then
    start=$(( $(cut -d' ' -f22 /proc/$pid/stat) / 100 ))
    if [ "$start" -ge 40 ] && [ "$start" -le 180 ]; then
        echo "$pid" > $S
        exit 0
    fi
fi

am force-stop $P
pm enable --user 0 $W >/dev/null
sleep 8
pm disable-user --user 0 $W >/dev/null
sleep 3
pidof $P > $S
echo "cast repaired"
