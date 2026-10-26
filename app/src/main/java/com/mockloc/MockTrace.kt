package com.mockloc

import android.content.ContentResolver
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import io.github.libxposed.api.XposedInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.util.function.Consumer

/**
 * Removes the traces a mock provider leaves inside the target app's own process.
 *
 * Why the field matters: hooking `isMock()` only covers the method path. WeChat's mini-program
 * location arrives as a Parcel-deserialised Location and its SDK can read the backing field
 * directly, so the field itself has to be cleared - and clearing it needs hidden-API access,
 * which is why HiddenApiBypass is initialised first.
 */
class MockTrace(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[MockTrace]"
    private val kit = HookKit(module, tag)

    fun init() {
        exemptHiddenApi()
        val locationClass = kit.findClass(classLoader, Location::class.java.name) ?: return
        val lmClass = kit.findClass(classLoader, LocationManager::class.java.name) ?: return

        hookFlagReaders(locationClass)
        hookFlagWriters(locationClass)
        hookExtras(locationClass)
        hookParcelPath(locationClass)
        hookConsumerDelivery(lmClass)
        hookSecureSetting()
        hookProviderLists(lmClass)
    }

    private fun active() = SpoofLogic.isActive(pkg)

    /**
     * The return value is the whole point here: HiddenApiBypass reports failure as `false` instead
     * of throwing, and if it failed then every reflection below silently stops working - which is
     * exactly the trap AnyWhere's empty catch walks into.
     */
    private fun exemptHiddenApi() {
        val ok = runCatching { HiddenApiBypass.addHiddenApiExemptions("") }.getOrNull() == true
        if (!ok) Diagnostics.note("$tag: HiddenApiBypass 未生效，反射清 mock 位会失败（改走 setter hook）")
    }

    private fun hookFlagReaders(clazz: Class<*>) {
        val on = { chain: XposedInterface.Chain -> if (active()) false else chain.proceed() }
        kit.install(kit.methodsOf(clazz, "isFromMockProvider"), on)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            kit.install(kit.methodsOf(clazz, "isMock"), on)
        }
    }

    /** Stop the flag being set at all, which also covers same-process listener callbacks. */
    private fun hookFlagWriters(clazz: Class<*>) {
        val forceFalse = { chain: XposedInterface.Chain ->
            if (!active()) chain.proceed() else chain.withArg(0, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            kit.install(kit.methodsOf(clazz, "setMock", Boolean::class.javaPrimitiveType!!), forceFalse)
        }
        // The pre-API-31 name; if this build dropped it, methodsOf says so instead of failing silently.
        kit.install(
            kit.methodsOf(clazz, "setIsFromMockProvider", Boolean::class.javaPrimitiveType!!),
            forceFalse,
        )
    }

    /**
     * A mock fix also carries a `mockLocation` extra. `Location.putExtra/removeExtra` are gone from
     * the API 36 surface, so the deletion happens on the Bundle `getExtras()` hands back - which is
     * the live Bundle, so the caller sees the key already gone.
     */
    private fun hookExtras(clazz: Class<*>) {
        kit.install(kit.methodsOf(clazz, "getExtras")) { chain ->
            val extras = chain.proceed() as? Bundle
            if (extras != null && active() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MOCK_EXTRA_KEYS.forEach { key ->
                    if (extras.containsKey(key)) runCatching { extras.remove(key) }
                        .onFailure { Diagnostics.note("$tag: 删 extra $key 失败: ${it.javaClass.simpleName}") }
                }
            }
            extras
        }
    }

    private fun hookParcelPath(clazz: Class<*>) {
        kit.install(kit.methodsOf(clazz, "readFromParcel", Parcel::class.java)) { chain ->
            val result = chain.proceed()
            clearMockField(chain.thisObject as? Location)
            result
        }
    }

    /**
     * `getLastKnownLocation` is deliberately not cleaned here: ModuleEntry already replaces its
     * result with a freshly constructed Location, whose mock bit is false by definition. This hook
     * covers the other delivery route, where the framework hands a Location straight to a callback.
     */
    private fun hookConsumerDelivery(lmClass: Class<*>) {
        // getCurrentLocation has several overloads across releases; the untyped lookup picks up
        // whichever ones this build has, and the Consumer is the argument that delivers the fix.
        kit.install(kit.methodsOf(lmClass, "getCurrentLocation")) { chain ->
            if (!active()) return@install chain.proceed()
            val args = chain.args.toTypedArray()
            for (i in args.indices) {
                val arg = args[i]
                if (arg is Consumer<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val original = arg as Consumer<Location?>
                    args[i] = Consumer<Location?> { location ->
                        location?.let { if (active()) clearMockField(it) }
                        original.accept(location)
                    }
                    break
                }
            }
            chain.proceed(args)
        }
    }

    /**
     * `mock_location` is the setting an app reads to learn that developer-options mocking is on.
     * getInt() normally delegates to getString() inside the framework, but that is an
     * implementation detail rather than a contract, so both entry points are covered.
     */
    private fun hookSecureSetting() {
        val secureClass = kit.findClass(classLoader, "android.provider.Settings\$Secure") ?: return
        kit.install(kit.methodsOf(secureClass, "getString", ContentResolver::class.java, String::class.java)) { chain ->
            val name = chain.args.getOrNull(1) as? String
            if (active() && name == MOCK_LOCATION_SETTING) "0" else chain.proceed()
        }
        kit.install(kit.methodsOf(secureClass, "getInt", ContentResolver::class.java, String::class.java)) { chain ->
            val name = chain.args.getOrNull(1) as? String
            if (active() && name == MOCK_LOCATION_SETTING) 0 else chain.proceed()
        }
        kit.install(
            kit.methodsOf(
                secureClass, "getInt",
                ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!
            )
        ) { chain ->
            val name = chain.args.getOrNull(1) as? String
            if (active() && name == MOCK_LOCATION_SETTING) 0 else chain.proceed()
        }
    }

    /** Hide any provider whose name is not one an app would expect to exist. */
    private fun hookProviderLists(lmClass: Class<*>) {
        val cleaner = { chain: XposedInterface.Chain ->
            @Suppress("UNCHECKED_CAST")
            val providers = chain.proceed() as? MutableList<String>
            if (providers != null && active()) {
                for (i in providers.indices.reversed()) {
                    if (providers[i] !in STANDARD_PROVIDERS) providers.removeAt(i)
                }
            }
            providers
        }
        kit.install(kit.methodsOf(lmClass, "getProviders", Boolean::class.javaPrimitiveType!!), cleaner)
        kit.install(
            kit.methodsOf(lmClass, "getProviders", Criteria::class.java, Boolean::class.javaPrimitiveType!!),
            cleaner,
        )
        kit.install(kit.methodsOf(lmClass, "getAllProviders"), cleaner)

        kit.install(kit.methodsOf(lmClass, "isProviderEnabled", String::class.java)) { chain ->
            val result = chain.proceed()
            val name = chain.args.getOrNull(0) as? String
            if (active() && name != null && name !in STANDARD_PROVIDERS) false else result
        }
    }

    private var mockField: Field? = null
    private var maskField: Field? = null
    private var fieldsResolved = false

    /**
     * Android has moved this between a boolean field and a bit in a mask across releases, so both
     * are attempted. Anything that fails is recorded rather than swallowed.
     */
    private fun clearMockField(location: Location?) {
        location ?: return
        if (!fieldsResolved) {
            fieldsResolved = true
            mockField = sequenceOf("mMock", "mIsFromMockProvider")
                .mapNotNull { name -> runCatching { Location::class.java.getDeclaredField(name) }.getOrNull() }
                .firstOrNull()
                ?.also { runCatching { it.isAccessible = true } }
            if (mockField == null) {
                Diagnostics.note("$tag: 未找到 mMock / mIsFromMockProvider 字段，反射清标记不可用")
            }
            maskField = runCatching {
                Location::class.java.getDeclaredField("mFieldsMask").apply { isAccessible = true }
            }.getOrNull()
        }

        mockField?.let { field ->
            runCatching { field.setBoolean(location, false) }
                .onFailure { Diagnostics.note("$tag: 写回 ${field.name} 失败: ${it.javaClass.simpleName} ${it.message}") }
        }
        val mask = maskField ?: return
        runCatching {
            val value = mask.getInt(location) and HAS_MOCK_PROVIDER_MASK.inv()
            mask.setInt(location, value)
        }.onFailure { Diagnostics.note("$tag: 清 mFieldsMask 位失败: ${it.javaClass.simpleName}") }
    }

    private companion object {
        val STANDARD_PROVIDERS = setOf("gps", "network", "passive", "fused")
        val MOCK_EXTRA_KEYS = listOf("mockLocation", "mock_location", "android.location.extra.MOCK_LOCATION")
        const val MOCK_LOCATION_SETTING = "mock_location"
        const val HAS_MOCK_PROVIDER_MASK = 1 shl 4
    }
}
