#!/system/bin/sh
# Run by the launcher (TvGuard) as the shell user through Shizuku, as soon as Shizuku is up after boot and then
# every 10 min. Each step is cheap when there is nothing to do.
# Install: adb push tv/tvguard.sh tv/castwatch.sh /data/local/tmp/
D=/data/local/tmp

# Cast: reload the receiver's device-auth keys after boot or a receiver restart (castwatch.sh).
sh $D/castwatch.sh

# Screen saver after 2 min: Ambient mode (dreamx) puts 10 min back when it updates.
[ "$(settings get system screen_off_timeout)" = 120000 ] || settings put system screen_off_timeout 120000
