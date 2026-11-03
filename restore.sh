#!/usr/bin/env bash
# Put a backup from ./backup.sh back on a Google TV Streamer (the same one after a reset, or another one): the
# launcher (built and installed by install.sh), the sideloaded apps from their APKs, the launcher's settings, config
# and screensaver photos, the disabled packages and the TV settings. Play Store apps are listed to install from the
# store. ADB over the network must be on (SETUP.md, part 2); the stock launcher is disabled last, once ours is Home.
# Usage:   ./restore.sh backups/<date_time>
#          SKIP_BUILD=1 ./restore.sh <folder>    use the launcher APK already built
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
IN=${1:?usage: ./restore.sh <backup folder>}
[ -f "$IN/apps.txt" ] || { echo "$IN is not a backup from ./backup.sh"; exit 1; }
source tv/find-tv.sh

PKG=com.filip.tvhome
FILES=/sdcard/Android/data/$PKG/files
A="$ADB -s $TV"
sh_tv() { $A shell "$@" </dev/null | tr -d '\r'; }   # </dev/null: adb would eat a while-read loop's input
echo "Obnova $IN na prehrávač $TV"

# 1. The launcher, its scripts and HomeKeyService (install.sh), and Home.
./install.sh
home=$(cat "$IN/home.txt" 2>/dev/null || true)
[ -n "$home" ] && sh_tv cmd package set-home-activity --user 0 "$home" >/dev/null && echo "  domovská obrazovka: $home"

# 2. Sideloaded apps from their APKs (split APKs together).
for dir in "$IN"/apks/*/; do
  [ -d "$dir" ] || continue
  pkg=$(basename "$dir")
  if sh_tv pm list packages "$pkg" | grep -qx "package:$pkg"; then echo "  $pkg už je nainštalovaná"; continue; fi
  apks=()
  for f in "$dir"*.apk; do apks+=("$(hostpath "$f")"); done
  if [ ${#apks[@]} -eq 1 ]; then $A install -r "${apks[0]}" </dev/null >/dev/null; else $A install-multiple -r "${apks[@]}" </dev/null >/dev/null; fi
  echo "  nainštalovaná $pkg"
done

# 3. The launcher's folder (config.json, screensaver photos, launcher-settings.json), then its settings from it.
sh_tv mkdir -p "$FILES"
$A push "$(hostpath "$IN/files")/." "$FILES/" >/dev/null 2>&1 && echo "  súbory launchera nahraté"
r=$(sh_tv am broadcast -a com.gathin.tvhome.RESTORE -n $PKG/com.gathin.tvhome.SettingsBackupReceiver)
case "$r" in *'data="ok"'*) echo "  nastavenia launchera obnovené" ;; *) echo "  POZOR: nastavenia launchera sa neobnovili" ;; esac

# 4. TV settings (accessibility comes from install.sh, which knows how to bind the helper).
while read -r ns key value; do
  case "$key" in enabled_accessibility_services|accessibility_enabled) continue ;; esac
  [ "$value" = null ] && continue
  sh_tv settings put "$ns" "$key" "$value"
done < "$IN/settings.txt"
echo "  nastavenia TV obnovené"

# 5. The debloat: disable what was disabled (the stock launcher last, after ours is Home).
while read -r pkg; do
  [ "$pkg" = com.google.android.apps.tv.launcherx ] && continue
  sh_tv pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
done < "$IN/disabled.txt"
if grep -qx com.google.android.apps.tv.launcherx "$IN/disabled.txt"; then
  sh_tv pm disable-user --user 0 com.google.android.apps.tv.launcherx >/dev/null 2>&1 || true
fi
echo "  vypnuté balíky: $(wc -l < "$IN/disabled.txt")"

# 6. Play Store apps: listed, the store installs them with the right account.
missing=""
while read -r pkg installer; do
  [ "$installer" = com.android.vending ] || continue
  sh_tv pm list packages "$pkg" | grep -qx "package:$pkg" || missing="$missing $pkg"
done < "$IN/apps.txt"
[ -n "$missing" ] && { echo "  Z Obchodu Play nainštaluj:"; for p in $missing; do echo "    $p"; done; }

echo "Hotovo. Over: ./health.sh"
