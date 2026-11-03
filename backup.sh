#!/usr/bin/env bash
# Back up the Google TV Streamer to the PC: launcher settings, config.json and the screensaver photos, the TV settings
# this setup changes, the list of apps (and which are disabled), and the APKs of apps not from the Play Store
# (IPTV, Nuvio...), which a reset would lose for good. Restore with ./restore.sh <folder>.
# Usage:   ./backup.sh              into backups/<date_time>
#          ./backup.sh <folder>     into that folder
#          NO_APKS=1 ./backup.sh    without the APKs (smaller, but sideloaded apps must be found again)
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash: keep /data/... paths as they are
source tv/find-tv.sh

PKG=com.filip.tvhome
FILES=/sdcard/Android/data/$PKG/files
OUT=${1:-backups/$(date +%Y-%m-%d_%H%M)}
A="$ADB -s $TV"
sh_tv() { $A shell "$@" </dev/null | tr -d '\r'; }   # </dev/null: adb would eat a while-read loop's input
mkdir -p "$OUT/files" "$OUT/apks"
echo "Záloha prehrávača $TV do $OUT"

# 1. Launcher settings (columns, wallpaper, the remote buttons' apps, voice names...) to launcher-settings.json.
r=$(sh_tv am broadcast -a com.gathin.tvhome.BACKUP -n $PKG/com.gathin.tvhome.SettingsBackupReceiver)
case "$r" in *'data="ok"'*) echo "  nastavenia launchera uložené" ;; *) echo "  POZOR: nastavenia launchera sa neuložili ($r)" ;; esac

# 2. The launcher's folder: launcher-settings.json, config.json, wallpapers/ (screensaver photos).
$A pull "$FILES/." "$(hostpath "$OUT/files")" >/dev/null 2>&1 && echo "  súbory launchera: $(find "$OUT/files" -type f | wc -l)"

# 3. TV settings this setup relies on.
{
  for k in screensaver_components screensaver_enabled enabled_accessibility_services accessibility_enabled; do
    echo "secure $k $(sh_tv settings get secure $k)"
  done
  echo "system screen_off_timeout $(sh_tv settings get system screen_off_timeout)"
} > "$OUT/settings.txt"
# Home as package/class (resolve-activity prints the class as name= and the package as packageName=).
sh_tv cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN \
  | awk -F= '/^ *name=/ && !c {c=$2} /^ *packageName=/ && !p {p=$2} END {if (c && p) print p "/" c}' \
  > "$OUT/home.txt" || true
echo "  nastavenia TV: $(wc -l < "$OUT/settings.txt")"

# 4. Apps: every user app with its installer, and every disabled package (the debloat).
sh_tv pm list packages -3 -i | sed 's/^package://; s/  installer=/ /' | sort > "$OUT/apps.txt"
sh_tv pm list packages -d | sed 's/^package://' | sort > "$OUT/disabled.txt"
echo "  appky: $(wc -l < "$OUT/apps.txt"), vypnuté balíky: $(wc -l < "$OUT/disabled.txt")"

# 5. APKs of apps not from the Play Store (they cannot be installed again from the store).
if [ "${NO_APKS:-}" != 1 ]; then
  while read -r pkg installer; do
    [ "$pkg" = "$PKG" ] && continue                          # the launcher is built from this repo
    [ "$installer" = com.android.vending ] && continue      # Play Store: reinstall from there
    mkdir -p "$OUT/apks/$pkg"
    for path in $(sh_tv pm path "$pkg" | sed 's/^package://'); do
      $A pull "$path" "$(hostpath "$OUT/apks/$pkg/")" </dev/null >/dev/null 2>&1
    done
    echo "  APK: $pkg ($(du -sh "$OUT/apks/$pkg" | cut -f1))"
  done < "$OUT/apps.txt"
fi

echo "Hotovo: $OUT ($(du -sh "$OUT" | cut -f1)). Obnova: ./restore.sh $OUT"
