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
# The module's own cadence: LOOP_INTERVAL wins, INTERVAL (the in-app push rate) is only the
# fallback so pre-v2 configs keep working. Clamped to >=1s - each tick forks one cmd per provider,
# and a sub-second cadence burns CPU without making the fix any fresher.
INTERVAL=$(cfg_get MOCKLOC_LOOP_INTERVAL)
[ -n "$INTERVAL" ] || INTERVAL=$(cfg_get MOCKLOC_INTERVAL)
[ -n "$INTERVAL" ] || INTERVAL=1
case "$INTERVAL" in ''|*[!0-9]*) INTERVAL=1 ;; esac
[ "$INTERVAL" -ge 1 ] || INTERVAL=1
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
    # Route mode wins over the static point when a route and its anchor are configured; both
    # layers sample the same epoch anchor, so App-side and shell-side never split apart.
    WPTS=$(cfg_get MOCKLOC_ROUTE_WAYPOINTS)
    STARTED=$(cfg_get MOCKLOC_ROUTE_STARTED)
    if [ -n "$WPTS" ] && [ -n "$STARTED" ] && [ "$STARTED" != "0" ]; then
      SPEED=$(cfg_get MOCKLOC_SPEED_MPS); SPEED=${SPEED:-1.4}
      LOOPF=$(cfg_get MOCKLOC_ROUTE_LOOP)
      set -- $(route_pos "$WPTS" "$SPEED" "$STARTED" "$(($(date +%s) * 1000))" "$LOOPF")
      JLAT=$1
      JLNG=$2
    else
      set -- $(jitter "$(cfg_get MOCKLOC_LAT)" "$(cfg_get MOCKLOC_LNG)" "$(cfg_get MOCKLOC_RADIUS)")
      JLAT=$1
      JLNG=$2
    fi
    if [ -n "$JLAT" ] && [ -n "$JLNG" ]; then
      for p in $(printf '%s' "$PROVIDERS" | tr ',' ' '); do
        if [ "$UP" != 1 ]; then
          cmd location providers add-test-provider "$p"
          cmd location providers set-test-provider-enabled "$p" true
        fi
        apply_once "$p" "$JLAT" "$JLNG" "$ACC"
      done
      UP=1
    fi
  elif [ "$UP" = 1 ]; then
    for p in $(printf '%s' "$PROVIDERS" | tr ',' ' '); do
      cmd location providers set-test-provider-enabled "$p" false
      cmd location providers remove-test-provider "$p"
    done
    UP=0
    echo "mockloc: global providers torn down" >> /data/adb/mockloc/service.log
  fi
  # Per-uid network cut is independent of GLOBAL: it is lever ② on its own (A15).
  netcut_apply "$(cfg_get MOCKLOC_NETCUT)"
  publish
  sleep "$INTERVAL"
done &
