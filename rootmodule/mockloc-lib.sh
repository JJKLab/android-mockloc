#!/system/bin/sh
# Shared helpers for the boot script. POSIX sh only (toybox ash), no bashisms, no awk floats
# unless awk is actually present.

CFG=/data/adb/mockloc/config.prop
PUB=/data/local/tmp/mockloc.conf

# Sourcing config.prop would break on a LABEL containing spaces, so read one key at a time.
cfg_get() {
  sed -n "s/^$1=//p" "$CFG" 2>/dev/null | head -n 1 | tr -d '\r'
}

jitter() {
  # jitter <lat> <lng> <radius_m> -> "lat lng"; empty/zero radius (or no awk) pins the centre.
  _lat=$1
  _lng=$2
  _r=$3
  if [ -z "$_r" ] || [ "$_r" = "0" ]; then
    printf '%s %s' "$_lat" "$_lng"
    return
  fi
  if ! command -v awk >/dev/null 2>&1; then
    printf '%s %s' "$_lat" "$_lng"
    return
  fi
  awk -v lat="$_lat" -v lng="$_lng" -v r="$_r" 'BEGIN {
    srand()
    d = r * sqrt(rand())
    b = rand() * 2 * 3.14159265358979
    pi = 3.14159265358979
    printf "%.7f %.7f", lat + d * cos(b) / 111132.0, lng + d * sin(b) / (111412.8 * cos(lat * pi / 180.0))
  }'
}

publish() {
  # The Xposed module runs in an app SELinux domain and cannot read /data/adb, so mirror the
  # effective config to /data/local/tmp with the shelldatafile label. Keys match 1:1, and the
  # module does its own jitter, so the centre is what gets published.
  cp "$CFG" "$PUB" 2>/dev/null || return 1
  chmod 664 "$PUB"
  chcon u:object_r:shelldatafile:s0 "$PUB" 2>/dev/null
  return 0
}

apply_once() {
  _provider=$1
  _lat=$2
  _lng=$3
  _acc=$4
  _stamp=$(($(date +%s) * 1000))
  cmd location providers set-test-provider-location "$_provider" \
    --location "$_lat",$_lng --accuracy "$_acc" --time "$_stamp"
}
