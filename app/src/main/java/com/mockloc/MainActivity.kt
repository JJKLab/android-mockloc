package com.mockloc

import android.app.Activity
import android.location.LocationManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.TextView

/**
 * No UI beyond a text dump on purpose: this screen exists to show what a hooked app actually
 * receives, which is the only way to tell injection success apart from injection silence.
 */
class MainActivity : Activity() {

    /** Rewritten to true by the module; false means LSPosed never loaded it into this process. */
    fun selfCheck(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            text = dump()
            setTextIsSelectable(true)
            setPadding(40, 40, 40, 40)
        }
        setContentView(view)
    }

    private fun dump(): String = buildString {
        appendLine("注入 : ${if (selfCheck()) "已生效" else "未生效（模块未启用 / 未把本 App 加进作用域）"}")
        appendLine("----")
        Config.refresh()
        appendLine("配置 : ${Config.DEFAULT_PATH}")
        appendLine("开关 : ${Config.enabled}   全局 provider: ${Config.global} ${Config.providers}")
        appendLine("错误 : ${Config.lastError ?: "无"}")
        if (Config.enabled) {
            appendLine("中心 : ${fmt(Config.centerLat)},${fmt(Config.centerLng)}")
            appendLine("抖动 : ${Config.radius.toInt()} m  精度 ${Config.accuracy} m")
            appendLine("当前 : ${fmt(Config.currentLat)},${fmt(Config.currentLng)}")
            val scope = if (Config.targets.isEmpty()) "作用域内全部 App" else Config.targets.joinToString()
            appendLine("范围 : $scope")
        }
        appendLine("----")
        appendLine("secure mock_location : ${readSecure()}")
        appendLine("自身 appop           : ${readAppOp()}")
        appendLine("master switch        : ${runCatching { location()?.isProviderEnabled("gps") }.getOrNull()}")
        appendLine("----")
        listOf("gps", "fused", "network", "passive").forEach { name ->
            appendLine("LKL $name : ${readback(name)}")
        }
        appendLine("----")
        appendLine("改完配置等 1 秒再回来看（缓存 1s）")
    }

    private fun location(): LocationManager? =
        getSystemService(LOCATION_SERVICE) as? LocationManager

    // Both accessors are shown on purpose: older apps still call the deprecated one, and the
    // whole point of this screen is to see which flag a target app would be reading.
    @Suppress("DEPRECATION")
    private fun readback(provider: String): String =
        runCatching {
            val loc = location()?.getLastKnownLocation(provider)
            if (loc == null) "null"
            else "${fmt(loc.latitude)},${fmt(loc.longitude)} acc=${loc.accuracy} mock=${loc.isFromMockProvider}"
        }.getOrElse { "需授权定位权限（${it.javaClass.simpleName}）" }

    private fun readSecure(): String =
        runCatching {
            Settings.Secure.getString(contentResolver, "mock_location") ?: "null"
        }.getOrElse { "读不到" }

    private fun readAppOp(): String =
        runCatching {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                packageName,
            )
            when (mode) {
                android.app.AppOpsManager.MODE_ALLOWED -> "allow"
                android.app.AppOpsManager.MODE_IGNORED -> "ignore"
                android.app.AppOpsManager.MODE_ERRORED -> "error"
                android.app.AppOpsManager.MODE_DEFAULT -> "default"
                else -> "mode=$mode"
            }
        }.getOrElse { "读不到" }

    private fun fmt(value: Double): String = String.format("%.6f", value)
}
