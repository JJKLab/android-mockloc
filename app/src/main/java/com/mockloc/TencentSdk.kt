package com.mockloc

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * WeChat's position comes from the Tencent Location SDK it embeds, not from `LocationManager`.
 * The in-process read-back proved the substitution there already works, yet WeChat's card stayed
 * real - so the value it uses is produced somewhere else.
 *
 * The SDK build inside WeChat is `com.tencent.map.geolocation.sapp.*`, which has no
 * `TencentLocationImpl` at all - that is why both this module's first attempt and LocationSpoofer's
 * "TencentLocation SDK not found" log came up empty. So nothing here is named after an
 * implementation class:
 *
 * 1. every `TencentLocationManager` method that takes a `TencentLocationListener` gets its listener
 *     argument wrapped, whatever the method is called and whatever else it takes;
 * 2. the wrapper swaps the `TencentLocation` handed to `onLocationChanged` for a dynamic proxy of
 *     that interface, which answers coordinates, accuracy, the mock flag and the address text.
 *
 * Answering the address text matters: faking only the numbers leaves the SDK's own reverse-geocoded
 * string naming the real street, which is the "map moved but the text didn't" inconsistency.
 *
 * Absent SDK classes are normal (Amap has none), so that path logs instead of raising a note.
 */
class TencentSdk(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[Tencent]"
    private val kit = HookKit(module, tag)

    fun init() {
        val location = iface(PKG + "TencentLocation") ?: return absent("TencentLocation")
        val listener = iface(PKG + "TencentLocationListener") ?: return absent("TencentLocationListener")
        val manager = runCatching { Class.forName(PKG + "TencentLocationManager", false, classLoader) }
            .getOrNull() ?: return absent("TencentLocationManager")

        val requests = manager.declaredMethods.filter { m ->
            m.parameterTypes.any { it == listener }
        }
        if (requests.isEmpty()) {
            kit.log("$tag: $manager 上没有以 listener 为参数的方法，跳过")
            return
        }
        val wrapped = { chain: XposedInterface.Chain ->
            if (!active()) chain.proceed() else {
                val args = chain.args.toTypedArray()
                for (i in args.indices) {
                    if (listener.isInstance(args[i])) {
                        args[i] = Proxy.newProxyInstance(
                            classLoader, arrayOf(listener), Deliver(location, args[i]!!),
                        )
                    }
                }
                chain.proceed(args)
            }
        }
        kit.install(requests, wrapped)
        kit.log("$tag: 接管 ${manager.simpleName} 的 ${requests.size} 个请求方法")
    }

    /** Replaces the SDK location object on its way to the app's listener. */
    private inner class Deliver(val location: Class<*>, val real: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val argv = args ?: emptyArray()
            val swapped = if (method.name == "onLocationChanged" && argv.isNotEmpty() &&
                location.isInstance(argv[0])
            ) {
                argv.copyOf().also {
                    it[0] = Proxy.newProxyInstance(classLoader, arrayOf(location), Fake(it[0]))
                }
            } else {
                argv
            }
            return call(real, method, swapped)
        }
    }

    private inner class Fake(val real: Any?) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val argv = args ?: emptyArray()
            if (!active()) return passthrough(method, argv)
            return when (method.name) {
                "getLatitude" -> Config.currentLat
                "getLongitude" -> Config.currentLng
                "getAccuracy" -> Config.accuracy
                "isMock" -> false
                "getAddress", "getCity", "getDistrict", "getProvince" ->
                    if (Config.label.isNotEmpty()) Config.label else passthrough(method, argv)
                else -> passthrough(method, argv)
            }
        }

        /** Anything we do not answer for still has to behave like the object it replaces. */
        private fun passthrough(method: Method, args: Array<Any?>): Any? =
            real?.let { call(it, method, args) } ?: default(method)

        private fun default(method: Method): Any? = when (method.returnType.name) {
            "double" -> 0.0
            "float" -> 0f
            "int", "long", "short", "byte" -> 0
            "boolean" -> false
            else -> null
        }
    }

    private fun call(target: Any, method: Method, args: Array<Any?>): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun iface(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }
            .getOrNull()?.takeIf { it.isInterface }

    private fun absent(what: String) = kit.log("$tag: 这个 App 里没有 $what，跳过（正常，比如高德）")

    private fun active() = SpoofLogic.isActive(pkg)

    private companion object {
        const val PKG = "com.tencent.map.geolocation.sapp."
    }
}
