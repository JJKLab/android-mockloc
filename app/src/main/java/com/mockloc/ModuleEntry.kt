package com.mockloc

import android.location.Location
import android.location.LocationManager
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed (modern) entry. Installed by LSPosed 1.9+ / libxposed frameworks.
 *
 * Phase 2 deliberately stays inside the target app process: no test provider, no app-op, and
 * therefore no mock bit at all. Only `android` (system_server) is skipped - that hook set is a
 * separate step, and getting it wrong takes system_server down with it.
 */
class ModuleEntry : XposedModule() {

    private val tag = "[mockloc]"

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        if (pkg == SYSTEM_UI_PKG) {
            log(Log.INFO, tag, "skipped system package scope")
            return
        }
        log(Log.INFO, tag, "attached to $pkg")
        installLocationHooks(param.classLoader, pkg)
        if (pkg == SELF_PKG) installSelfCheck(param.classLoader)
    }

    private fun installLocationHooks(classLoader: ClassLoader, pkg: String) {
        val locationClass = try {
            Class.forName(Location::class.java.name, false, classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, tag, "Location class unavailable: ${t.message}")
            return
        }
        hookGetter(locationClass, "getLatitude", pkg) { Config.currentLat }
        hookGetter(locationClass, "getLongitude", pkg) { Config.currentLng }
        hookGetter(locationClass, "isMock", pkg) { false }
        hookGetter(locationClass, "isFromMockProvider", pkg) { false }

        try {
            val lm = Class.forName(LocationManager::class.java.name, false, classLoader)
            val method = lm.getDeclaredMethod("getLastKnownLocation", String::class.java)
            hook(method).intercept { chain ->
                if (!SpoofLogic.isActive(pkg)) return@intercept chain.proceed()
                val provider = chain.getArg(0) as? String
                val original = chain.proceed() as? Location
                SpoofLogic.fakeLocation(original, provider)
            }
        } catch (t: Throwable) {
            log(Log.ERROR, tag, "getLastKnownLocation hook failed: ${t.message}")
        }
    }

    private fun hookGetter(clazz: Class<*>, name: String, pkg: String, replacement: () -> Any?) {
        val method = try {
            clazz.getDeclaredMethod(name)
        } catch (t: NoSuchMethodException) {
            // isMock() only exists from API 31; absence is expected on older devices.
            log(Log.INFO, tag, "$name not present: ${t.message}")
            return
        } catch (t: Throwable) {
            log(Log.ERROR, tag, "$name lookup failed: ${t.message}")
            return
        }
        try {
            hook(method).intercept { chain ->
                if (SpoofLogic.isActive(pkg)) replacement() else chain.proceed()
            }
        } catch (t: Throwable) {
            log(Log.ERROR, tag, "$name hook failed: ${t.message}")
        }
    }

    private fun installSelfCheck(classLoader: ClassLoader) {
        try {
            val clazz = Class.forName(MainActivity::class.java.name, false, classLoader)
            val method = clazz.getDeclaredMethod("selfCheck")
            hook(method).intercept { true }
        } catch (t: Throwable) {
            log(Log.ERROR, tag, "selfCheck hook failed: ${t.message}")
        }
    }

    private companion object {
        const val SYSTEM_UI_PKG = "android"
        const val SELF_PKG = "com.mockloc"
    }
}
