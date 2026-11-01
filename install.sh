#!/usr/bin/env bash
# Build and install this launcher on the Google TV Streamer and turn on HomeKeyService (remote's app buttons,
# and Home while the stock launcher is still enabled). The full setup, debloat and casting fix: SETUP.md.
#
# Usage:   TV=192.168.1.50:5555 ./install.sh            build + install
#          SKIP_BUILD=1 TV=... ./install.sh                install the APK already built
# Undo:    adb -s $TV shell settings put secure enabled_accessibility_services '""'
#          adb -s $TV uninstall com.filip.tvhome
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.filip.tvhome
SVC=$PKG/com.gathin.tvhome.HomeKeyService
TV=${TV:?set TV=<ip>:5555}
APK=app/build/outputs/apk/release/app-release.apk
A="adb -s $TV"

if [ "${SKIP_BUILD:-}" != 1 ]; then
  export JAVA_HOME=${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2}
  export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
  ./gradlew --no-daemon -q assembleRelease
fi

adb connect "$TV" >/dev/null
# The old cleanyourtv launcher answers Home through the same kind of service: switch it off first.
if $A shell pm list packages com.gathin.tvhome | grep -qx package:com.gathin.tvhome; then
  echo "removing the old com.gathin.tvhome"
  $A shell "settings put secure enabled_accessibility_services ''"
  $A uninstall com.gathin.tvhome >/dev/null
fi

$A push "$APK" /data/local/tmp/tvhome.apk >/dev/null
$A shell pm install -r /data/local/tmp/tvhome.apk
$A shell rm /data/local/tmp/tvhome.apk
$A shell pm grant $PKG android.permission.READ_TV_LISTINGS
$A shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
$A shell cmd package compile -m speed -f $PKG >/dev/null

# Android unbinds a service whose APK was replaced; clear the list and write it back so it binds again.
$A shell "settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0"
sleep 1
$A shell "settings put secure enabled_accessibility_services $SVC; settings put secure accessibility_enabled 1"
$A shell am start -W -n $PKG/com.gathin.tvhome.MainActivity | grep -E 'Status|Error'
echo "Installed. Press Home on the remote."
