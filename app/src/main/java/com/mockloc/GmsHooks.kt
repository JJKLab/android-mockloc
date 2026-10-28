package com.mockloc

import android.location.Location
import io.github.libxposed.api.XposedInterface

/**
 * Belt-and-braces layer over GMS fused delivery (REFACTOR-PLAN 3.6). The android.location.Location
 * getter hooks already rewrite anything GMS hands over inside a LocationResult, but a target that
 * calls `LocationResult.getLastLocation()` or re-reads `getLocations()` from its own copy gets one
 * more chance to observe a consistent answer - and the hooks are cheap and class-optional.
 *
 * On ROMs without GMS the class is simply absent and this layer quietly does nothing - that is the
 * normal case on the devices this project targets, so absence logs nothing (unlike a missing
 * framework class, which would be worth a Diagnostics note).
 */
class GmsHooks(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Gms]"
    private val kit = HookKit(module, tag)

    fun init() {
        if (!Config.gmsSpoof) return
        // Silent lookup on purpose: no GMS on a Chinese ROM is normal, not a diagnostics event.
        val resultClass = runCatching {
            Class.forName("com.google.android.gms.location.LocationResult", false, classLoader)
        }.getOrNull() ?: return

        val fake = { provider: String ->
            SpoofLogic.fakeLocation(null, provider, pkg)
        }

        kit.install(kit.methodsOf(resultClass, "getLocations")) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            mutableListOf<Location>(fake("fused"))
        }
        kit.install(kit.methodsOf(resultClass, "getLastLocation")) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            fake("fused")
        }

        kit.log("$tag: GMS LocationResult 接管就绪")
    }
}
