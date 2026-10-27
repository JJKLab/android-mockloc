package com.mockloc

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * What each privilege tier can actually do, and what each known target app needs.
 *
 * The rows below are measurements from this device (OnePlus Ace 3V / Android 16 / KernelSU +
 * Stellar), not folklore. They matter because the tiers are *not* a ladder: 0, 1 and 2 all hand the
 * target app the same thing - one extra mock provider in the system - so an app that ignores the
 * mock provider at Tier 0 ignores it at Tier 2 as well. What genuinely differs is whether we touch
 * the app's radio inputs, and whether we run inside its process.
 */
object Tiers {

    /** Which lever makes this app move. */
    enum class Needs(val label: String, val detail: String) {
        /** Reads the system provider: any tier works, even the no-root one. */
        SYSTEM("①系统provider即可", "普通 App / Shizuku / root 任一档都能改到它"),
        /** Ignores or discards the system provider; needs its radio inputs emptied (Tier 2 断网 or Tier 3 致盲). */
        INPUT("②要抽空输入", "它自己算路：致盲 WiFi/基站或按 uid 断网才落回系统定位"),
        /** Reads its own SDK inside its process and checks the mock flag: only Tier 3 reaches it. */
        PROCESS("③要进进程", "只有 LSPosed 作用域内的进程内改写有效，且要在它自家 SDK 上挂钩"),
        UNKNOWN("未测", "还没有本机结论，先按①试，不行再升到②/③"),
    }

    /**
     * Measured verdicts. Anything not listed is [Needs.UNKNOWN] on purpose - guessing here is what
     * cost a whole debug round with 高德.
     */
    private val KNOWN = mapOf(
        "com.autonavi.minimap" to Needs.INPUT,
        "com.tencent.mm" to Needs.PROCESS,
        // 2026-10-27: Tier 0 (developer-options app-op, in-app provider) left 美团 showing the real
        // city and real distances, but it is not in the module's scope either, so no tier above 0
        // has been measured for it yet.
        "com.sankuai.meituan" to Needs.UNKNOWN,
        "com.ss.android.ugc.aweme.lite" to Needs.UNKNOWN,
        "com.ss.android.ugc.aweme" to Needs.UNKNOWN,
    )

    fun needsOf(packageName: String): Needs =
        KNOWN[packageName] ?: KNOWN.entries.firstOrNull { packageName.startsWith(it.key) }?.value
        ?: Needs.UNKNOWN

    /**
     * Tiers that are *not* expressible through the location API and therefore never belong in this
     * app's UI - stated here so the policy page does not quietly imply otherwise.
     */
    val outsideScope = listOf(
        "IP 属地 / 服务端城市",
        "账号注册地、收货地址",
        "基站与 WiFi 数据库本身（在厂商服务器上）",
    )

    /** The picker in 开发者选项 is the only thing that can grant Tier 0's app-op on this OEM build. */
    fun developmentSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Some ROMs hide the development-settings entry until it is switched on. */
    fun fallbackSettingsIntent(): Intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun developerOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * Whether *this* app currently holds the mock-location app-op.
     *
     * `checkOpNoThrow` is the only public way to read it without triggering a note in the ops log;
     * it is deprecated but still present on API 36. Reinstalling the APK clears the grant, which is
     * why the status page shows this instead of trusting a remembered "已授权".
     */
    @Suppress("DEPRECATION")
    fun mockAppOpAllowed(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
            ?: return false
        val uid = runCatching { context.applicationInfo.uid }.getOrDefault(-1)
        return ops.checkOpNoThrow(MOCK_OP, uid, context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** API 29 removed the public constant for this op string; the value is stable. */
    private const val MOCK_OP = "android:mock_location"

    fun summary(): String = buildString {
        appendLine("① 系统 provider：Tier 0/1/2 同一数据面，目标 App 一律看得到 isMock=true")
        appendLine("② 抽空输入：Tier 2（按 uid 断网/关射频）或 Tier 3（致盲扫描结果）；本机实测高德靠这条")
        appendLine("③ 进程内改写：只有 Tier 3，痕迹在目标进程里，但 isMock=false")
    }
}
