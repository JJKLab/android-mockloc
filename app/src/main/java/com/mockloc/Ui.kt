package com.mockloc

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Material Design 3 设计系统（手写实现，零第三方依赖）。
 *
 * - 色彩：[Palette] 静态基线（Google 蓝）+ Android 12+ Material You 动态取色
 *   （亮度带筛选，语义与数学在 Palette.kt，纯 JVM 可测）；
 * - 形状：MD3 形状刻度——卡片 16dp、底部浮层 28dp、按钮与 chip 全圆角（pill）；
 * - 字阶：titleLarge 22 / titleMedium 16 / bodyMedium 14 / labelLarge 14 / labelSmall 12；
 * - 组件：卡片、填充/tonal/ghost/危险按钮、assist chip、填充文本框、导航栏项、状态胶囊。
 *
 * 所有颜色经由 [Md.scheme] 读取：`Md.resolve(context)` 在 Activity onCreate 时解析一次
 * （暗色判定 + 动态取色），之后本进程内共享同一套 token。
 */
object Md {
    lateinit var scheme: Palette.Scheme
        private set

    var dark: Boolean = false
        private set

    fun resolve(context: Context) {
        dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        scheme = if (Build.VERSION.SDK_INT >= 31) {
            Palette.dynamicScheme(dark) { kind, suffix ->
                // system_accent1_600 这类 Material You 调色板资源：名字进 getIdentifier，
                // 避免 android.jar 公开面差异造成的编译风险；缺失/异常按静态兜底处理。
                val id = Resources.getSystem().getIdentifier("system_${kind}_$suffix", "color", "android")
                if (id == 0) null else runCatching { Resources.getSystem().getColor(id) }.getOrNull()
            }
        } else {
            if (dark) Palette.DARK else Palette.LIGHT
        }
    }
}

/**
 * MD3 组件工厂。所有页面从这里取样式，不再出现裸 OEM 控件。
 */
class Ui(private val context: Context) {

    // ---- 形状与尺寸 ------------------------------------------------------------------------

    fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    private fun rounded(fill: Int, stroke: Int, widthDp: Float, radiusDp: Float = 16f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            if (widthDp > 0f) setStroke(dp(widthDp.toInt().coerceAtLeast(1)), stroke)
            cornerRadius = dp(radiusDp.toInt().coerceAtLeast(1)).toFloat()
        }

    private fun pill(fill: Int, stroke: Int = fill, widthDp: Float = 0f): GradientDrawable =
        rounded(fill, stroke, widthDp, 100f)

    // ---- 字阶 ------------------------------------------------------------------------------

