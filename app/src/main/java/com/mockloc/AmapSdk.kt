package com.mockloc

import io.github.libxposed.api.XposedInterface

/**
 * Amap's own location stack.
 *
 * Why this layer is needed at all: rewriting `android.location.Location` did not move Amap's blue
 * dot even with mock providers live. The probe (A11/C23) then dumped the real method tables from the
 * running app, and this file is built from what it printed rather than from guesswork:
 *
 *   com.amap.location.type.location.Location  -> getLatitude/getLongitude/getAccuracy/getAltitude/
 *       getBearing/getSpeed/getProvider/getSource/getLocationUtcTime/...
 *   com.amap.location.api.ILocationService    -> getLatestLocation(), getSatelliteList(),
 *       getFixSatelliteCount(), appCanLocation(), isGnssOn(), isLocating(), isLocationOn(),
 *       isLocationDim(), fakeNetworkLocation(boolean), registerSatelliteStatusCallback(...)
 *
 * Two consequences shaped the implementation. First, `ILocationService` is an interface, so hooking
 * it does nothing; the client-side AIDL proxy `ILocationService$Stub$Proxy` is what actually runs in
 * the app process, and its `int`/`boolean` answers (satellite count, "is GNSS on") can be rewritten
 * there directly. Second, the objects `getLatestLocation()` hands back are Amap `Location` instances,
 * so hooking that class's getters covers the data path for every consumer at once - which is also why
 * no method-name guessing is needed for the coordinates themselves.
 *
 * Timestamps are deliberately left real: a fake position with a frozen clock is a far stronger
 * detection signal than one with a real one.
 */
class AmapSdk(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Amap]"
    private val kit = HookKit(module, tag)

    fun init() {
        val locationClass = hookLocationType()
        hookServiceProxy()
        if (locationClass == null) {
            // Absent is normal outside Amap (WeChat has no Amap stack), so this is a log line.
            kit.log("$tag: 没有高德定位栈，跳过")
        }
        if (Config.probe) dumpCandidates()
    }

    /** Amap's Location type: the same treatment ModuleEntry gives the framework one. */
    private fun hookLocationType(): Class<*>? {
        val clazz = LOCATION_TYPES.mapNotNull { load(it) }.firstOrNull() ?: return null
        kit.install(kit.methodsOf(clazz, "getLatitude")) { chain ->
            if (active()) Config.currentLat else chain.proceed()
        }
        kit.install(kit.methodsOf(clazz, "getLongitude")) { chain ->
            if (active()) Config.currentLng else chain.proceed()
        }
        kit.install(kit.methodsOf(clazz, "getAccuracy")) { chain ->
            if (active()) Config.accuracy else chain.proceed()
        }
        kit.install(kit.methodsOf(clazz, "getAltitude")) { chain ->
            if (active()) ALTITUDE_M else chain.proceed()
        }
        kit.install(kit.methodsOf(clazz, "getProvider")) { chain ->
            if (active()) LocationManager_GPS else chain.proceed()
        }
        kit.install(kit.methodsOf(clazz, "getSource")) { chain ->
            if (active()) LocationManager_GPS else chain.proceed()
        }
        kit.log("$tag: 已挂钩 ${clazz.name} 的坐标/精度/高度/来源")
        return clazz
    }

    /**
     * The AIDL client proxy, i.e. the object that really answers `getFixSatelliteCount()` inside the
     * app process. Hooking the interface itself would be a no-op.
     */
    private fun hookServiceProxy() {
        val proxy = load("$SERVICE\$Stub\$Proxy") ?: load(SERVICE) ?: return
        val truthy = mapOf(
            "appCanLocation" to true, "isGnssOn" to true, "isGnssLocating" to true,
            "isLocating" to true, "isLocationOn" to true, "isLocationDim" to false,
        )
        var hits = 0
        truthy.forEach { (name, value) ->
            hits += kit.install(kit.methodsOf(proxy, name)) { chain ->
                if (active()) value else chain.proceed()
            }
        }
        kit.install(kit.methodsOf(proxy, "getFixSatelliteCount")) { chain ->
            if (active()) Sky.SATS.size else chain.proceed()
        }
        kit.log("$tag: 服务代理 $proxy 已挂钩 $hits 个状态查询 + 卫星计数")
    }

    /** Print what the stack exposes, so the next iteration reads facts instead of names. */
    private fun dumpCandidates() {
        (LOCATION_TYPES + PROBE_ONLY).forEach { name ->
            val clazz = load(name) ?: return@forEach
            val summary = clazz.declaredMethods.joinToString("; ") { m ->
                "${m.returnType.simpleName} ${m.name}(" +
                    m.parameterTypes.joinToString(",") { t -> t.simpleName } + ")"
            }
            kit.log("$tag: 方法表 $clazz -> $summary")
        }
    }

    private fun load(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private fun active() = SpoofLogic.isActive(pkg)

    private companion object {
        const val SERVICE = "com.amap.location.api.ILocationService"
        val LOCATION_TYPES = listOf(
            "com.amap.location.type.location.Location",
            "com.amap.location.sdkh.base.type.location.AmapLocation",
        )
        // Not hooked yet - only dumped - until the tables say what they actually expose.
        val PROBE_ONLY = listOf(
            "com.amap.location.type.gnss.Satellite",
            "com.amap.location.api.define.VALocationResult",
            SERVICE,
        )
        const val LocationManager_GPS = "gps"
        const val ALTITUDE_M = 4.0
    }
}
