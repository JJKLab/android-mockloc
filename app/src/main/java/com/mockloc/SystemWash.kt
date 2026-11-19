package com.mockloc

import android.location.Location
import android.os.Build
import android.os.Parcel
import io.github.libxposed.api.XposedModule

/**
 * system_server 层的"洗 mock 位"（可选，见 `Config.systemWash`）。
 *
 * 为什么需要它：`LocationManager.setTestProviderLocation` 注入的位置**必然**带 `isMock()=true`
 * （Android 官方文档：该位置会向所有客户端标识为模拟位置），无法在注入端绕过。很多 App
 * （银行/支付类尤甚）一旦读到 mock 位就丢弃，回落到真实定位——表现为"明明注入了却没生效"。
 *
 * 为什么放在 system_server：目标 App 进程内挂钩（档3）会被加固壳（梆梆 DexP 等）检测到并
 * 直接打崩进程；而 Location 跨 Binder 传给 App 时是由 system_server 写 parcel 的，在这里
 * 把 `mFieldsMask` 的 `HAS_MOCK_PROVIDER_MASK` 位洗掉，目标进程里没有任何 Xposed 痕迹。
 *
 * 安全边界：**只处理 `isMock()==true` 的位置**，真实定位原样放行；整个 hook 失败就回退到
 * 原始调用，最坏情况只是"没洗掉"，不会让定位链路出错。
 */
class SystemWash(private val module: XposedModule, private val classLoader: ClassLoader) {

    private val tag = "[mockloc-sys]"

    @Volatile
    private var washedOnce = false

    fun init() {
        val kit = HookKit(module, tag)
        val locationClass = kit.findClass(classLoader, Location::class.java.name) ?: run {
            Diagnostics.note("$tag: 找不到 android.location.Location，洗位未启用")
            return
        }
        val target = kit.methodsOf(
            locationClass, "writeToParcel",
            Parcel::class.java, Int::class.javaPrimitiveType!!,
        )
        if (target.isEmpty()) {
            Diagnostics.note("$tag: 找不到 Location#writeToParcel，洗位未启用")
            return
        }
        kit.install(target) { chain ->
            val loc = chain.thisObject as? Location
            if (loc == null || !isMock(loc)) {
                return@install chain.proceed()
            }
            // 写一份去掉 mock 位的副本；不调用原方法，避免写两遍。
            runCatching {
                val copy = Location(loc)
                clearMock(copy)
                copy.writeToParcel(chain.args[0] as Parcel, (chain.args[1] as Int))
                if (!washedOnce) {
                    washedOnce = true
                    Diagnostics.note("$tag: 首次洗位 ${"%.4f".format(loc.latitude)},${"%.4f".format(loc.longitude)}")
                }
            }.onFailure {
                Diagnostics.note("$tag: 洗位失败，回退原位置: ${it.javaClass.simpleName}")
                chain.proceed()
            }
        }
        Diagnostics.note("$tag: 已启用（只洗 isMock 位置）")
    }

    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock else loc.isFromMockProvider

    private fun clearMock(loc: Location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.setMock(false)
        } else {
            // API 29-30：mFieldsMask 的 bit4 = HAS_MOCK_PROVIDER_MASK
            runCatching {
                val f = Location::class.java.getDeclaredField("mFieldsMask").apply { isAccessible = true }
                f.setInt(loc, f.getInt(loc) and (1 shl 4).inv())
            }
        }
    }
}
