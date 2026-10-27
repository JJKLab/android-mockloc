#!/system/bin/sh
# Shared helpers for the boot script. POSIX sh only (toybox ash), no bashisms, no awk floats
# unless awk is actually present.

CFG=/data/adb/mockloc/config.prop
INBOX=/data/local/tmp/mockloc.in
PUB=/data/local/tmp/mockloc.conf

# The inbox is world-writable-by-shell on purpose: KernelSU does not expose su to `adb shell`, so
# a PC must be able to hand the module a config without any root channel. The module then mirrors
# it under /data/adb, which is where a manual on-device edit would live.
sync_inbox() {
  [ -f "$INBOX" ] || return 0
  mkdir -p $(dirname "$CFG") 2>/dev/null
  cmp -s "$INBOX" "$CFG" || cp "$INBOX" "$CFG"
  chmod 600 "$CFG" 2>/dev/null
}

cfg_src() {
  if [ -f "$INBOX" ]; then printf '%s' "$INBOX"; else printf '%s' "$CFG"; fi
}

# Sourcing config.prop would break on a LABEL containing spaces, so read one key at a time.
cfg_get() {
  sed -n "s/^$1=//p" "$(cfg_src)" 2>/dev/null | head -n 1 | tr -d '\r'
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

# The App cannot write /data/local/tmp (an app process has no write access there), so the App
# renders its own config text into private prefs and we carry it over - but only when the App has
# been touched more recently than the published file, otherwise a PC-side `set` would be stomped.
# Last-writer-wins is the whole contract; the earlier "App says Shanghai, WeChat says Beijing" bug
# was these two channels having no rule at all.
sync_app_prefs() {
  _prefs=/data/data/com.mockloc/shared_prefs/settings.xml
  [ -r "$_prefs" ] || return 1
  [ "$_prefs" -nt "$PUB" ] || return 0
  _tmp=$(mktemp "/data/local/tmp/mockloc.app.XXXXXX") || return 1
  sed -n '/name="module_conf"/,/<\/string>/p' "$_prefs"     | sed '1s|.*name="module_conf"[^>]*>||; $s|<\/string>.*||' >"$_tmp"
  [ -s "$_tmp" ] || { rm -f "$_tmp"; return 1; }
  # Android's XmlUtils writes a newline as &#10;, so without this the whole config lands on one
  # line and the module's parser reads ENABLED's value as "true&#10;GLOBAL=..." - i.e. spoofing off.
  sed -i 's/&#10;/
/g; s/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g; s/&amp;/\&/g' "$_tmp"
  cp "$_tmp" "$PUB" && chmod 664 "$PUB"
  cp "$_tmp" "$CFG" 2>/dev/null
  rm -f "$_tmp"
  return 0
}

publish() {
  # The Xposed module runs in an app SELinux domain and cannot read /data/adb, so mirror the
  # effective config to /data/local/tmp with the shelldatafile label. Keys match 1:1, and the
  # module does its own jitter, so the centre is what gets published.
  cp "$(cfg_src)" "$PUB" 2>/dev/null || return 1
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
