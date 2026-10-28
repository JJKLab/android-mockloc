package com.mockloc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Replays the last injection after a reboot. The spec (point, providers, interval, altitude) was
 * stored by [MockService] on every start, so nothing here needs to talk to the bridge or resolve
 * a place again - it either starts the foreground service or, when Android 15 refuses a FGS start
 * from BOOT_COMPLETED, posts the tappable 恢复注入 notification instead of failing silently.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // A location-type FGS cannot start without the grant; starting one anyway produces the
        // "did not call startForeground" crash a few seconds later. Stay quiet instead.
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = context.getSharedPreferences(MockService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MockService.KEY_SERVICE_RUNNING, false)) return
        if (prefs.getString(MockService.KEY_LAT, null).isNullOrEmpty()) return
        try {
            context.startForegroundService(
                Intent(context, MockService::class.java)
                    .putExtra(MockService.EXTRA_CMD, "restore"),
            )
        } catch (t: Throwable) {
            MockService.postRestoreNotification(context)
        }
    }
}
