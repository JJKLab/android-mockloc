package com.mockloc

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.concurrent.thread
import kotlin.math.abs

/** 致盲三路：策略页的三个独立开关（v1.3.0 走查 P1-1）。 */
internal enum class BlindKind { WIFI, CELL, BLE }

/**
 * Five tabs: search, pick on the map, favourites, which apps are affected, and what the device is
 * actually reporting. Views rather than Compose on purpose - this file is compiled and type-checked
 * locally against android.jar and osmdroid's classes, the only verification available to a project
 * that is never built on a dev machine.
 *
 * Cards and status pills replace the original wall of text: users read "注入: 未生效" as "模块没装"
 * when those are two unrelated facts.
 */
class MainActivity : Activity() {

    /** Rewritten by the module; false means LSPosed never loaded it into this process. */
    fun selfCheck(): Boolean = false

    // 这些成员被 pages/ 里的页面类共用（阶段四拆分）：internal = 本 module 内可见。
    internal val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    internal val places by lazy { Places(this) }
    internal val bridge by lazy { ModuleBridge(this) }
    internal val ui by lazy { Ui(this) }

    internal lateinit var flipper: ViewFlipper
    internal lateinit var map: MapView
    internal lateinit var statusPillView: TextView
    internal lateinit var pickGcjView: TextView
    internal lateinit var pickWgsView: TextView
    internal lateinit var searchSheet: View
    internal lateinit var searchBar: View
    internal lateinit var pickPanel: View
    internal lateinit var statusBigText: TextView
    internal lateinit var statusCoordWgs: TextView
    internal lateinit var statusCoordGcj: TextView
    internal lateinit var statusTarget: TextView
    internal lateinit var stopBtn: Button
    internal lateinit var appRows: LinearLayout
    internal lateinit var diagSection: LinearLayout
    internal lateinit var diagToggleBtn: Button
    internal var diagExpanded = false
    internal lateinit var statusDetailText: TextView
    internal lateinit var riskSummary: TextView
    internal lateinit var riskRows: LinearLayout
    internal lateinit var riskToggleBtn: Button
    internal var riskExpanded = false
    internal lateinit var keyField: EditText
    internal lateinit var queryField: EditText
    internal lateinit var pasteField: EditText
    internal lateinit var pastePreview: TextView
    internal var injectMarker: Marker? = null
    internal lateinit var policyList: ListView
    internal var policyPointed: Set<String> = emptySet()
    internal var policyLoadMs = 0L
    internal lateinit var policyFilter: EditText
    internal lateinit var policyCount: TextView
    internal lateinit var appOpChipRow: LinearLayout
    internal lateinit var hitRows: LinearLayout
    internal lateinit var placeAdapter: ArrayAdapter<String>

    internal var hits: List<Hit> = emptyList()
    internal var aroundAnchor: Hit? = null
    internal var pickedWgs: DoubleArray? = null
    internal lateinit var mapSrc: MapSource
    internal lateinit var sourceChips: LinearLayout
    internal lateinit var baiduOverlay: BaiduOverlay
    internal var marker: Marker? = null
    internal var pasteGcj = true

    // ---- route page state (REFACTOR-PLAN 2.3) ----
    internal lateinit var routeMap: MapView
    internal lateinit var routeInfo: TextView
    internal lateinit var routeWayRows: LinearLayout
    internal lateinit var routeSpeedField: EditText
    internal lateinit var routeLoopBox: android.widget.CheckBox
    internal val routeWaypoints = mutableListOf<Route.Pt>()
    internal val routeMarkers = mutableListOf<Marker>()
    internal var routeGpxUri: android.net.Uri? = null

    /** Everything the policy list knows, kept out of the views so filtering is cheap. */
    internal var policyAll: List<Pair<String, String>> = emptyList()
    internal val policyChecked = linkedSetOf<String>()
    internal var policyIncludeSystem = false
    internal var policyLoaded = false
    internal lateinit var blindWifiBtn: Button
    internal lateinit var blindCellBtn: Button
    internal lateinit var blindBleBtn: Button

    /** The route page carries a whole second MapView; built lazily on first tab show. */
    internal var routePageBuilt = false

    private val navItems = mutableListOf<Ui.NavItem>()

    /** 本轮注入是否走 ROOT 巡检（供状态页展示与停止路径选择）。 */
    internal var useRootPipeline = false


    // ---------------------------------------------------------------- 页面拆分（阶段四）

