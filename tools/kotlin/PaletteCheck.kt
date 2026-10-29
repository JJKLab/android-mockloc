import com.mockloc.Palette
import kotlin.system.exitProcess

/**
 * MD3 色板层验收：亮度数学、挑色带、静态双色板的对比度底线、动态组色的带筛选/去重/兜底。
 * 纯 JVM，不碰 android.*。
 */
private var fails = 0

private fun check(label: String, ok: Boolean, detail: String = "") {
    println("${if (ok) "PASS" else "FAIL"} $label${if (detail.isEmpty()) "" else ": $detail"}")
    if (!ok) fails++
}

private fun near(a: Double, b: Double, tol: Double) = kotlin.math.abs(a - b) <= tol

fun main() {
    // ---- 亮度数学 ----
    check("白亮度=1", near(Palette.relativeLuminance(0xFFFFFFFF.toInt()), 1.0, 1e-9))
    check("黑亮度=0", near(Palette.relativeLuminance(0xFF000000.toInt()), 0.0, 1e-9))
    check("黑白对比≈21", near(Palette.contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 21.0, 0.1))

    // ---- 挑色带 ----
    val c1 = 0xFF0B57D0.toInt()   // 深（lum ≈ 0.13）
    val c2 = 0xFFD3E3FD.toInt()   // 浅（lum ≈ 0.76）
    check("带内命中深色", Palette.pickInBand(intArrayOf(c1, c2), 0.05, 0.30, fallback = -1) == c1)
    check("带内命中浅色", Palette.pickInBand(intArrayOf(c1, c2), 0.60, 0.90, fallback = -1) == c2)
    check("全脱靶走兜底",
        Palette.pickInBand(intArrayOf(c1), 0.90, 1.00, fallback = 0xFF123456.toInt()) == 0xFF123456.toInt())
    check("skip 跳过已用色", Palette.pickInBand(intArrayOf(c1, c2), 0.05, 0.30, skip = setOf(c1), fallback = -1) == -1)

    // ---- 静态色板：MD3 对比度底线 ----
    // 主操作对：primary/onPrimary ≥ 4.5（WCAG AA 正文）。
    check("亮 primary/onPrimary ≥4.5",
        Palette.contrastRatio(Palette.LIGHT.primary, Palette.LIGHT.onPrimary) >= 4.5)
    check("暗 primary/onPrimary ≥4.5",
        Palette.contrastRatio(Palette.DARK.primary, Palette.DARK.onPrimary) >= 4.5)
    // 容器对：onPrimaryContainer/primaryContainer ≥ 4.5。
    check("亮 onPrimaryContainer/primaryContainer ≥4.5",
        Palette.contrastRatio(Palette.LIGHT.onPrimaryContainer, Palette.LIGHT.primaryContainer) >= 4.5)
    check("暗 onPrimaryContainer/primaryContainer ≥4.5",
        Palette.contrastRatio(Palette.DARK.onPrimaryContainer, Palette.DARK.primaryContainer) >= 4.5)
    // 正文对：onSurface/surface ≥ 7（MD3 正文要求高对比）。
    check("亮 onSurface/surface ≥7",
        Palette.contrastRatio(Palette.LIGHT.onSurface, Palette.LIGHT.surface) >= 7.0)
    check("暗 onSurface/surface ≥7",
        Palette.contrastRatio(Palette.DARK.onSurface, Palette.DARK.surface) >= 7.0)
    // 副文本对：onSurfaceVariant/surface ≥ 4.5。
    check("亮 onSurfaceVariant/surface ≥4.5",
        Palette.contrastRatio(Palette.LIGHT.onSurfaceVariant, Palette.LIGHT.surface) >= 4.5)
    check("暗 onSurfaceVariant/surface ≥4.5",
        Palette.contrastRatio(Palette.DARK.onSurfaceVariant, Palette.DARK.surface) >= 4.5)

    // ---- 动态组色：带筛选 / 去重 / 兜底 ----
    // 合成 resolver：accent1_600=深蓝、accent1_100=浅蓝、neutral1_1000=白、其余一律 null。
    val fake = mapOf(
        "accent1:600" to 0xFF0B57D0.toInt(),
        "accent1:100" to 0xFFD3E3FD.toInt(),
        "accent1:900" to 0xFF041E49.toInt(),
        "neutral1:1000" to 0xFFF9F9FF.toInt(),
        "neutral1:900" to 0xFFF0F3FA.toInt(),
        "neutral1:800" to 0xFFE9EEF6.toInt(),
        "neutral2:100" to 0xFF191C20.toInt(),
        "neutral2:300" to 0xFF44474E.toInt(),
        "neutral2:500" to 0xFF74777F.toInt(),
        "neutral2:800" to 0xFFE0E2EC.toInt(),
    )
    fun resolver(kind: String, suffix: Int): Int? = fake["$kind:$suffix"]

    val dyn = Palette.dynamicScheme(dark = false, resolve = ::resolver)
    check("动态 primary=带内深色", dyn.primary == 0xFF0B57D0.toInt())
    check("动态 primaryContainer=带内浅色", dyn.primaryContainer == 0xFFD3E3FD.toInt())
    check("动态 onPrimaryContainer=带内最深", dyn.onPrimaryContainer == 0xFF041E49.toInt())
    check("动态 surface=带内白", dyn.surface == 0xFFF9F9FF.toInt())
    check("动态 surfaceContainer 不与 surface 撞色", dyn.surfaceContainer != dyn.surface)
    check("动态 error 不被壁纸染色", dyn.error == Palette.LIGHT.error)
    // 全 null resolver → 整套回退静态。
    val fallback = Palette.dynamicScheme(dark = false) { _, _ -> null }
    check("全脱靶回退静态 primary", fallback.primary == Palette.LIGHT.primary)
    check("全脱靶回退静态 surface", fallback.surface == Palette.LIGHT.surface)

    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails > 0) exitProcess(1)
}
