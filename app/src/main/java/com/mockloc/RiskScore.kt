package com.mockloc

/**
 * 暴露风险评分（16 维）。
 *
 * README 阶段四 ⑭ 声称"状态页顶部暴露风险卡（全域 16 维的可观测子集）"已落地，实际代码里没有；
 * 这里补上完整 16 维。**它评的是暴露面，不是效果**——每一维只回答"这条面收口了没有"，
 * 不回答"目标 App 认不认"。
 *
 * 纯 Kotlin、不碰 android.*，所以能在 JVM 上断言（`tools/kotlin/RiskScoreCheck.kt`）。
 */
object RiskScore {

    enum class State { OK, WARN, BAD, OFF }

    data class Dim(
        val name: String,
        val category: String,
        val state: State,
        val note: String,
    )

    data class Report(
        val dims: List<Dim>,
        val level: String,
        val bad: Int,
        val warn: Int,
    )

    /** 评分输入：全部可由 Config + 少量运行时标志得到。 */
    data class Snapshot(
        val confEnabled: Boolean = false,
        val mockProviders: Int = 0,
        val mockTotal: Int = 0,
        val confMatch: Boolean = false,
        val rootMode: Boolean = false,
        val loopIntervalSec: Int = 1,
        val blindWifi: Boolean = false,
        val blindCell: Boolean = false,
        val blindBle: Boolean = false,
        val netcutCount: Int = 0,
        val forgeWifi: Boolean = false,
        val sensorFreeze: Boolean = false,
        val hidePkgsCount: Int = 0,
        val stackClean: Boolean = true,
        val gmsSpoof: Boolean = true,
        val gnssSpoof: Boolean = true,
        val hasSatellites: Boolean = false,
        val dopplerNonZero: Boolean = false,
        val hookLedger: Int = 0,
        val leakClosed: Boolean? = null,
    )

    fun score(s: Snapshot): Report {
        val dims = ArrayList<Dim>(16)
        dims += providerDim(s)
        dims += confMatchDim(s)
        dims += blindDim("致盲 WiFi", "输入面", s.blindWifi, "抽空扫描结果，逼 App 落回系统定位")
        dims += blindDim("致盲基站", "输入面", s.blindCell, "空表；CellInfo 伪造明确不做")
        dims += blindDim("致盲 BLE", "输入面", s.blindBle, "蓝牙扫描结果")
        dims += countDim("掐网名单", "输入面", s.netcutCount, "ROOT 守护按 uid 断网")
        dims += flagDim("伪 WiFi", "输入面", s.forgeWifi, "伪造 4 个确定性 AP，避开 0-AP 异常态")
        dims += flagDim("传感器冻结", "输入面", s.sensorFreeze, "加速度/陀螺/计步静止化")
        dims += countDim("包隐藏", "输入面", s.hidePkgsCount, "枚举与直查都不出现")
        dims += flagDim("栈清洗", "无痕面", s.stackClean, "异常栈里的 hook 框架指纹")
        dims += flagDim("GMS 封边", "无痕面", s.gmsSpoof, "fused 自读一次的口")
        dims += flagDim("GNSS 伪造", "无痕面", s.gnssSpoof, "测量/时钟/星历整链")
        dims += flagDim("卫星视图", "无痕面", s.hasSatellites, "星历非空且自洽")
        dims += flagDim("多普勒/ADR", "无痕面", s.dopplerNonZero, "伪距率非零、ADR 积分漂移")
        dims += ledgerDim(s)
        dims += leakDim(s)

        val bad = dims.count { it.state == State.BAD }
        val warn = dims.count { it.state == State.WARN }
        val level = when {
            bad > 0 -> "高"
            warn > 0 -> "中"
            else -> "低"
        }
        return Report(dims, level, bad, warn)
    }

    private fun providerDim(s: Snapshot): Dim {
        val state = when {
            s.mockTotal <= 0 -> State.OFF
            s.mockProviders >= s.mockTotal -> State.OK
            s.mockProviders > 0 -> State.WARN
            else -> State.BAD
        }
        val note = when (state) {
            State.OK -> "${s.mockProviders}/${s.mockTotal} 个 provider 读到 mock=true"
            State.WARN -> "只有 ${s.mockProviders}/${s.mockTotal} 个读到 mock=true"
            State.BAD -> "没有任何 provider 读到 mock=true"
            State.OFF -> "未注入"
        }
        return Dim("系统 provider 注入", "注入面", state, note)
    }

    private fun confMatchDim(s: Snapshot): Dim {
        if (!s.confEnabled) return Dim("配置一致", "注入面", State.OFF, "模块未启用")
        val state = if (s.confMatch) State.OK else State.BAD
        return Dim(
            "配置一致", "注入面", state,
            if (s.confMatch) "App 渲染体与模块 conf 一致" else "App 渲染体与模块 conf 不一致（可能被静默重置）",
        )
    }

    private fun blindDim(name: String, cat: String, on: Boolean, note: String): Dim =
        if (on) Dim(name, cat, State.OK, note) else Dim(name, cat, State.OFF, "未开 · $note")

    private fun flagDim(name: String, cat: String, on: Boolean, note: String): Dim =
        if (on) Dim(name, cat, State.OK, note) else Dim(name, cat, State.OFF, "未开 · $note")

    private fun countDim(name: String, cat: String, count: Int, note: String): Dim =
        if (count > 0) Dim(name, cat, State.OK, "$count 项 · $note") else Dim(name, cat, State.OFF, "空 · $note")

    private fun ledgerDim(s: Snapshot): Dim =
        if (s.hookLedger > 0) {
            Dim("挂钩台账", "可观测", State.OK, "${s.hookLedger} 条 · Diagnostics 有记账")
        } else {
            Dim("挂钩台账", "可观测", State.WARN, "0 条 · 无法自证挂钩装上了")
        }

    private fun leakDim(s: Snapshot): Dim {
        val closed = s.leakClosed ?: return Dim("泄露反推", "可观测", State.OFF, "探针未开")
        return if (closed) {
            Dim("泄露反推", "可观测", State.OK, "钩子前/后对照闭合")
        } else {
            Dim("泄露反推", "可观测", State.BAD, "有真实射频证据泄露")
        }
    }
}