    /** 状态页整块搬到了 pages/StatusPage.kt；这里只留薄委托，调用点保持不变。 */
    private val statusPageView by lazy { com.mockloc.pages.StatusPage(this) }

    /** 策略页搬到了 pages/PolicyPage.kt。 */
    private val policyPageView by lazy { com.mockloc.pages.PolicyPage(this) }

    /** 地图页/收藏页搬到了 pages/MapPage.kt，路线页搬到了 pages/RoutePage.kt。 */
    private val mapPageView by lazy { com.mockloc.pages.MapPage(this) }
    private val routePageView by lazy { com.mockloc.pages.RoutePage(this) }

    internal fun mapPage(): View = mapPageView.build()
    internal fun placesPage(): View = mapPageView.placesPage()
    internal fun buildRoutePage() = routePageView.buildRoutePage()
    internal fun renderRouteWaypoints() = routePageView.renderRouteWaypoints()
    internal fun startRoute() = routePageView.startRoute()
    internal fun routeMapLoopChecked(): Boolean = routePageView.routeMapLoopChecked()
    internal fun openSearch() = mapPageView.openSearch()
    internal fun closeSearch() = mapPageView.closeSearch()
    internal fun renderHits() = mapPageView.renderHits()
    internal fun runSearch() = mapPageView.runSearch()
    internal fun onPickHit(index: Int) = mapPageView.onPickHit(index)
    internal fun showAround(anchor: Hit, near: List<Hit>) = mapPageView.showAround(anchor, near)
    internal fun pickHitPoint(hit: Hit) = mapPageView.pickHitPoint(hit)
    internal fun injectPasted() = mapPageView.injectPasted()
    internal fun onMapTap(point: GeoPoint) = mapPageView.onMapTap(point)
    internal fun switchSource(src: MapSource) = mapPageView.switchSource(src)
    internal fun renderSourceChips() = mapPageView.renderSourceChips()
    internal fun centerOn(lat: Double, lng: Double) = mapPageView.centerOn(lat, lng)
    internal fun reloadPlaces() = mapPageView.reloadPlaces()
    internal fun promptPlace(defaultName: String, lat: Double, lng: Double, existing: Place?) =
        mapPageView.promptPlace(defaultName, lat, lng, existing)
    internal fun confirmDelete(place: Place) = mapPageView.confirmDelete(place)

    internal fun statusPage(): View = statusPageView.build()
    internal fun policyPage(): View = policyPageView.build()
    internal fun listHeader(card: LinearLayout): android.view.View = policyPageView.listHeader(card)
    internal fun loadPolicyList() = policyPageView.loadList()
    internal fun renderPolicyRows() = policyPageView.renderRows()
    internal fun saveBlindScope() = policyPageView.save()
    internal fun publishBridge() = policyPageView.publish()
    internal fun cycleBlind(kind: BlindKind) = policyPageView.cycleBlind(kind)
    internal fun renderBlindChips() = policyPageView.renderBlindChips()
    internal fun blindLabel(scope: Scope): String = policyPageView.blindLabel(scope)
    internal fun renderAppOpChips() = policyPageView.renderAppOpChips()

    internal var lastRiskReport: RiskScore.Report? = null

    internal fun refreshStatus() = statusPageView.refresh()
    internal fun renderStatusBar(mockActive: Boolean, label: String) =
        statusPageView.renderStatusBar(mockActive, label)
    internal fun hasLocationGrant(): Boolean = statusPageView.hasLocationGrant()
    internal fun serviceRunning(): Boolean = statusPageView.serviceRunning()
    internal fun lastLat(): Double = statusPageView.lastLat()
    internal fun lastLng(): Double = statusPageView.lastLng()
    internal fun onOff(value: Boolean): String = statusPageView.onOff(value)
    internal fun readback(): List<String> = statusPageView.readback()
    internal fun maybeAutoResume(fixes: List<String>) = statusPageView.maybeAutoResume(fixes)
    internal fun updateInjectMarker() = statusPageView.updateInjectMarker()
    internal fun toggleRisk() = statusPageView.toggleRisk()
    internal fun toggleDiag() = statusPageView.toggleDiag()
    internal fun renderRiskCard(report: RiskScore.Report) = statusPageView.renderRiskCard(report)
    internal fun renderRiskRows(report: RiskScore.Report) = statusPageView.renderRiskRows(report)
    internal fun buildRiskSnapshot(fixes: List<String>, confMatch: Boolean) =
        statusPageView.buildRiskSnapshot(fixes, confMatch)
    internal fun guardianFresh(): Boolean? = statusPageView.guardianFresh()
    internal fun renderAppRows(installed: Set<String>) = statusPageView.renderAppRows(installed)
    internal fun toggleNetcut(pkg: String) = statusPageView.toggleNetcut(pkg)
    internal fun onSyncToModule() = statusPageView.onSyncToModule()
    internal fun showSyncFallbackDialog() = statusPageView.showSyncFallbackDialog()
    internal fun applyStatus(
        last: String,
        label: String,
        fixes: List<String>,
        mockActive: Boolean,
        installed: Set<String>,
    ) = statusPageView.apply(last, label, fixes, mockActive, installed)

