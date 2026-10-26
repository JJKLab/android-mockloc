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

    /** The first frame that is neither the framework, the VM, nor this module. */
    private fun caller(): String {
        return Thread.currentThread().stackTrace.firstOrNull { frame ->
            val c = frame.className
            listOf("android.", "java.", "kotlin.", "dalvik.", "com.android.", "com.mockloc.", "libcore.")
                .none { c.startsWith(it) }
        }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "未知调用方"
    }
}
