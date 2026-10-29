package com.mockloc

import kotlin.math.pow

/**
 * Material Design 3 色板层，纯 Kotlin、零 android 依赖，可在 JVM 上验收（tools/kotlin/PaletteCheck.kt）。
 *
 * 两条来源：
 * - 静态：Google 系应用（Gmail/Maps）的蓝色 MD3 基线值，亮/暗两套。
 * - 动态：Android 12+ 的 Material You 系统调色板（android.R.color.system_accent1_* /
 *   neutral1_* / neutral2_*）。系统资源的 tone 语义随版本有漂移，所以这里不做"后缀=角色"的
 *   硬映射，而是按【相对亮度带】挑色：每个角色给出候选后缀列表与亮度区间，命中即用，
 *   全部脱靶则回退静态色——最坏结果也只是回到 Google 蓝，不会产出难看的搭配。
 */
object Palette {

    // ---- 相对亮度（sRGB 传递函数，WCAG 定义）------------------------------------------------

    fun relativeLuminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 对比度，用于自检与运行时挑字色。 */
    fun contrastRatio(a: Int, b: Int): Double {
        val l1 = relativeLuminance(a)
        val l2 = relativeLuminance(b)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * 从候选（按优先级排序）里挑第一个亮度落在 [minLum, maxLum] 的颜色；全脱靶返回 fallback。
     * skip 里的颜色（已分配给其他角色的色值）会被跳过，避免两个角色拿到同一个色。
     */
    fun pickInBand(
        candidates: IntArray,
        minLum: Double,
        maxLum: Double,
        skip: Set<Int> = emptySet(),
        fallback: Int,
    ): Int = candidates.firstOrNull { argb ->
        argb !in skip && relativeLuminance(argb) in minLum..maxLum
    } ?: fallback

    // ---- 静态基线（Google 蓝种子）------------------------------------------------------------

    data class Scheme(
        val primary: Int, val onPrimary: Int,
        val primaryContainer: Int, val onPrimaryContainer: Int,
        val secondaryContainer: Int, val onSecondaryContainer: Int,
        val surface: Int, val surfaceContainer: Int, val surfaceHigh: Int,
        val onSurface: Int, val onSurfaceVariant: Int,
        val outline: Int, val outlineVariant: Int,
        val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
        val tertiaryContainer: Int, val onTertiaryContainer: Int,
    )

    /** Google 蓝种子（Gmail/Maps 同源值）。 */
    val LIGHT = Scheme(
        primary = 0xFF0B57D0.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFD3E3FD.toInt(), onPrimaryContainer = 0xFF041E49.toInt(),
        secondaryContainer = 0xFFD8E2F9.toInt(), onSecondaryContainer = 0xFF101C2C.toInt(),
        surface = 0xFFF9F9FF.toInt(), surfaceContainer = 0xFFF0F3FA.toInt(),
        surfaceHigh = 0xFFE9EEF6.toInt(),
        onSurface = 0xFF191C20.toInt(), onSurfaceVariant = 0xFF44474E.toInt(),
        outline = 0xFF74777F.toInt(), outlineVariant = 0xFFE0E2EC.toInt(),
        error = 0xFFBA1A1A.toInt(), onError = 0xFFFFFFFF.toInt(),
        errorContainer = 0xFFFFDAD6.toInt(), onErrorContainer = 0xFF410002.toInt(),
        tertiaryContainer = 0xFFFFE08D.toInt(), onTertiaryContainer = 0xFF261A00.toInt(),
    )

    val DARK = Scheme(
        primary = 0xFFA8C7FA.toInt(), onPrimary = 0xFF062E6F.toInt(),
        primaryContainer = 0xFF0842A0.toInt(), onPrimaryContainer = 0xFFD3E3FD.toInt(),
        secondaryContainer = 0xFF3A4759.toInt(), onSecondaryContainer = 0xFFD8E2F9.toInt(),
        surface = 0xFF111318.toInt(), surfaceContainer = 0xFF1D2024.toInt(),
        surfaceHigh = 0xFF282A2F.toInt(),
        onSurface = 0xFFE2E2E9.toInt(), onSurfaceVariant = 0xFFC4C6D0.toInt(),
        outline = 0xFF8E9099.toInt(), outlineVariant = 0xFF44474E.toInt(),
        error = 0xFFFFB4AB.toInt(), onError = 0xFF690005.toInt(),
        errorContainer = 0xFF93000A.toInt(), onErrorContainer = 0xFFFFDAD6.toInt(),
        tertiaryContainer = 0xFF5B4200.toInt(), onTertiaryContainer = 0xFFFFE08D.toInt(),
    )

    // ---- 动态取色的候选与亮度带（Android 12+ Material You）-----------------------------------

    /**
     * 每个角色 = 候选后缀列表（按优先级）+ 亮度带 + 静态兜底。
     * 后缀经 context.getColor(android.R.color.system_accent1_<n>) 解析后按亮度带筛选。
     */
    data class Role(
        val kind: String,          // accent1 / accent2 / accent3 / neutral1 / neutral2
        val suffixes: IntArray,
        val minLum: Double,
        val maxLum: Double,
    )

    // 亮色：primary 深而饱和，容器浅，onX 与底色高对比。
    val LIGHT_ROLES = listOf(
        Role("accent1", intArrayOf(600, 700, 500, 400), 0.08, 0.32),   // primary
        Role("accent1", intArrayOf(100, 200, 50), 0.72, 0.95),          // primaryContainer
        Role("accent1", intArrayOf(900, 800, 700), 0.0, 0.28),          // onPrimaryContainer
        Role("accent2", intArrayOf(200, 100, 300), 0.72, 0.95),         // secondaryContainer
        Role("accent2", intArrayOf(900, 800, 700), 0.0, 0.28),          // onSecondaryContainer
        Role("neutral1", intArrayOf(1000, 900), 0.92, 1.01),            // surface
        Role("neutral1", intArrayOf(900, 800, 1000), 0.84, 0.94),       // surfaceContainer
        Role("neutral1", intArrayOf(800, 900), 0.76, 0.88),             // surfaceHigh
        Role("neutral2", intArrayOf(100, 50, 200), 0.0, 0.14),          // onSurface
        Role("neutral2", intArrayOf(300, 400, 500), 0.26, 0.48),        // onSurfaceVariant
        Role("neutral2", intArrayOf(500, 400, 600), 0.36, 0.56),        // outline
        Role("neutral2", intArrayOf(800, 700, 900), 0.70, 0.90),        // outlineVariant
        Role("accent3", intArrayOf(200, 100, 300), 0.70, 0.96),         // tertiaryContainer
        Role("accent3", intArrayOf(900, 800, 700), 0.0, 0.26),          // onTertiaryContainer
    )

    // 暗色：primary 浅而饱和，容器深，onX 反转。
    val DARK_ROLES = listOf(
        Role("accent1", intArrayOf(200, 300, 100), 0.50, 0.86),         // primary
        Role("accent1", intArrayOf(50, 100, 200), 0.0, 0.16),           // onPrimary（深）
        Role("accent1", intArrayOf(800, 900, 700), 0.06, 0.28),         // primaryContainer
        Role("accent1", intArrayOf(100, 200, 300), 0.62, 0.95),         // onPrimaryContainer
        Role("accent2", intArrayOf(800, 900, 700), 0.08, 0.30),         // secondaryContainer
        Role("accent2", intArrayOf(100, 200, 300), 0.62, 0.95),         // onSecondaryContainer
        Role("neutral1", intArrayOf(50, 100, 0), 0.005, 0.05),          // surface
        Role("neutral1", intArrayOf(100, 200, 50), 0.05, 0.11),         // surfaceContainer
        Role("neutral1", intArrayOf(200, 300, 100), 0.10, 0.16),        // surfaceHigh
        Role("neutral2", intArrayOf(900, 1000, 800), 0.78, 0.95),       // onSurface
        Role("neutral2", intArrayOf(800, 900, 700), 0.60, 0.82),        // onSurfaceVariant
        Role("neutral2", intArrayOf(500, 400, 600), 0.36, 0.56),        // outline
        Role("neutral2", intArrayOf(300, 400, 200), 0.20, 0.40),        // outlineVariant
        Role("accent3", intArrayOf(800, 900, 700), 0.05, 0.26),         // tertiaryContainer
        Role("accent3", intArrayOf(100, 200, 300), 0.62, 0.95),         // onTertiaryContainer
    )

    /**
     * 动态组色：resolve(kind, suffix) 返回系统调色板 ARGB（缺失返回 null），按亮度带 + 去重依次
     * 填充各角色；error 系保持静态红（语义色不做壁纸染色）。纯函数，JVM 可测。
     */
    fun dynamicScheme(dark: Boolean, resolve: (kind: String, suffix: Int) -> Int?): Scheme {
        val base = if (dark) DARK else LIGHT
        val roles = if (dark) DARK_ROLES else LIGHT_ROLES
        // 每个角色的兜底 = 它自己的静态值，而不是统一拿 primary。
        val fallbacks = listOf(
            base.primary, base.primaryContainer, base.onPrimaryContainer,
            base.secondaryContainer, base.onSecondaryContainer,
            base.surface, base.surfaceContainer, base.surfaceHigh,
            base.onSurface, base.onSurfaceVariant, base.outline, base.outlineVariant,
            base.tertiaryContainer, base.onTertiaryContainer,
        )
        val used = mutableSetOf<Int>()
        fun pick(index: Int, role: Role): Int {
            val candidates = role.suffixes.map { suffix -> resolve(role.kind, suffix) }.filterNotNull()
            val picked = pickInBand(candidates.toIntArray(), role.minLum, role.maxLum, used, fallbacks[index])
            used.add(picked)
            return picked
        }
        return Scheme(
            primary = pick(0, roles[0]), onPrimary = base.onPrimary,
            primaryContainer = pick(1, roles[1]), onPrimaryContainer = pick(2, roles[2]),
            secondaryContainer = pick(3, roles[3]), onSecondaryContainer = pick(4, roles[4]),
            surface = pick(5, roles[5]), surfaceContainer = pick(6, roles[6]),
            surfaceHigh = pick(7, roles[7]),
            onSurface = pick(8, roles[8]), onSurfaceVariant = pick(9, roles[9]),
            outline = pick(10, roles[10]), outlineVariant = pick(11, roles[11]),
            error = base.error, onError = base.onError,
            errorContainer = base.errorContainer, onErrorContainer = base.onErrorContainer,
            tertiaryContainer = pick(12, roles[12]), onTertiaryContainer = pick(13, roles[13]),
        )
    }
}
