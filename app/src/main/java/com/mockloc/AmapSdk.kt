package com.mockloc

import io.github.libxposed.api.XposedInterface

/**
 * Amap's own location stack, rebuilt from DEX evidence (see README A13) instead of SDK docs.
 *
 * The failure this file fixes: Amap showed the fake point on cold start and snapped back to the
 * real one on pull-to-refresh. Reason, proved over all 8 dex / 6.04 M decoded instructions:
 *
 * - `com.amap.location.sdkh.base.type.location.AmapLocation` is a *parallel* coordinate carrier
 *   (super is Object, NOT the Amap `Location` type), read at 26 sites, and the previous
 *   `LOCATION_TYPES.firstOrNull()` meant it was never hooked at all.
 * - `OffsetUtils.getGcjLatlng(AmapLocation)` reads the public `gcjLatitude/gcjLongitude` **fields**
 *   with `iget-wide`, so no getter hook can reach it. That is the value the map paints from.
 * - `MockHelper.isMock` / `AmapLocationListener.isFilterMock` / `ForgeryLabeling.hitForgery` can
 *   discard our fix, after which the engine re-delivers the real one - which looks exactly like
 *   "it worked, then it didn't".
 *
 * The rewrite therefore covers three planes instead of one: read (getters), write (setters, so the
 * field itself holds the fake for copy-ctors, toString and JNI `GetDoubleField` readers), and the
 * engine's own authenticity checks.
 *
 * `ILocationService$Stub$Proxy` is deliberately gone: that class does not exist (the interface is
 * plain Java, implemented in-process by six obfuscated classes), so the old hook was a silent no-op.
 * Everything the service hands back is an `AmapLocation` or a `Location`, both of which are covered
 * here - which is why the obfuscated implementor names are not needed and won't rot with the next
 * Amap update.
 */
