package com.mockloc

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tier-2 injection driven from inside the App, replacing the "wait for the root module's mtime
 * loop or plug in a PC" hop (REFACTOR-PLAN 1.3 / 4.2). KernelSU grants su to *apps* independently
 * of what it gives `adb shell` (which this ROM strips), so when the user has approved us once, the
 * App can:
 *  1. publish the rendered conf straight to /data/adb + /data/local/tmp (the module and the
 *     mtime-based `sync_app_prefs` then see the App as the newest writer and yield), and
 *  2. run the same `cmd location` commands the boot script runs, for an immediate effect.
 *
 * One-shot providers still die with the shell that registered them (A10), so this is always paired
 * with publishConfig(): with the module flashed the boot loop takes over maintenance; without it,
 * the user re-injects from the App.
 */
object RootInjector {

    /**
     * 持久 su 会话：巡检模式每 tick 都要注入，逐次 spawn su（每次过 KernelSU 校验）太重，
     * 这里保持一个常驻 su 进程，命令经 stdin 流式下发。
     *
     * 两个坑已实测：
     * - 输出必须有人消费（后台守护线程排空），否则 pipe 写满后 su 子进程整体阻塞；
     * - 会话关闭时 KernelSU 会回收其子进程——nohup/setsid 都逃不掉（v1.0.0 脱离循环实验
     *   结论），所以会话生命周期必须由 App 前台服务持有，脱离 root 循环方案作废。
     */
    object SuSession {
        private var proc: Process? = null
        private var stdin: java.io.OutputStream? = null

        @Synchronized
        fun alive(): Boolean = proc?.isAlive == true

        @Synchronized
        fun ensure(): Boolean {
            if (alive()) return true
            return runCatching {
                val p = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                // 排空线程：巡检的 cmd 输出只进黑洞（诊断靠 dumpsys 读回，不靠这里）。
                Thread {
                    runCatching { p.inputStream.use { it.readBytes() } }
                }.apply { isDaemon = true }.start()
                proc = p
                stdin = p.outputStream
                true
            }.getOrElse { false }
        }

        @Synchronized
        fun send(commands: List<String>): Boolean {
            if (!ensure()) return false
            return runCatching {
                val w = stdin ?: return false
                commands.forEach { w.write((it + "\n").toByteArray()) }
                w.flush()
                true
            }.getOrElse {
                close()
                false
            }
        }

        @Synchronized
        fun close() {
            runCatching { stdin?.write("exit\n".toByteArray()); stdin?.flush() }
            runCatching { proc?.destroy() }
            proc = null
            stdin = null
        }
    }

    /** AGPS 辅助数据清理：换点后旧星历会让收敛变慢甚至先跳回旧点。 */
    fun agpsPurge(): Boolean = SuSession.send(
        listOf("cmd location send-extra-command gps delete_aiding_data 2>/dev/null; true"),
    )

    /** 按 uid 掐网（v4+v6 双栈）；uid 由包名解析，失败即跳过该包。 */
    fun netcutApply(packages: List<String>): Boolean {
        if (packages.isEmpty()) return netcutClear()
        val cmds = mutableListOf(
            "iptables -S OUTPUT 2>/dev/null | grep mockloc-netcut | sed 's/^-A/-D/' | while read -r r; do sh -c \"iptables \$r\"; done; true",
            "ip6tables -S OUTPUT 2>/dev/null | grep mockloc-netcut | sed 's/^-A/-D/' | while read -r r; do sh -c \"ip6tables \$r\"; done; true",
        )
        packages.forEach { pkg ->
            cmds.add(
                "uid=\$(stat -c %u /data/data/$pkg 2>/dev/null); " +
                    "[ -n \"\$uid\" ] || uid=\$(dumpsys package $pkg 2>/dev/null | sed -n 's/.*userId=\\([0-9]*\\).*/\\1/p' | head -n 1); " +
                    "if [ -n \"\$uid\" ]; then " +
                    "iptables -I OUTPUT -m owner --uid-owner \$uid -m comment --comment mockloc-netcut -j DROP 2>/dev/null; " +
                    "ip6tables -I OUTPUT -m owner --uid-owner \$uid -m comment --comment mockloc-netcut -j DROP 2>/dev/null; fi; true",
            )
        }
        return SuSession.send(cmds)
    }

