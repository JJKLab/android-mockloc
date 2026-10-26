package com.mockloc

import io.github.libxposed.api.XposedInterface

/**
 * WeChat's position comes from the embedded Tencent Location SDK, not from `LocationManager`.
 *
 * That is not a guess: the in-process read-back (SelfTest) shows WeChat's own process returning the
 * fake fix with `mock=false`, yet its location picker still rendered the real city. The SDK carries
 * its own location type - with its own `isMock()` - so the substitution has to happen there too.
 *
 * `TencentLocation` is an interface, and hooking an interface method does not reach the
 * implementations, so the concrete class is looked up from a candidate list; the implementation's
 * package has moved between SDK releases and a miss is recorded rather than swallowed.
 *
 * The address text is answered with the config label on purpose. Faking only the coordinates would
 * leave the SDK's own reverse-geocoded string pointing at the real street, which is exactly the
 * inconsistency a user sees as "the map moved but the text didn't".
 */
class TencentSdk(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Tencent]"
    private val kit = HookKit(module, tag)

    fun init() {
        val impl = CANDIDATES.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        } ?: return Diagnostics.note("$tag: 没找到 TencentLocationImpl，微信走自家 SDK 的路径未覆盖")

        val coord = { getter: () -> Double ->
            { chain: XposedInterface.Chain -> if (active()) getter() else chain.proceed() }
        }
        kit.install(kit.methodsOf(impl, "getLatitude"), coord({ Config.currentLat }))
        kit.install(kit.methodsOf(impl, "getLongitude"), coord({ Config.currentLng }))
        kit.install(kit.methodsOf(impl, "getAccuracy")) { chain ->
            if (active()) Config.accuracy else chain.proceed()
        }
        kit.install(kit.methodsOf(impl, "isMock")) { chain -> if (active()) false else chain.proceed() }
        listOf("getAddress", "getCity", "getDistrict", "getProvince").forEach { name ->
            kit.install(kit.methodsOf(impl, name)) { chain ->
                if (active() && Config.label.isNotEmpty()) Config.label else chain.proceed()
            }
        }
    }

    private fun active() = SpoofLogic.isActive(pkg)

    private companion object {
        val CANDIDATES = listOf(
            "com.tencent.map.geolocation.internal.TencentLocationImpl",
            "com.tencent.map.geolocation.TencentLocationImpl",
        )
    }
}
