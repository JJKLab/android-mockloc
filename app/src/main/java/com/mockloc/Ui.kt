package com.mockloc

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
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

    fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
    ).toInt()

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xFFFFFFFF.toInt(), 0x1F000000, 1f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(12), dp(8), dp(12), 0) }
    }

    fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFF1B1B1B.toInt())
        setPadding(0, 0, 0, dp(4))
    }

    fun body(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF444444.toInt())
        setLineSpacing(0f, 1.15f)
    }

    fun hint(text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF6B6B6B.toInt())
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
        background = rounded(state.bg, state.border, 1f)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, dp(6), dp(6)) }
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
