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
[ -f "$CFG" ] || [ -f "$INBOX" ] || exit 0

PROVIDERS=$(cfg_get MOCKLOC_PROVIDERS); PROVIDERS=${PROVIDERS:-gps,network,fused}
ACC=$(cfg_get MOCKLOC_ACC); ACC=${ACC:-5.0}
INTERVAL=$(cfg_get MOCKLOC_INTERVAL); INTERVAL=${INTERVAL:-15}
UP=0

# Two independent layers, so "global" and "per-app" can coexist:
#   MOCKLOC_GLOBAL  -> this loop maintains the system test provider (every app, isMock()=true)
#   MOCKLOC_ENABLED -> the Xposed module spoofs TARGETS in-process (isMock()=false)
# The config is mirrored to /data/local/tmp either way, because the module needs it even when
# the global provider is off.
while :; do
  sync_inbox
  sync_app_prefs
  if [ "$(cfg_get MOCKLOC_ENABLED)" = "true" ] && [ "$(cfg_get MOCKLOC_GLOBAL)" = "true" ]; then
    appops set 0 android:mock_location allow 2>/dev/null
    appops set 2000 android:mock_location allow 2>/dev/null
    cmd location set-location-enabled true
    set -- $(jitter "$(cfg_get MOCKLOC_LAT)" "$(cfg_get MOCKLOC_LNG)" "$(cfg_get MOCKLOC_RADIUS)")
    JLAT=$1
    JLNG=$2
    for p in $(printf '%s' "$PROVIDERS" | tr ',' ' '); do
      if [ "$UP" != 1 ]; then
        cmd location providers add-test-provider "$p"
        cmd location providers set-test-provider-enabled "$p" true
      fi
      apply_once "$p" "$JLAT" "$JLNG" "$ACC"
    done
    UP=1
  elif [ "$UP" = 1 ]; then
    for p in $(printf '%s' "$PROVIDERS" | tr ',' ' '); do
      cmd location providers set-test-provider-enabled "$p" false
      cmd location providers remove-test-provider "$p"
    done
    UP=0
    echo "mockloc: global providers torn down" >> /data/adb/mockloc/service.log
  fi
  publish
  sleep "$INTERVAL"
done &
