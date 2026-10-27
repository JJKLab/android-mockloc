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
    private var ticks = 0L

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
        providerNames.forEach { name ->
            runCatching {
                lm.setTestProviderEnabled(name, false)
                lm.removeTestProvider(name)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Mocking only `gps` leaks the truth: on the test device `network` kept reporting the real
     * fix while `passive` mirrored the fake one. AOSP lets a test provider replace any provider of
     * the same name, so every name an app might ask for gets covered.
     */
    private var providerNames = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused",
    )

    /**
     * API 31 replaced the 10-argument form with `ProviderProperties`. The old one still exists on
     * API 36, but every reference project that survived a system upgrade moved to the builder, and
     * the deprecated form is what a future removal would take first.
     */
    private fun register(lm: LocationManager, name: String) {
        val gps = name == LocationManager.GPS_PROVIDER
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val props = android.location.provider.ProviderProperties.Builder()
                    .setPowerUsage(android.location.Criteria.POWER_LOW)
                    .setAccuracy(
                        if (gps) android.location.Criteria.ACCURACY_FINE
                        else android.location.Criteria.ACCURACY_COARSE,
                    )
                    .setHasSatelliteRequirement(gps)
                    .setHasCellRequirement(!gps)
                    .setHasNetworkRequirement(!gps)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .build()
                lm.addTestProvider(name, props)
            } else {
                addTestProviderLegacy(lm, name, gps)
            }
        }.onFailure { Log.e(TAG, "addTestProvider($name) failed: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun addTestProviderLegacy(lm: LocationManager, name: String, gps: Boolean) =
        lm.addTestProvider(
            name,
            /* requiresNetwork */ !gps,
            /* requiresSatellite */ gps,
            /* requiresCell */ !gps,
            /* hasMonetaryCost */ false,
            /* supportsAltitude */ true,
            /* supportsSpeed */ true,
            /* supportsBearing */ true,
            android.location.Criteria.POWER_LOW,
            if (gps) android.location.Criteria.ACCURACY_FINE else android.location.Criteria.ACCURACY_COARSE,
        )

    /**
     * Amap-family SDKs drop a fix that carries no satellite information, so a bare coordinate is
     * not enough for the no-root tiers even when the provider is accepted. The numbers come from
     * the same Sky catalog the module's GNSS layer uses, so 档1 and 档3 cannot disagree about the
     * sky. (AnyWhere/AnyDoor do the same thing.)
     */
    private fun buildFix(name: String, lat: Double, lng: Double, acc: Float) =
        android.location.Location(name).apply {
            latitude = lat
            longitude = lng
            accuracy = acc
            altitude = 20.0
            speed = 0f
            bearing = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtime() * 1_000_000L
            extras = android.os.Bundle().apply {
                putInt("satellites", Sky.SATS.size)
                putFloat("maxCn0", Sky.SATS.maxOf { it.cn0DbHz })
                putFloat("meanCn0", Sky.SATS.map { it.cn0DbHz }.average().toFloat())
            }
        }

    /**
     * "已开启" is not a claim this service should make about itself: read the fix back out of
     * LocationManager and report what actually landed, the same way the status page does.
     */
    private fun verify(lm: LocationManager, lat: Double, lng: Double) {
        var matched = 0
        val ages = ArrayList<String>()
        providerNames.forEach { name ->
            val loc = runCatching { lm.getLastKnownLocation(name) }.getOrNull()
            if (loc != null && kotlin.math.abs(loc.latitude - lat) < 1e-6 &&
                kotlin.math.abs(loc.longitude - lng) < 1e-6
            ) {
                matched++
                ages += "$name:${System.currentTimeMillis() - loc.time}ms"
            }
        }
        Log.i(TAG, "读回验收 matched=$matched/${providerNames.size} age=${ages.joinToString()}")
    }

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
        intent?.getStringExtra(EXTRA_PROVIDER)?.takeIf { it.isNotBlank() }?.let { providerNames = listOf(it) }
        intent?.getStringExtra(EXTRA_PROVIDERS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.let { providerNames = it }
        val lat = intent?.getStringExtra(EXTRA_LAT)?.toDoubleOrNull() ?: return stopEverything()
        val lng = intent?.getStringExtra(EXTRA_LNG)?.toDoubleOrNull() ?: return stopEverything()
        val acc = intent?.getStringExtra(EXTRA_ACC)?.toFloatOrNull() ?: 5f
        val radius = intent?.getStringExtra(EXTRA_RADIUS)?.toDoubleOrNull() ?: 0.0
        val intervalMs = (intent?.getStringExtra(EXTRA_INTERVAL)?.toFloatOrNull() ?: DEFAULT_INTERVAL_S) * 1000f

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        providerNames.forEach { name ->
            runCatching { lm.removeTestProvider(name) }
            register(lm, name)
            runCatching { lm.setTestProviderEnabled(name, true) }
        }
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
                providerNames.forEach { name ->
                    runCatching {
                        lm.setTestProviderLocation(name, buildFix(name, jLat, jLng, acc))
                    }.onFailure {
                        Log.e(TAG, "setTestProviderLocation($name) failed: ${it.message}")
                    }
                }
                ticks++
                // Verifying every second would be noise; every ~20s is enough to catch a provider
                // that silently stopped accepting fixes.
                if (ticks % VERIFY_EVERY == 0L) verify(lm, jLat, jLng)
                handler.postDelayed(this, intervalMs.toLong())
            }
        })
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

    companion object {
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
        const val EXTRA_PROVIDERS = "providers"

        /**
         * Every reference project that survived an upgrade re-injects at 1s or faster: a stale fix
         * is what makes an app re-locate through its own path, which is exactly the hole 高德 walks
         * into. 15s was a battery compromise that cost correctness.
         */
        const val DEFAULT_INTERVAL_S = 1f
        const val VERIFY_EVERY = 20L
    }
}
