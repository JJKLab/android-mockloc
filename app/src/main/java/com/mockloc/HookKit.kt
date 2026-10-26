package com.mockloc

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections

/**
 * Hook plumbing shared by the hook sets, plus a visible failure log.
 *
 * AnyWhere swallows every reflection/hook error with an empty catch, which means a hook that
 * silently stopped working on a newer Android looks identical to one that works. Every failure
 * here is recorded and surfaced on the status page instead.
 */
class HookKit(private val module: XposedInterface, private val tag: String) {

    fun findClass(classLoader: ClassLoader, vararg names: String): Class<*>? {
        for (name in names) {
            runCatching { return Class.forName(name, false, classLoader) }.recoverCatching { null }
        }
        Diagnostics.note("$tag: 找不到类 ${names.joinToString()}")
        return null
    }

    fun methodsOf(clazz: Class<*>, name: String, vararg types: Class<*>): List<Method> =
        if (types.isEmpty()) {
            clazz.declaredMethods.filter { it.name == name }.also {
                if (it.isEmpty()) Diagnostics.note("$tag: ${clazz.simpleName}#$name 在这个版本上不存在")
            }
        } else {
            runCatching { listOf(clazz.getDeclaredMethod(name, *types)) }
                .getOrElse {
                    // ART hides member lookup and reflection behind the same greylist check, so a
                    // blocked member and a genuinely absent one both surface as NoSuchMethod.
                    Diagnostics.note("$tag: 找不到 ${clazz.simpleName}#$name（该版本没有，或被 hidden API 挡住）")
                    emptyList()
                }
        }

    /** Returns the number of successfully hooked overloads; 0 means the caller should worry. */
    fun install(methods: List<Method>, handler: (XposedInterface.Chain) -> Any?): Int {
        var hooked = 0
        methods.forEach { method ->
            runCatching { module.hook(method).intercept { chain -> handler(chain) } }
                .onSuccess { hooked++ }
                .onFailure { Diagnostics.note("$tag: 挂钩 ${method.declaringClass.simpleName}#${method.name} 失败: ${it.message}") }
        }
        if (hooked == 0 && methods.isNotEmpty()) {
            Diagnostics.note("$tag: ${methods.first().name} 声明了 ${methods.size} 个重载但一个都没挂上")
        }
        return hooked
    }

    fun log(message: String) = module.log(Log.INFO, tag, message)
}

/** Replace one argument and continue the original call. */
fun XposedInterface.Chain.withArg(index: Int, value: Any?): Any? {
    val args = this.args.toTypedArray()
    args[index] = value
    return this.proceed(args)
}

/** Hook failures and version surprises, readable from the app's status page. */
object Diagnostics {
    private val items: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /**
     * Set by the module entry so a note reaches logcat the moment it happens. The list below only
     * lives in the process that recorded it, so without this a failure inside WeChat's process
     * would be invisible to every other process.
     */
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun attach(printer: (String) -> Unit) {
        sink = printer
    }

    fun note(message: String) {
        val isNew = synchronized(items) {
            if (items.size < 64 && !items.contains(message)) items.add(message) else false
        }
        if (isNew) runCatching { sink?.invoke(message) }
    }

    fun snapshot(): List<String> = synchronized(items) { items.toList() }

    fun clear() = synchronized(items) { items.clear() }
}
