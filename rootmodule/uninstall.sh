#!/system/bin/sh
PATH=/system/bin:/system/xbin:/sbin:$PATH
cmd location providers set-test-provider-enabled gps false 2>/dev/null
cmd location providers set-test-provider-enabled network false 2>/dev/null
cmd location providers set-test-provider-enabled fused false 2>/dev/null
cmd location providers remove-test-provider gps 2>/dev/null
cmd location providers remove-test-provider network 2>/dev/null
cmd location providers remove-test-provider fused 2>/dev/null
appops set 2000 android:mock_location deny 2>/dev/null
appops set 0 android:mock_location deny 2>/dev/null
# Flush the per-uid DROP rules this module installed before the state file that names them is gone.
iptables -S OUTPUT 2>/dev/null | grep 'mockloc-netcut' | sed 's/^-A/-D/' | while read -r rule; do
  sh -c "iptables $rule" 2>/dev/null
done
rm -f /data/local/tmp/mockloc.conf
rm -rf /data/adb/mockloc
