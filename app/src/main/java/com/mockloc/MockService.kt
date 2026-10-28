package com.mockloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * In-app test provider: the tier that works without root, because the app-op is granted by the
 * developer-options picker (Settings writes it, a privileged process) rather than by shell - and
 * this OEM build strips MANAGE_APP_OPS_MODES from shell, so the adb route is dead here.
 *
 * Gated behind android:permission=DUMP so only adb/shell can drive it, not arbitrary apps.
 *
 * Three pieces of hardening beyond the plain push loop (REFACTOR-PLAN 1.1):
 * - self-healing patrol: a test provider can be reclaimed by the system, and fixes can drift away
 *   from the target point; both used to mean "the app quietly stopped working". The patrol notices
 *   and rebuilds the provider instead of only logging it.
 * - notification actions: 暂停/继续/停止 straight from the shade, no need to reopen the app.
 * - boot restore: [BootReceiver] replays the last spec after a reboot; if Android refuses the
 *   foreground start, it posts a tappable "恢复注入" notification instead of failing silently.
 */
class MockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var paused = false
    private var ticks = 0L
    private var healFails = 0

    // Started-only service; the interface is mandatory on Service.
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_CMD)) {
            CMD_STOP -> stopEverything()
            CMD_PAUSE -> pause()
            CMD_RESUME -> startLoop(specFromPrefs())
            "restore" -> {
                // A stored spec with no point means we were running with nothing to replay; stop
                // cleanly instead of pushing (0,0) into the Gulf of Guinea.
                val spec = specFromPrefs()
                if (spec.lat == 0.0 && spec.lng == 0.0) stopEverything() else startLoop(spec)
            }
            else -> startLoop(specFromIntent(intent))
        }
        return START_STICKY
    }

    private fun stopEverything() {
        running = false
        paused = false
        handler.removeCallbacksAndMessages(null)
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        providerNames.forEach { name ->
            runCatching {
                lm.setTestProviderEnabled(name, false)
                lm.removeTestProvider(name)
            }
        }
        markRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 暂停 keeps the service foreground but disables the providers: target apps fall back to their
     * real position immediately, and 继续 re-registers everything from the stored spec.
     */
    private fun pause() {
        running = true
        paused = true
        handler.removeCallbacksAndMessages(null)
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        providerNames.forEach { name ->
            runCatching { lm.setTestProviderEnabled(name, false) }
        }
        notify(buildNotification())
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

    private var lat = 0.0
    private var lng = 0.0
    private var acc = 5f
    private var radius = 0.0
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var altMeters = DEFAULT_ALT

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
    private fun buildFix(
        name: String,
        lat: Double,
        lng: Double,
        acc: Float,
        speedMps: Float = 0f,
        bearingDeg: Float = 0f,
    ) = Location(name).apply {
        latitude = lat
        longitude = lng
        accuracy = acc
        altitude = altMeters
        speed = speedMps
        bearing = bearingDeg
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtime() * 1_000_000L
        extras = android.os.Bundle().apply {
            putInt("satellites", Sky.SATS.size)
            putFloat("maxCn0", Sky.SATS.maxOf { it.cn0DbHz })
            putFloat("meanCn0", Sky.SATS.map { it.cn0DbHz }.average().toFloat())
        }
    }

    /**
     * Route mode (REFACTOR-PLAN 2.3): the route and its epoch anchor live in the bridge's prefs
     * (same file), so the provider loop samples the same wall-clock position every tier sees.
     * Speed and course ride in the fix, which is what makes the movement read as movement.
     */
    private fun routeSample(nowMs: Long): Route.Sample? {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val waypoints = Route.decode(p.getString(ModuleBridge.KEY_ROUTE_WPTS, "").orEmpty())
        val started = p.getString(ModuleBridge.KEY_ROUTE_STARTED, "0").orEmpty().toLongOrNull() ?: 0L
        val speed = p.getString(ModuleBridge.KEY_SPEED, "1.4").orEmpty().toDoubleOrNull() ?: 1.4
        val loop = p.getBoolean(ModuleBridge.KEY_ROUTE_LOOP, false)
        if (waypoints.size < 2 || started <= 0L) return null
        return Route.sampleElapsed(waypoints, speed, loop, started, nowMs)
    }

    /** Great-circle distance in metres, good enough at the 200m threshold this guards. */
    private fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 6371000.0 * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Read-back is no longer just a log line: a missing or drifted provider is healed on the spot
     * (re-register + immediate fix). Backoff after MAX_HEAL_FAILS consecutive failures keeps a
     * broken environment from churning a remove/add cycle every second.
     */
    private fun patrol(lm: LocationManager) {
        if (!running || paused) return
        var healed = false
        providerNames.forEach { name ->
            val loc = runCatching { lm.getLastKnownLocation(name) }.getOrNull()
            val bad = loc == null ||
                distanceMeters(loc.latitude, loc.longitude, lat, lng) > HEAL_DRIFT_LIMIT_M
            if (bad) {
                healed = true
                runCatching { lm.removeTestProvider(name) }
                register(lm, name)
                runCatching {
                    lm.setTestProviderEnabled(name, true)
                    lm.setTestProviderLocation(name, buildFix(name, lat, lng, acc))
                }
            }
        }
        if (!healed) {
            healFails = 0
            return
        }
        healFails++
        if (healFails >= MAX_HEAL_FAILS) {
            Log.w(TAG, "自愈连续失败 $healFails 次，巡检间隔拉长到 ${BACKOFF_MS}ms")
        }
        verify(lm)
    }

    /**
     * "已开启" is not a claim this service should make about itself: read the fix back out of
     * LocationManager and report what actually landed, the same way the status page does.
     */
    private fun verify(lm: LocationManager) {
        var matched = 0
        val ages = ArrayList<String>()
        providerNames.forEach { name ->
            val loc = runCatching { lm.getLastKnownLocation(name) }.getOrNull()
            if (loc != null && abs(loc.latitude - lat) < 1e-6 &&
                abs(loc.longitude - lng) < 1e-6
            ) {
                matched++
                ages += "$name:${System.currentTimeMillis() - loc.time}ms"
            }
        }
        Log.i(TAG, "读回验收 matched=$matched/${providerNames.size} age=${ages.joinToString()}")
    }

    private fun startLoop(spec: Spec) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Without the runtime grant addTestProvider still works, but nothing downstream reads us.
            Log.w(TAG, "no location permission; provider will be invisible to apps")
        }
        lat = spec.lat
        lng = spec.lng
        acc = spec.acc
        radius = spec.radius
        altMeters = spec.alt
        intervalMs = spec.intervalMs
        if (spec.providers.isNotEmpty()) providerNames = spec.providers
        if (spec.lat == 0.0 && spec.lng == 0.0) return stopEverything()

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        providerNames.forEach { name ->
            runCatching { lm.removeTestProvider(name) }
            register(lm, name)
            runCatching { lm.setTestProviderEnabled(name, true) }
        }
        running = true
        paused = false
        ticks = 0
        healFails = 0
        spec.store(this)
        markRunning(true)
        notify(buildNotification())

        handler.post(object : Runnable {
            override fun run() {
                if (!running || paused) return
                val now = System.currentTimeMillis()
                val sample = routeSample(now)
                val (baseLat, baseLng) = if (sample != null) {
                    sample.lat to sample.lng
                } else if (radius > 0.0) {
                    val dist = radius * sqrt(Random.nextDouble())
                    val bearing = Random.nextDouble() * 2.0 * Math.PI
                    lat + dist * cos(bearing) / 111132.0 to
                        lng + dist * sin(bearing) / (111412.8 * cos(Math.toRadians(lat)))
                } else {
                    lat to lng
                }
                val speed = sample?.speedMps?.toFloat() ?: 0f
                val bearing = sample?.bearingDeg?.toFloat() ?: 0f
                providerNames.forEach { name ->
                    runCatching {
                        lm.setTestProviderLocation(
                            name, buildFix(name, baseLat, baseLng, acc, speed, bearing),
                        )
                    }.onFailure {
                        Log.e(TAG, "setTestProviderLocation($name) failed: ${it.message}")
                    }
                }
                ticks++
                // Verifying every second would be noise; the patrol cadence catches a provider that
                // silently stopped accepting fixes within a few seconds.
                if (ticks % patrolEvery() == 0L) patrol(lm)
                handler.postDelayed(this, intervalMs)
            }
        })
    }

    /** 5s patrol normally; after repeated heal failures stretch to 10s so a hostile environment
     *  cannot keep us in a remove/add churn. */
    private fun patrolEvery(): Long = if (healFails >= MAX_HEAL_FAILS) {
        (BACKOFF_MS / intervalMs).coerceAtLeast(1)
    } else {
        (PATROL_MS / intervalMs).coerceAtLeast(1)
    }

    private fun markRunning(on: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_RUNNING, on).apply()
    }

    private fun notify(n: Notification) {
        runCatching { startForeground(NOTIF_ID, n) }
            .onFailure { Log.e(TAG, "startForeground failed: ${it.message}") }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "虚拟定位", NotificationManager.IMPORTANCE_LOW)
            )
        }
        fun action(cmd: String, title: String) = Notification.Action.Builder(
            null, title,
            PendingIntent.getService(
                this, cmd.hashCode(),
                Intent(this, MockService::class.java).putExtra(EXTRA_CMD, cmd),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
        return Notification.Builder(this, CHAN)
            .setContentTitle("mockloc 正在伪造定位")
            .setContentText(
                if (paused) "已暂停（目标 App 拿到真实位置）"
                else "%.5f,%.5f · %s".format(lat, lng, providerNames.joinToString("/")),
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(action(if (paused) CMD_RESUME else CMD_PAUSE, if (paused) "继续" else "暂停"))
            .addAction(action(CMD_STOP, "停止"))
            .build()
    }

    /** Everything needed to bring the loop back after reboot or a 继续 tap. */
    private data class Spec(
        val lat: Double, val lng: Double, val acc: Float, val radius: Double,
        val intervalMs: Long, val alt: Double, val providers: List<String>,
    )

    private fun specFromIntent(intent: Intent?): Spec {
        val providers = intent?.getStringExtra(EXTRA_PROVIDERS)?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: intent?.getStringExtra(EXTRA_PROVIDER)?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: DEFAULT_PROVIDERS
        return Spec(
            lat = intent?.getStringExtra(EXTRA_LAT)?.toDoubleOrNull() ?: 0.0,
            lng = intent?.getStringExtra(EXTRA_LNG)?.toDoubleOrNull() ?: 0.0,
            acc = intent?.getStringExtra(EXTRA_ACC)?.toFloatOrNull() ?: 5f,
            radius = intent?.getStringExtra(EXTRA_RADIUS)?.toDoubleOrNull() ?: 0.0,
            intervalMs = ((intent?.getStringExtra(EXTRA_INTERVAL)?.toFloatOrNull()
                ?: DEFAULT_INTERVAL_S) * 1000f).roundToInt().coerceAtLeast(200).toLong(),
            alt = intent?.getStringExtra(EXTRA_ALT)?.toDoubleOrNull() ?: DEFAULT_ALT,
            providers = providers,
        )
    }

    private fun specFromPrefs(): Spec {
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Spec(
            lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: 0.0,
            lng = p.getString(KEY_LNG, null)?.toDoubleOrNull() ?: 0.0,
            acc = p.getString(KEY_ACC, null)?.toFloatOrNull() ?: 5f,
            radius = p.getString(KEY_RADIUS, null)?.toDoubleOrNull() ?: 0.0,
            intervalMs = p.getString(KEY_INTERVAL, null)?.toLongOrNull() ?: DEFAULT_INTERVAL_MS,
            alt = p.getString(KEY_ALT, null)?.toDoubleOrNull() ?: DEFAULT_ALT,
            providers = p.getString(KEY_PROVIDERS, null)?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_PROVIDERS,
        )
    }

    private fun Spec.store(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LNG, lng.toString())
            .putString(KEY_ACC, acc.toString())
            .putString(KEY_RADIUS, radius.toString())
            .putString(KEY_INTERVAL, intervalMs.toString())
            .putString(KEY_ALT, alt.toString())
            .putString(KEY_PROVIDERS, providers.joinToString(","))
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        notify(buildNotification())
    }

    companion object {
        const val TAG = "[MockService]"
        const val CHAN = "mockloc"
        const val NOTIF_ID = 42
        const val PREFS = "settings"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_LAT = "svc_lat"
        const val KEY_LNG = "svc_lng"
        const val KEY_ACC = "svc_acc"
        const val KEY_RADIUS = "svc_radius"
        const val KEY_INTERVAL = "svc_interval_ms"
        const val KEY_ALT = "svc_alt"
        const val KEY_PROVIDERS = "svc_providers"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ACC = "acc"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_ALT = "alt"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_PROVIDERS = "providers"
        const val CMD_STOP = "stop"
        const val CMD_PAUSE = "pause"
        const val CMD_RESUME = "resume"

        val DEFAULT_PROVIDERS_LIST = "gps,network,fused"
        val DEFAULT_PROVIDERS = DEFAULT_PROVIDERS_LIST.split(',')
        const val DEFAULT_ALT = 20.0

        /**
         * Every reference project that survived an upgrade re-injects at 1s or faster: a stale fix
         * is what makes an app re-locate through its own path, which is exactly the hole 高德 walks
         * into. 15s was a battery compromise that cost correctness.
         */
        const val DEFAULT_INTERVAL_S = 1f
        const val DEFAULT_INTERVAL_MS = 1000L

        /** Patrol window and self-heal thresholds (REFACTOR-PLAN 1.1, mirrors 全域's 巡检补针). */
        const val PATROL_MS = 5000L
        const val BACKOFF_MS = 10_000L
        const val HEAL_DRIFT_LIMIT_M = 200.0
        const val MAX_HEAL_FAILS = 5

        /** tappable "恢复注入" fallback for the boot path when Android refuses the foreground start. */
        fun postRestoreNotification(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHAN, "虚拟定位", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val resume = PendingIntent.getService(
                context, 1,
                Intent(context, MockService::class.java).putExtra(EXTRA_CMD, "restore"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, CHAN)
                .setContentTitle("mockloc 开机恢复被系统拦下")
                .setContentText("点这里恢复上次的注入")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(resume)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIF_ID + 1, n) }
        }
    }
}
