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
    private val places by lazy { Places(this) }
    internal val bridge by lazy { ModuleBridge(this) }
    internal val ui by lazy { Ui(this) }

    private lateinit var flipper: ViewFlipper
    internal lateinit var map: MapView
    internal lateinit var statusPillView: TextView
    private lateinit var pickGcjView: TextView
    private lateinit var pickWgsView: TextView
    private lateinit var searchSheet: View
    private lateinit var searchBar: View
    private lateinit var pickPanel: View
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
    private lateinit var keyField: EditText
    private lateinit var queryField: EditText
    private lateinit var pasteField: EditText
    private lateinit var pastePreview: TextView
    internal var injectMarker: Marker? = null
    internal lateinit var policyList: ListView
    internal var policyPointed: Set<String> = emptySet()
    internal var policyLoadMs = 0L
    internal lateinit var policyFilter: EditText
    internal lateinit var policyCount: TextView
    internal lateinit var appOpChipRow: LinearLayout
    private lateinit var hitRows: LinearLayout
    private lateinit var placeAdapter: ArrayAdapter<String>

    private var hits: List<Hit> = emptyList()
    private var aroundAnchor: Hit? = null
    private var pickedWgs: DoubleArray? = null
    internal lateinit var mapSrc: MapSource
    private lateinit var sourceChips: LinearLayout
    private lateinit var baiduOverlay: BaiduOverlay
    private var marker: Marker? = null
    private var pasteGcj = true

    // ---- route page state (REFACTOR-PLAN 2.3) ----
    private lateinit var routeMap: MapView
    private lateinit var routeInfo: TextView
    private lateinit var routeWayRows: LinearLayout
    private lateinit var routeSpeedField: EditText
    private lateinit var routeLoopBox: android.widget.CheckBox
    private val routeWaypoints = mutableListOf<Route.Pt>()
    private val routeMarkers = mutableListOf<Marker>()
    private var routeGpxUri: android.net.Uri? = null

    /** Everything the policy list knows, kept out of the views so filtering is cheap. */
    internal var policyAll: List<Pair<String, String>> = emptyList()
    internal val policyChecked = linkedSetOf<String>()
    internal var policyIncludeSystem = false
    internal var policyLoaded = false
    internal lateinit var blindWifiBtn: Button
    internal lateinit var blindCellBtn: Button
    internal lateinit var blindBleBtn: Button

    /** The route page carries a whole second MapView; built lazily on first tab show. */
    private var routePageBuilt = false

    private val navItems = mutableListOf<Ui.NavItem>()

    /** 本轮注入是否走 ROOT 巡检（供状态页展示与停止路径选择）。 */
    private var useRootPipeline = false


    // ---------------------------------------------------------------- 页面拆分（阶段四）

    /** 状态页整块搬到了 pages/StatusPage.kt；这里只留薄委托，调用点保持不变。 */
    private val statusPageView by lazy { com.mockloc.pages.StatusPage(this) }

    /** 策略页搬到了 pages/PolicyPage.kt。 */
    private val policyPageView by lazy { com.mockloc.pages.PolicyPage(this) }

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

    private fun isDebuggable(): Boolean =
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

    private fun pageParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun scrollColumn(): Pair<ScrollView, LinearLayout> {
        val column = ui.column()
        return ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        } to column
    }

    private fun mapPage(): View {
        // Google Maps 式布局：全屏地图 + 顶部悬浮搜索条 + 底部选点面板/搜索浮层。
        val page = FrameLayout(this)
        map = MapView(this)
        // 百度/谷歌的瓦片端点会拒非浏览器 UA（osmdroid 默认 UA 直接 403，实测百度全空）：
        // 全局换成浏览器 UA，高德不检查 UA 不受影响。
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue =
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            // 只在 debug 构建开：release 里开着会往 logcat 刷瓦片 provider/下载器细节（P2-2）。
            // 用 FLAG_DEBUGGABLE 而不是 BuildConfig：本地 kotlinc 检查没有 AGP 生成的 BuildConfig。
            isDebugTileProviders = isDebuggable()
            isDebugMapTileDownloader = isDebuggable()
        }
        mapSrc = MapSource.entries.firstOrNull {
            it.name == prefs.getString(KEY_MAP_SOURCE, "AMAP")
        } ?: MapSource.AMAP
        map.setTileSource(mapSrc.tileSource())
        map.setMultiTouchControls(true)
        map.controller.setZoom(11.0)
        map.post { centerOn(31.2304, 121.4737) }
        baiduOverlay = BaiduOverlay(map).apply { active = mapSrc == MapSource.BAIDU }
        map.getOverlays().add(baiduOverlay)
        map.getOverlays().add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        page.addView(map, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // 顶部悬浮搜索条（MD3 search bar）：点击展开搜索浮层。
        searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.pillSurface()
            elevation = ui.dp(4).toFloat()
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            addView(ui.icon(R.drawable.ic_search, ui.hintColor()))
            addView(TextView(this@MainActivity).apply {
                text = "搜索地点 · 粘贴坐标"
                textSize = 14f
                setTextColor(ui.hintColor())
                setPadding(ui.dp(10), 0, 0, 0)
            })
            setOnClickListener { openSearch() }
        }
        page.addView(searchBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(ui.dp(12), ui.dp(12), ui.dp(12), 0) })

        // 源切换 chips：右上角悬浮。切源 = 换瓦片 + 全部覆盖物按新坐标系重投。
        sourceChips = ui.row().apply {
            background = ui.pillSurface()
            elevation = ui.dp(4).toFloat()
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        }
        renderSourceChips()
        page.addView(sourceChips, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, ui.dp(64), ui.dp(10), 0) })

        // 底部选点面板：点图后浮起，坐标双系 + 动作。
        pickGcjView = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(ui.bodyColor()) }
        pickWgsView = TextView(this@MainActivity).apply {
            textSize = 13f; setTextColor(ui.bodyColor()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        pickPanel = ui.cardHigh().apply {
            addView(TextView(this@MainActivity).apply {
                text = "选点"
                textSize = 16f
                setTextColor(ui.titleColor())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(pickGcjView)
            addView(pickWgsView)
            val actions = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            actions.addView(ui.button("注入", Ui.Btn.PRIMARY) { injectPicked("地图点选") }, ui.fill())
            actions.addView(ui.button("收藏这个点") {
                val w = pickedWgs ?: return@button toast("先点一下地图")
                promptPlace("点${"%.4f".format(w[0])}", w[0], w[1], null)
            }, ui.fill())
            addView(actions)
        }
        pickPanel.visibility = View.GONE
        page.addView(pickPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(10)) })

        // 搜索浮层：占地图底部 ~55%，内含搜索 + 粘贴 + key。
        searchSheet = buildSearchSheet()
        searchSheet.visibility = View.GONE
        page.addView(searchSheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.55f).toInt(),
            Gravity.BOTTOM,
        ))
        return page
    }

    /** MD3 底部浮层外观：28dp 顶圆角 + 顶栏拖把条。 */
    private fun sheetCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(ui.cardBg())
            cornerRadii = floatArrayOf(
                ui.dp(28).toFloat(), ui.dp(28).toFloat(), ui.dp(28).toFloat(), ui.dp(28).toFloat(),
                0f, 0f, 0f, 0f,  // 顶两角 28dp，底两角直角
            )
        }
        elevation = ui.dp(8).toFloat()
        setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(14))
    }

    private fun buildSearchSheet(): View {
        val sheet = sheetCard()
        val scroll = ScrollView(this)
        val column = ui.column()
        scroll.addView(column)
        val head = ui.row()
        head.addView(TextView(this).apply {
            text = "搜索与坐标"
            textSize = 16f
            setTextColor(ui.titleColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, ui.fill())
        head.addView(ui.icon(R.drawable.ic_close, ui.hintColor()).apply {
            setPadding(ui.dp(8), ui.dp(8), 0, 0)
            setOnClickListener { closeSearch() }
        })
        sheet.addView(head)
        sheet.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        column.addView(ui.sectionLabel("搜地点（高德，结果即注入）"))
        val keyField2 = ui.editText("高德 Web服务 key（存本机私有 prefs）")
        keyField = keyField2
        keyField2.setText(prefs.getString(KEY_AMAP, ""))
        column.addView(keyField2)
        column.addView(ui.button("保存 key") {
            prefs.edit().putString(KEY_AMAP, keyField2.text.toString().trim()).apply()
            toast("key 已存到本机私有配置")
        })
        val goRow = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        queryField = ui.editText("地点，如 东方明珠")
        goRow.addView(queryField, ui.fill())
        goRow.addView(ui.button("搜索", Ui.Btn.PRIMARY) { runSearch() })
        column.addView(goRow)
        hitRows = ui.column()
        column.addView(hitRows)

        column.addView(ui.divider())
        column.addView(ui.sectionLabel("粘贴坐标（双系预览，贴错当场可见）"))
        val pasteRow = ui.row()
        pasteField = ui.editText("31.239703,121.499733")
        pasteField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updatePastePreview()
        })
        pasteRow.addView(pasteField, ui.fill())
        val pasteToggle = Button(this)
        pasteGcj = prefs.getBoolean(KEY_PASTE_GCJ, true)
        pasteToggle.text = if (pasteGcj) "GCJ-02" else "WGS-84"
        ui.styleSegment(pasteToggle, selected = false)
        pasteToggle.setOnClickListener {
            pasteGcj = !pasteGcj
            prefs.edit().putBoolean(KEY_PASTE_GCJ, pasteGcj).apply()
            pasteToggle.text = if (pasteGcj) "GCJ-02" else "WGS-84"
            ui.styleSegment(pasteToggle, selected = false)
            updatePastePreview()
        }
        pasteRow.addView(pasteToggle)
        column.addView(pasteRow)
        pastePreview = TextView(this).apply {
            textSize = 12f
            setTextColor(ui.bodyColor())
            setPadding(0, ui.dp(6), 0, 0)
        }
        column.addView(pastePreview)
        column.addView(ui.button("按这组坐标注入", Ui.Btn.PRIMARY) { injectPasted() })
        column.addView(ui.hint("高德/腾讯/百度网页抄的是 GCJ-02；Google Earth/GPS 工具抄的是 WGS-84"))
        updatePastePreview()
        return sheet
    }

    private fun openSearch() {
        searchSheet.visibility = View.VISIBLE
        searchBar.visibility = View.GONE
        queryField.requestFocus()
    }

    private fun closeSearch() {
        searchSheet.visibility = View.GONE
        searchBar.visibility = View.VISIBLE
    }

    private fun placesPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val list = ListView(this)
        placeAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = placeAdapter
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val item = places.all().getOrElse(pos) { return@OnItemClickListener }
            inject(item.lat, item.lng, item.name)
        }
        list.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val item = places.all().getOrElse(pos) { return@OnItemLongClickListener true }
            // Long press used to delete outright, which is the one gesture nobody wants to repeat.
            android.app.AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(arrayOf("改名称 / 备注", "删除")) { _, which ->
                    when (which) {
                        0 -> promptPlace(item.name, item.lat, item.lng, item)
                        else -> confirmDelete(item)
                    }
                }
                .show()
            true
        }
        page.addView(list, weightParams())
        page.addView(ui.hint("点 = 注入    长按 = 改名称/备注、删除    收藏存的就是 WGS-84，不再转换"))
        return page
    }



    /**
     * The app-only sync (REFACTOR-PLAN C34): the module reads /data/local/tmp/mockloc.conf, which
     * an app process cannot write - but root can, and this device runs KernelSU. Force-probe su
     * (the spawn itself is what pops the grant dialog the first time), then publish the rendered
     * conf straight to both locations. No PC, no waiting for the module's mtime loop.
     */

    // ---------------------------------------------------------------- status


    /**
     * The two facts the no-root tier actually depends on, read fresh every time: the app-op grant
     * (a reinstall clears it) and whether 开发者选项 is even reachable on this ROM.
     */


    internal var lastAutoResume = 0L

    /**
     * 地图页常驻"当前注入点"蓝标（WGS→GCJ 转回显示）：用户一眼核对系统指向与选点是否重合，
     * 偏移感的三种来源（坐标系看串、App 内地图显示偏移、真·注入漂移）当场可分辨。
     */

    /** One glanceable verdict, colored before it is worded (same rule as the chips). */

    // ---------------------------------------------------------------- actions

    /** One tappable row per geocode hit, rendered inline (see searchPage for why not a ListView). */
    private fun renderHits() {
        hitRows.removeAllViews()
        hits.forEachIndexed { index, hit ->
            val row = ui.row().apply {
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                background = ui.pillSurface()
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            }
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(this).apply {
                text = hit.title
                textSize = 14f
                setTextColor(ui.bodyColor())
            })
            if (hit.sub.isNotEmpty() && hit.sub != "无地址") {
                textCol.addView(TextView(this).apply {
                    text = hit.sub
                    textSize = 12f
                    setTextColor(ui.hintColor())
                    maxLines = 1
                })
            }
            row.addView(textCol, ui.fill())
            row.setOnClickListener { onPickHit(index) }
            hitRows.addView(row)
        }
        if (hits.isEmpty()) {
            hitRows.addView(ui.body("还没有结果：填好 key 后搜一个地点"))
        }
    }

    /**
     * 双查合并：geocode（门牌号/区域，"XX路100号"型）+ POI 文本搜（"星巴克"型）。
     * 两个 API 各有所长，混着搜再按名称去重，就是高德 App 搜索框的形态。
     */
    private fun runSearch() {
        val key = keyField.text.toString().trim()
        val query = queryField.text.toString().trim()
        if (key.isEmpty()) return toast("先填并保存高德 key")
        if (query.isEmpty()) return toast("先输入地点")
        toast("搜索中…")
        thread {
            try {
                val geos = runCatching { AmapSearch.geocode(key, query) }.getOrDefault(emptyList())
                val pois = runCatching { AmapSearch.poiText(key, query) }.getOrDefault(emptyList())
                val errors = listOfNotNull(
                    if (geos.isEmpty() && pois.isEmpty()) "两条 API 都没有结果（检查 key 与关键词）" else null,
                )
                // 合并去重：POI 优先（有精确入口坐标），geocode 补门牌/区域
                val merged = (pois + geos).distinctBy {
                    it.name to "%.4f".format(it.latGcj) + "%.4f".format(it.lngGcj)
                }
                runOnUiThread {
                    hits = merged
                    renderHits()
                    errors.forEach { toast(it) }
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("失败：${t.message}") }
            }
        }
    }

    /**
     * 搜索结果点选：
     * - POI → 直接选点（POI 坐标是精确入口）
     * - 门牌号/区域 → 拉周边 POI 列表（"XX号下的公司/餐厅"，和高德 App 同款体验），
     *   空结果（野外门牌）才直接选门牌坐标。
     */
    private fun onPickHit(index: Int) {
        val hit = hits.getOrNull(index) ?: return
        if (hit.source != "geo") return pickHitPoint(hit)
        toast("正在取 ${hit.name.substringBefore('号')}附近的地点…")
        thread {
            val near = runCatching {
                AmapSearch.around(keyField.text.toString().trim(), hit.latGcj, hit.lngGcj)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (near.isNotEmpty()) {
                    showAround(hit, near)
                } else {
                    pickHitPoint(hit)
                }
            }
        }
    }

    /** 周边 POI 列表替换结果区（带"返回搜索"项），点一条选那个 POI 的精确入口。 */
    private fun showAround(anchor: Hit, near: List<Hit>) {
        val w = CoordTransform.gcj02ToWgs84(anchor.latGcj, anchor.lngGcj)
        centerOn(w[0], w[1])
        aroundAnchor = anchor
        hits = near
        renderHits()
        val back = ui.button("↑ 返回「${anchor.name.substringBefore('号')}」的搜索结果", Ui.Btn.GHOST) {
            aroundAnchor = null
            hits = emptyList()
            runSearch()
        }
        hitRows.addView(back, 0)
    }

    private fun pickHitPoint(hit: Hit) {
        val w = CoordTransform.gcj02ToWgs84(hit.latGcj, hit.lngGcj)
        pickedWgs = w
        closeSearch()
        val g = mapSrc.toDisplay(w[0], w[1])
        val existing = marker
        if (existing == null) {
            marker = Marker(map, this).also {
                it.setPosition(GeoPoint(g[0], g[1]))
                map.getOverlays().add(it)
            }
        } else {
            existing.setPosition(GeoPoint(g[0], g[1]))
        }
        pickGcjView.text = "${mapSrc.label}  ${"%.6f".format(g[0])},${"%.6f".format(g[1])}"
        pickWgsView.text = "WGS  ${"%.6f".format(w[0])},${"%.6f".format(w[1])}   ← 注入用这组"
        pickPanel.visibility = View.VISIBLE
        toast("已选「${hit.name}」——按注入生效")
    }

    internal fun updatePastePreview() {
        if (!::pastePreview.isInitialized) return
        pastePreview.text = CoordTransform.dualPreview(pasteField.text.toString(), pasteGcj)
            ?: "格式：纬度,经度（经度在前也认）"
    }

    private fun injectPasted() {
        val parts = pasteField.text.toString().split(',', '，')
        if (parts.size != 2) return toast("格式：纬度,经度")
        val a = parts[0].trim().toDoubleOrNull()
        val b = parts[1].trim().toDoubleOrNull()
        if (a == null || b == null) return toast("坐标读不懂")
        // Accept either order: what people copy off a Chinese map is usually 经度,纬度.
        val (lat, lng) = if (abs(a) <= 90.0) a to b else b to a
        val w = if (pasteGcj) CoordTransform.gcj02ToWgs84(lat, lng) else doubleArrayOf(lat, lng)
        centerOn(w[0], w[1])
        inject(w[0], w[1], "粘贴坐标")
    }

    private fun onMapTap(point: GeoPoint) {
        val w = mapSrc.toWgs(point.latitude, point.longitude)
        pickedWgs = w
        val existing = marker
        if (existing == null) {
            marker = Marker(map, this).also {
                it.setPosition(point)
                map.getOverlays().add(it)
            }
        } else {
            existing.setPosition(point)
        }
        closeSearch()
        pickGcjView.text = "${mapSrc.label}  ${"%.6f".format(point.latitude)},${"%.6f".format(point.longitude)}"
        pickWgsView.text = "WGS  ${"%.6f".format(w[0])},${"%.6f".format(w[1])}   ← 注入用这组"
        pickPanel.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- route page (2.3)

    /** Swap the placeholder for the real route page on first tab show - one MapView less on the
     *  cold-start path, and saved waypoints (if any) come back with it. */
    private fun buildRoutePage() {
        val index = TABS.indexOf("路线")
        routePageBuilt = true
        // A saved route survives process death: the conf keys are the source of truth, so the
        // editor comes back pre-filled instead of silently empty.
        if (routeWaypoints.isEmpty()) routeWaypoints.addAll(bridge.routeWaypoints())
        val page = routePage()
        flipper.removeViewAt(index)
        flipper.addView(page, index, pageParams())
        flipper.displayedChild = index
        renderRouteWaypoints()
    }

    /** 路线 tab: tap waypoints on the map, pick a speed, press start - every tier shares the epoch
     *  anchor, so the route reads as one movement no matter which tier carries it. */
    private fun routePage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        routeMap = MapView(this).apply {
            setTileSource(AmapTiles())
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            post { controller.setCenter(GeoPoint(31.2304, 121.4737)) }
        }
        routeMap.getOverlays().add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    // Map tiles are GCJ-referenced; the route is consumed in WGS-84 like every
                    // other injection path in this app.
                    routeWaypoints.add(
                        Route.Pt(
                            CoordTransform.gcj02ToWgs84(p.latitude, p.longitude)[0],
                            CoordTransform.gcj02ToWgs84(p.latitude, p.longitude)[1],
                        ),
                    )
                    renderRouteWaypoints()
                    return true
                }

                override fun longPressHelper(p: GeoPoint): Boolean = false
            }),
        )
        page.addView(routeMap, weightParams())

        routeInfo = TextView(this).apply {
            textSize = 13f
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(2))
        }
        page.addView(routeInfo)

        routeWayRows = ui.column()
        page.addView(
            ScrollView(this).apply {
                addView(routeWayRows)
                isFillViewport = false
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f,
            ),
        )

        val controls = ui.column().apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(6)) }
        val speedRow = ui.row()
        speedRow.addView(
            TextView(this).apply {
                text = "速度 m/s"
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, ui.dp(8), 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        routeSpeedField = EditText(this).apply {
            setText("1.4")
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        speedRow.addView(routeSpeedField, ui.fill())
        listOf("步行1.4" to 1.4, "骑行5.5" to 5.5, "驾车12.0" to 12.0).forEach { (label, value) ->
            speedRow.addView(ui.button(label) { routeSpeedField.setText(value.toString()) })
        }
        controls.addView(speedRow)
        controls.addView(
            android.widget.CheckBox(this).apply {
                routeLoopBox = this
                text = "循环（走完回到起点继续）"
                isChecked = true
            },
        )
        val runRow = ui.row()
        runRow.addView(ui.button("开始路线", Ui.Btn.PRIMARY) { startRoute() }, ui.fill())
        runRow.addView(ui.button("停止路线") {
            bridge.stopRoute()
            toast("路线已停：注入点回到静态中心")
            renderRouteWaypoints()
            refreshStatus()
        }, ui.fill())
        controls.addView(runRow)
        val ioRow = ui.row()
        ioRow.addView(ui.button("导入GPX") {
            runCatching {
                routeGpxUri = null
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    REQUEST_GPX_IMPORT,
                )
            }.onFailure { toast("这个系统不给打开文件选择器：${it.message}") }
        }, ui.fill())
        ioRow.addView(ui.button("导出GPX") {
            if (routeWaypoints.size < 2) return@button toast("没有可导出的航点")
            runCatching {
                routeGpxUri = null
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/gpx+xml")
                        .putExtra(Intent.EXTRA_TITLE, "mockloc-route.gpx"),
                    REQUEST_GPX_EXPORT,
                )
            }.onFailure { toast("这个系统不给打开文件选择器：${it.message}") }
        }, ui.fill())
        ioRow.addView(ui.button("清空") {
            routeWaypoints.clear()
            renderRouteWaypoints()
        }, ui.fill())
        controls.addView(ioRow)
        page.addView(controls)
        return page
    }

    private fun renderRouteWaypoints() {
        // Rebuild markers from scratch - waypoint count is small and this beats incremental
        // marker surgery on three code paths (tap / import / clear).
        routeMarkers.forEach { routeMap.getOverlays().remove(it) }
        routeMarkers.clear()
        routeWaypoints.forEachIndexed { index, pt ->
            val m = Marker(routeMap, this).apply {
                // Tiles are GCJ: draw the marker where the tap landed, not at the raw WGS value.
                val g = mapSrc.toDisplay(pt.lat, pt.lng)
                // setPosition/setTitle, not the Kotlin property form: osmdroid 6.1.20 has no
                // matching getter pair here (the README recorded this trap in v0.4.0).
                setPosition(GeoPoint(g[0], g[1]))
                setTitle("航点${index + 1}")
            }
            routeMarkers.add(m)
            routeMap.getOverlays().add(m)
        }
        routeMap.invalidate()
        routeWayRows.removeAllViews()
        routeWaypoints.forEachIndexed { index, pt ->
            routeWayRows.addView(
                ui.body("${index + 1}. ${"%.5f".format(pt.lat)},${"%.5f".format(pt.lng)}"),
            )
        }
        val path = Route.build(routeWaypoints)
        routeInfo.text = buildString {
            if (routeWaypoints.size < 2) {
                append("在地图上点 ≥2 个航点；长按地图页可注入单点")
            } else {
                append("航点 ${routeWaypoints.size} 个 · 全程 ${"%.0f".format(path!!.totalMeters)} m")
            }
            if (bridge.routeRunning()) {
                val speed = bridge.routeSpeed()
                val etaMin = if (path != null) "%.0f".format(path.totalMeters / speed / 60.0) else "?"
                append(" · 运行中 @${"%.1f".format(speed)} m/s（单圈约 $etaMin 分钟）")
            }
        }
    }

    private fun startRoute() {
        if (routeWaypoints.size < 2) return toast("先在地图上点至少两个航点")
        val speed = routeSpeedField.text.toString().toDoubleOrNull()
            ?: return toast("速度读不懂：填 0.1–60 之间的数字")
        if (speed < 0.1 || speed > 60.0) return toast("速度要在 0.1–60 m/s 之间")
        val loop = routeMapLoopChecked()
        val started = System.currentTimeMillis()
        val label = "路线 ${routeWaypoints.size} 点"
        val first = routeWaypoints.first()
        toast("启动路线…")
        thread {
            runCatching {
                val alt = Elevation.resolve(this@MainActivity, first.lat, first.lng)
                    ?: MockService.DEFAULT_ALT
                val useRoot = RootInjector.rootAvailable()
                runOnUiThread {
                    bridge.setRoute(routeWaypoints.toList(), speed, loop, started)
                    if (useRoot) {
                        bridge.publish(first.lat, first.lng, label, global = true)
                        val published = RootInjector.publishConfig(this@MainActivity, bridge.render())
                        useRootPipeline = true
                        startForegroundService(
                            Intent(this@MainActivity, MockService::class.java)
                                .putExtra(MockService.EXTRA_CMD, "start")
                                .putExtra(MockService.EXTRA_ROOT, true)
                                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                                .putExtra(MockService.EXTRA_LAT, "%.7f".format(first.lat))
                                .putExtra(MockService.EXTRA_LNG, "%.7f".format(first.lng))
                                .putExtra(MockService.EXTRA_ACC, "%.1f".format(bridge.injectionAcc()))
                                .putExtra(MockService.EXTRA_ALT, "%.1f".format(alt))
                                .putExtra(MockService.EXTRA_INTERVAL, bridge.injectionIntervalSec().toString()),
                        )
                        toast(if (published) "路线启动（ROOT 巡检 + 路线推进）" else "路线启动（conf 发布失败）")
                    } else {
                        bridge.publish(first.lat, first.lng, label, global = false)
                        startForegroundService(
                            Intent(this, MockService::class.java)
                                .putExtra(MockService.EXTRA_CMD, "start")
                                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                                .putExtra(MockService.EXTRA_LAT, "%.7f".format(first.lat))
                                .putExtra(MockService.EXTRA_LNG, "%.7f".format(first.lng))
                                .putExtra(MockService.EXTRA_ACC, "%.1f".format(bridge.injectionAcc()))
                                .putExtra(MockService.EXTRA_RADIUS, "0.0")
                                .putExtra(MockService.EXTRA_ALT, "%.1f".format(alt))
                                .putExtra(MockService.EXTRA_INTERVAL, bridge.injectionIntervalSec().toString()),
                        )
                        toast("路线启动（档0，按 ${bridge.injectionIntervalSec()}s 推进）")
                    }
                    showWizardIfNeeded()
                    renderRouteWaypoints()
                    refreshStatus()
                }
            }.onFailure {
                runOnUiThread { toast("路线启动失败：${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}") }
            }
        }
    }

    private fun routeMapLoopChecked(): Boolean = routeLoopBox.isChecked

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_GPX_IMPORT -> thread {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val pts = text?.let { Gpx.parse(it) }.orEmpty()
                runOnUiThread {
                    if (pts.size < 2) {
                        toast("这个 GPX 里没读到 ≥2 个轨迹点")
                    } else {
                        routeWaypoints.clear()
                        routeWaypoints.addAll(pts)
                        renderRouteWaypoints()
                        val g = mapSrc.toDisplay(pts[0].lat, pts[0].lng)
                        routeMap.controller.setCenter(GeoPoint(g[0], g[1]))
                        toast("导入 ${pts.size} 个航点")
                    }
                }
            }
            REQUEST_GPX_EXPORT -> thread {
                val body = Gpx.export("mockloc route", routeWaypoints)
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { os ->
                        os.write(body.toByteArray())
                    } != null
                }.getOrDefault(false)
                runOnUiThread { toast(if (ok) "GPX 已导出" else "写入失败") }
            }
        }
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

    private fun injectPicked(fallbackName: String) {
        val w = pickedWgs ?: return toast("先点一下地图")
        inject(w[0], w[1], fallbackName)
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
    private fun showWizardIfNeeded() {
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
    private fun switchSource(src: MapSource) {
        if (src == mapSrc) return
        mapSrc = src
        prefs.edit().putString(KEY_MAP_SOURCE, src.name).apply()
        map.setTileSource(src.tileSource())
        updateInjectMarker()
        if (::baiduOverlay.isInitialized) baiduOverlay.active = src == MapSource.BAIDU
        pickedWgs?.let { w ->
            val g = src.toDisplay(w[0], w[1])
            marker?.setPosition(GeoPoint(g[0], g[1]))
        }
        lastLat().takeIf { it != 0.0 }?.let { centerOn(it, lastLng()) }
        renderSourceChips()
        toast("地图源 → ${src.label}（${src.hint}）")
    }

    private fun renderSourceChips() {
        if (!::sourceChips.isInitialized) return
        sourceChips.removeAllViews()
        MapSource.entries.forEach { src ->
            sourceChips.addView(ui.chip(src.label, if (src == mapSrc) Ui.State.OK else Ui.State.OFF).apply {
                setOnClickListener { switchSource(src) }
            })
        }
    }

    private fun centerOn(lat: Double, lng: Double) {
        // 瓦片参考系随源变（高德 GCJ / 谷歌 WGS / 百度 BD09）：拿 WGS 值直接 setCenter 会在
        // 非谷歌源上停在真实选点旁几百米——注入值本身没错，显示参考系不同。
        val g = mapSrc.toDisplay(lat, lng)
        map.controller.setCenter(GeoPoint(g[0], g[1]))
    }

    private fun reloadPlaces() {
        placeAdapter.clear()
        placeAdapter.addAll(places.all().map { it.label })
    }

    /**
     * Ask for a name and a note instead of inventing "点3782". A saved point with no note is
     * indistinguishable from the one 30 m away, which is the whole reason people stop using
     * favourites.
     */
    private fun promptPlace(defaultName: String, lat: Double, lng: Double, existing: Place?) {
        val fields = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val nameField = EditText(this).apply {
            setText(existing?.name ?: defaultName)
            hint = "名称"
            isSingleLine = true
        }
        val noteField = EditText(this).apply {
            setText(existing?.note ?: "")
            hint = "备注（哪栋楼、几点打卡、给谁用）"
            isSingleLine = true
        }
        fields.addView(nameField)
        fields.addView(noteField)
        android.app.AlertDialog.Builder(this)
            .setTitle(if (existing == null) "收藏这个点" else "编辑收藏")
            .setView(fields)
            .setPositiveButton("保存") { _, _ ->
                val name = nameField.text.toString().trim().ifEmpty { defaultName }
                val note = noteField.text.toString().trim()
                val edited = existing?.let { old ->
                    places.all().indexOfFirst { it.name == old.name && it.lat == old.lat }
                } ?: -1
                if (edited >= 0) places.replaceAt(edited, Place(name, lat, lng, note)) else
                    places.add(Place(name, lat, lng, note))
                reloadPlaces()
                toast(if (existing == null) "已收藏 $name" else "已更新 $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(place: Place) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除 ${place.name}")
            .setMessage(place.label)
            .setPositiveButton("删除") { _, _ ->
                places.all().indexOfFirst { it.name == place.name && it.lat == place.lat }
                    .takeIf { it >= 0 }
                    ?.let { places.removeAt(it) }
                reloadPlaces()
            }
            .setNegativeButton("留下", null)
            .show()
    }

    internal fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "settings"
        const val KEY_AMAP = "amap_key"
        const val KEY_LAST = "last_injected"
        const val KEY_MAP_SOURCE = "map_source"
        const val KEY_LAST_LABEL = "last_label"
        const val KEY_WIZARD_SKIP = "wizard_skip"
        const val KEY_PASTE_GCJ = "paste_gcj"
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        const val REQUEST_GPX_IMPORT = 71
        const val REQUEST_GPX_EXPORT = 72
        val TABS = listOf("地图", "路线", "收藏", "策略", "状态")
    }
}
