package com.mockloc

import android.location.Location
import android.location.LocationManager
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed entry (see https://github.com/libxposed/api).
 *
 * This hook set is what makes per-app spoofing possible: it runs inside each scoped app only,
 * so unlisted apps keep their real fix and the listed ones read fake coordinates off genuine
 * Location objects - no test provider, hence no mock bit to detect.
 *
 * Work is split by concern, and no hook is installed twice:
 * - here: the coordinates themselves, on every Location the process can touch
 * - [MockTrace]: the traces a system test provider leaves behind (mock bit, extras, Settings)
 * - [Sensors]: the consistency layer a detecting app cross-checks against (GNSS, WiFi, cell)
 *
 * `android` (system_server) is deliberately skipped. Rewriting locations there would make the
 * spoof global *and* flag-free, but a mistake in that process takes system_server down.
 */
class ModuleEntry : XposedModule() {

    private val tag = "[mockloc]"

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        if (pkg == SYSTEM_UI_PKG) {
            // system_server 在 LSPosed 里走 onSystemServerStarting，这个分支通常不会被调用；
            // 保留它是为了兼容把它当普通包调用的实现（同样装 SystemWash，已去重）。
            log(Log.INFO, tag, "attached to $pkg (system_server via onPackageReady)")
            Diagnostics.attach { message -> log(Log.ERROR, tag, message) }
            runCatching { Config.refresh() }
            if (!Config.systemWash) {
                log(Log.INFO, tag, "skipped system package scope (systemWash off)")
            } else if (!systemWashInstalled) {
                systemWashInstalled = true
                runCatching { SystemWash(this, param.classLoader).init() }
                    .onFailure { log(Log.WARN, tag, "system_server wash init failed: $it") }
            }
            return
        }
        // Anything that fails from now on has to reach logcat, not only an in-memory list that
        // lives in a process nobody is looking at.
        Diagnostics.attach { message -> log(Log.ERROR, tag, message) }
        log(Log.INFO, tag, "attached to $pkg")

        if (pkg == SELF_PKG) {
            // Only the load proof is hooked in our own process. Cleaning the mock bit here would
            // make the status page report success while the target apps were never touched.
            installSelfCheck(param.classLoader)
            return
        }

        Config.refresh()
        val kit = HookKit(this, tag)
        val locationClass = kit.findClass(param.classLoader, Location::class.java.name) ?: return
        // 组件级隔离：任何一个 hooker 的异常只丢它自己的钩子，绝不带崩宿主进程——
        // 2026-11-14 实锤：阳光惠生活(com.cebbank.yaoyao) 勾进作用域即闪退，就是某个组件
        // 在银行加固壳里抛异常把 onPackageReady 一起拖死。修好后它才能用 LSP 档。
        fun safeInstall(name: String, block: () -> Unit) {
            runCatching(block).onFailure {
                Diagnostics.note("$pkg: 组件 $name 异常(已跳过，不影响其他组件): ${it.message ?: it.javaClass.simpleName}")
                log(Log.WARN, tag, "$pkg: $name init failed: ${it}")
            }
        }
        if (Config.probe) safeInstall("Probe") { Probe(this, param.classLoader).init() }
        safeInstall("Coordinates") { installCoordinates(kit, locationClass, param.classLoader, pkg) }
        safeInstall("MockTrace") { MockTrace(this, param.classLoader, pkg).init() }
        safeInstall("Sensors") { Sensors(this, param.classLoader, pkg).init() }
        safeInstall("BleBlind") { BleBlind(this, param.classLoader, pkg).init() }
        safeInstall("GnssStream") { GnssStream(this, param.classLoader, pkg).init() }
        safeInstall("TencentSdk") { TencentSdk(this, param.classLoader, pkg).init() }
        safeInstall("AmapSdk") { AmapSdk(this, param.classLoader, pkg).init() }
        safeInstall("StackClean") { StackClean(this, param.classLoader, pkg).init() }
        safeInstall("PkgHide") { PkgHide(this, param.classLoader, pkg).init() }
        safeInstall("GmsHooks") { GmsHooks(this, param.classLoader, pkg).init() }
        safeInstall("SelfTest") { SelfTest.schedule(pkg) { message -> log(Log.INFO, tag, message) } }
        log(
            Log.INFO, tag,
            "$pkg: providers=${Config.providers} global=${Config.global} " +
                "gnss=${Config.gnssSpoof} blind=${Config.blindWifi}/${Config.blindCell}/${Config.blindBle} " +
                "probe=${Config.probe} notes=${Diagnostics.snapshot().size}"
        )
    }

    /**
     * system_server 走的是**这个**回调（不是 [onPackageReady]——实测后者在 system_server 里
     * 从不被调用）。在这里装"洗 mock 位"的 [SystemWash]：Location 跨 Binder 给 App 时是
     * system_server 写 parcel 的，把 `HAS_MOCK_PROVIDER_MASK` 位洗掉后，目标进程里不会
     * 有任何 Xposed 痕迹，也就不会被加固壳（梆梆 DexP 等）检测到而打崩。
     *
     * 风险：这里出错会拖垮 system_server。所以整套逻辑 fail-safe：初始化失败就不装，
     * hook 本身失败回退原位置；并用 [systemWashInstalled] 防重复安装。
     */
    @Volatile
    private var systemWashInstalled = false

    override fun onSystemServerStarting(param: io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam) {
        Diagnostics.attach { message -> log(Log.ERROR, tag, message) }
        runCatching { Config.refresh() }
        if (!Config.systemWash) {
            log(Log.INFO, tag, "skipped system_server (systemWash off)")
            return
        }
        if (systemWashInstalled) return
        systemWashInstalled = true
        log(Log.INFO, tag, "system_server: 安装洗位 hook")
        runCatching { SystemWash(this, param.classLoader).init() }
            .onFailure {
                log(Log.WARN, tag, "system_server wash init failed: $it")
                Diagnostics.note("$tag: 初始化失败，已放弃：${it.javaClass.simpleName}")
            }
    }

    /**
     * getLatitude/getLongitude cover every delivery route at once - push callbacks, poll APIs,
     * and objects the app deserialises itself - which is why no per-API rewriting is needed.
     *
     * `getLastKnownLocation` is still replaced outright because a scope with no real provider
     * returns null there, and null makes the app start a fresh fix request instead of reading
     * the coordinates we would then have to intercept.
     */
    private fun installCoordinates(kit: HookKit, clazz: Class<*>, classLoader: ClassLoader, pkg: String) {
        // Per-app points (REFACTOR-PLAN 2.2): a listed app reads its own coordinate, everyone else
        // the global one - from the same shared jitter offset.
        // 输出坐标走 currentOutCoordsFor：独立点位 + 抖动 + 按 App 坐标系一次算齐，lat/lng 成对
        // （GCJ/BD09 换算是二元函数，拆开各自换算会错位）。
        kit.install(kit.methodsOf(clazz, "getLatitude")) { chain ->
            if (SpoofLogic.isActive(pkg)) Config.currentOutCoordsFor(pkg)[0] else chain.proceed()
        }
        kit.install(kit.methodsOf(clazz, "getLongitude")) { chain ->
            if (SpoofLogic.isActive(pkg)) Config.currentOutCoordsFor(pkg)[1] else chain.proceed()
        }

        val lmClass = kit.findClass(classLoader, LocationManager::class.java.name) ?: return
        kit.install(kit.methodsOf(lmClass, "getLastKnownLocation", String::class.java)) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            SpoofLogic.fakeLocation(
                chain.proceed() as? Location,
                chain.args.getOrNull(0) as? String,
                pkg,
            )
        }
    }

    private fun installSelfCheck(classLoader: ClassLoader) {
        val kit = HookKit(this, tag)
        val clazz = kit.findClass(classLoader, MainActivity::class.java.name) ?: return
        kit.install(kit.methodsOf(clazz, "selfCheck")) { true }
    }

    private companion object {
        const val SYSTEM_UI_PKG = "android"
        const val SELF_PKG = "com.mockloc"
    }
}
