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
        kit.log("OLS Location 输出改写: ${hooked}/${methods.size} 个重载")
        Diagnostics.note("$tag: 已挂钩 ${hooked}/${methods.size} 个 Location 返回方法")
        return hooked
    }

    /** AMap encodes city/adcode in Location extras; replacing only lat/lng leaves a default city. */
    private fun applyCityExtras(out: Location, original: Location?, city: String) {
        val (province, citycode, adcode, district) = cityInfo(city)
        val extras = original?.extras ?: Bundle()
        extras.putInt("amap_location_resultCode", 0)
        extras.putString("amap_location_result_info", city)
        extras.putInt("amap", 1)
        extras.putString("country", "中国")
        extras.putString("province", province)
        extras.putString("city", city)
        extras.putString("district", district)
        extras.putString("citycode", citycode)
        extras.putString("adcode", adcode)

        val address = Address(Locale.CHINA).apply {
            countryName = "中国"
            adminArea = province
            locality = city
            subLocality = district
            latitude = out.latitude
            longitude = out.longitude
            val addrExtras = Bundle().apply {
                putString("citycode", citycode)
                putString("adcode", adcode)
            }
            this.extras = addrExtras
        }
        extras.putParcelable("address", address)
        out.extras = extras
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
