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

        kit.install(kit.methodsOf(pmClass, "getInstalledPackages", Int::class.javaPrimitiveType!!)) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val list = chain.proceed() as? List<*> ?: return@install chain.proceed()
            list.filter { el -> (el as? PackageInfo)?.packageName !in hidden }
        }
        kit.install(kit.methodsOf(pmClass, "getInstalledApplications", Int::class.javaPrimitiveType!!)) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val list = chain.proceed() as? List<*> ?: return@install chain.proceed()
            list.filter { el -> (el as? ApplicationInfo)?.packageName !in hidden }
        }
        // A direct query for a hidden package must fail the way a missing package fails - the
        // documented NameNotFoundException, not null and not silence.
        val notFoundCtor = runCatching {
            PackageManager.NameNotFoundException::class.java.getDeclaredConstructor(String::class.java)
        }.getOrNull()
        kit.install(
            kit.methodsOf(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!),
        ) { chain ->
            val query = chain.args.getOrNull(0) as? String
            if (SpoofLogic.isActive(pkg) && query in hidden) {
                notFoundCtor?.newInstance("Package $query not found")?.let { throw it as Throwable }
            }
            chain.proceed()
        }
        kit.install(
            kit.methodsOf(pmClass, "getApplicationInfo", String::class.java, Int::class.javaPrimitiveType!!),
        ) { chain ->
            val query = chain.args.getOrNull(0) as? String
            if (SpoofLogic.isActive(pkg) && query in hidden) {
                notFoundCtor?.newInstance("Package $query not found")?.let { throw it as Throwable }
            }
            chain.proceed()
        }

        kit.log("$tag: 包隐藏就绪（${hidden.size} 个包不出现在枚举与直查里）")
    }
}
