package com.mockloc

import io.github.libxposed.api.XposedInterface

/**
 * Amap's own location stack.
 *
 * Why this exists separately from the framework hooks: rewriting `android.location.Location` did not
 * move Amap's blue dot even with the mock providers live, and the reason is that Amap carries its
 * own location type and its own network-positioning manager. Two independent sources say so -
 * our own DEX scan of the installed app (A7) found `com.amap.location.type.location.Location`,
 * `com.amap.location.api.ILocationService` and `INetworkLocationListener`, and the reverse-engineered
 * commercial tool (A8/参考) loads `com.amap.android.location.NetworkLocationManager` and
 * `com.amap.location.offline.{flp,nlp}.LocationProvider` reflectively.
 *
 * So this hooks Amap's Location type the same way ModuleEntry hooks the framework one: coordinates,
 * accuracy, and the mock flag. Hooking the *type* rather than each callback means every listener
 * variant and every cached object goes through the same door.
 *
 * Nothing here is assumed: every class is looked up by name at runtime, misses are recorded, and in
 * probe mode the candidate classes' declared methods are dumped to logcat so the next iteration is
 * driven by what Amap actually exposes rather than by another guess.
 */
class AmapSdk(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Amap]"
    private val kit = HookKit(module, tag)

    fun init() {
        var hooked = 0
        LOCATION_TYPES.forEach { name ->
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: return@forEach
            hooked += kit.install(kit.methodsOf(clazz, "getLatitude")) { chain ->
                if (active()) Config.currentLat else chain.proceed()
            }
            hooked += kit.install(kit.methodsOf(clazz, "getLongitude")) { chain ->
                if (active()) Config.currentLng else chain.proceed()
            }
            kit.install(kit.methodsOf(clazz, "getAccuracy")) { chain ->
                if (active()) Config.accuracy else chain.proceed()
            }
            // Amap's own mock flag, if it has one: same treatment as the framework's.
            kit.install(kit.methodsOf(clazz, "isMock")) { chain -> if (active()) false else chain.proceed() }
            kit.install(kit.methodsOf(clazz, "isFromMock")) { chain -> if (active()) false else chain.proceed() }
            kit.log("$tag: 已挂钩 $name")
        }
        if (hooked == 0) {
            // Absent is the normal case outside Amap, so this is a log line, not a fault note.
            kit.log("$tag: 这个 App 里没有高德定位栈，跳过")
        }
        if (Config.probe) dumpCandidates()
    }

    /**
     * Probe mode: print what the manager classes actually expose. The point is to stop guessing
     * method signatures for the listener/callback layer and read them off the running app instead.
     */
    private fun dumpCandidates() {
        (LOCATION_TYPES + MANAGER_TYPES).forEach { name ->
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: return@forEach
            val summary = clazz.declaredMethods.joinToString("; ") { m ->
                val args = m.parameterTypes.joinToString(",") { it.simpleName }
                "${m.returnType.simpleName} ${m.name}($args)"
            }
            kit.log("$tag: 方法表 $name -> $summary")
        }
    }

    private fun active() = SpoofLogic.isActive(pkg)

    private companion object {
        val LOCATION_TYPES = listOf(
            "com.amap.location.type.location.Location",
            "com.amap.api.location.AMapLocation",
        )
        val MANAGER_TYPES = listOf(
            "com.amap.android.location.NetworkLocationManager",
            "com.amap.location.offline.nlp.LocationProvider",
            "com.amap.location.offline.flp.LocationProvider",
            "com.amap.location.api.ILocationService",
        )
    }
}
