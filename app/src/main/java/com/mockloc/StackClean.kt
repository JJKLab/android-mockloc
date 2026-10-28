package com.mockloc

import io.github.libxposed.api.XposedInterface

/**
 * Scrubs framework/VM frames that name the hooking framework out of the stacks a target app can
 * read (REFACTOR-PLAN 3.1). Risk-control SDKs walk `Throwable.getStackTrace` /
 * `Thread.getStackTrace` looking for `xposed`/`lsposed`-shaped class names; before this layer the
 * module was fully exposed on that surface (README E.3).
 *
 * Guardrails:
 * - only frames whose class name actually matches a known hooking-framework token are dropped;
 * - if filtering would leave an empty stack, the original is returned untouched - an empty stack
 *   from a Throwable that plainly has one is a louder tell than a filtered one;
 * - failures land in [Diagnostics] like every other hook, never a silent empty catch.
 */
class StackClean(
    private val module: XposedInterface,
    private val classLoader: ClassLoader,
    private val pkg: String,
) {
    private val tag = "[StackClean]"
    private val kit = HookKit(module, tag)

    fun init() {
        if (!Config.stackClean) return
        if (!SpoofLogic.isActive(pkg)) {
            // Hooks are per process; a package not in TARGETS keeps its own stacks untouched.
            return
        }

        val throwableClass = kit.findClass(classLoader, "java.lang.Throwable") ?: return
        kit.install(kit.methodsOf(throwableClass, "getStackTrace")) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val frames = chain.proceed() as? Array<*> ?: return@install chain.proceed()
            @Suppress("UNCHECKED_CAST")
            filter(frames as Array<StackTraceElement>)
        }

        val threadClass = kit.findClass(classLoader, "java.lang.Thread") ?: return
        kit.install(kit.methodsOf(threadClass, "getStackTrace")) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            val frames = chain.proceed() as? Array<*> ?: return@install chain.proceed()
            @Suppress("UNCHECKED_CAST")
            filter(frames as Array<StackTraceElement>)
        }

        val logClass = kit.findClass(classLoader, "android.util.Log") ?: return
        kit.install(kit.methodsOf(logClass, "getStackTraceString", Throwable::class.java)) { chain ->
            if (!SpoofLogic.isActive(pkg)) return@install chain.proceed()
            (chain.proceed() as? String)?.let(::filterText) ?: chain.proceed()
        }

        kit.log("$tag: 栈清洗就绪（Throwable/Thread/Log.getStackTraceString）")
    }

    private fun filter(frames: Array<StackTraceElement>): Array<StackTraceElement> {
        val kept = frames.filterNot { isDirtyFrame(it.className) }
        // Never hand back an empty stack where the app knows there was one.
        return if (kept.isEmpty()) frames else kept.toTypedArray()
    }

    private fun filterText(text: String): String {
        val lines = text.split('\n')
        val kept = lines.filterNot { line -> isDirtyFrame(line) }
        return if (kept.isEmpty()) text else kept.joinToString("\n")
    }

    private fun isDirtyFrame(name: String): Boolean {
        val lower = name.lowercase()
        return DIRTY_TOKENS.any { lower.contains(it) }
    }

    private companion object {
        /** Hooking-framework fingerprints; deliberately lowercase substrings, matched caseless. */
        val DIRTY_TOKENS = listOf(
            "de.robv.android.xposed", "io.github.libxposed", "org.lsposed", "org.meowcat",
            "edxposed", "sandhook", "dobby", "lsplant", "pine.kr", "topjohnwu",
        )
    }
}
