package com.mockloc

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedInterface

/**
 * Hides configured packages (and nothing else) from PackageManager enumeration inside hooked
 * processes (REFACTOR-PLAN 3.7). A target app can list installed packages and spot the LSPosed
 * manager or this module itself; the list is configuration-driven because over-filtering breaks
 * app functionality in ways that are much harder to debug than the exposure was.
 *
 * With `MOCKLOC_HIDE_PKGS` empty (the default) no hooks are installed at all - zero behaviour
 * change, which is the only honest default for a layer that lies to the app's own package queries.
 */
class PkgHide(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[PkgHide]"
    private val kit = HookKit(module, tag)

    fun init() {
        if (Config.hidePkgs.isEmpty()) return
        if (!SpoofLogic.isActive(pkg)) return
        val hidden = Config.hidePkgs

        val pmClass = kit.findClass(classLoader, PackageManager::class.java.name) ?: return

        // API 33+ 的 Flags 重载：目标 App 只要 targetSdk>=33 就会走这些方法，只钩 int 老重载会漏装
        // （v1.3.0 走查 P1-8）。Flags 是 PackageManager 的嵌套类、低版本上不存在，所以按名反射取，
        // 取不到就只装老重载——不能因为新版本 API 让整层在旧系统上崩掉。
        val pkgFlags = runCatching {
            classLoader.loadClass("android.content.pm.PackageManager\$PackageInfoFlags")
        }.getOrNull()
        val appFlags = runCatching {
            classLoader.loadClass("android.content.pm.PackageManager\$ApplicationFlags")
        }.getOrNull()

        kit.install(kit.methodsOf(pmClass, "getInstalledPackages", Int::class.javaPrimitiveType!!)) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val list = chain.proceed() as? List<*> ?: return@install chain.proceed()
            list.filter { el -> (el as? PackageInfo)?.packageName !in hidden }
        }
        if (pkgFlags != null) {
            kit.install(kit.methodsOf(pmClass, "getInstalledPackages", pkgFlags)) { chain ->
                if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
                val list = chain.proceed() as? List<*> ?: return@install chain.proceed()
                list.filter { el -> (el as? PackageInfo)?.packageName !in hidden }
            }
        }
        kit.install(kit.methodsOf(pmClass, "getInstalledApplications", Int::class.javaPrimitiveType!!)) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val list = chain.proceed() as? List<*> ?: return@install chain.proceed()
            list.filter { el -> (el as? ApplicationInfo)?.packageName !in hidden }
        }
        if (appFlags != null) {
            kit.install(kit.methodsOf(pmClass, "getInstalledApplications", appFlags)) { chain ->
                if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
                val list = chain.proceed() as? List<*> ?: return@install chain.proceed()
                list.filter { el -> (el as? ApplicationInfo)?.packageName !in hidden }
            }
        }
        // A direct query for a hidden package must fail the way a missing package fails - the
        // documented NameNotFoundException, not null and not silence.
        val notFoundCtor = runCatching {
            PackageManager.NameNotFoundException::class.java.getDeclaredConstructor(String::class.java)
        }.getOrNull()
        val queryGuard: (XposedInterface.Chain) -> Any? = { chain ->
            val query = chain.args.getOrNull(0) as? String
            if (SpoofLogic.isActive(pkg) && query in hidden) {
                notFoundCtor?.newInstance("Package $query not found")?.let { throw it as Throwable }
            }
            chain.proceed()
        }
        kit.install(
            kit.methodsOf(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!),
            queryGuard,
        )
        if (pkgFlags != null) {
            kit.install(
                kit.methodsOf(pmClass, "getPackageInfo", String::class.java, pkgFlags),
                queryGuard,
            )
        }
        kit.install(
            kit.methodsOf(pmClass, "getApplicationInfo", String::class.java, Int::class.javaPrimitiveType!!),
            queryGuard,
        )
        if (appFlags != null) {
            kit.install(
                kit.methodsOf(pmClass, "getApplicationInfo", String::class.java, appFlags),
                queryGuard,
            )
        }

        kit.log(
            "$tag: 包隐藏就绪（${hidden.size} 个包；枚举+直查，Flags 重载 ${if (pkgFlags != null) "已装" else "本版本无"}）",
        )
    }
}
