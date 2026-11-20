package com.mockloc

import android.location.Address
import android.location.Location
import android.os.Bundle
import java.util.Locale
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
            val fake = SpoofLogic.fakeLocation(original, original?.provider ?: "network", pkg)
            applyCityExtras(fake, original, Config.olsCity)
            fake
        }
        // OLS 的 city 也可能被缓存进 Location 对象本身；同步改写 getLatitude/getLongitude/getExtras，
        // 避免“坐标变了但城市仍是默认”的假阳性。
        val locationClass = kit.findClass(classLoader, Location::class.java.name) ?: return hooked
        var locationHooks = 0
        locationHooks += kit.install(kit.methodsOf(locationClass, "getLatitude")) { chain ->
            if (SpoofLogic.isActive(pkg)) Config.currentOutCoordsFor(pkg)[0] else chain.proceed()
        }
        locationHooks += kit.install(kit.methodsOf(locationClass, "getLongitude")) { chain ->
            if (SpoofLogic.isActive(pkg)) Config.currentOutCoordsFor(pkg)[1] else chain.proceed()
        }
        locationHooks += kit.install(kit.methodsOf(locationClass, "getExtras")) { chain ->
            if (SpoofLogic.isActive(pkg)) cityExtras(Config.olsCity) else chain.proceed()
        }
        kit.log("OLS Location 输出改写: ${hooked}/${methods.size} 个重载 + Location ${locationHooks}/3")
        Diagnostics.note("$tag: 已挂钩 ${hooked}/${methods.size} 个 Location 返回方法")
        return hooked
    }

    /** AMap encodes city/adcode in Location extras; replacing only lat/lng leaves a default city. */
    private fun applyCityExtras(out: Location, original: Location?, city: String) {
        val extras = cityExtras(city)
        original?.extras?.let { extras.putAll(it) }
        cityExtras(city).let { fresh -> extras.putAll(fresh) }
        out.extras = extras
    }

    private fun cityExtras(city: String): Bundle {
        val (province, citycode, adcode, district) = cityInfo(city)
        return Bundle().apply {
            putInt("amap_location_resultCode", 0)
            putString("amap_location_result_info", city)
            putInt("amap", 1)
            putString("country", "中国")
            putString("province", province)
            putString("city", city)
            putString("district", district)
            putString("citycode", citycode)
            putString("adcode", adcode)
            putParcelable(
                "address",
                Address(Locale.CHINA).apply {
                    countryName = "中国"
                    adminArea = province
                    locality = city
                    subLocality = district
                    val addrExtras = Bundle().apply {
                        putString("citycode", citycode)
                        putString("adcode", adcode)
                    }
                    this.extras = addrExtras
                },
            )
        }
    }

    private fun cityInfo(city: String): Array<String> = when (city) {
        "杭州" -> arrayOf("浙江省", "330100", "330100", "西湖区")
        "上海" -> arrayOf("上海市", "310000", "310000", "浦东新区")
        "北京" -> arrayOf("北京市", "110000", "110000", "朝阳区")
        "苏州" -> arrayOf("江苏省", "320500", "320500", "姑苏区")
        "广州" -> arrayOf("广东省", "440100", "440100", "天河区")
        "深圳" -> arrayOf("广东省", "440300", "440300", "南山区")
        else -> arrayOf("", "", "", "")
    }
}
