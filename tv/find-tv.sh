# Sourced by health.sh and install.sh: sets ADB and TV (<ip>:5555) and connects.
# TV from the environment wins; otherwise the last address that worked (.tv-address), otherwise every host on the
# PC's /24 networks with port 5555 open is tried and the first Google TV Streamer is taken.

ADB=${ADB:-adb}
if ! command -v "$ADB" >/dev/null 2>&1; then
  for a in "$HOME/Android/Sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb.exe" \
           "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"; do
    [ -x "$a" ] && { ADB=$a; break; }
  done
fi

_tv_file="$(dirname "${BASH_SOURCE[0]}")/../.tv-address"

_tv_is_streamer() {   # $1 = ip:port
  "$ADB" connect "$1" >/dev/null 2>&1 || return 1
  [ "$("$ADB" -s "$1" shell getprop ro.product.model 2>/dev/null | tr -d '\r')" = "Google TV Streamer" ]
}

_tv_scan() {
  local nets net i found=""
  if command -v ipconfig >/dev/null 2>&1 && ! command -v ip >/dev/null 2>&1; then
    nets=$(ipconfig | grep -i 'IPv4' | grep -oE '([0-9]+\.){3}[0-9]+' | cut -d. -f1-3)
  else
    nets=$(ip -4 -o addr show scope global 2>/dev/null | grep -oE 'inet ([0-9]+\.){3}' | cut -d' ' -f2 | sed 's/\.$//')
  fi
  for net in $nets; do
    local tmp; tmp=$(mktemp)
    for i in $(seq 1 254); do
      ( timeout 1 bash -c "exec 3<>/dev/tcp/$net.$i/5555" 2>/dev/null && echo "$net.$i" >> "$tmp" ) &
    done
    wait
    for ip in $(cat "$tmp"); do
      if _tv_is_streamer "$ip:5555"; then found="$ip:5555"; break; fi
    done
    rm -f "$tmp"
    [ -n "$found" ] && break
  done
  echo "$found"
}

if [ -z "${TV:-}" ]; then
  [ -f "$_tv_file" ] && TV=$(tr -d '\r\n' < "$_tv_file")
  if [ -z "${TV:-}" ] || ! _tv_is_streamer "$TV"; then
    echo "Hľadám prehrávač v sieti..." >&2
    TV=$(_tv_scan)
  fi
fi
if [ -z "${TV:-}" ] || ! _tv_is_streamer "$TV"; then
  echo "Prehrávač sa nenašiel. Je zapnutý, v tej istej sieti a má zapnuté ladenie cez sieť? Alebo zadaj TV=<ip>:5555" >&2
  exit 1
fi
echo "$TV" > "$_tv_file"
# Scripts that call plain "adb" (cast-fix.sh) find the same one.
case "$ADB" in */*) PATH="$(dirname "$ADB"):$PATH" ;; esac
export TV ADB PATH

# A local file path as adb (a Windows program under Git Bash) understands it.
hostpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
