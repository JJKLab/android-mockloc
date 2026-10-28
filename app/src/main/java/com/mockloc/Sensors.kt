package com.mockloc

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two optional layers on top of the mock-flag cleanup:
 *
 * - GNSS: a mocked "gps" provider leaves the real satellite count at zero, which is exactly the
 *   inconsistency a detecting app looks for. Registration is only swallowed once a synthetic
 *   GnssStatus can actually be produced - otherwise the app would receive nothing at all, which
 *   is worse than receiving a real (contradicting) status.
 * - WiFi / cell: removes the inputs that network-positioning SDKs feed to their own servers.
 *   Off by default because it genuinely breaks those apps' WiFi features.
 *
 * Two phase-3 upgrades (REFACTOR-PLAN 3.4/3.5):
 * - FORGE_WIFI swaps "return an empty scan list" (itself an anomaly - there is always *some*
 *   AP around) for a small self-consistent AP set derived deterministically from the injected
 *   point, so rescans do not invent a brand-new environment every second.
 * - SENSOR_FREEZE stops motion sensors from contradicting a static position: the listener the
 *   app registers is swapped for a proxy that rewrites events in place (accelerometer at rest,
 *   gyro silent, step counter frozen, step detector silent).
 */
class Sensors(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Sensors]"
    private val kit = HookKit(module, tag)
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = ArrayList<GnssStatus.Callback>()

    fun init() {
        Config.refresh()
        if (Config.gnssSpoof) hookGnssStatusGetters()
        // Per package: these hooks are installed once per process, and blinding breaks the host
        // app's own WiFi features, so only the apps that need it should pay for it.
        if (Config.blindWifiFor(pkg)) {
            if (Config.forgeWifi) hookWifiForge() else hookWifi()
        }
        if (Config.blindCellFor(pkg)) hookCell()
        if (Config.sensorFreeze) hookSensors()
    }

    private fun active() = SpoofLogic.isActive(pkg)

    /**
     * Every getter here is declared `float` in the framework, so the replacements have to box a
     * Float and not a Double - a wrong box type is a ClassCastException inside the target app.
     */
    /**
     * Every getter here is declared `float` in the framework, so the replacements have to box a
     * Float and not a Double - a wrong box type is a ClassCastException inside the target app.
     *
     * The numbers come from Sky, the same catalog the measurement and NMEA streams use, so the
     * three surfaces cannot disagree about which satellites are visible or how strong they are.
     */
    private fun hookGnssStatusGetters() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val statusClass = kit.findClass(classLoader, GnssStatus::class.java.name) ?: return

        kit.install(kit.methodsOf(statusClass, "getSatelliteCount")) { chain ->
            if (active()) Sky.SATS.size else chain.proceed()
        }
        // An out-of-range index must fall through rather than throw inside the target app.
        fun <T : Any> answer(value: (Sky.Sat) -> T): (XposedInterface.Chain) -> Any? = { chain ->
            val index = chain.args.firstOrNull() as? Int
            val sat = index?.let { Sky.SATS.getOrNull(it) }
            if (active() && sat != null) value(sat) else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getSvid", Int::class.javaPrimitiveType!!),
            answer { it.svid })
        kit.install(kit.methodsOf(statusClass, "getConstellationType", Int::class.javaPrimitiveType!!),
            answer { CONSTELLATION_GPS })
        kit.install(kit.methodsOf(statusClass, "getCn0DbHz", Int::class.javaPrimitiveType!!),
            answer { it.cn0DbHz })
        kit.install(kit.methodsOf(statusClass, "getBasebandCn0DbHz", Int::class.javaPrimitiveType!!),
            answer { it.cn0DbHz - 4f })
        kit.install(kit.methodsOf(statusClass, "getElevationDegrees", Int::class.javaPrimitiveType!!),
            answer { it.elevationDeg.toFloat() })
        kit.install(kit.methodsOf(statusClass, "getAzimuthDegrees", Int::class.javaPrimitiveType!!),
            answer { it.azimuthDeg.toFloat() })
        kit.install(kit.methodsOf(statusClass, "usedInFix", Int::class.javaPrimitiveType!!),
            answer { true })
        listOf("hasEphemerisData", "hasAlmanacData", "hasCarrierFrequencyHz", "hasBasebandCn0DbHz")
            .forEach { name ->
                kit.install(kit.methodsOf(statusClass, name, Int::class.javaPrimitiveType!!),
                    answer { true })
            }
        kit.install(kit.methodsOf(statusClass, "getCarrierFrequencyHz", Int::class.javaPrimitiveType!!),
            answer { FAKE_L1 })

        startStatusFeed()
    }

    /**
     * Only takes over registration if a synthetic status can be built; otherwise the app would be
     * left with no satellite data at all, which is worse than a contradicting real status.
     */
    private fun startStatusFeed() {
        val dummy = buildDummyStatus() ?: run {
            Diagnostics.note("$tag: 造不出 GnssStatus（Builder 签名变了？），保留真实注册，只改 getter")
            return
        }
        val callbackClass = kit.findClass(classLoader, "android.location.GnssStatus\$Callback") ?: return
        val lmClass = kit.findClass(classLoader, LocationManager::class.java.name) ?: return

        // Three overloads on API 36 and the callback is not always argument 0.
        val swallow = { chain: XposedInterface.Chain ->
            val callback = chain.args.firstOrNull { callbackClass.isInstance(it) } as? GnssStatus.Callback
            if (callback != null && active()) {
                synchronized(callbacks) { if (!callbacks.contains(callback)) callbacks.add(callback) }
                true
            } else {
                chain.proceed()
            }
        }
        kit.install(kit.methodsOf(lmClass, "registerGnssStatusCallback", callbackClass), swallow)
        kit.install(
            kit.methodsOf(lmClass, "registerGnssStatusCallback", callbackClass, Handler::class.java),
            swallow,
        )
        kit.install(
            kit.methodsOf(lmClass, "registerGnssStatusCallback", Executor::class.java, callbackClass),
            swallow,
        )
        kit.install(kit.methodsOf(lmClass, "unregisterGnssStatusCallback", callbackClass)) { chain ->
            val callback = chain.args.firstOrNull { callbackClass.isInstance(it) }
            if (active() && callback != null) {
                synchronized(callbacks) { callbacks.remove(callback) }
                null
            } else {
                chain.proceed()
            }
        }

        handler.post(object : Runnable {
            override fun run() {
                val targets = synchronized(callbacks) { callbacks.toList() }
                if (targets.isNotEmpty() && active()) {
                    targets.forEach { callback ->
                        runCatching { callback.onSatelliteStatusChanged(dummy) }
                            .onFailure { Diagnostics.note("$tag: 回调 onSatelliteStatusChanged 失败: ${it.javaClass.simpleName}") }
                    }
                }
                handler.postDelayed(this, FEED_INTERVAL_MS)
            }
        })
    }

    private fun buildDummyStatus(): GnssStatus? {
        val builderClass = kit.findClass(classLoader, "android.location.GnssStatus\$Builder") ?: return null
        val ctor: Constructor<*> = builderClass.declaredConstructors.firstOrNull { it.parameterCount <= 1 }
            ?: return null.also { Diagnostics.note("$tag: GnssStatus.Builder 没有可用的构造器") }
        val add: Method = builderClass.declaredMethods.firstOrNull { it.name == "addSatellite" }
            ?: return null.also { Diagnostics.note("$tag: GnssStatus.Builder 里没有 addSatellite()") }
        val build = builderClass.declaredMethods.firstOrNull { it.name == "build" }
            ?: return null.also { Diagnostics.note("$tag: GnssStatus.Builder 里没有 build()") }

        return runCatching {
            ctor.isAccessible = true
            val builder = if (ctor.parameterCount == 0) ctor.newInstance() else ctor.newInstance(Sky.SATS.size)
            add.isAccessible = true
            for (i in Sky.SATS.indices) {
                val args = satelliteArgs(add.parameterCount, Sky.SATS[i]) ?: return null
                add.invoke(builder, *args)
            }
            build.isAccessible = true
            build.invoke(builder) as GnssStatus
        }.onFailure {
            Diagnostics.note("$tag: 构造伪 GnssStatus 失败: ${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()
    }

    /**
     * addSatellite() changed shape twice: API 26 was 8 params with svid first, API 30 moved
     * constellationType first and appended the carrier pair, API 33 appended the baseband pair.
     * Only the parameter count identifies the shape, so each one is spelled out. Values come from
     * Sky so this status agrees with the measurement and NMEA streams.
     */
    private fun satelliteArgs(parameterCount: Int, sat: Sky.Sat): Array<Any?>? {
        val cn0 = sat.cn0DbHz
        val elevation = sat.elevationDeg.toFloat()
        val azimuth = sat.azimuthDeg.toFloat()
        val baseband = cn0 - 4f
        return when (parameterCount) {
            8 -> arrayOf(sat.svid, CONSTELLATION_GPS, cn0, elevation, azimuth, true, true, true)
            10 -> arrayOf(
                CONSTELLATION_GPS, sat.svid, cn0, elevation, azimuth,
                true, true, true, true, FAKE_L1,
            )
            12 -> arrayOf(
                CONSTELLATION_GPS, sat.svid, cn0, elevation, azimuth,
                true, true, true, true, FAKE_L1, true, baseband,
            )
            else -> null.also {
                Diagnostics.note("$tag: addSatellite() 有 $parameterCount 个参数，这一版没对上")
            }
        }
    }

    private fun hookWifi() {
        val wifiClass = kit.findClass(classLoader, WifiManager::class.java.name) ?: return
        kit.install(kit.methodsOf(wifiClass, "getScanResults")) { chain ->
            if (active()) emptyList<Any>() else chain.proceed()
        }
        // The connected AP alone is enough for a server-side lookup, so blanking only the scan list
        // leaves the answer intact - that is the hole the first blinding test fell into.
        // Empty strings rather than null: requestConnectionInfo() and friends hand back a WifiInfo
        // object and the SDKs dereference it without checking.
        val infoClass = kit.findClass(classLoader, "android.net.wifi.WifiInfo") ?: return
        val blank = { chain: XposedInterface.Chain -> if (active()) "" else chain.proceed() }
        listOf("getBSSID", "getSSID", "getMacAddress").forEach { name ->
            kit.install(kit.methodsOf(infoClass, name), blank)
        }
    }

    /**
     * The forged-environment variant of blinding: instead of "zero APs around" (which no real
     * place produces), return a small AP set derived deterministically from the injected point.
     * Deterministic matters - a rescan that invents a brand-new neighbourhood every second is as
     * suspicious as an empty one. CellInfo is still blinded empty: building CellIdentity/CellInfo
     * objects reflectively differs across API levels 28→36 and a malformed one crashes the host,
     * which is a worse failure than the anomaly (REFACTOR-PLAN 3.4 records this trade-off).
     */
    private fun hookWifiForge() {
        val wifiClass = kit.findClass(classLoader, WifiManager::class.java.name) ?: return
        kit.install(kit.methodsOf(wifiClass, "getScanResults")) { chain ->
            if (!active()) return@install chain.proceed()
            runCatching { forgeScanResults() }.getOrElse { emptyList<Any>() }
        }
        // The connected-AP identity still has to move with the injection point.
        val infoClass = kit.findClass(classLoader, "android.net.wifi.WifiInfo") ?: return
        kit.install(kit.methodsOf(infoClass, "getBSSID")) { chain ->
            if (active()) forgedBssid(chain) else chain.proceed()
        }
        val blank = { chain: XposedInterface.Chain -> if (active()) "" else chain.proceed() }
        listOf("getSSID", "getMacAddress").forEach { name ->
            kit.install(kit.methodsOf(infoClass, name), blank)
        }
        kit.log("$tag: WiFi 伪造模式（确定性 AP 集，随注入点派生）")
    }

    private fun forgedBssid(chain: XposedInterface.Chain): String {
        val seed = (Config.currentLat * 10_000).toInt() xor (Config.currentLng * 10_000).toInt()
        val r = Random(seed)
        return (1..5).joinToString(":") { "%02x".format(r.nextInt(256)) }.let { "02:$it" }
    }

    /** ScanResult has public fields but no usable public empty constructor on every level; the
     *  no-arg constructor is reached reflectively under the module's hidden-API exemptions. */
    private fun forgeScanResults(): List<Any> {
        val srClass = Class.forName(ScanResult::class.java.name, false, classLoader)
        val ctor: Constructor<*> = srClass.declaredConstructors
            .firstOrNull { it.parameterCount == 0 }
            ?: run {
                Diagnostics.note("$tag: ScanResult 无参构造不存在，退回清空模式")
                return emptyList()
            }
        ctor.isAccessible = true
        val seed = (Config.currentLat * 10_000).toInt() xor (Config.currentLng * 10_000).toInt()
        val r = Random(seed)
        val aps = ArrayList<Any>(FORGED_AP_COUNT)
        repeat(FORGED_AP_COUNT) { i ->
            val ap = ctor.newInstance()
            fun field(name: String, value: Any?) {
                srClass.getField(name).set(ap, value)
            }
            val bssid = (1..5).joinToString(":") { "%02x".format(r.nextInt(256)) }
            field("BSSID", "02:$bssid")
            field("SSID", FORGED_SSIDS[i % FORGED_SSIDS.size] + "-" + (seed and 0xff).toString(16))
            field("level", -48 - i * 8)
            field("frequency", if (i < FORGED_AP_COUNT - 1) 2412 + 5 * (i % 11) else 5180 + 20 * (i % 4))
            field("channelWidth", 0) // ScanResult.CHANNEL_WIDTH_20MHZ
            field("centerFreq0", 0)
            field("centerFreq1", 0)
            field("timestamp", SystemClock.elapsedRealtime() * 1000)
            field("capabilities", if (i % 2 == 0) "[WPA2-PSK-CCMP][ESS]" else "[ESS]")
            aps.add(ap)
        }
        return aps
    }

    /**
     * Sensor freeze: the listener the app registers is replaced by a proxy that rewrites each
     * event's public `values` array in place and forwards it, so no SensorEvent ever has to be
     * constructed (its constructors are hidden and version-fragile).
     */
    private fun hookSensors() {
        val smClass = kit.findClass(classLoader, SensorManager::class.java.name) ?: return
        val listenerClass = kit.findClass(classLoader, SensorEventListener::class.java.name) ?: return

        kit.install(kit.methodsOf(smClass, "registerListener")) { chain ->
            if (!active()) return@install chain.proceed()
            val index = chain.args.indexOfFirst { listenerClass.isInstance(it) }
            val original = chain.args.getOrNull(index) as? SensorEventListener
                ?: return@install chain.proceed()
            val proxy = Proxy.newProxyInstance(
                classLoader, arrayOf(listenerClass),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onSensorChanged" -> {
                            val ev = args?.getOrNull(0) as? SensorEvent
                            if (ev != null && rewriteEvent(ev)) {
                                runCatching { original.onSensorChanged(ev) }
                            }
                            null
                        }
                        "onAccuracyChanged" -> {
                            val sensor = args?.getOrNull(0) as? Sensor
                            val accuracy = args?.getOrNull(1) as? Int
                            if (sensor != null && accuracy != null) {
                                runCatching { original.onAccuracyChanged(sensor, accuracy) }
                            }
                            null
                        }
                        else -> null
                    }
                },
            )
            chain.withArg(index, proxy)
        }
        kit.log("$tag: 传感器冻结就绪（加速度/陀螺/计步与静止位置自洽）")
    }

    /** Rewrites the event in place; false means the event should be swallowed entirely. */
    private fun rewriteEvent(ev: SensorEvent): Boolean {
        val lat = Config.currentLat
        val v = ev.values
        return when (ev.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY -> {
                if (v.size >= 3) {
                    v[0] = noise(0.02f); v[1] = noise(0.02f); v[2] = 9.81f + noise(0.02f)
                }
                true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (v.size >= 3) { v[0] = noise(0.01f); v[1] = noise(0.01f); v[2] = noise(0.01f) }
                true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (v.size >= 3) { v[0] = noise(0.001f); v[1] = noise(0.001f); v[2] = noise(0.001f) }
                true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Dipole approximation at the injected latitude, device assumed flat to match the
                // at-rest accelerometer - the same physical story, told consistently.
                val latRad = Math.toRadians(lat)
                val field = 55_000.0 / sqrt(1.0 + 3 * sin(latRad) * sin(latRad)) / 1000.0
                val dip = kotlin.math.atan(2.0 * kotlin.math.tan(latRad))
                if (v.size >= 3) {
                    v[0] = (field * cos(dip)).toFloat() + noise(0.1f)
                    v[1] = noise(0.1f)
                    v[2] = (field * sin(dip)).toFloat() + noise(0.1f)
                }
                true
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (v.size >= 4) { v[0] = 0f; v[1] = 0f; v[2] = 0f; v[3] = 1f }
                true
            }
            Sensor.TYPE_STEP_COUNTER -> {
                // Freeze at the first value seen in this process; a counter that keeps counting
                // under a static position is the mismatch this layer exists to close.
                if (frozenSteps < 0f && v.isNotEmpty()) frozenSteps = v[0]
                if (v.isNotEmpty()) v[0] = frozenSteps
                true
            }
            // A step *event* under a frozen counter is an outright contradiction: swallow it.
            Sensor.TYPE_STEP_DETECTOR -> false
            else -> true
        }
    }

    private var frozenSteps = -1f

    private fun noise(scale: Float): Float = (Random.nextFloat() - 0.5f) * 2f * scale

    private fun hookCell() {
        val telephonyClass = kit.findClass(classLoader, TelephonyManager::class.java.name) ?: return
        kit.install(kit.methodsOf(telephonyClass, "getCellLocation")) { chain ->
            if (active()) null else chain.proceed()
        }
        kit.install(kit.methodsOf(telephonyClass, "getAllCellInfo")) { chain ->
            if (active()) emptyList<Any>() else chain.proceed()
        }
        hookCellInfoUpdate(telephonyClass)
    }

    /**
     * API 30 moved the tower list behind a callback; the pull getters above are the deprecated path,
     * so blinding only those leaves an app that calls this one holding the real cell info.
     */
    private fun hookCellInfoUpdate(telephonyClass: Class<*>) {
        val callbackClass =
            kit.findClass(classLoader, "android.telephony.TelephonyManager\$CellInfoCallback") ?: return
        kit.install(kit.methodsOf(telephonyClass, "requestCellInfoUpdate")) { chain ->
            if (!active()) return@install chain.proceed()
            val callback = chain.args.firstOrNull { callbackClass.isInstance(it) }
                ?: return@install chain.proceed()
            val deliver = Runnable {
                runCatching {
                    callbackClass.getMethod("onCellInfo", List::class.java).invoke(callback, emptyList<Any>())
                }.onFailure { Diagnostics.note("$tag: 递交空基站列表失败: ${it.javaClass.simpleName}") }
            }
            val executor = chain.args.firstOrNull { it is Executor } as? Executor
            if (executor != null) executor.execute(deliver) else handler.post(deliver)
            null
        }
    }

    private companion object {
        const val CONSTELLATION_GPS = 1   // GnssStatus.CONSTELLATION_GPS
        const val FAKE_L1 = 1.57542e9f    // GPS L1 carrier, Hz
        const val FEED_INTERVAL_MS = 1000L
        const val FORGED_AP_COUNT = 4
        val FORGED_SSIDS = listOf("Home", "Office", "Coffee", "Guest", "TP-LINK", "Xiaomi")
    }
}
