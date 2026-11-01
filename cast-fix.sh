#!/usr/bin/env bash
# Restore the Cast receiver's factory credentials after a reboot.
# The credentials live in a provider inside setupwraith, which stays disabled so its RecoveryActivity
# cannot claim Home; they survive only in the receiver's process, so this runs again after every reboot.
# Normally not needed: the launcher (TvGuard) runs tv/tvguard.sh through Shizuku about a minute after every
# boot and every 10 min, which does the same on the TV. Use this when Shizuku is not running.
# Usage: TV=<ip>:5555 ./cast-fix.sh
set -euo pipefail
TV=${TV:?set TV=<ip>:5555}
A="adb -s $TV shell"
adb connect "$TV" >/dev/null
$A am force-stop com.google.android.apps.mediashell
$A pm enable --user 0 com.google.android.tungsten.setupwraith >/dev/null
sleep 8
$A pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null
if $A logcat -d 2>/dev/null | grep -q ClientAuthSigner; then echo "casting restored"; else echo "signer not seen; run again"; fi
