package com.mockloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * In-app test provider: the tier that works without root, because the app-op is granted by the
 * developer-options picker (Settings writes it, a privileged process) rather than by shell - and
 * this OEM build strips MANAGE_APP_OPS_MODES from shell, so the adb route is dead here.
 *
 * Gated behind android:permission=DUMP so only adb/shell can drive it, not arbitrary apps.
 */
class MockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    // Started-only service; the interface is mandatory on Service.
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_CMD)) {
            "stop" -> stopEverything()
            else -> startLoop(intent)
        }
        return START_STICKY
    }

    private fun stopEverything() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        runCatching {
            lm.setTestProviderEnabled(providerName, false)
            lm.removeTestProvider(providerName)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var providerName = LocationManager.GPS_PROVIDER

    // The 10-arg addTestProvider is deprecated since API 31 but still present on API 36
    // (LocationManager.java:2079); the replacement needs a Build.VERSION branch for minSdk 29 and
    // does nothing extra for us.
    @Suppress("DEPRECATION")
    private fun startLoop(intent: Intent?) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Without the runtime grant addTestProvider still works, but nothing downstream reads us.
            Log.w(TAG, "no location permission; provider will be invisible to apps")
        }
        providerName = intent?.getStringExtra(EXTRA_PROVIDER) ?: LocationManager.GPS_PROVIDER
        val lat = intent?.getStringExtra(EXTRA_LAT)?.toDoubleOrNull() ?: return stopEverything()
        val lng = intent?.getStringExtra(EXTRA_LNG)?.toDoubleOrNull() ?: return stopEverything()
        val acc = intent?.getStringExtra(EXTRA_ACC)?.toFloatOrNull() ?: 5f
        val radius = intent?.getStringExtra(EXTRA_RADIUS)?.toDoubleOrNull() ?: 0.0
        val intervalMs = (intent?.getStringExtra(EXTRA_INTERVAL)?.toFloatOrNull() ?: 15f) * 1000f

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        runCatching { lm.removeTestProvider(providerName) }
        lm.addTestProvider(
            providerName,
            false, false, false, false, true, true, true,
            android.location.Criteria.POWER_LOW,
            android.location.Criteria.ACCURACY_FINE,
        )
        lm.setTestProviderEnabled(providerName, true)
        running = true

        handler.post(object : Runnable {
            override fun run() {
                if (!running) return
                val (jLat, jLng) = if (radius > 0.0) {
                    val dist = radius * kotlin.math.sqrt(Random.nextDouble())
                    val bearing = Random.nextDouble() * 2.0 * Math.PI
                    lat + dist * cos(bearing) / 111132.0 to
                        lng + dist * sin(bearing) / (111412.8 * cos(Math.toRadians(lat)))
                } else {
                    lat to lng
                }
                runCatching {
                    lm.setTestProviderLocation(providerName, buildFix(jLat, jLng, acc))
                }.onFailure {
                    Log.e(TAG, "setTestProviderLocation failed: ${it.message}")
                    stopEverything()
                }
                handler.postDelayed(this, intervalMs.toLong())
            }
        })
    }

    private fun buildFix(lat: Double, lng: Double, acc: Float) =
        android.location.Location(providerName).apply {
            latitude = lat
            longitude = lng
            accuracy = acc
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtime() * 1_000_000L
        }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "虚拟定位", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHAN)
            .setContentTitle("mockloc 正在伪造定位")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "[MockService]"
        const val CHAN = "mockloc"
        const val NOTIF_ID = 42
        const val EXTRA_CMD = "cmd"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ACC = "acc"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_PROVIDER = "provider"
    }
}
