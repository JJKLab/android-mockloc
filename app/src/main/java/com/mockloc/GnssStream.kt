package com.mockloc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executor

/**
 * The satellite view, shared by every GNSS surface so they cannot contradict each other.
 *
 * `GnssStatus`, the raw measurement stream and the NMEA sentences have to name the same satellites
 * with the same signal strengths - cross-checking those three is the main way a half-finished
 * spoofer gets caught. Slant range is computed from the real GPS orbital radius rather than
 * invented, so a consumer that checks travel time against elevation gets a consistent answer.
 */
object Sky {
    data class Sat(val svid: Int, val elevationDeg: Double, val azimuthDeg: Int, val cn0DbHz: Float)

    val SATS: List<Sat> = listOf(
        Sat(3, 74.0, 25, 44f), Sat(11, 56.0, 118, 41f), Sat(20, 41.0, 202, 38f),
        Sat(28, 33.0, 316, 36f), Sat(15, 22.0, 61, 33f), Sat(8, 15.0, 149, 31f),
        Sat(24, 8.0, 274, 29f),
    )

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val GPS_ORBIT_RADIUS_M = 20_200_000.0
    private const val SPEED_OF_LIGHT = 299_792_458.0

    /** Geometric slant range for a satellite at this elevation, metres. */
    fun pathMeters(elevationDeg: Double): Double {
        val e = Math.toRadians(elevationDeg)
        val r = EARTH_RADIUS_M
        val orbit = EARTH_RADIUS_M + GPS_ORBIT_RADIUS_M
        return kotlin.math.sqrt(orbit * orbit - r * r * Math.cos(e) * Math.cos(e)) - r * Math.sin(e)
    }

    fun travelNanos(elevationDeg: Double): Long =
        (pathMeters(elevationDeg) / SPEED_OF_LIGHT * 1e9).toLong()
}

/**
 * GNSS raw-measurement and NMEA injection.
 *
 * Why: Amap registers `GnssMeasurement` callbacks (dumpsys shows `measureListener: 4` and hundreds
 * of measurement events) while ignoring the `Location` values we already rewrite - so the satellite
 * side of its evidence still points at the real sky.
 *
 * Why it is possible on Android 16: `GnssClock()`, `GnssMeasurement()` and their setters are public
 * in AOSP but `@hide`, so they are absent from android.jar yet present at runtime, and the module
 * already installs hidden-API exemptions. Everything is therefore reached reflectively by name,
 * which also keeps this file compiling against any SDK level.
 *
 * Measurement registration is swallowed only after an event has actually been built at attach time;
 * if the framework shapes change, the app keeps its genuine subscription and the reason is recorded.
 * NMEA needs no such guard - it is plain text we author.
 */
