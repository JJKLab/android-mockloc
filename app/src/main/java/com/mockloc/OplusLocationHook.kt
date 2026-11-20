package com.mockloc

import android.location.Location
import io.github.libxposed.api.XposedModule

/**
 * ColorOS「系统定位」的 Java 输出面。
 *
 * OLS（com.oplus.location）内置了一份 com.amap.android.location.NetworkLocationManager，
 * 银行/支付类 App 的「系统定位到您在 X」读的是这条链路，而不是 Android LocationManager。
 * 这里只替换 OLS 进程里所有返回 Location 的方法，不碰银行 App 进程，因此不会触发加固壳。
 */
class OplusLocationHook(private val module: XposedModule, private val classLoader: ClassLoader) {

    private val tag = "[mockloc-ols]"
    private val pkg = "com.oplus.location"

    fun init(): Int {
        val kit = HookKit(module, tag)
        val clazz = kit.findClass(classLoader, "com.amap.android.location.NetworkLocationManager")
            ?: run {
                Diagnostics.note("$tag: 未找到内置高德 NetworkLocationManager，OLS 改写未启用")
                return 0
            }
        val methods = clazz.declaredMethods.filter {
            it.returnType == Location::class.java && !it.name.startsWith("access$")
        }
        if (methods.isEmpty()) {
            Diagnostics.note("$tag: NetworkLocationManager 没有 Location 返回方法")
            return 0
        }
        val hooked = kit.install(methods) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val original = runCatching { chain.proceed() }.getOrNull() as? Location
            SpoofLogic.fakeLocation(original, original?.provider ?: "network", pkg)
        }
        kit.log("OLS Location 输出改写: ${hooked}/${methods.size} 个重载")
        Diagnostics.note("$tag: 已挂钩 ${hooked}/${methods.size} 个 Location 返回方法")
        return hooked
    }
}
