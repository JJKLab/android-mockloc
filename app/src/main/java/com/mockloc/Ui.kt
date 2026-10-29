package com.mockloc

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small shared styling helpers, so the pages read as cards instead of a stack of bare widgets.
 *
 * Plain Views on purpose (see the README's "为什么 UI 是 View 而不是 Compose"): this file has to
 * compile and type-check locally against android.jar, because the project is never built on a dev
 * machine and anything unverifiable here would only fail on the phone.
 */
class Ui(private val context: Context) {

    /** Day/night tokens (REFACTOR-PLAN 阶段四): one source, so no page hardcodes a light colour. */
    val night: Boolean
        get() = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun pageBg() = if (night) 0xFF121316.toInt() else 0xFFF4F5F7.toInt()

    fun cardBg() = if (night) 0xFF1E2024.toInt() else 0xFFFFFFFF.toInt()

    fun titleColor() = if (night) 0xFFE8EAED.toInt() else 0xFF1B1B1B.toInt()

    fun bodyColor() = if (night) 0xFFC7CACD.toInt() else 0xFF444444.toInt()

    fun hintColor() = if (night) 0xFF9AA0A6.toInt() else 0xFF6B6B6B.toInt()

    fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardBg(), 0x1F000000, 1f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
    }

    fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(titleColor())
        setPadding(0, 0, 0, dp(4))
    }

    fun body(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(bodyColor())
        setLineSpacing(0f, 1.15f)
    }

    fun hint(text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(hintColor())
        setPadding(dp(14), dp(6), dp(14), dp(10))
    }

    /**
     * Status pill. Colour carries the verdict so the eye lands on it before the words do - the
     * first version of this screen was one long line of text and users read "未生效" as "模块没装".
     */
    fun chip(label: String, state: State) = TextView(context).apply {
        text = "$label ${state.mark}"
        textSize = 12f
        setTextColor(state.fg)
        gravity = Gravity.CENTER
        // 竖排文字气泡的元凶：chip 换行后每行一个字。单行 + 省略号，长标签宁可截断。
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        background = rounded(state.bg, state.border, 1f)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, dp(6), dp(6)) }
    }

    enum class Btn { PRIMARY, TONAL, GHOST }

    /**
     * The one place a button gets styled. The OEM default Button rendered as a flat grey slab with
     * no radius, which is half of why the old UI read as unfinished; PRIMARY = solid blue, TONAL =
     * pale blue fill, GHOST = text only.
     */
    fun button(text: String, style: Btn = Btn.TONAL, onClick: (Button) -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setTextSize(14f)
        val (fill, stroke, fg) = when (style) {
            Btn.PRIMARY -> Triple(0xFF1F6FEB.toInt(), 0xFF1F6FEB.toInt(), 0xFFFFFFFF.toInt())
            Btn.TONAL -> Triple(0xFFE8F0FE.toInt(), 0xFFC5D8F7.toInt(), 0xFF175BC0.toInt())
            Btn.GHOST -> Triple(0x00000000.toInt(), 0x00000000.toInt(), 0xFF1F6FEB.toInt())
        }
        setTextColor(fg)
        background = rounded(fill, stroke, if (style == Btn.GHOST) 0f else 1f)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minimumHeight = dp(44)
        stateListAnimator = null
        setOnClickListener { onClick(this) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** Segmented-control look for toggle buttons (坐标系切换这类二选一), shared by all callers. */
    fun styleSegment(button: android.widget.Button, selected: Boolean) {
        val fill = if (selected) 0xFF1F6FEB.toInt() else 0xFFE8F0FE.toInt()
        val stroke = if (selected) 0xFF1F6FEB.toInt() else 0xFFC5D8F7.toInt()
        val fg = if (selected) 0xFFFFFFFF.toInt() else 0xFF175BC0.toInt()
        button.setTextColor(fg)
        button.background = rounded(fill, stroke, 1f)
        button.stateListAnimator = null
        button.setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    fun chipRow(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    enum class State(val mark: String, val fg: Int, val bg: Int, val border: Int) {
        OK("✓", 0xFF0F6B32.toInt(), 0xFFE8F6EC.toInt(), 0xFFBFE3CB.toInt()),
        WARN("!", 0xFF8A5A00.toInt(), 0xFFFFF4E0.toInt(), 0xFFEFD9A6.toInt()),
        OFF("–", 0xFF6B6B6B.toInt(), 0xFFF2F2F2.toInt(), 0xFFDCDCDC.toInt()),
        BAD("✗", 0xFF9B1C1C.toInt(), 0xFFFDECEC.toInt(), 0xFFF3C6C6.toInt()),
    }

    private fun rounded(fill: Int, stroke: Int, widthDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(widthDp.toInt().coerceAtLeast(1)), stroke)
        cornerRadius = dp(10).toFloat()
    }

    fun row(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** Equal-width column inside a row. */
    fun fill(weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
}
