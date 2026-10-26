package com.mockloc

import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in (`MOCKLOC_PROBE=true`) call logger: which positioning inputs does the target app actually
 * consult, and from which of its own classes.
 *
 * Guessing at SDK internals has already produced two wrong conclusions in this project (A7), so
 * rather than adding more hooks on speculation this prints the evidence. One line per distinct
 * method, then every 20th repeat, each with the first stack frame that belongs to the app itself.
 *
 * Everything here only observes - it never changes a return value, so a probe run cannot itself
 * make an app behave differently.
 */
class Probe(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
) {
    private val tag = "[Probe]"
    private val kit = HookKit(module, tag)
    private val hits = ConcurrentHashMap<String, Int>()

    private companion object {
        val SKIP_PREFIXES = listOf(
            "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.",
            "libcore.", "com.android.", "com.mockloc.", "art.",
        )
    }

    fun init() {
        watch("定位读取", LocationManager::class.java.name,
            "getLastKnownLocation", "getCurrentLocation", "requestLocationUpdates", "getProviders")
        watch("WiFi输入", WifiManager::class.java.name,
            "getScanResults", "getConnectionInfo", "isScanAlwaysAvailable")
        watch("WiFi身份", "android.net.wifi.WifiInfo", "getBSSID", "getSSID", "getMacAddress")
        watch("基站输入", TelephonyManager::class.java.name,
            "getAllCellInfo", "getCellLocation", "requestCellInfoUpdate", "getNetworkOperator")
        watch("mock痕迹", Location::class.java.name,
            "isMock", "isFromMockProvider", "getExtras", "getProvider")
        watch("开发者设置", "android.provider.Settings\$Secure", "getString", "getInt")
    }

    private fun watch(group: String, className: String, vararg methods: String) {
        val clazz = kit.findClass(classLoader, className) ?: return
        methods.forEach { name ->
            // Every overload of that name, so a version that added a parameter is still covered.
            kit.install(clazz.declaredMethods.filter { it.name == name }) { chain ->
                val key = "$group#$name"
                val count = hits.merge(key, 1) { old, one -> old + one } ?: 1
                if (count == 1 || count % 20 == 0) kit.log("$group $name ×$count ← ${caller()}")
                chain.proceed()
            }
        }
    }

    /**
     * Up to three frames that belong to neither the framework, the VM, nor a hook layer.
     *
     * One frame was not enough to tell an app call site from the hook chain's own dispatch: the
     * first version printed `k2.intercept:8`, which is a callback frame, not the caller. Anything
     * named after Xposed/LSPosed/libxposed is the plumbing and has to be skipped too.
     */
    private fun caller(): String {
        val frames = Thread.currentThread().stackTrace.filter { frame ->
            val c = frame.className
            SKIP_PREFIXES.none { c.startsWith(it) } && !c.contains("xposed", true) && !c.contains("Hooker")
        }
        if (frames.isEmpty()) return "未知调用方"
        return frames.take(3).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
    }
}
