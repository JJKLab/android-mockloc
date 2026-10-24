package com.mockloc

import android.location.Location
import android.location.LocationManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Legacy Xposed entry (api 82), kept alongside [ModuleEntry] so the same APK loads on LSPosed
 * builds that predate the libxposed service API. Behaviour is identical: both delegate every
 * decision to [SpoofLogic].
 */
class LegacyEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == SYSTEM_UI_PKG) return

        runCatching { installLocationHooks(lpparam.classLoader, pkg) }
            .onFailure { XposedBridge.log("mockloc: legacy hooks failed for $pkg: ${it.message}") }

        if (pkg == SELF_PKG) {
            runCatching {
                XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(MainActivity::class.java.name, lpparam.classLoader),
                    "selfCheck",
                    ReplacingHook(pkg) { true },
                )
            }.onFailure { XposedBridge.log("mockloc: selfCheck hook failed: ${it.message}") }
        }
    }

    private fun installLocationHooks(classLoader: ClassLoader, pkg: String) {
        val locationClass = XposedHelpers.findClass(Location::class.java.name, classLoader)
        val coordinateGetters: List<Pair<String, () -> Any?>> =
            listOf("getLatitude" to { Config.currentLat }, "getLongitude" to { Config.currentLng })
        coordinateGetters.forEach { (name, value) -> hookZeroArg(locationClass, name, pkg, value) }
        listOf("isMock", "isFromMockProvider").forEach { hookZeroArg(locationClass, it, pkg) { false } }

        val lmClass = XposedHelpers.findClass(LocationManager::class.java.name, classLoader)
        XposedBridge.hookAllMethods(lmClass, "getLastKnownLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!SpoofLogic.isActive(pkg)) return
                val provider = param.args?.getOrNull(0) as? String
                param.result = SpoofLogic.fakeLocation(param.result as? Location, provider)
            }
        })
    }

    private fun hookZeroArg(clazz: Class<*>, name: String, pkg: String, replacement: () -> Any?) {
        runCatching {
            XposedBridge.hookAllMethods(clazz, name, ReplacingHook(pkg, replacement))
        }.onFailure { XposedBridge.log("mockloc: $name not hooked (${it.javaClass.simpleName})") }
    }

    private class ReplacingHook(
        private val pkg: String,
        private val replacement: () -> Any?,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (SpoofLogic.isActive(pkg)) param.result = replacement()
        }
    }

    private companion object {
        const val SYSTEM_UI_PKG = "android"
        const val SELF_PKG = "com.mockloc"
    }
}
