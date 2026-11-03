#!/usr/bin/env bash
# Repair casting from the PC: runs the TV's own watchdog (tv/castwatch.sh) right now, forcing a repair.
# Normally not needed: the launcher (TvGuard) runs it through Shizuku after every boot and every 10 min.
# Use this when Shizuku is not running, or after `./health.sh` reports "cert 0 B".
# Usage: ./cast-fix.sh            (finds the TV itself; TV=<ip>:5555 ./cast-fix.sh for a given one)
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash: keep /data/... paths as they are
source tv/find-tv.sh

# The current watchdog, with LF line ends (a CRLF checkout on Windows would break sh on the TV).
tmp=$(mktemp)
tr -d '\r' < tv/castwatch.sh > "$tmp"
"$ADB" -s "$TV" push "$(hostpath "$tmp")" /data/local/tmp/castwatch.sh >/dev/null 2>&1
rm -f "$tmp"
# Forget the last repaired receiver, so the watchdog repairs this one even if it looks fine.
"$ADB" -s "$TV" shell "rm -f /data/local/tmp/castwatch.pid; sh /data/local/tmp/castwatch.sh"
