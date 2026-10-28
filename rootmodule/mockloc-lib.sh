#!/system/bin/sh
# Shared helpers for the boot script. POSIX sh only (toybox ash), no bashisms, no awk floats
# unless awk is actually present.

CFG=/data/adb/mockloc/config.prop
INBOX=/data/local/tmp/mockloc.in
PUB=/data/local/tmp/mockloc.conf
NETCUT_STATE=/data/adb/mockloc/netcut.pkgs

# The inbox is world-writable-by-shell on purpose: KernelSU does not expose su to `adb shell`, so
# a PC must be able to hand the module a config without any root channel. One-shot semantics:
# apply it once and remove it - a persisted inbox used to win publish() forever via cfg_src, so a
# PC-era file kept shadowing every newer App pick (the "配置不一致，还要 PC 刷新" report).
sync_inbox() {
  [ -f "$INBOX" ] || return 0
  mkdir -p $(dirname "$CFG") 2>/dev/null
  cp "$INBOX" "$CFG" && rm -f "$INBOX"
  chmod 600 "$CFG" 2>/dev/null
}

# Sourcing config.prop would break on a LABEL containing spaces, so read one key at a time.
# CFG is the single authority: sync_inbox feeds it from the PC inbox, sync_app_prefs from the
# App's picks, and publish() mirrors it to the world-readable copy the module reads.
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
  # srand() with no seed keys on whole seconds: two calls inside the same second produce the same
  # offset, exactly the repetition a detector looks for (README C7). /dev/urandom gives a real
  # seed; a missing od falls back to time seeding rather than killing the loop.
  _seed=$(od -An -N4 -tu4 /dev/urandom 2>/dev/null | tr -d ' \n')
  awk -v lat="$_lat" -v lng="$_lng" -v r="$_r" -v seed="$_seed" 'BEGIN {
    if (seed != "") srand(seed + 0); else srand()
    d = r * sqrt(rand())
    b = rand() * 2 * 3.14159265358979
    pi = 3.14159265358979
    printf "%.7f %.7f", lat + d * cos(b) / 111132.0, lng + d * sin(b) / (111412.8 * cos(lat * pi / 180.0))
  }'
}

# route_pos <waypoints> <speed_mps> <started_ms> <now_ms> <loop> -> "lat lng"
# Shell-side mirror of Route.kt's progression: walk the waypoint chain linearly at speed from the
# epoch anchor. The App's tiers sample the same anchor with Catmull-Rom smoothing between points;
# the shell walks the straight segments - same waypoints, same speed, same clock, so the layers
# never disagree about "where along the route now is" by more than one segment's curvature.
route_pos() {
  _w=$1
  _s=$2
  _t0=$3
  _tn=$4
  _loop=$5
  [ -n "$_w" ] || return 0
  command -v awk >/dev/null 2>&1 || return 0
  awk -v w="$_w" -v sp="$_s" -v t0="$_t0" -v tn="$_tn" -v lp="$_loop" 'BEGIN {
    n = split(w, P, ";")
    if (n < 2) exit 0
    for (i = 1; i <= n; i++) { split(P[i], c, ","); lat[i] = c[1] + 0; lng[i] = c[2] + 0 }
    pi = 3.14159265358979
    KLAT = 111132.0
    KLng = 111412.8 * cos(((lat[1] + lat[n]) / 2.0) * pi / 180.0)
    total = 0
    for (i = 1; i < n; i++) {
      dlat = (lat[i + 1] - lat[i]) * KLAT
      dlng = (lng[i + 1] - lng[i]) * KLng
      seg[i] = sqrt(dlat * dlat + dlng * dlng)
      total += seg[i]
    }
    if (total < 1e-6) { printf "%.7f %.7f", lat[1], lng[1]; exit 0 }
    dist = sp * (tn - t0) / 1000.0
    if (dist < 0) dist = 0
    if (lp == "true" || lp == "1") dist = dist % total
    else if (dist > total) dist = total
    acc = 0
    for (i = 1; i < n; i++) {
      if (acc + seg[i] >= dist || i == n - 1) {
        f = (seg[i] > 1e-9) ? (dist - acc) / seg[i] : 0
        if (f > 1) f = 1
        if (f < 0) f = 0
        printf "%.7f %.7f", lat[i] + (lat[i + 1] - lat[i]) * f, lng[i] + (lng[i + 1] - lng[i]) * f
        exit 0
      }
      acc += seg[i]
    }
  }'
}

