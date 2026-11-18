package com.mockloc.pages

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mockloc.Config
import com.mockloc.CoordTransform
import com.mockloc.Diagnostics
import com.mockloc.MainActivity
import com.mockloc.Md
import com.mockloc.MockService
import com.mockloc.RiskScore
import com.mockloc.RootInjector
import com.mockloc.Sky
import com.mockloc.Ui
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 状态页（原 MainActivity 的 statusPage + 16 维风险卡 + 目标 App 行 + 同步/掐网动作）。
 *
 * 阶段四拆分：MainActivity 把这一整块搬了出来。页面通过 [MainActivity] 的 internal 成员拿状态、
 * 通过宿主方法做动作（注入/同步/停止），不复制业务逻辑。
 */
internal class StatusPage(private val a: MainActivity) {

    private val ui get() = a.ui
    private val bridge get() = a.bridge

    /** 常用目标 App 与管线的对应关系。needsLsp = 该 App 只能通过 LSP 进程内改写生效。 */
    internal data class KnownApp(val label: String, val pkg: String, val needsLsp: Boolean = false)

    internal val KNOWN_APPS = listOf(
        KnownApp("高德地图", "com.autonavi.minimap"),
        KnownApp("微信", "com.tencent.mm", needsLsp = true),
        KnownApp("美团", "com.sankuai.meituan"),
        KnownApp("大众点评", "com.dianping.v1"),
        // 银行/生活类是 mock 读数重灾区（读到 isMock=true 就丢弃修复）：
        // 掐网能断它自己服务器的交叉验证，LSP 档（若加固后不再闪退）才洗得掉 mock 位。
        KnownApp("阳光惠生活", "com.cebbank.yaoyao"),
        KnownApp("光大银行", "com.cebbank.mobile.cemb"),
        KnownApp("饿了么", "me.ele"),
    )

    // ---------------------------------------------------------------- 构建