    private fun text(text: String, sizeSp: Float, color: Int, weight: Int? = null): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (weight != null) setTypeface(typeface, weight)
        }

    /** MD3 titleMedium：区块标题。 */
    fun title(text: String) = text(text, 16f, Md.scheme.onSurface, Typeface.BOLD).apply {
        setPadding(0, 0, 0, dp(6))
    }

    /** MD3 bodyMedium：正文。 */
    fun body(text: String) = text(text, 14f, Md.scheme.onSurface).apply {
        setLineSpacing(0f, 1.2f)
    }

    /** MD3 labelSmall：辅助说明。 */
    fun hint(text: String) = text(text, 12f, Md.scheme.onSurfaceVariant).apply {
        setPadding(dp(2), dp(6), dp(2), dp(8))
        setLineSpacing(0f, 1.15f)
    }

    /** 区块小节标：labelLarge 全大写风格。 */
    fun sectionLabel(text: String) = text(text, 12f, Md.scheme.onSurfaceVariant, Typeface.BOLD).apply {
        setPadding(0, dp(14), 0, dp(4))
    }

    // ---- 容器 ------------------------------------------------------------------------------

    /** MD3 filled card：surfaceContainer + 16dp 圆角，无边框。 */
    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Md.scheme.surfaceContainer, 0, 0f, 16f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
    }

    /** MD3 elevated 卡：surfaceHigh，用于需要强调的面（浮层、面板）。 */
    fun cardHigh(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Md.scheme.surfaceHigh, 0, 0f, 16f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        elevation = dp(4).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
    }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 等宽列。 */
    fun fill(weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    fun divider(): View = View(context).apply {
        setBackgroundColor(Md.scheme.outlineVariant)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        ).apply { setMargins(0, dp(10), 0, dp(10)) }
    }

    // ---- 按钮 ------------------------------------------------------------------------------

    enum class Btn { PRIMARY, TONAL, GHOST, DANGER }

    /**
     * MD3 按钮：全圆角（pill）、40dp 高、labelLarge。PRIMARY=填充、TONAL=次级容器、
     * GHOST=纯文字、DANGER=错误容器（破坏性操作）。
     */
    fun button(text: String, style: Btn = Btn.TONAL, onClick: (Button) -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextSize(14f)
        setTypeface(typeface, Typeface.BOLD)
        val (fill, stroke, fg) = when (style) {
            Btn.PRIMARY -> Triple(Md.scheme.primary, Md.scheme.primary, Md.scheme.onPrimary)
            Btn.TONAL -> Triple(Md.scheme.secondaryContainer, Md.scheme.secondaryContainer, Md.scheme.onSecondaryContainer)
            Btn.GHOST -> Triple(0x00000000, 0x00000000, Md.scheme.primary)
            Btn.DANGER -> Triple(Md.scheme.errorContainer, Md.scheme.errorContainer, Md.scheme.onErrorContainer)
        }
        setTextColor(fg)
        background = if (style == Btn.GHOST) {
            rounded(fill, stroke, 0f, 20f)
        } else {
            pill(fill, stroke)
        }
        setPadding(dp(18), dp(9), dp(18), dp(9))
        minimumHeight = dp(40)
        stateListAnimator = null
        setOnClickListener { onClick(this) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 分段控件的选中态（坐标系切换这类二选一）。 */
    fun styleSegment(button: Button, selected: Boolean) {
        val fill = if (selected) Md.scheme.primary else Md.scheme.surfaceHigh
        val fg = if (selected) Md.scheme.onPrimary else Md.scheme.onSurfaceVariant
        button.setTextColor(fg)
        button.background = pill(fill, 0)
        button.stateListAnimator = null
        button.setPadding(dp(14), dp(8), dp(14), dp(8))
        button.setTextSize(13f)
    }

    // ---- Chip 与状态胶囊 -------------------------------------------------------------------

    /**
     * MD3 assist chip 变体：语义四态。OK=tertiary 容器、BAD=error 容器、WARN=静态暖色、
     * OFF=surfaceVariant。单行省略——竖排文字气泡的老毛病从这里堵死。
     */
    fun chip(label: String, state: State) = TextView(context).apply {
        val (mark, fg, bg) = when (state) {
            State.OK -> Triple("✓", Md.scheme.onTertiaryContainer, Md.scheme.tertiaryContainer)
            State.WARN -> Triple("!", 0xFF6B4E00.toInt(), 0xFFFFEFC4.toInt())
            State.OFF -> Triple("–", Md.scheme.onSurfaceVariant, Md.scheme.surfaceHigh)
            State.BAD -> Triple("✗", Md.scheme.onErrorContainer, Md.scheme.errorContainer)
        }
        text = "$label $mark"
        textSize = 12f
        setTextColor(fg)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = pill(bg)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, dp(8), dp(8)) }
    }

    fun chipRow(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** 悬浮搜索条外观：surfaceHigh 胶囊 + 投影。 */
    fun pillSurface(): GradientDrawable = pill(Md.scheme.surfaceHigh)

    /** 状态胶囊的就地更新（视图只建一次，刷新只改字与色）。 */
    fun styleStatusPill(pillView: TextView, text: String, running: Boolean) {
        pillView.text = text
        pillView.setTextColor(if (running) Md.scheme.onPrimaryContainer else Md.scheme.onSurfaceVariant)
        pillView.background = pill(
            if (running) Md.scheme.primaryContainer else Md.scheme.surfaceHigh,
        )
    }


    enum class State(val mark: String) {
        OK("✓"), WARN("!"), OFF("–"), BAD("✗");
    }

    /** 顶栏状态胶囊：运行中=primary 容器色，空闲=surfaceHigh。 */
    fun statusPill(text: String, running: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (running) Md.scheme.onPrimaryContainer else Md.scheme.onSurfaceVariant)
        background = pill(
            if (running) Md.scheme.primaryContainer else Md.scheme.surfaceHigh,
        )
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginEnd = dp(8) }
    }

    // ---- 输入框 ----------------------------------------------------------------------------

    /** MD3 filled 文本框：surfaceHigh 圆角底 + 内边距，无 OEM 下划线。 */
    fun editText(hint: String, singleLine: Boolean = true) = EditText(context).apply {
        this.hint = hint
        isSingleLine = singleLine
        textSize = 14f
        setTextColor(Md.scheme.onSurface)
        setHintTextColor(Md.scheme.onSurfaceVariant)
        background = rounded(Md.scheme.surfaceHigh, 0, 0f, 12f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // ---- 图标 ------------------------------------------------------------------------------

    fun icon(resId: Int, tint: Int, sizeDp: Int = 24): ImageView = ImageView(context).apply {
        setImageResource(resId)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // ---- 导航栏（MD3 navigation bar）--------------------------------------------------------

    /**
     * 一个导航项：32dp 图标胶囊（选中=secondaryContainer）+ 12sp 标签。
     * 返回 (容器, 图标, 标签) 供选中态重绘。
     */
    fun navItem(iconRes: Int, label: String, selected: Boolean, onClick: () -> Unit): NavItem {
        val icon = icon(iconRes, if (selected) Md.scheme.onSecondaryContainer else Md.scheme.onSurfaceVariant)
        icon.setPadding(dp(6), dp(6), dp(6), dp(6))
        icon.background = pill(
            if (selected) Md.scheme.secondaryContainer else 0x00000000,
        )
        val labelView = text(label, 12f, if (selected) Md.scheme.onSurface else Md.scheme.onSurfaceVariant, Typeface.BOLD)
        labelView.gravity = Gravity.CENTER
        labelView.setPadding(0, dp(2), 0, 0)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(icon)
            addView(labelView)
        }
        container.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        return NavItem(container, icon, labelView)
    }

    inner class NavItem(val container: LinearLayout, val icon: ImageView, val label: TextView) {
        fun restyle(selected: Boolean) {
            icon.setColorFilter(if (selected) Md.scheme.onSecondaryContainer else Md.scheme.onSurfaceVariant)
            icon.background = pill(
                if (selected) Md.scheme.secondaryContainer else 0x00000000,
            )
            label.setTextColor(if (selected) Md.scheme.onSurface else Md.scheme.onSurfaceVariant)
            label.setTypeface(label.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** 底部导航栏容器：surfaceContainer，顶缘 1dp outlineVariant 分隔。 */
    fun navBar(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(Md.scheme.surfaceContainer)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        elevation = dp(6).toFloat()
        foreground = rounded(0x00000000, Md.scheme.outlineVariant, 0f, 0f).apply { setStroke(0, 0) }
        // 顶缘分隔线：一条 1dp 视图放在 navbar 上方（由调用者 addView），这里只提供颜色。
    }

    /** 顶缘分隔线视图。 */
    fun navDivider(): View = View(context).apply {
        setBackgroundColor(Md.scheme.outlineVariant)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        )
    }

    /** MD3 顶栏：surface 底，titleLarge 标题 + 右侧状态胶囊。返回 (栏, 状态胶囊)。 */
    fun topBar(title: String, statusPill: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Md.scheme.surface)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(
            text(title, 22f, Md.scheme.onSurface, Typeface.BOLD),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(statusPill)
    }

    // ---- 兼容旧调用点的着色 ---------------------------------------------------------------

    fun pageBg(): Int = Md.scheme.surface
    fun cardBg(): Int = Md.scheme.surfaceContainer
    fun titleColor(): Int = Md.scheme.onSurface
    fun bodyColor(): Int = Md.scheme.onSurface
    fun hintColor(): Int = Md.scheme.onSurfaceVariant

    val night: Boolean
        get() = Md.dark
}
