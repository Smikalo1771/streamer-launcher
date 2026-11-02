#!/system/bin/sh
# Run by the launcher (TvGuard) as the shell user through Shizuku, as soon as Shizuku is up after boot and then
# every 10 min. Each step is cheap when there is nothing to do.
# Install: adb push tv/tvguard.sh tv/castwatch.sh /data/local/tmp/
D=/data/local/tmp

# HDMI: after boot the Streamer does not claim the TV's input by itself. Once per boot, while it is awake, send
# One Touch Play so the TV switches to its HDMI input. Retried on the next run when it was asleep or the send failed.
B=$(cat /proc/sys/kernel/random/boot_id)
if [ "$(cat $D/hdmi.boot 2>/dev/null)" != "$B" ] \
    && dumpsys power 2>/dev/null | grep -qE 'mWakefulness=(Awake|Dreaming)' \
    && cmd hdmi_control onetouchplay 2>/dev/null | grep -q Success; then
  echo "$B" > $D/hdmi.boot
fi

# Cast: reload the receiver's device-auth keys after boot or a receiver restart (castwatch.sh).
sh $D/castwatch.sh

# Screen saver after 2 min: Ambient mode (dreamx) puts 10 min back when it updates.
[ "$(settings get system screen_off_timeout)" = 120000 ] || settings put system screen_off_timeout 120000