    fun build(): View {
        val page = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        // ---- 当前状态卡 ----
        val statusCard = ui.card()
        a.statusBigText = TextView(a).apply {
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, ui.dp(4))
        }
        statusCard.addView(a.statusBigText)
        a.statusCoordWgs = TextView(a).apply { textSize = 14f; setTextColor(ui.bodyColor()) }
        statusCard.addView(a.statusCoordWgs)
        a.statusCoordGcj = TextView(a).apply { textSize = 14f; setTextColor(ui.hintColor()) }
        statusCard.addView(a.statusCoordGcj)
        a.statusTarget = TextView(a).apply {
            textSize = 12f; setTextColor(ui.hintColor()); setPadding(0, ui.dp(4), 0, 0)
        }
        statusCard.addView(a.statusTarget)
        a.stopBtn = ui.button("停止注入", Ui.Btn.DANGER) {
            a.startService(Intent(a, MockService::class.java)
                .putExtra(MockService.EXTRA_CMD, "stop"))
            bridge.clear()
            thread {
                val out = RootInjector.teardown(DEFAULT_PROVIDERS.split(','))
                a.runOnUiThread {
                    refresh()
                    if (out.contains("Error") || out.contains("denied")) a.toast("root 停止异常：${out.take(60)}")
                }
            }
        }
        a.stopBtn.visibility = View.GONE
        statusCard.addView(a.stopBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = ui.dp(10) })
        page.addView(statusCard)

        // ---- 暴露风险卡（16 维）----
        // README 阶段四 ⑭ 声称已落地但代码里没有，这里补齐。评的是暴露面不是效果。
        val riskCard = ui.card()
        a.riskSummary = TextView(a).apply {
            textSize = 14f
            setTextColor(ui.bodyColor())
            setPadding(0, 0, 0, ui.dp(4))
        }
        riskCard.addView(a.riskSummary)
        a.riskToggleBtn = ui.button("16 维明细 ▼") { toggleRisk() }
        riskCard.addView(a.riskToggleBtn)
        a.riskRows = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        riskCard.addView(a.riskRows)
        riskCard.addView(ui.hint("评的是暴露面（这条面收口了没有），不是效果（目标 App 认不认）"))
        page.addView(riskCard)

        // ---- 目标 App 快捷控制 ----
        val appCard = ui.card()
        appCard.addView(ui.title("目标 App"))
        a.appRows = ui.column()
        appCard.addView(a.appRows)
        appCard.addView(ui.button("更多 App → 策略页", Ui.Btn.GHOST) { a.openTab(TABS.indexOf("策略")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        page.addView(appCard)

        // ---- 操作行 ----
        val actions = ui.row().apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(4)) }
        actions.addView(ui.button("同步到模块", Ui.Btn.PRIMARY) { onSyncToModule() }, ui.fill())
        actions.addView(ui.button("刷新") { refresh() }, ui.fill())
        a.diagToggleBtn = ui.button("诊断详情 ▼") { toggleDiag() }
        actions.addView(a.diagToggleBtn, ui.fill())
        page.addView(actions)

        // 悬浮摇杆：拖圆盘微调当前注入点（阶段五）。
        val joyRow = ui.row().apply { setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(4)) }
        joyRow.addView(ui.button("\uD83C\uDFF9 摇杆") { toggleJoystick() }, ui.fill())
        page.addView(joyRow)

        // ---- 诊断详情（折叠）----
        a.diagSection = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        a.statusDetailText = TextView(a).apply {
            textSize = 12f
            setTextColor(ui.bodyColor())
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            setLineSpacing(0f, 1.15f)
        }
        a.diagSection.addView(a.statusDetailText)
        a.diagSection.visibility = View.GONE
        page.addView(a.diagSection)

        return ScrollView(a).apply { addView(page); isFillViewport = true }
    }

    // ---------------------------------------------------------------- 刷新

    internal fun refresh() {
        val last = a.prefs.getString(KEY_LAST, "无")
        val label = a.prefs.getString(KEY_LAST_LABEL, "")
        // 重活挪到后台：readback 的 4 次 getLastKnownLocation、Config.refresh 读文件、
        // renderAppRows 的逐个 getPackageInfo——onResume/每次注入都跑，主线程做会掉帧（P1-16）。
        thread {
            val fixes = readback()
            Config.refresh()
            val installed = KNOWN_APPS.mapNotNull { app ->
                runCatching { a.packageManager.getPackageInfo(app.pkg, 0) }.getOrNull()?.let { app.pkg }
            }.toSet()
            val mockActive = fixes.any { "mock=true" in it }
            a.runOnUiThread { apply(last ?: "无", label ?: "", fixes, mockActive, installed) }
        }
    }

    internal fun apply(
        last: String,
        label: String,
        fixes: List<String>,
        mockActive: Boolean,
        installed: Set<String>,
    ) {
        renderStatusBar(mockActive, label)

        val mismatch = Config.enabled && last != "无" &&
            (abs(Config.centerLat - lastLat()) > 1e-3 || abs(Config.centerLng - lastLng()) > 1e-3)

        // ---- 当前状态卡：一句话说清在不在跑 ----
        a.statusBigText.text = when {
            mockActive -> "● 模拟中"
            Config.enabled -> "◐ 已配置未生效"
            else -> "○ 未注入"
        }
        a.statusBigText.setTextColor(
            if (mockActive) Md.scheme.primary else Md.scheme.onSurfaceVariant,
        )
        if (last != "无" && lastLat() != 0.0) {
            a.statusCoordWgs.text = "WGS  ${"%.5f".format(lastLat())}, ${"%.5f".format(lastLng())}"
            // 地图 App（高德/腾讯/百度）按 GCJ-02 瓦片渲染，注入的 WGS 值会被换算——
            // 两行坐标差 ~500m 是坐标系不同，不是偏移（2026-11 实测高德蓝点位置正确）。
            val g = CoordTransform.wgs84ToGcj02(lastLat(), lastLng())
            a.statusCoordGcj.text = "高德显示位  ${"%.5f".format(g[0])}, ${"%.5f".format(g[1])}"
            a.statusTarget.text = "目标：${label.ifBlank { "手动坐标" }}"
        } else {
            a.statusCoordWgs.text = "尚未选择注入点"
            a.statusCoordGcj.text = ""
            a.statusTarget.text = ""
        }
        a.stopBtn.visibility = if (mockActive || serviceRunning()) View.VISIBLE else View.GONE

        renderAppRows(installed)
        renderRiskCard(RiskScore.score(buildRiskSnapshot(fixes, mismatch)))

        // ---- 诊断详情：技术细节收进折叠区，默认不展开 ----
        a.statusDetailText.text = buildString {
            append("系统 provider 读回\n")
            fixes.forEach { append("  $it\n") }
            if (mismatch) {
                append("\n⚠ App 选点与模块配置不一致\n")
                append("  App : $label $last\n")
                append("  模块: ${Config.label} ${"%.5f".format(Config.centerLat)},${"%.5f".format(Config.centerLng)}\n")
                append("  → 回到上方点「同步到模块」一键解决\n")
            }
            append("\n运行管线\n")
            append("  ROOT 巡检: ${if (RootInjector.SuSession.alive() && serviceRunning()) "运行中（持久 su 会话）" else "未运行"}")
            if (MockService.lastFreezeGapMs > 0L) {
                append("，曾被系统冻结 ${MockService.lastFreezeGapMs / 1000}s 后自动恢复")
            }
            append("\n")
            append("  LSP 模块: ${if (a.selfCheck()) "已挂载（进程内改写）" else "未启用（可选，微信等 App 需要）"}\n")
            append("  NETCUT 掐网: ${if (Config.netcutPkgs.isEmpty()) "未配置" else Config.netcutPkgs.joinToString()}\n")
            append("  BLE 盲化: ${if (Config.blindBle.packages.isEmpty()) "未配置" else Config.blindBle.render()}\n")
            append("\n模块配置（${Config.DEFAULT_PATH}）\n")
            append("  开关=${onOff(Config.enabled)} 全局=${onOff(Config.global)} 卫星=${onOff(Config.gnssSpoof)}\n")
            append("  致盲 WiFi=${Config.blindWifi.render()} 基站=${Config.blindCell.render()} BLE=${Config.blindBle.render()}\n")
            append("  路线=${if (Config.routeActive) "运行中 ${Config.routeWaypoints.size}点 @${Config.speedMps}m/s" else "未配置"}")
            append("  独立点位=${Config.appPoints.size}个")
            run {
                val custom = Config.appCoord.filterValues { it != "WGS84" }
                if (custom.isNotEmpty()) {
                    append("（输出坐标系 " + custom.entries.joinToString { "${it.key.substringBeforeLast('.')}=${it.value}" } + "）")
                }
            }
            append("\n")
            append("  读回: ${Config.lastError ?: "正常（写入于 ${Config.lastLoadedAgo}ms 前）"}\n")
            // 高德机制：自有融合 SDK（真实 WiFi/基站/服务器定位）优先级高于系统 LocationManager，
            // 不盲化它就直接拿真数据定位覆盖注入点——2026-11-05 实机复现（关盲化即回跳真实位置）。
            append("\n高德提示：高德自带定位引擎优先用自己的真实 WiFi/基站数据，系统注入点会被它覆盖；" +
                "所以高德必须开「致盲 WiFi/基站」（策略页上方三个按钮）+ 掐网，改动后要重启高德。" +
                "微信等 App 读系统定位，LSP 挂上就稳。\n")
            val notes = Diagnostics.snapshot()
            if (notes.isNotEmpty()) {
                append("\n本进程挂钩记录\n")
                notes.forEach { append("  $it\n") }
            }
        }

        maybeAutoResume(fixes)
        updateInjectMarker()
        a.updatePastePreview()
    }

    // ---------------------------------------------------------------- 16 维风险卡

    internal fun toggleRisk() {
        a.riskExpanded = !a.riskExpanded
        a.riskToggleBtn.text = if (a.riskExpanded) "16 维明细 ▲" else "16 维明细 ▼"
        a.riskRows.visibility = if (a.riskExpanded) View.VISIBLE else View.GONE
        if (a.riskExpanded) {
            // 展开时才重建明细，避免每次刷新都重建 16 行。
            a.lastRiskReport?.let { renderRiskRows(it) }
        }
    }

    internal fun buildRiskSnapshot(fixes: List<String>, confMatch: Boolean): RiskScore.Snapshot {
        return RiskScore.Snapshot(
            confEnabled = Config.enabled,
            mockProviders = fixes.count { "mock=true" in it },
            mockTotal = fixes.size,
            confMatch = confMatch,
            loopIntervalSec = Config.loopIntervalSec,
            blindWifi = bridge.blindWifiScope().enabled,
            blindCell = bridge.blindCellScope().enabled,
            blindBle = bridge.blindBleScope().enabled,
            netcutCount = bridge.netcutPkgs().size,
            forgeWifi = Config.forgeWifi,
            sensorFreeze = Config.sensorFreeze,
            hidePkgsCount = Config.hidePkgs.size,
            stackClean = Config.stackClean,
            gmsSpoof = Config.gmsSpoof,
            gnssSpoof = Config.gnssSpoof,
            hasSatellites = Sky.SATS.isNotEmpty(),
            dopplerNonZero = Config.gnssSpoof,
            guardianFresh = guardianFresh(),
            leakClosed = null, // 反推在目标进程里跑，App 进程拿不到结论；探针未开时就是 OFF
        )
    }

    /**
     * 守护循环每 tick 都会 publish() 一次，所以 conf 的 mtime 就是心跳。App 进程能读这个文件
     * （Config 本身就靠它），不需要 root。
     */
    internal fun guardianFresh(): Boolean? {
        val f = java.io.File(Config.DEFAULT_PATH)
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        return age in 0..15_000L
    }

    internal fun renderRiskCard(report: RiskScore.Report) {
        a.lastRiskReport = report
        a.riskSummary.text = "暴露面 ${report.level}（BAD ${report.bad} / WARN ${report.warn} / 共 16 维）"
        if (a.riskExpanded) renderRiskRows(report)
    }

    internal fun renderRiskRows(report: RiskScore.Report) {
        a.riskRows.removeAllViews()
        report.dims.forEach { d ->
            val state = when (d.state) {
                RiskScore.State.OK -> Ui.State.OK
                RiskScore.State.WARN -> Ui.State.WARN
                RiskScore.State.BAD -> Ui.State.BAD
                RiskScore.State.OFF -> Ui.State.OFF
            }
            a.riskRows.addView(ui.chip("${d.name} · ${d.state}", state))
            a.riskRows.addView(ui.hint(d.note))
        }
    }

    /** 开关悬浮摇杆。首次用要用户去设置里授「显示在其他应用上层」。 */
    internal fun toggleJoystick() {
        if (joystickRunning()) {
            a.startService(
                Intent(a, com.mockloc.JoystickService::class.java)
                    .setAction(com.mockloc.JoystickService.ACTION_STOP),
            )
            a.toast("摇杆已关闭")
            return
        }
        if (!android.provider.Settings.canDrawOverlays(a)) {
            a.toast("需要「显示在其他应用上层」授权")
            runCatching {
                a.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + a.packageName),
                    ),
                )
            }.onFailure { a.toast("这个 ROM 不给跳授权页，请手动到设置里开") }
            return
        }
        a.prefs.edit().putBoolean(KEY_JOYSTICK, true).apply()
        a.startForegroundService(Intent(a, com.mockloc.JoystickService::class.java))
        a.toast("摇杆已开启：拖圆盘微调注入点（松手回中）")
    }

    private fun joystickRunning(): Boolean = a.prefs.getBoolean(KEY_JOYSTICK, false)

    internal fun toggleDiag() {
        a.diagExpanded = !a.diagExpanded
        a.diagSection.visibility = if (a.diagExpanded) View.VISIBLE else View.GONE
        a.diagToggleBtn.text = if (a.diagExpanded) "诊断详情 ▲" else "诊断详情 ▼"
    }

    // ---------------------------------------------------------------- 目标 App 行

    internal fun renderAppRows(installed: Set<String>) {
        a.appRows.removeAllViews()
        for (app in KNOWN_APPS) {
            // installed 集合由 refresh() 在后台线程算好（P1-16），这里只做 UI。
            if (app.pkg !in installed) continue
            val row = ui.row().apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) }
            row.addView(TextView(a).apply {
                text = app.label
                textSize = 14f
                setTextColor(ui.bodyColor())
            }, ui.fill())
            if (app.needsLsp) {
                // 微信等 App 自带定位 SDK 不读系统 provider，ROOT 管线改不到它，
                // 只能在 LSP 管理器里把 mockloc 勾进作用域走进程内改写。
                val lspOn = a.selfCheck() && Config.shouldSpoof(app.pkg)
                row.addView(ui.chip(if (lspOn) "LSP ✓" else "需 LSP",
                    if (lspOn) Ui.State.OK else Ui.State.WARN))
                row.addView(ui.button("LSP 管理器", Ui.Btn.GHOST) {
                    thread {
                        // 寄生管理器没有独立包名：start BugreportWarningActivity 只会弹原生 bugreport
                        // 对话框（守护进程的 UI 交换不认外部身份，root+category 实测无效）。可靠入口
                        // 是守护进程注册的模块广播——通知点击即 am broadcast -a <UUID> -d module://<pkg>:0，
                        // 管理器直接打开本模块作用域页。UUID 每个守护进程会话都变，从 root 的
                        // PendingIntent 记录动态解析；解析不到再退回独立管理器包名。
                        val dump = runCatching {
                            RootInjector.runSu(listOf("dumpsys activity intents")).getOrDefault("")
                        }.getOrDefault("")
                        val uuid = Regex("act=([0-9a-f-]{36}) dat=module://" + Regex.escape(a.packageName))
                            .find(dump)?.groupValues?.get(1)
                        if (uuid != null) {
                            RootInjector.runSu(listOf("am broadcast -a $uuid -d module://${a.packageName}:0"))
                            a.runOnUiThread { a.toast("已打开 LSP 管理器（本模块作用域页）") }
                        } else {
                            val intent = a.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                            runCatching { a.startActivity(intent) }
                                .onFailure { a.runOnUiThread { a.toast("请下拉通知栏，点 LSPosed 的通知打开管理器") } }
                        }
                    }
                })
            } else {
                // NETCUT 切换：root 管线按 uid 掐掉该 App 的真实网络定位。
                val on = app.pkg in Config.netcutPkgs
                val toggle = Button(a).apply {
                    text = if (on) "掐网 ✓" else "掐网"
                    isAllCaps = false
                    setTextSize(12f)
                    val (fill, fg) = if (on) Pair(Md.scheme.primaryContainer, Md.scheme.onPrimaryContainer)
                                     else Pair(Md.scheme.surfaceHigh, Md.scheme.onSurfaceVariant)
                    setTextColor(fg)
                    background = GradientDrawable().apply {
                        setColor(fill); cornerRadius = ui.dp(16).toFloat()
                    }
                    stateListAnimator = null
                    setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                }
                toggle.setOnClickListener { toggleNetcut(app.pkg) }
                row.addView(toggle)
            }
            a.appRows.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    /**
     * 切换单个 App 的 NETCUT。名单独立于致盲（v1.3.0 走查 P1-2）：旧版与致盲共用一个作用域，
     * 掐网一关会把高德致盲一起抹掉（2026-11-06 实锤）。现在只写 module_netcut。
     */
    internal fun toggleNetcut(pkg: String) {
        val current = bridge.netcutPkgs().toMutableSet()
        if (pkg in current) { current.remove(pkg); a.toast("已关闭 $pkg 掐网") }
        else { current.add(pkg); a.toast("已开启 $pkg 掐网") }
        bridge.setNetcutPkgs(current.toList())
        thread {
            runCatching {
                if (RootInjector.rootAvailable()) {
                    RootInjector.publishConfig(a, bridge.render())
                    RootInjector.netcutApply(current.toList())
                }
            }
        }
        thread {
            runCatching { Thread.sleep(1500) }
            a.runOnUiThread { refresh() }
        }
    }

    // ---------------------------------------------------------------- 同步

    /**
     * The app-only sync (REFACTOR-PLAN C34): the module reads /data/local/tmp/mockloc.conf, which
     * an app process cannot write - but root can, and this device runs KernelSU. Force-probe su
     * (the spawn itself is what pops the grant dialog the first time), then publish the rendered
     * conf straight to both locations. No PC, no waiting for the module's mtime loop.
     */
    internal fun onSyncToModule() {
        a.toast("正在尝试 root 通道…（首次会弹 KernelSU 授权，请允许）")
        thread {
            runCatching {
                if (!RootInjector.rootAvailable(force = true)) {
                    a.runOnUiThread { showSyncFallbackDialog() }
                    return@runCatching
                }
                val published = RootInjector.publishConfig(a, bridge.render())
                a.runOnUiThread {
                    a.toast(if (published) "已同步（su 直写模块配置）" else "su 写入失败：检查 KernelSU 授权与磁盘")
                    // Config carries a 1s stat cache; give it a beat before the read-back verdict.
                    thread {
                        runCatching { Thread.sleep(1200) }
                        a.runOnUiThread { refresh() }
                    }
                }
            }.onFailure {
                a.runOnUiThread { a.toast("同步失败：${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}") }
            }
        }
    }

    internal fun showSyncFallbackDialog() {
        android.app.AlertDialog.Builder(a)
            .setTitle("需要一条 root 通道")
            .setMessage(
                "模块读的是 /data/local/tmp/mockloc.conf，App 进程自己写不进去。按顺序试：\n\n" +
                    "① KernelSU/APatch 管理器里给 mockloc 授予 root，回来再点一次「同步到模块」（刚才弹的授权框点了允许就直接成功）；\n" +
                    "② 刷 mockloc-root.zip，模块循环会自动搬运 App 的选点；\n" +
                    "③ 最后手段：PC 上跑 python tools/mockloc.py sync。",
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------------------------------------------------------- 零散状态读取

    internal fun renderStatusBar(mockActive: Boolean, label: String) {
        // 顶栏胶囊只说一句话：实色=在跑，灰=空闲/待确认。细节在诊断页。
        val (text, running) = when {
            mockActive -> "模拟中${if (label.isNotBlank()) " · $label" else ""}" to true
            Config.enabled -> "配置生效 · 待确认" to false
            else -> "未注入" to false
        }
        ui.styleStatusPill(a.statusPillView, text, running)
    }

    internal fun serviceRunning(): Boolean =
        a.prefs.getBoolean(MockService.KEY_SERVICE_RUNNING, false)

    internal fun hasLocationGrant(): Boolean =
        a.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    internal fun lastLat(): Double =
        a.prefs.getString(KEY_LAST, "")?.substringBefore(',')?.toDoubleOrNull() ?: 0.0

    internal fun lastLng(): Double =
        a.prefs.getString(KEY_LAST, "")?.substringAfter(',', "")?.toDoubleOrNull() ?: 0.0

    internal fun onOff(value: Boolean) = if (value) "开" else "关"

    // Both accessors are shown on purpose: older apps still call the deprecated one, and the whole
    // point of this screen is to see which flag a target app would be reading.
    @Suppress("DEPRECATION")
    internal fun readback(): List<String> {
        val lm = a.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager
        return listOf("gps", "fused", "network", "passive").map { name ->
            val line = runCatching {
                val loc = lm?.getLastKnownLocation(name)
                if (loc == null) "null"
                else "${"%.6f".format(loc.latitude)},${"%.6f".format(loc.longitude)} acc=${loc.accuracy} mock=${loc.isFromMockProvider}"
            }.getOrElse { "需授权（${it.javaClass.simpleName}）" }
            "$name : $line"
        }
    }

    /**
     * A force-stop or process reclaim takes the tier-0 provider with the app's own process, so
     * "reopen the app" is the natural recovery point: the service_running marker says we were
     * spoofing, the read-back says nothing is - replay the last injection (10s throttle). A user
     * 暂停 or 停止 marks/clears the state so this never fights an explicit stop.
     */
    internal fun maybeAutoResume(fixes: List<String>) {
        if (!a.prefs.getBoolean(MockService.KEY_SERVICE_RUNNING, false)) return
        if (a.prefs.getBoolean(MockService.KEY_PAUSED, false)) return
        if (fixes.any { "mock=true" in it }) return
        if (!hasLocationGrant()) return
        val last = a.prefs.getString(KEY_LAST, "无") ?: return
        if (last == "无") return
        val now = System.currentTimeMillis()
        if (now - a.lastAutoResume < 10_000L) return
        a.lastAutoResume = now
        a.inject(lastLat(), lastLng(), a.prefs.getString(KEY_LAST_LABEL, "恢复上次注入") ?: "恢复上次注入")
    }

    /**
     * 地图页常驻"当前注入点"蓝标（WGS→GCJ 转回显示）：用户一眼核对系统指向与选点是否重合，
     * 偏移感的三种来源（坐标系看串、App 内地图显示偏移、真·注入漂移）当场可分辨。
     */
    internal fun updateInjectMarker() {
        val last = a.prefs.getString(KEY_LAST, "无") ?: return
        if (last == "无") return
        val lat = lastLat()
        val lng = lastLng()
        if (lat == 0.0 && lng == 0.0) return
        // map 是 MainActivity 的 lateinit，跨对象不能用 ::map.isInitialized，用 runCatching 探测。
        val mapView = runCatching { a.map }.getOrNull() ?: return
        val g = a.mapSrc.toDisplay(lat, lng)
        val pos = org.osmdroid.util.GeoPoint(g[0], g[1])
        val existing = a.injectMarker
        if (existing == null) {
            a.injectMarker = org.osmdroid.views.overlay.Marker(mapView, a).apply {
                setPosition(pos)
                setTitle("当前注入点 · WGS ${"%.5f".format(lat)},${"%.5f".format(lng)}")
                mapView.getOverlays().add(this)
            }
        } else {
            existing.setPosition(pos)
            existing.title = "当前注入点 · WGS ${"%.5f".format(lat)},${"%.5f".format(lng)}"
        }
    }

    private companion object {
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        const val KEY_LAST = "last_injected"
        const val KEY_LAST_LABEL = "last_label"
        val TABS = listOf("地图", "路线", "收藏", "策略", "状态")
        const val KEY_JOYSTICK = "joystick_running"
    }
}