    /** 选中态是用户判断"我在哪一页"的唯一线索。 */
    private fun styleNav(selected: Int) {
        navItems.forEachIndexed { index, item -> item.restyle(index == selected) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MD3 token 在任何视图构建之前解析（动态取色 + 暗色判定只在这里做一次）。
        Md.resolve(this)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().setUserAgentValue(packageName)
        // 悬浮摇杆与主进程的桥（同进程，静态委托够用）。
        com.mockloc.JoystickBridge.point = {
            val la = lastLat()
            val ln = lastLng()
            if (la == 0.0 && ln == 0.0) doubleArrayOf(31.2304, 121.4737) else doubleArrayOf(la, ln)
        }
        com.mockloc.JoystickBridge.apply = { lat, lng -> inject(lat, lng, "摇杆微调") }
        setContentView(buildUi())
        map.setTileSource(mapSrc.tileSource())
        map.setMultiTouchControls(true)
        map.controller.setZoom(11.0)
        // setCenter before the first layout is dropped by osmdroid, which leaves the view parked at
        // 0,0 in the Gulf of Guinea.
        map.post { centerOn(31.2304, 121.4737) }
        map.getOverlays().add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onMapTap(p)
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
        )
        // Without the notification grant the tier-0 service runs fine but its notification never
        // shows (README C11); without the location grant the provider is invisible AND an API-34+
        // location FGS hard-fails. Fresh installs get all three asked up front, in one dialog.
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 41)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (routePageBuilt) routeMap.onResume()
        reloadPlaces()
        refreshStatus()
    }

    override fun onPause() {
        map.onPause()
        if (routePageBuilt) routeMap.onPause()
        super.onPause()
    }

    internal fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onDestroy() {
        // osmdroid 的 MapView 持有瓦片下载线程与位图缓存，页面重建时不 onDetach 会泄漏（P2-7）。
        runCatching { map.onDetach() }
        if (routePageBuilt) runCatching { routeMap.onDetach() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // targetSdk 35+ forces edge-to-edge on Android 15/16: without this the nav bar is drawn
            // under the system bar and the app looks like it has no navigation at all.
            fitsSystemWindows = true
            setBackgroundColor(ui.pageBg())
        }

        // MD3 顶栏：标题 + 常驻状态胶囊（点击进诊断页）。
        statusPillView = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openTab(TABS.lastIndex) }
        }
        ui.styleStatusPill(statusPillView, "初始化…", running = false)
        root.addView(ui.topBar("mockloc", statusPillView))

        flipper = ViewFlipper(this)
        // ViewFlipper is a FrameLayout: passing LinearLayout.LayoutParams here gets converted and
        // the conversion keeps height=0 while dropping weight, which is how a whole page measured
        // zero pixels and the app looked blank.
        flipper.addView(mapPage(), pageParams())
        // Placeholder: the route page owns a full second MapView, and building it here put two
        // tile stacks on the cold-start path. It is swapped in on first tab show (buildRoutePage).
        flipper.addView(FrameLayout(this), pageParams())
        flipper.addView(placesPage(), pageParams())
        flipper.addView(policyPage(), pageParams())
        flipper.addView(statusPage(), pageParams())
        root.addView(flipper, weightParams())

        // MD3 navigation bar：5 个目的地，搜索并入地图浮层。
        val bar = ui.navBar()
        val defs = listOf(
            Triple(R.drawable.ic_tab_map, "地图") { openTab(0) },
            Triple(R.drawable.ic_tab_route, "路线") { openTab(1) },
            Triple(R.drawable.ic_tab_fav, "收藏") { openTab(2) },
            Triple(R.drawable.ic_tab_policy, "策略") {
                openTab(3)
                policyPageView.renderAppOpChips()
                if (!policyLoaded) {
                    policyLoaded = true
                    // 勾选框现在只管掐网名单；致盲三路由上方三个按钮单独设（P1-1/P1-2）。
                    policyChecked.addAll(bridge.netcutPkgs())
                    policyPageView.loadList()
                }
            },
            Triple(R.drawable.ic_tab_status, "状态") { openTab(4) },
        )
        defs.forEachIndexed { index, (icon, label, action) ->
            val item = ui.navItem(icon, label, selected = index == 0, onClick = action)
            navItems.add(item)
            bar.addView(item.container)
        }
        root.addView(ui.navDivider())
        root.addView(bar)
        return root
    }

    /** 统一的目的地切换：页面 + 导航选中态 + 惰性构建副作用。 */
    internal fun openTab(index: Int) {
        flipper.displayedChild = index
        navItems.forEachIndexed { i, item -> item.restyle(i == index) }
        if (index == TABS.indexOf("路线")) {
            if (!routePageBuilt) buildRoutePage() else renderRouteWaypoints()
        }
        if (index == TABS.lastIndex) refreshStatus()
    }

    internal fun weightParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    internal fun pageParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
    )

    internal var lastAutoResume = 0L

    // ---------------------------------------------------------------- actions

    internal fun updatePastePreview() = mapPageView.updatePastePreview()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        routePageView.onGpxResult(requestCode, uri)
    }

    internal fun promptAppPoint(pkg: String, label: String) {
        val current = bridge.appPoints()[pkg]
        val last = prefs.getString(KEY_LAST, "")
        val options = mutableListOf<String>()
        if (!last.isNullOrBlank() && last != "无") options.add("用最近注入的点 $last")
        if (current != null) {
            options.add("清除独立点位（当前 ${"%.5f".format(current[0])},${"%.5f".format(current[1])}）")
        }
        // 输出坐标系轮换：瓦片直读型 App（不自己 WGS→GCJ 的）喂 GCJ 免二次偏移；百度系喂 BD09。
        val coord = bridge.appCoord(pkg)
        val next = when (coord) { "WGS84" -> "GCJ02"; "GCJ02" -> "BD09"; else -> "WGS84" }
        options.add("输出坐标系：$coord → 切到 $next（默认 WGS84，改完重启该 App）")
        if (options.isEmpty()) {
            return toast("先注入一个点，再来给这个 App 指定独立点位")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("$label 的独立点位")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    options[which].startsWith("用最近") -> {
                        val parts = last!!.split(',')
                        val la = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                        val ln = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                        if (la == null || ln == null) {
                            toast("最近点位读不懂：$last")
                        } else {
                            bridge.setAppPoint(pkg, la, ln)
                            toast("已设 $label 独立点 $last；重新注入生效，并重启该 App")
                        }
                    }
                    options[which].startsWith("清除") -> {
                        bridge.clearAppPoint(pkg)
                        bridge.setAppCoord(pkg, "WGS84")
                        toast("已清除 $label 的独立点位")
                    }
                    else -> {
                        bridge.setAppCoord(pkg, next)
                        toast("$label 输出坐标系 → $next；重启该 App 生效")
                    }
                }
                policyPointed = bridge.appPoints().keys
                policyPageView.renderRows()
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    internal fun inject(lat: Double, lng: Double, label: String) {
        prefs.edit()
            .putString(KEY_LAST_LABEL, label)
            .putString(KEY_LAST, "%.7f,%.7f".format(lat, lng))
            .apply()
        // A tier-0 provider is invisible to every app without this grant, and an API-34+ FGS with
        // the location type hard-fails without it - refuse here with a pointer instead of crashing
        // inside the service.
        if (!hasLocationGrant()) {
            showWizardIfNeeded()
            return toast("先授予本 App 定位权限（开发者选项选 mockloc 也要重选；见状态页徽章）")
        }
        toast("已注入 $label")
        // The whole body runs on a worker thread: anything uncaught here kills the process from
        // outside the UI thread. A worker failure is a toast, never a crash - the Elevation cache
        // bug took the app down exactly this way on every fresh install (README C33a).
        thread {
            runCatching {
                // Altitude resolves off the main thread: a cache miss is one HTTP round trip, and the
                // fallback must never block or break the injection itself.
                val pinnedAlt = bridge.injectionAlt()
                val alt = if (pinnedAlt >= 0.0) pinnedAlt
                else Elevation.resolve(this@MainActivity, lat, lng) ?: MockService.DEFAULT_ALT
                // Tier selection, measured on this ROM (README A15): su available -> tier 2 (root
                // publishes the conf AND builds the providers itself); otherwise tier 0 in-app
                // provider. Tier 3 stacks on top of either, through LSPosed scope only.
                val useRoot = RootInjector.rootAvailable()
                runOnUiThread {
                    if (useRoot) {
                        bridge.publish(lat, lng, label, global = true)
                        val published = RootInjector.publishConfig(this@MainActivity, bridge.render())
                        // ROOT 管线：MockService 进入 patrol 模式，用持久 su 会话每 tick
                        // 重注入（identity=0，目标进程零侵入；无 LSP 时的推荐形态）。
                        useRootPipeline = true
                        startForegroundService(
                            Intent(this@MainActivity, MockService::class.java)
                                .putExtra(MockService.EXTRA_CMD, "start")
                                .putExtra(MockService.EXTRA_ROOT, true)
                                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                                .putExtra(MockService.EXTRA_LAT, "%.7f".format(lat))
                                .putExtra(MockService.EXTRA_LNG, "%.7f".format(lng))
                                .putExtra(MockService.EXTRA_ACC, "%.1f".format(bridge.injectionAcc()))
                                .putExtra(MockService.EXTRA_RADIUS, "%.1f".format(bridge.injectionRadius()))
                                .putExtra(MockService.EXTRA_ALT, "%.1f".format(alt))
                                .putExtra(MockService.EXTRA_INTERVAL, bridge.injectionIntervalSec().toString()),
                        )
                        toast(if (published) "已注入（ROOT 巡检运行中）" else "已注入（root，conf 发布失败）")
                    } else {
                        // Same point into the module's channel, so 档 3 stops disagreeing with 档 1 (ModuleBridge).
                        bridge.publish(lat, lng, label, global = false)
                        startForegroundService(
                            Intent(this, MockService::class.java)
                                .putExtra(MockService.EXTRA_CMD, "start")
                                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                                .putExtra(MockService.EXTRA_LAT, "%.7f".format(lat))
                                .putExtra(MockService.EXTRA_LNG, "%.7f".format(lng))
                                .putExtra(MockService.EXTRA_ACC, "%.1f".format(bridge.injectionAcc()))
                                .putExtra(MockService.EXTRA_RADIUS, "%.1f".format(bridge.injectionRadius()))
                                .putExtra(MockService.EXTRA_ALT, "%.1f".format(alt))
                                .putExtra(MockService.EXTRA_INTERVAL, bridge.injectionIntervalSec().toString())
                        )
                    }
                    showWizardIfNeeded()
                    refreshStatus()
                }
            }.onFailure {
                runOnUiThread {
                    toast("注入失败：${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}")
                }
            }
        }
        closeSearch()
    }

    /**
     * The README's most repeated lesson, made un-skippable exactly once: a target app's displayed
     * location is often a cached value, so "看着没动" must be confirmed by a fresh locate request
     * reading its returned coordinates - not by watching the screen.
     */
    internal fun showWizardIfNeeded() {
        if (prefs.getBoolean(KEY_WIZARD_SKIP, false)) return
        val box = android.widget.CheckBox(this).apply { text = "不再显示" }
        val column = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(ui.body(
                "1. 完全退出并重新打开目标 App（致盲这类改动只在进程启动时装钩子）\n" +
                    "2. 在目标 App 里主动点一次「我的位置」/发送一次定位\n" +
                    "3. 读它返回的坐标——别看首页缓存的地址文案，那不是判据\n\n" +
                    "IP 属地、收货地址这类服务端信息，任何定位层都改不了。",
            ))
            addView(box)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("怎么确认注入真的生效")
            .setView(column)
            .setPositiveButton("知道了") { _, _ ->
                if (box.isChecked) prefs.edit().putBoolean(KEY_WIZARD_SKIP, true).apply()
            }
            .show()
    }

    /** 切换地图源：换瓦片源 + 把所有已画覆盖物按新坐标系重投（坐标真值全部存在 WGS，重画零损耗）。 */
    internal fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "settings"
        const val KEY_LAST = "last_injected"
        const val KEY_LAST_LABEL = "last_label"
        const val KEY_WIZARD_SKIP = "wizard_skip"
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        val TABS = listOf("地图", "路线", "收藏", "策略", "状态")
    }
}