class AmapSdk(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Amap]"
    private val kit = HookKit(module, tag)

    fun init() {
        var hits = 0
        val beans = listOf(LOCATION_BEAN, AMAP_BEAN, LBS_BEAN)
            .mapNotNull { name -> load(name)?.let { name to it } }
        beans.forEach { (name, clazz) ->
            val found = hookBean(clazz, alignGcj = name == AMAP_BEAN)
            if (found > 0) kit.log("$tag: $name 读+写已挂钩 $found 处")
            hits += found
        }
        load(OFFSET_UTILS)?.let { hits += hookGcjReader(it) }
        hits += neutraliseFilters()
        if (hits == 0) kit.log("$tag: 没有高德定位栈，跳过")
    }

    /**
     * One hooker for all three coordinate carriers, because Amap duplicated the bean: `Location`
     * (74 methods, `m`-prefixed fields), `AmapLocation` (34 fields, no prefix, plus the two public
     * gcj fields) and Alipay's `LBSLocation`, which is the only real subclass of `Location` and does
     * override the coordinate getters - so it would bypass the base-class hooks entirely.
     */
    private fun hookBean(clazz: Class<*>, alignGcj: Boolean): Int {
        var hits = 0
        hits += stub(clazz, "getLatitude") { chain ->
            // Aligning the fields here as well means the first read of an object also repairs the
            // two paths that never go through a getter: the gcj field reader and the native engine.
            if (alignGcj) alignFields(clazz, chain.thisObject)
            Config.currentLat
        }
        hits += stub(clazz, "getLongitude") { Config.currentLng }
        hits += stub(clazz, "getAccuracy") { Config.accuracy }
        hits += stub(clazz, "getAltitude") { ALTITUDE_M }
        hits += stub(clazz, "getProvider") { GPS }
        hits += stub(clazz, "getSource") { GPS }
        if (alignGcj) hits += stub(clazz, "getSatelliteCount") { Sky.SATS.size }

        // Write side: the field has to hold the fake value too, or a copy-ctor, a log line or the
        // native engine's GetDoubleField reads the truth out from under the getters.
        hits += forceArg(clazz, "setLatitude") { Config.currentLat }
        hits += forceArg(clazz, "setLongitude") { Config.currentLng }
        hits += forceArg(clazz, "setAccuracy") { Config.accuracy }
        hits += forceArg(clazz, "setProvider") { GPS }
        hits += forceArg(clazz, "setSource") { GPS }
        if (alignGcj) {
            hits += forceArg(clazz, "setGcjLatitude") { gcj()[0] }
            hits += forceArg(clazz, "setGcjLongitude") { gcj()[1] }
        }
        return hits
    }

    /**
     * `getGcjLatlng` is the single proven reader of the public gcj fields (its only caller is the
     * `AmapLocation -> Location` converter), and it bypasses every accessor - so it gets its own
     * hook. Return order is read off the method name; the field names are lat/lng in that order.
     */
    private fun hookGcjReader(clazz: Class<*>): Int =
        stub(clazz, "getGcjLatlng") { doubleArrayOf(gcj()[0], gcj()[1]) }

    /**
     * The engine's own "is this fix real?" gates. Forced only while spoofing is active for this
     * package; with the layer off every one of them runs untouched.
     */
    private fun neutraliseFilters(): Int {
        var hits = 0
        load(MOCK_HELPER)?.let {
            hits += stub(it, "isMock") { false }
            hits += stub(it, "isMockSwitchOn") { false }
        }
        load(LISTENER)?.let { hits += stub(it, "isFilterMock") { false } }
        load(FORGERY)?.let { hits += stub(it, "hitForgery") { 0 } }
        load(NO_MOVE)?.let { hits += stub(it, "addGnssAndReturnIsFake") { false } }
        load(HIGH_SPEED)?.let { hits += stub(it, "isHighSpeedThenStopFake") { false } }
        listOf(GEO_UTILS, AMAP_BEAN).forEach { name ->
            load(name)?.let { hits += stub(it, "isLocationCorrect") { true } }
        }
        if (hits > 0) kit.log("$tag: 反 mock 判定已挂钩 $hits 处（仅本包生效期间）")
        return hits
    }

    /** Replace the return value of every declared overload of `name` while spoofing is active. */
    private fun stub(clazz: Class<*>, name: String, value: (XposedInterface.Chain) -> Any?): Int =
        intercept(clazz, name) { chain -> if (active()) value(chain) else chain.proceed() }

    /** Force the first argument of a setter and continue, so the stored value is the fake one. */
    private fun forceArg(clazz: Class<*>, name: String, value: () -> Any?): Int =
        intercept(clazz, name) { chain ->
            if (active()) chain.withArg(0, value()) else chain.proceed()
        }

    private fun intercept(clazz: Class<*>, name: String, handler: (XposedInterface.Chain) -> Any?): Int {
        val methods = clazz.declaredMethods.filter { it.name == name }
        if (methods.isEmpty()) return 0
        var hits = 0
        methods.forEach { method ->
            runCatching { module.hook(method).intercept { chain -> handler(chain) } }
                .onSuccess { hits++ }
                .onFailure { Diagnostics.note("$tag: 挂钩 ${clazz.simpleName}#$name 失败: ${it.message}") }
        }
        return hits
    }

    private fun active() = SpoofLogic.isActive(pkg)

    private fun gcj() = CoordTransform.wgs84ToGcj02(Config.currentLat, Config.currentLng)

    /**
     * `AmapLocation` carries both datums as plain fields: `latitude/longitude` (WGS-84, what the
     * getters return) and `gcjLatitude/gcjLongitude` (what the map paints). Writing both keeps one
     * object self-consistent instead of faking one reading path and leaving the other real.
     */
    private fun alignFields(clazz: Class<*>, instance: Any?) {
        if (instance == null) return
        val wgs = doubleArrayOf(Config.currentLat, Config.currentLng)
        val gcj = gcj()
        putField(clazz, instance, "latitude", wgs[0])
        putField(clazz, instance, "longitude", wgs[1])
        putField(clazz, instance, "gcjLatitude", gcj[0])
        putField(clazz, instance, "gcjLongitude", gcj[1])
    }

    private fun putField(clazz: Class<*>, instance: Any, name: String, value: Double) {
        val field = runCatching { clazz.getDeclaredField(name) }.getOrNull() ?: return
        runCatching {
            field.isAccessible = true
            field.setDouble(instance, value)
        }
    }

    private fun load(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    private companion object {
        const val LOCATION_BEAN = "com.amap.location.type.location.Location"
        const val AMAP_BEAN = "com.amap.location.sdkh.base.type.location.AmapLocation"
        const val LBS_BEAN = "com.alipay.mobile.map.model.LBSLocation"
        const val OFFSET_UTILS = "com.amap.location.sdkh.base.tools.geo.OffsetUtils"
        const val MOCK_HELPER = "com.amap.location.sdkh.environment.gnss.MockHelper"
        const val LISTENER = "com.amap.location.sdkh.base.common.listener.AmapLocationListener"
        const val FORGERY = "com.amap.location.sdkh.environment.gnss.ForgeryLabeling"
        const val NO_MOVE = "com.amap.location.sdkh.environment.gnss.ForgeryLabeling\$NoMoveRecognizer"
        const val HIGH_SPEED =
            "com.amap.location.sdkh.environment.gnss.ForgeryLabeling\$HighSpeedThenStopRecognizer"
        const val GEO_UTILS = "com.amap.location.sdkh.base.tools.geo.GeoUtils"
        const val GPS = "gps"
        const val ALTITUDE_M = 4.0
    }
}
