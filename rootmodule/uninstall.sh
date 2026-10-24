#!/system/bin/sh
PATH=/system/bin:/system/xbin:/sbin:$PATH
cmd location providers set-test-provider-enabled gps false 2>/dev/null
cmd location providers remove-test-provider gps 2>/dev/null
appops set 2000 android:mock_location deny 2>/dev/null
appops set 0 android:mock_location deny 2>/dev/null
rm -f /data/local/tmp/mockloc.conf
rm -rf /data/adb/mockloc