    fun netcutClear(): Boolean = SuSession.send(
        listOf(
            "iptables -S OUTPUT 2>/dev/null | grep mockloc-netcut | sed 's/^-A/-D/' | while read -r r; do sh -c \"iptables \$r\"; done; true",
            "ip6tables -S OUTPUT 2>/dev/null | grep mockloc-netcut | sed 's/^-A/-D/' | while read -r r; do sh -c \"ip6tables \$r\"; done; true",
        ),
    )

    // 15s, not the usual 8s: the first probe is the moment KernelSU/APatch pops its grant dialog,
    // and the user is allowed to be slower than that. A dismissed dialog still fails fast; only
    // an unanswered one waits.
    private const val TIMEOUT_MS = 15_000L
    private val CFG_DIR = "/data/adb/mockloc"
    private val CFG_PATH = "$CFG_DIR/config.prop"
    private val PUB_PATH = "/data/local/tmp/mockloc.conf"

    @Volatile
    private var probeCache: Boolean? = null

    /** Cached su probe; [force] re-runs it (the user may have granted us between probes). */
    fun rootAvailable(force: Boolean = false): Boolean {
        if (!force) probeCache?.let { return it }
        val out = runSu(listOf("id")).getOrElse { return false.also { probeCache = false } }
        val ok = out.contains("uid=0")
        probeCache = ok
        return ok
    }

    /**
     * Feed commands to an interactive su shell over stdin: no quoting layers, one process for the
     * whole batch. Returns combined stdout/stderr; a nonzero exit is reported by the caller via
     * the output itself, because `cmd location` failures print to stderr while su exits 0.
     */
    fun runSu(commands: List<String>): Result<String> = runCatching {
        val process = ProcessBuilder("su").start()
        process.outputStream.use { os ->
            commands.forEach { cmd -> os.write((cmd + "\n").toByteArray()) }
            os.write("exit\n".toByteArray())
            os.flush()
        }
        val out = process.inputStream.use { it.readBytes().decodeToString() }
        val err = process.errorStream.use { it.readBytes().decodeToString() }
        if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("su timed out after ${TIMEOUT_MS}ms")
        }
        (out + err).ifBlank { "(no output, exit=${process.exitValue()})" }
    }

    /**
     * Write the conf the way the boot module publishes it: the private copy under /data/adb
     * (root-only) and the world-readable mirror the Xposed module reads. The rendered body comes
     * from [ModuleBridge.render] verbatim - one renderer, no reformatting here.
     */
    fun publishConfig(context: Context, body: String): Boolean {
        val staging = File(context.cacheDir, "mockloc-publish.conf").apply { writeText(body) }
        val out = runSu(
            listOf(
                "mkdir -p $CFG_DIR",
                "cat ${staging.absolutePath} > $CFG_PATH",
                "cp ${staging.absolutePath} $PUB_PATH",
                "chmod 600 $CFG_PATH",
                "chmod 664 $PUB_PATH",
                "rm -f ${staging.absolutePath}",
            ),
        ).getOrElse { return false }
        // The mirror must parse as enabled for the module to act; a truncated cat shows up here.
        return out.contains("Permission denied").not()
    }

    /**
     * The exact command set the boot script runs (A15 tier-2 shape): app-op for uid 0, master
     * switch on, one test provider per name, each fed immediately. Returns the su output so the
     * caller can surface failures on the status page instead of guessing.
     */
    fun apply(lat: Double, lng: Double, acc: Float, providers: List<String>, alt: Double): String {
        val stamp = System.currentTimeMillis()
        val cmds = mutableListOf(
            "appops set 0 android:mock_location allow",
            "cmd location set-location-enabled true",
        )
        providers.forEach { p ->
            cmds += "cmd location providers add-test-provider $p"
            cmds += "cmd location providers set-test-provider-enabled $p true"
            cmds += ("cmd location providers set-test-provider-location $p " +
                "--location $lat,$lng --accuracy $acc --time $stamp")
        }
        return runSu(cmds).getOrElse { "su failed: ${it.message}" }
    }

    fun teardown(providers: List<String>): String = runSu(
        providers.flatMap { p ->
            listOf(
                "cmd location providers set-test-provider-enabled $p false",
                "cmd location providers remove-test-provider $p",
            )
        },
    ).getOrElse { "su failed: ${it.message}" }

    /** Nothing to reclaim yet - called when the App's pick stops being global. */
    fun clearGlobalFlag(context: Context): Boolean =
        runSu(listOf("sed -i 's/^MOCKLOC_GLOBAL=.*/MOCKLOC_GLOBAL=false/' $CFG_PATH $PUB_PATH"))
            .isSuccess
}
