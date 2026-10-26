package com.mockloc

import android.location.GnssStatus
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.Executor

/**
 * Two optional layers on top of the mock-flag cleanup:
 *
 * - GNSS: a mocked "gps" provider leaves the real satellite count at zero, which is exactly the
 *   inconsistency a detecting app looks for. Registration is only swallowed once a synthetic
 *   GnssStatus can actually be produced - otherwise the app would receive nothing at all, which
 *   is worse than receiving a real (contradicting) status.
 * - WiFi / cell: removes the inputs that network-positioning SDKs feed to their own servers.
 *   Off by default because it genuinely breaks those apps' WiFi features.
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
        if (Config.blindWifi) hookWifi()
        if (Config.blindCell) hookCell()
    }

    private fun active() = SpoofLogic.isActive(pkg)

    /**
     * Every getter here is declared `float` in the framework, so the replacements have to box a
     * Float and not a Double - a wrong box type is a ClassCastException inside the target app.
     */
    private fun hookGnssStatusGetters() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val statusClass = kit.findClass(classLoader, GnssStatus::class.java.name) ?: return
        val index = { chain: XposedInterface.Chain -> (chain.args[0] as Int) }

        kit.install(kit.methodsOf(statusClass, "getSatelliteCount")) { chain ->
            if (active()) FAKE_SATELLITES else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getSvid", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) index(chain) + 1 else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getConstellationType", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) CONSTELLATION_GPS else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getCn0DbHz", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) FAKE_CN0 else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getBasebandCn0DbHz", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) FAKE_BASEBAND else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getElevationDegrees", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) FAKE_ELEVATION else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "getAzimuthDegrees", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) ((index(chain) * FAKE_AZ_SPREAD) % 360).toFloat() else chain.proceed()
        }
        kit.install(kit.methodsOf(statusClass, "usedInFix", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) true else chain.proceed()
        }
        // The names are hasEphemerisData/hasAlmanacData; the shorter forms AnyWhere hooks do not exist.
        listOf("hasEphemerisData", "hasAlmanacData", "hasCarrierFrequencyHz", "hasBasebandCn0DbHz")
            .forEach { name ->
                kit.install(kit.methodsOf(statusClass, name, Int::class.javaPrimitiveType!!)) { chain ->
                    if (active()) true else chain.proceed()
                }
            }
        kit.install(kit.methodsOf(statusClass, "getCarrierFrequencyHz", Int::class.javaPrimitiveType!!)) { chain ->
            if (active()) FAKE_L1 else chain.proceed() // GPS L1
        }

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
            val builder = if (ctor.parameterCount == 0) ctor.newInstance() else ctor.newInstance(FAKE_SATELLITES)
            add.isAccessible = true
            for (i in 0 until FAKE_SATELLITES) {
                val args = satelliteArgs(add.parameterCount, i) ?: return null
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
     * Only the parameter count identifies the shape, so each one is spelled out.
     */
    private fun satelliteArgs(parameterCount: Int, index: Int): Array<Any?>? {
        val svid = index + 1
        val azimuth = ((index * FAKE_AZ_SPREAD) % 360).toFloat()
        return when (parameterCount) {
            8 -> arrayOf(svid, CONSTELLATION_GPS, FAKE_CN0, FAKE_ELEVATION, azimuth, true, true, true)
            10 -> arrayOf(
                CONSTELLATION_GPS, svid, FAKE_CN0, FAKE_ELEVATION, azimuth,
                true, true, true, true, FAKE_L1,
            )
            12 -> arrayOf(
                CONSTELLATION_GPS, svid, FAKE_CN0, FAKE_ELEVATION, azimuth,
                true, true, true, true, FAKE_L1, true, FAKE_BASEBAND,
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
    }

    private fun hookCell() {
        val telephonyClass = kit.findClass(classLoader, TelephonyManager::class.java.name) ?: return
        kit.install(kit.methodsOf(telephonyClass, "getCellLocation")) { chain ->
            if (active()) null else chain.proceed()
        }
        kit.install(kit.methodsOf(telephonyClass, "getAllCellInfo")) { chain ->
            if (active()) emptyList<Any>() else chain.proceed()
        }
    }

    private companion object {
        const val FAKE_SATELLITES = 7
        const val CONSTELLATION_GPS = 1   // GnssStatus.CONSTELLATION_GPS
        const val FAKE_CN0 = 35f          // dB-Hz: a healthy sky, but not suspiciously perfect
        const val FAKE_BASEBAND = 30f     // slightly below the antenna C/N0, as a real chip reports
        const val FAKE_ELEVATION = 45f    // degrees
        const val FAKE_AZ_SPREAD = 47     // degrees, multiplied by index so the view is not radial
        const val FAKE_L1 = 1.57542e9f    // GPS L1 carrier, Hz
        const val FEED_INTERVAL_MS = 1000L
    }
}
