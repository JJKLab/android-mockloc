#!/system/bin/sh
PATH=/system/bin:/system/xbin:/sbin:$PATH
MODDIR=${0%/*}
. "$MODDIR/mockloc-lib.sh"

# The location service is not up the moment service.sh runs; injecting before it would silently
# no-op, so block on the real boot signal instead of a fixed sleep.
i=0
while [ "$i" -lt 90 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 2
  i=$((i + 1))
done
cmd location is-location-enabled >/dev/null 2>&1 || exit 0
[ -f "$CFG" ] || exit 0

[ "$(cfg_get MOCKLOC_ENABLED)" = "true" ] || exit 0

PROVIDER=$(cfg_get MOCKLOC_PROVIDER); PROVIDER=${PROVIDER:-gps}
ACC=$(cfg_get MOCKLOC_ACC); ACC=${ACC:-5.0}
INTERVAL=$(cfg_get MOCKLOC_INTERVAL); INTERVAL=${INTERVAL:-15}

appops set 0 android:mock_location allow 2>/dev/null
appops set 2000 android:mock_location allow 2>/dev/null
cmd location set-location-enabled true
cmd location providers add-test-provider "$PROVIDER"
cmd location providers set-test-provider-enabled "$PROVIDER" true

while :; do
  # Re-read every cycle: `mockloc.py set --persist` then needs no reboot, only one interval.
  if [ "$(cfg_get MOCKLOC_ENABLED)" != "true" ]; then
    cmd location providers set-test-provider-enabled "$PROVIDER" false
    cmd location providers remove-test-provider "$PROVIDER"
    publish
    exit 0
  fi
  LAT=$(cfg_get MOCKLOC_LAT)
  LNG=$(cfg_get MOCKLOC_LNG)
  RADIUS=$(cfg_get MOCKLOC_RADIUS)
  set -- $(jitter "$LAT" "$LNG" "$RADIUS")
  apply_once "$PROVIDER" "$1" "$2" "$ACC"
  publish
  sleep "$INTERVAL"
done &
