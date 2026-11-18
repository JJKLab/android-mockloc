import com.mockloc.RiskScore
import kotlin.system.exitProcess

/** JVM 断言：16 维评分的维度数、分级与"评暴露面不评效果"的语义。 */
private var fails = 0

private fun check(label: String, ok: Boolean, detail: String = "") {
    println("${if (ok) "PASS" else "FAIL"} $label${if (detail.isEmpty()) "" else ": $detail"}")
    if (!ok) fails++
}

fun main() {
    val closed = RiskScore.Snapshot(
        confEnabled = true,
        mockProviders = 4,
        mockTotal = 4,
        confMatch = true,
        loopIntervalSec = 1,
        blindWifi = true,
        blindCell = true,
        blindBle = true,
        netcutCount = 1,
        forgeWifi = true,
        sensorFreeze = true,
        hidePkgsCount = 2,
        stackClean = true,
        gmsSpoof = true,
        gnssSpoof = true,
        hasSatellites = true,
        dopplerNonZero = true,
        hookLedger = 12,
        leakClosed = true,
    )
    val all = RiskScore.score(closed)
    check("恰好 16 维", all.dims.size == 16, "${all.dims.size}")
    check("全收口 → 低", all.level == "低", all.level)
    check("全收口无 BAD/WARN", all.bad == 0 && all.warn == 0, "bad=${all.bad} warn=${all.warn}")

    // 一个开口（没有任何 provider 读到 mock）就应当是"高"。
    val openProvider = RiskScore.score(closed.copy(mockProviders = 0))
    check("provider 全丢 → 高", openProvider.level == "高", openProvider.level)
    check("provider 维是 BAD", openProvider.dims.first { it.name == "系统 provider 注入" }.state == RiskScore.State.BAD)

    // 部分读到 → 中。
    val partial = RiskScore.score(closed.copy(mockProviders = 2))
    check("provider 半读 → 中", partial.level == "中", partial.level)

    // OFF（可选面未开）不该拉高评级——评的是暴露面，不是"功能开得全不全"。
    val optionalOff = RiskScore.score(
        closed.copy(
            blindWifi = false, blindCell = false, blindBle = false,
            netcutCount = 0, forgeWifi = false, sensorFreeze = false, hidePkgsCount = 0,
        ),
    )
    check("可选面全关仍是低", optionalOff.level == "低", optionalOff.level)

    // 台账为 0 是可观测性缺口 → 中。
    val noLedger = RiskScore.score(closed.copy(hookLedger = 0))
    check("无台账 → 中", noLedger.level == "中", noLedger.level)

    // 配置不一致是 BAD。
    val mismatch = RiskScore.score(closed.copy(confMatch = false))
    check("配置不一致 → 高", mismatch.level == "高", mismatch.level)

    // 维度名不重复，且分类齐全。
    check("维度名不重复", all.dims.map { it.name }.distinct().size == 16)
    check("四个分类都有", all.dims.map { it.category }.toSet() == setOf("注入面", "输入面", "无痕面", "可观测"))

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    exitProcess(if (fails == 0) 0 else 1)
}
