package com.mockloc

import android.content.Context
import java.io.File

/**
 * ROOT-only 管线的核心：把与 KernelSU 模块同源的两份脚本（assets/rootmodule/，CI 断言与
 * rootmodule/ 逐字节同步）安装到 /data/adb/mockloc/，再用 su 拉起 service.sh 循环。
 *
 * 为什么是"装脚本"而不是 App 内自己循环：A10 实测 test provider 的生命周期绑定注册方，
 * App 进程一死 provider 就没了；service.sh 循环以 root 身份常驻（nohup），App 关掉、
 * 甚至进程被 ColorOS 回收都不断供，BOOT 后由 [BootReceiver] 重新拉起。
 *
 * 不绑 LSP 的取舍（用户决策）：系统 provider 的 isMock 位无法隐藏，但目标进程零侵入
 * （无 classloader/maps/栈帧痕迹），对"难以检测"的优先级高于"无痕"。
 */
object RootLoopManager {

    private const val DIR = "/data/adb/mockloc"
    private const val PID = "$DIR/loop.pid"
    private const val LOG = "$DIR/service.log"

    /** 单次 su 批处理；RootInjector.runSu 复用同一超时语义。 */
    private fun su(commands: List<String>): Result<String> = RootInjector.runSu(commands)

    /** 脚本就位：assets → cache → su 拷贝到 /data/adb（幂等，每次覆盖为最新版本）。 */
    fun installScripts(context: Context): Boolean {
        val staging = File(context.cacheDir, "rootmodule").apply { mkdirs() }
        for (name in listOf("mockloc-lib.sh", "service.sh")) {
            context.assets.open("rootmodule/$name").use { input ->
                File(staging, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        val out = su(
            listOf(
                "mkdir -p $DIR",
                "cp ${staging.absolutePath}/mockloc-lib.sh $DIR/mockloc-lib.sh",
                "cp ${staging.absolutePath}/service.sh $DIR/service.sh",
                "chmod 700 $DIR/*.sh",
                "rm -rf ${staging.absolutePath}",
            ),
        )
        return out.isSuccess
    }

    /**
     * 拉起（幂等）：杀掉旧循环 → 装脚本 → nohup 新循环。conf 必须已经发布
     * （[ModuleBridge.render] → [RootInjector.publishConfig]），循环只读不写选点。
     */
    fun start(context: Context): Boolean {
        if (!RootInjector.rootAvailable(force = false)) return false
        if (!installScripts(context)) return false
        su(
            listOf(
                // 旧循环先停（pid 文件或进程名两种线索都试，幂等）
                "kill \$(cat $PID) 2>/dev/null; true",
                "pkill -f 'mockloc/service.sh' 2>/dev/null; true",
                "nohup sh $DIR/service.sh >> $LOG 2>&1 &",
            ),
        )
        return true
    }

    /** 停止循环 + 拆 provider + 清 netcut（App 的"停止注入"与关停都走这里）。 */
    fun stop(): Boolean {
        val out = su(
            listOf(
                "kill \$(cat $PID) 2>/dev/null; true",
                "pkill -f 'mockloc/service.sh' 2>/dev/null; true",
                "rm -f $PID",
                // 拆掉全局 test provider（与 service.sh 的 UP teardown 同一组命令）
                "for p in gps network fused; do " +
                    "cmd location providers set-test-provider-enabled \$p false 2>/dev/null; " +
                    "cmd location providers remove-test-provider \$p 2>/dev/null; done; true",
                // netcut 规则也一并清掉（teardown 在脚本里，这里直接按注释清理）
                "iptables -S OUTPUT 2>/dev/null | grep mockloc-netcut | sed 's/^-A/-D/' | " +
                    "while read -r r; do sh -c \"iptables \$r\"; done; true",
                "ip6tables -S OUTPUT 2>/dev/null | grep mockloc-netcut | sed 's/^-A/-D/' | " +
                    "while read -r r; do sh -c \"ip6tables \$r\"; done; true",
            ),
        )
        return out.isSuccess
    }

    /**
     * 循环是否在跑：pid 文件存在且 /proc/<pid> 可见。su 调用有成本，调用方须在
     * 后台线程且节流（状态页刷新已有 1s TTL，这里再按 10s 节流）。
     */
    private var lastCheckMs = 0L
    private var lastCheckResult = false

    fun running(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < 10_000) return lastCheckResult
        lastCheckMs = now
        lastCheckResult = su(listOf("test -d /proc/\$(cat $PID) && echo alive")).getOrElse { "" }
            .contains("alive")
        return lastCheckResult
    }
}