# NETCUT (REFACTOR-PLAN 2.5): the per-uid network cut that A15 measured as the tier-2 lever that
# moves Amap. Rules carry a marker comment so teardown can find exactly ours; the package list is
# cached and only re-resolved when the config changes (stat per app per second would churn).
netcut_teardown() {
  iptables -S OUTPUT 2>/dev/null | grep 'mockloc-netcut' | sed 's/^-A/-D/' | while read -r rule; do
    sh -c "iptables $rule" 2>/dev/null
  done
  rm -f "$NETCUT_STATE"
}

netcut_apply() {
  _pkgs=$1
  _prev=$(cat "$NETCUT_STATE" 2>/dev/null)
  [ "$_prev" = "$_pkgs" ] && return 0
  netcut_teardown
  mkdir -p "$(dirname "$NETCUT_STATE")" 2>/dev/null
  printf '%s' "$_pkgs" > "$NETCUT_STATE"
  [ -n "$_pkgs" ] || return 0
  if ! command -v iptables >/dev/null 2>&1; then
    echo "mockloc: netcut skipped, no iptables" >> /data/adb/mockloc/service.log
    return 1
  fi
  echo "$_pkgs" | tr ',' '\n' | while read -r pkg; do
    [ -n "$pkg" ] || continue
    _uid=$(stat -c %u "/data/data/$pkg" 2>/dev/null)
    [ -n "$_uid" ] || _uid=$(dumpsys package "$pkg" 2>/dev/null | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -n 1)
    if [ -z "$_uid" ]; then
      echo "mockloc: netcut cannot resolve uid for $pkg" >> /data/adb/mockloc/service.log
      continue
    fi
    iptables -I OUTPUT -m owner --uid-owner "$_uid" -m comment --comment mockloc-netcut -j DROP 2>/dev/null ||
      echo "mockloc: netcut iptables failed for $pkg (uid=$_uid)" >> /data/adb/mockloc/service.log
  done
}

# The App cannot write /data/local/tmp (an app process has no write access there), so the App
# renders its own config text into private prefs and we carry it over - but only when the App has
# been touched more recently than the published file, otherwise a PC-side `set` would be stomped.
# Last-writer-wins is the whole contract; the earlier "App says Shanghai, WeChat says Beijing" bug
# was these two channels having no rule at all.
sync_app_prefs() {
  _prefs=/data/data/com.mockloc/shared_prefs/settings.xml
  [ -r "$_prefs" ] || return 1
  # Compare against CFG (the authority), not PUB: PUB is rewritten by publish() every tick, so a
  # PUB-based check let stale App prefs re-publish over a fresh PC inbox apply.
  [ "$_prefs" -nt "$CFG" ] || return 0
  _tmp=$(mktemp "/data/local/tmp/mockloc.app.XXXXXX") || return 1
  sed -n '/name="module_conf"/,/<\/string>/p' "$_prefs"     | sed '1s|.*name="module_conf"[^>]*>||; $s|<\/string>.*||' >"$_tmp"
  [ -s "$_tmp" ] || { rm -f "$_tmp"; return 1; }
  # Android's XmlUtils writes a newline as &#10;, so without this the whole config lands on one
  # line and the module's parser reads ENABLED's value as "true&#10;GLOBAL=..." - i.e. spoofing off.
  sed -i 's/&#10;/
/g; s/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g; s/&amp;/\&/g' "$_tmp"
  cp "$_tmp" "$CFG" 2>/dev/null
  chmod 600 "$CFG" 2>/dev/null
  rm -f "$_tmp"
  return 0
}

publish() {
  # The Xposed module runs in an app SELinux domain and cannot read /data/adb, so mirror the
  # effective config to /data/local/tmp. CFG is the single authority (inbox + App prefs both
  # land there first); keys match 1:1, and the module does its own jitter, so the centre is what
  # gets published.
  cp "$CFG" "$PUB" 2>/dev/null || return 1
  chmod 664 "$PUB"
  # chcon is gone (2026-10-28): this ROM ships no chcon in shell and the module read the mirror
  # fine without any relabel (A0.1), so the line was dead weight pretending to do work.
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
