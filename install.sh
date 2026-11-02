#!/usr/bin/env bash
# Build and install this launcher on the Google TV Streamer, push the TvGuard scripts (tv/tvguard.sh,
# tv/castwatch.sh) and turn on HomeKeyService (remote's app buttons, and Home while the stock launcher is still
# enabled). Finds the player by itself (tv/find-tv.sh). The full setup, debloat and casting fix: SETUP.md.
#
# Usage:   ./install.sh                        build + install + scripts
#          SKIP_BUILD=1 ./install.sh           install the APK already built
#          SCRIPTS_ONLY=1 ./install.sh         only push the scripts in tv/ (no build, no reinstall)
#          TV=192.168.1.50:5555 ./install.sh   a given player instead of searching
# Undo:    adb -s $TV shell settings put secure enabled_accessibility_services '""'
#          adb -s $TV uninstall com.filip.tvhome
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash: keep /data/... paths as they are
source tv/find-tv.sh

PKG=com.filip.tvhome
SVC=$PKG/com.gathin.tvhome.HomeKeyService
APK=app/build/outputs/apk/release/app-release.apk
A="$ADB -s $TV"

push_scripts() {
  local f tmp
  for f in tvguard.sh castwatch.sh; do
    tmp=$(mktemp)
    tr -d '\r' < tv/$f > "$tmp"   # a CRLF checkout on Windows would break sh on the TV
    $A push "$(hostpath "$tmp")" /data/local/tmp/$f >/dev/null
    rm -f "$tmp"
  done
  $A shell chmod 755 /data/local/tmp/tvguard.sh /data/local/tmp/castwatch.sh
  echo "TvGuard scripts pushed"
}

if [ "${SCRIPTS_ONLY:-}" = 1 ]; then
  push_scripts
  exit 0
fi

if [ "${SKIP_BUILD:-}" != 1 ]; then
  if [ -z "${JAVA_HOME:-}" ]; then
    for j in "$HOME"/.jdks/jdk-17* "$HOME"/.gradle/jdks/*17* /usr/lib/jvm/*17*; do
      [ -d "$j" ] && { export JAVA_HOME=$j; break; }
    done
  fi
  export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
  ./gradlew --no-daemon -q assembleRelease
fi

# The old cleanyourtv launcher answers Home through the same kind of service: switch it off first.
if $A shell pm list packages com.gathin.tvhome | tr -d '\r' | grep -qx package:com.gathin.tvhome; then
  echo "removing the old com.gathin.tvhome"
  $A shell "settings put secure enabled_accessibility_services ''"
  $A uninstall com.gathin.tvhome >/dev/null
fi

$A push "$APK" /data/local/tmp/tvhome.apk >/dev/null
$A shell pm install -r /data/local/tmp/tvhome.apk
$A shell rm /data/local/tmp/tvhome.apk
$A shell pm grant $PKG android.permission.READ_TV_LISTINGS
$A shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
# restore_key_services: lets ServiceRepair put HomeKeyService back after a force stop (Android drops it then).
$A shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
$A shell cmd package compile -m speed -f $PKG >/dev/null

push_scripts

# Android unbinds a service whose APK was replaced; clear the list and write it back so it binds again.
$A shell "settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0"
sleep 1
$A shell "settings put secure enabled_accessibility_services $SVC; settings put secure accessibility_enabled 1"
$A shell am start -W -n $PKG/com.gathin.tvhome.MainActivity | grep -E 'Status|Error'
echo "Installed. Press Home on the remote. Check everything: ./health.sh"