class GnssStream(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[GnssStream]"
    private val kit = HookKit(module, tag)
    private val handler = Handler(Looper.getMainLooper())
    private val measurementCallbacks = ArrayList<Sink>()
    private val nmeaListeners = ArrayList<Sink>()

    /** Stable across epochs: a receiver that restarts its clock every second is its own tell. */
    private val fullBiasNanos = -(gpsSecondsSinceEpoch() * 1_000_000_000L)

    /** A static receiver accumulates no delta range, so this stays put and matches Doppler = 0. */
    private val adrMeters = 0.0

    /** The probe prints one epoch's sentences so the format can be checked with a real parser. */
    private var sampled = false

    private class Sink(val target: Any, val method: Method)

    fun init() {
        val lmClass = kit.findClass(classLoader, "android.location.LocationManager") ?: return
        if (buildEvent() != null) {
            hookMeasurementRegistration(lmClass)
            kit.log("$tag: 测量流接管就绪（${Sky.SATS.size} 颗星，自洽斜距由高度角算出）")
        } else {
            Diagnostics.note("$tag: 造不出 GnssMeasurementsEvent，保留真实注册，只注入 NMEA")
        }
        hookNmeaRegistration(lmClass)
        startLoop()
    }

    // ---- takeover ----------------------------------------------------------------------------

    private fun hookMeasurementRegistration(lmClass: Class<*>) {
        val cbClass = kit.findClass(classLoader, "android.location.GnssMeasurementsEvent\$Callback")
            ?: return Diagnostics.note("$tag: 找不到 GnssMeasurementsEvent.Callback，测量流不接管")
        val deliverMethod = cbClass.methods.firstOrNull { it.name == "onGnssMeasurementsReceived" }
            ?: return Diagnostics.note("$tag: Callback 上没有 onGnssMeasurementsReceived，不接管")

        kit.install(kit.methodsOf(lmClass, "registerGnssMeasurementsCallback")) { chain ->
            val cb = chain.args.firstOrNull { cbClass.isInstance(it) }
            if (cb != null && active()) {
                synchronized(measurementCallbacks) {
                    if (measurementCallbacks.none { it.target === cb })
                        measurementCallbacks.add(Sink(cb, deliverMethod))
                }
                true
            } else {
                chain.proceed()
            }
        }
        kit.install(kit.methodsOf(lmClass, "unregisterGnssMeasurementsCallback")) { chain ->
            val cb = chain.args.firstOrNull { cbClass.isInstance(it) }
            if (active() && cb != null) {
                synchronized(measurementCallbacks) { measurementCallbacks.removeAll { it.target === cb } }
                true
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookNmeaRegistration(lmClass: Class<*>) {
        val collect = { chain: XposedInterface.Chain ->
            val listener = chain.args.firstOrNull { it != null && !isPlumbingArg(it) }
            // The two listener generations disagree on both method name and argument order.
            val method = listener?.javaClass?.methods?.firstOrNull {
                it.name == "onNmeaMessage" || it.name == "onNmeaReceived"
            }
            if (listener != null && method != null && active()) {
                synchronized(nmeaListeners) {
                    if (nmeaListeners.none { it.target === listener }) nmeaListeners.add(Sink(listener, method))
                }
                true
            } else {
                chain.proceed()
            }
        }
        // LocationManager only ever had add/remove; the register* spelling belongs to the AIDL layer,
        // and asking for it produced two "不存在" notes on device.
        kit.install(kit.methodsOf(lmClass, "addNmeaListener"), collect)
        kit.install(kit.methodsOf(lmClass, "removeNmeaListener")) { chain ->
            val listener = chain.args.firstOrNull { it != null && !isPlumbingArg(it) }
            if (active() && listener != null) {
                synchronized(nmeaListeners) { nmeaListeners.removeAll { it.target === listener } }
                true
            } else {
                chain.proceed()
            }
        }
    }

    private fun isPlumbingArg(value: Any): Boolean =
        value is Number || value is Boolean || value is CharSequence || value is Char ||
            value is Executor || value is Handler || value is android.os.CancellationSignal

    // ---- the stream ---------------------------------------------------------------------------

    private fun startLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (active()) deliver()
                handler.postDelayed(this, EPOCH_MS)
            }
        })
    }

    private fun deliver() {
        val sinks = synchronized(measurementCallbacks) { measurementCallbacks.toList() }
        if (sinks.isNotEmpty()) {
            val event = buildEvent()
            if (event != null) {
                sinks.forEach { sink ->
                    runCatching { sink.method.invoke(sink.target, event) }.onFailure {
                        Diagnostics.note("$tag: 投递测量事件失败: ${it.javaClass.simpleName}")
                    }
                }
            }
        }
        val listeners = synchronized(nmeaListeners) { nmeaListeners.toList() }
        if (listeners.isEmpty()) return
        val now = System.currentTimeMillis()
        val sentences = nmeaSentences(now)
        if (Config.probe && !sampled) {
            sampled = true
            kit.log("$tag: 样例 $sentences")
        }
        sentences.forEach { sentence ->
            listeners.forEach { sink ->
                runCatching {
                    if (sink.method.name == "onNmeaMessage") {
                        sink.method.invoke(sink.target, sentence, now)
                    } else {
                        sink.method.invoke(sink.target, now, sentence)
                    }
                }.onFailure { Diagnostics.note("$tag: 投递 NMEA 失败: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun buildEvent(): Any? {
        val clock = build(GnssClock_NAME) {
            put("setTimeNanos", SystemClock.elapsedRealtimeNanos())
            put("setFullBiasNanos", fullBiasNanos)
            put("setBiasNanos", 0.0)
            put("setBiasUncertaintyNanos", 5.0)
            put("setDriftNanosPerSecond", 0.0)
            put("setDriftUncertaintyNanosPerSecond", 2.0)
            put("setElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            put("setElapsedRealtimeUncertaintyNanos", 1000.0)
            put("setLeapSecond", LEAP_SECONDS)
        } ?: return null

        val measurements = Sky.SATS.map { sat ->
            build("android.location.GnssMeasurement") {
                val rx = SystemClock.elapsedRealtimeNanos()
                put("setSvid", sat.svid)
                put("setConstellationType", CONSTELLATION_GPS)
                put("setCn0DbHz", sat.cn0DbHz.toDouble())
                put("setBasebandCn0DbHz", (sat.cn0DbHz - 4f).toDouble())
                put("setSnrInDb", sat.cn0DbHz.toDouble())
                put("setCarrierFrequencyHz", L1_HZ)
                put("setCarrierCycles", -1L)
                put("setCarrierPhase", 0.0)
                put("setCarrierPhaseUncertainty", 0.0)
                put("setMultipathIndicator", MULTIPATH_NOT_DETECTED)
                put("setAutomaticGainControlLevelInDb", -32.0)
                put("setState", STATE_LOCKED)
                put("setTimeOffsetNanos", 0.0)
                put("setReceivedSvTimeNanos", rx - Sky.travelNanos(sat.elevationDeg))
                put("setReceivedSvTimeUncertaintyNanos", 10L)
                put("setPseudorangeRateMetersPerSecond", 0.0)
                put("setPseudorangeRateUncertaintyMetersPerSecond", 0.1)
                put("setAccumulatedDeltaRangeState", ADR_USE_AND_LOCKED)
                put("setAccumulatedDeltaRangeMeters", adrMeters)
                put("setAccumulatedDeltaRangeUncertaintyMeters", 0.1)
            }
        }
        if (measurements.any { it == null }) return null

        return runCatching {
            val builderClass = Class.forName("$Event_NAME\$Builder", false, classLoader)
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.method("setClock").invoke(builder, clock)
            builderClass.method("setMeasurements").invoke(builder, measurements.filterNotNull())
            builderClass.method("build").invoke(builder)
        }.onFailure {
            Diagnostics.note("$tag: 组装事件失败: ${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()
    }

    /** Instantiate a hidden framework type by name and apply its single-argument setters. */
    private inline fun build(className: String, apply: Setters.() -> Unit): Any? {
        val setters = Setters(className)
        return runCatching {
            val clazz = Class.forName(className, false, classLoader)
            val instance = clazz.getDeclaredConstructor().newInstance()
            setters.of(instance).apply()
            instance
        }.onFailure {
            Diagnostics.note("$tag: $className 造不出来（豁免没生效？）: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    private inner class Setters(private val className: String) {
        private var target: Any? = null
        fun of(instance: Any): Setters = also { target = instance }
        fun put(name: String, value: Any?) {
            val instance = target ?: return
            val method = instance.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1
            } ?: throw NoSuchMethodException("$className#$name")
            method.invoke(instance, value)
        }
    }

    private fun Class<*>.method(name: String): Method =
        methods.first { it.name == name && it.parameterCount == 1 }

    // ---- NMEA ---------------------------------------------------------------------------------

    /** Sentences are built by the pure Nmea object so their format can be checked off-device. */
    private fun nmeaSentences(nowMillis: Long): List<String> =
        Nmea.sentences(Config.currentLat, Config.currentLng, nowMillis, Sky.SATS, ALTITUDE_M, HDOP)

    private fun gpsSecondsSinceEpoch(): Long =
        (System.currentTimeMillis() / 1000L) - GPS_EPOCH_OFFSET_SECONDS

    private fun active() = Config.gnssSpoof && SpoofLogic.isActive(pkg)

    private companion object {
        const val GnssClock_NAME = "android.location.GnssClock"
        const val Event_NAME = "android.location.GnssMeasurementsEvent"
        const val EPOCH_MS = 1000L
        const val L1_HZ = 1.57542e9f
        const val LEAP_SECONDS = 18
        const val CONSTELLATION_GPS = 1
        const val MULTIPATH_NOT_DETECTED = 2
        const val ADR_USE_AND_LOCKED = 3
        // CODE_LOCK | BIT_SYNC | SUBFRAME_SYNC | TOW_DECODED | 2ND_CODE_LOCK
        const val STATE_LOCKED = 0x1 or 0x2 or 0x4 or 0x8 or (1 shl 16)
        const val HDOP = 0.9
        const val ALTITUDE_M = 4.0
        const val GPS_EPOCH_OFFSET_SECONDS = 315_964_800L // 1980-01-06
    }
}
