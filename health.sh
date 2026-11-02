#!/usr/bin/env bash
# Checks the Google TV Streamer in one go: launcher, remote buttons, Shizuku and TvGuard, HDMI input, casting,
# screen saver, memory, and the last lines of tvguard.log. Finds the player by itself (tv/find-tv.sh).
# Usage:   ./health.sh            check
#          FIX=1 ./health.sh      check, and fix what can be fixed from the PC (buttons, HDMI, casting, scripts)
set -uo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash: keep /data/... paths as they are
source tv/find-tv.sh

PKG=com.filip.tvhome
SVC=$PKG/com.gathin.tvhome.HomeKeyService
sh_tv() { "$ADB" -s "$TV" shell "$@" 2>/dev/null | tr -d '\r'; }
ok=0; bad=0
pass() { printf '  \033[32mOK\033[0m    %s\n' "$*"; ok=$((ok + 1)); }
fail() { printf '  \033[31mCHYBA\033[0m %s\n' "$*"; bad=$((bad + 1)); }
info() { printf '        %s\n' "$*"; }

echo "Prehrávač $TV ($(sh_tv getprop ro.product.model), Android $(sh_tv getprop ro.build.version.release), $(sh_tv uptime | sed 's/^ *//; s/,  *load.*//'))"

echo "Launcher"
home=$(sh_tv cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN | grep -m1 packageName= | sed 's/.*packageName=//')
[ "$home" = "$PKG" ] && pass "domovská obrazovka je launcher ($PKG $(sh_tv dumpsys package $PKG | grep -m1 versionName | sed 's/.*=//'))" \
  || fail "domovská obrazovka je '$home', nie $PKG"

echo "Tlačidlá na ovládači (HomeKeyService)"
list=$(sh_tv settings get secure enabled_accessibility_services)
a11y=$(sh_tv dumpsys accessibility)
if [[ "$list" == *"$SVC"* ]] && grep -q "Bound services:{Service" <<<"$a11y"; then
  pass "služba je zapnutá a beží"
else
  fail "služba nebeží (zoznam: '${list}') – YouTube, Netflix a hviezdička nebudú fungovať"
  if [ "${FIX:-}" = 1 ]; then
    sh_tv "settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services $SVC; settings put secure accessibility_enabled 1"
    info "opravené: služba znova zapnutá"
  fi
fi
grep -q "Crashed services:{}" <<<"$a11y" || fail "Android hlási službu ako spadnutú: $(grep 'Crashed services' <<<"$a11y" | sed 's/^ *//')"

echo "Shizuku a TvGuard"
[ -n "$(sh_tv pidof shizuku_server)" ] && pass "Shizuku beží" || fail "Shizuku nebeží – TvGuard (casting, HDMI, oprava tlačidiel) nepôjde"
for f in tvguard.sh castwatch.sh; do
  if [ "$(sh_tv cat /data/local/tmp/$f | md5sum)" = "$(tr -d '\r' < tv/$f | md5sum)" ]; then
    pass "$f na prehrávači je aktuálny"
  else
    fail "$f na prehrávači chýba alebo je starý"
    if [ "${FIX:-}" = 1 ]; then
      tmp=$(mktemp); tr -d '\r' < tv/$f > "$tmp"
      "$ADB" -s "$TV" push "$(hostpath "$tmp")" /data/local/tmp/$f >/dev/null && sh_tv chmod 755 /data/local/tmp/$f
      rm -f "$tmp"; info "opravené: $f nahratý"
    fi
  fi
done
boot=$(sh_tv cat /proc/sys/kernel/random/boot_id)
[ "$(sh_tv cat /data/local/tmp/tvguard.boot)" = "$boot" ] && pass "TvGuard po tomto štarte bežal" \
  || fail "TvGuard po tomto štarte ešte nebežal (Shizuku nabieha asi minútu po štarte)"

echo "HDMI"
power=$(sh_tv dumpsys power | grep -m1 mWakefulness= | sed 's/.*=//')
hdmi=$(sh_tv dumpsys hdmi_control)
info "stav prehrávača: $power"
if grep -q "isActiveSource(): true" <<<"$hdmi"; then
  pass "prehrávač je aktívny vstup TV"
elif [ "$power" = Asleep ]; then
  info "prehrávač spí, aktívny vstup sa nekontroluje"
else
  fail "TV nie je prepnutá na prehrávač"
  if [ "${FIX:-}" = 1 ]; then sh_tv cmd hdmi_control onetouchplay >/dev/null; info "opravené: TV prepnutá na prehrávač"; fi
fi

echo "Casting"
if [ "$power" = Asleep ]; then
  info "prehrávač spí, prijímač castu v spánku nepočúva – nekontroluje sa"
else
  if sh_tv netstat -ltn | grep -q ':8009 '; then pass "prijímač počúva na porte 8009"; else fail "prijímač nepočúva na porte 8009"; fi
  auth=""
  uvx=$(command -v uvx || ls "${LOCALAPPDATA:-/nonexistent}"/Microsoft/WinGet/Packages/astral-sh.uv_*/uvx.exe 2>/dev/null | head -1)
  if [ -n "$uvx" ]; then
    auth=$(timeout 90 "$uvx" -q --with pychromecast python -I tv/cast-auth-check.py "${TV%:*}" 2>&1 | tail -1)
    [[ "$auth" == "AUTH OK"* ]] && pass "Chrome kontrola: $auth" || fail "Chrome kontrola: ${auth:-bez odpovede}"
  else
    info "uvx nie je nainštalované, Chrome kontrola sa preskočí (winget install astral-sh.uv)"
  fi
  if [ "${FIX:-}" = 1 ] && { [ -n "$auth" ] && [[ "$auth" != "AUTH OK"* ]]; }; then
    ./cast-fix.sh >/dev/null 2>&1 && info "opravené: cast-fix.sh spustený"
  fi
fi

echo "Ostatné"
t=$(sh_tv settings get system screen_off_timeout)
[ "$t" = 120000 ] && pass "šetrič po 2 minútach" || fail "šetrič je na $t ms, má byť 120000 (TvGuard to opraví do 10 min)"
avail=$(sh_tv cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}')
[ "${avail:-0}" -ge 400 ] && pass "voľná pamäť ${avail} MB" || fail "málo voľnej pamäte: ${avail} MB"
idle=$(sh_tv top -b -n 1 | grep -m1 -oE '[0-9]+%idle' | tr -d '%idle')
[ -n "$idle" ] && [ "$idle" -ge 150 ] && pass "procesor je voľný ($idle % nečinný zo 400 %)" || fail "procesor je vyťažený (nečinný len ${idle:-?} % zo 400 %)"

echo "Posledné záznamy TvGuard (/data/local/tmp/tvguard.log)"
log=$(sh_tv tail -n 8 /data/local/tmp/tvguard.log)
[ -n "$log" ] && sed 's/^/        /' <<<"$log" || info "(prázdny)"

echo
if [ $bad = 0 ]; then echo "Všetko v poriadku ($ok kontrol)."; else echo "$bad problémov, $ok v poriadku.${FIX:+}"; [ "${FIX:-}" = 1 ] || echo "Oprava: FIX=1 ./health.sh"; fi
[ $bad = 0 ]
