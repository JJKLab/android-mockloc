package com.mockloc

import android.app.Activity
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

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val places by lazy { Places(this) }
    private val bridge by lazy { ModuleBridge(this) }
    private val ui by lazy { Ui(this) }

    private lateinit var flipper: ViewFlipper
    private lateinit var map: MapView
    private lateinit var mapInfo: TextView
    private lateinit var statusLine: TextView
    private lateinit var statusText: TextView
    private lateinit var tierChips: LinearLayout
    private lateinit var riskChips: LinearLayout
    private lateinit var riskText: TextView
    private lateinit var keyField: EditText
    private lateinit var queryField: EditText
    private lateinit var pasteField: EditText
    private lateinit var policyRows: LinearLayout
    private lateinit var policyFilter: EditText
    private lateinit var policyCount: TextView
    private lateinit var appOpChipRow: LinearLayout
    private lateinit var hitRows: LinearLayout
    private lateinit var placeAdapter: ArrayAdapter<String>

    private var hits: List<Hit> = emptyList()
    private var pickedWgs: DoubleArray? = null
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
    private var policyAll: List<Pair<String, String>> = emptyList()
    private val policyChecked = linkedSetOf<String>()
    private var policyIncludeSystem = false
    private var policyLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().setUserAgentValue(packageName)
        setContentView(buildUi())
        map.setTileSource(AmapTiles())
        map.setMultiTouchControls(true)
        map.controller.setZoom(11.0)
        routeMap.setTileSource(AmapTiles())
        routeMap.setMultiTouchControls(true)
        routeMap.controller.setZoom(13.0)
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
        // Without this grant the tier-0 service runs fine but its notification never shows
        // (README C11), which made "it stopped working?" indistinguishable from "paused".
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        routeMap.onResume()
        reloadPlaces()
        refreshStatus()
    }

    override fun onPause() {
        map.onPause()
        routeMap.onPause()
        super.onPause()
    }

    // ---------------------------------------------------------------- ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // targetSdk 35+ forces edge-to-edge on Android 15/16: without this the tab row is drawn
            // under the status bar and the app looks like it has no navigation at all.
            fitsSystemWindows = true
            setBackgroundColor(ui.pageBg())
        }
        root.addView(header())
        root.addView(statusBar())

        flipper = ViewFlipper(this)
        // ViewFlipper is a FrameLayout: passing LinearLayout.LayoutParams here gets converted and
        // the conversion keeps height=0 while dropping weight, which is how a whole page measured
        // zero pixels and the app looked blank.
        flipper.addView(searchPage(), pageParams())
        flipper.addView(mapPage(), pageParams())
        flipper.addView(routePage(), pageParams())
        flipper.addView(placesPage(), pageParams())
        flipper.addView(policyPage(), pageParams())
        flipper.addView(statusPage(), pageParams())

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
        }
        TABS.forEachIndexed { index, title ->
            tabRow.addView(Button(this).apply {
                text = title
                isAllCaps = false
                // Explicit colours: the OEM default button style rendered these with no visible
                // background or text, which made a zero-height content bug look like a dead app.
                setBackgroundColor(0xFF1F6FEB.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                setTextSize(12f)
                setOnClickListener {
                    flipper.displayedChild = index
                    // Enumerating packages and reading the app-op are both too slow/noisy to do on
                    // every keystroke, so the pages that need them fill in on first show.
                    if (index == TABS.indexOf("策略")) {
                        renderAppOpChips()
                        if (!policyLoaded) {
                            policyLoaded = true
                            // A Scope of All cannot be shown as ticked packages, so start from the
                            // saved list and let the user re-narrow it explicitly.
                            policyChecked.addAll(bridge.blindScope().packages)
                            loadPolicyList()
                        }
                    }
                    if (index == TABS.indexOf("路线")) renderRouteWaypoints()
                    if (index == TABS.lastIndex) refreshStatus()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(4))
            })
        }
        root.addView(tabRow)
        root.addView(flipper, weightParams())
        return root
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF1F6FEB.toInt())
        setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        addView(TextView(this@MainActivity).apply {
            text = "mockloc"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        })
        addView(TextView(this@MainActivity).apply {
            text = "四档：免root / Shizuku / root / 模块无痕（策略页看哪个 App 要哪层）"
            textSize = 12f
            setTextColor(0xD9FFFFFF.toInt())
        })
    }

    /**
     * The one-line verdict the user actually needs ("is anything spoofing right now"), always
     * visible above every page. Tapping it jumps to the full status page - the wall of detail is
     * still there, it just stopped being the only place to look.
     */
    private fun statusBar(): View = TextView(this).apply {
        statusLine = this
        textSize = 13f
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        setOnClickListener { flipper.displayedChild = TABS.lastIndex }
    }

    private fun weightParams() =
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

    private fun searchPage(): View {
        val (scroll, column) = scrollColumn()

        val keyCard = ui.card()
        keyCard.addView(ui.title("高德 Web服务 key"))
        keyField = EditText(this).apply {
            hint = "只存本机私有 prefs，不写 world-readable 配置"
            setText(prefs.getString(KEY_AMAP, ""))
            isSingleLine = true
        }
        keyCard.addView(keyField)
        keyCard.addView(Button(this).apply {
            text = "保存 key"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putString(KEY_AMAP, keyField.text.toString().trim()).apply()
                toast("key 已存到本机私有配置")
            }
        })
        column.addView(keyCard)

        val searchCard = ui.card()
        searchCard.addView(ui.title("搜地点"))
        val goRow = ui.row()
        queryField = EditText(this).apply { hint = "地点，如 东方明珠"; isSingleLine = true }
        goRow.addView(queryField, ui.fill())
        goRow.addView(Button(this).apply {
            text = "搜索"
            isAllCaps = false
            setOnClickListener { runSearch() }
        })
        searchCard.addView(goRow)
        // Same reason as the policy list: a ListView inside this ScrollView cannot scroll, so
        // anything past the first screenful of results was unreachable.
        hitRows = ui.column()
        searchCard.addView(hitRows)
        searchCard.addView(ui.hint("点一条即注入；高德给的是 GCJ-02，注入前自动反算 WGS-84"))
        column.addView(searchCard)

        val pasteCard = ui.card()
        pasteCard.addView(ui.title("粘贴坐标"))
        val pasteRow = ui.row()
        pasteField = EditText(this).apply { hint = "31.239703,121.499733"; isSingleLine = true }
        pasteRow.addView(pasteField, ui.fill())
        pasteRow.addView(Button(this).apply {
            text = "GCJ-02"
            isAllCaps = false
            setOnClickListener {
                pasteGcj = !pasteGcj
                text = if (pasteGcj) "GCJ-02" else "WGS-84"
            }
        })
        pasteCard.addView(pasteRow)
        pasteCard.addView(Button(this).apply {
            text = "按这组坐标注入"
            isAllCaps = false
            setOnClickListener { injectPasted() }
        })
        pasteCard.addView(ui.hint("国内网页地图上抄下来的一般是 GCJ-02；选 GCJ-02 时自动反算成 WGS-84"))
        column.addView(pasteCard)
        return scroll
    }

    private fun mapPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        map = MapView(this)
        mapInfo = TextView(this).apply {
            text = "点地图任意位置选点"
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        }
        val actions = ui.row().apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(8)) }
        actions.addView(Button(this).apply {
            text = "注入"
            isAllCaps = false
            setOnClickListener { injectPicked("地图点选") }
        }, ui.fill())
        actions.addView(Button(this).apply {
            text = "收藏这个点"
            isAllCaps = false
            setOnClickListener {
                val w = pickedWgs ?: return@setOnClickListener toast("先点一下地图")
                promptPlace("点${"%.4f".format(w[0])}", w[0], w[1], null)
            }
        }, ui.fill())
        page.addView(map, weightParams())
        page.addView(mapInfo)
        page.addView(actions)
        return page
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

    private fun policyPage(): View {
        val (scroll, column) = scrollColumn()

        val preflight = ui.card()
        preflight.addView(ui.title("免 root 档自检"))
        appOpChipRow = ui.chipRow()
        preflight.addView(appOpChipRow)
        preflight.addView(Button(this).apply {
            text = "去开发者选项"
            isAllCaps = false
            setOnClickListener {
                val intent = if (Tiers.developerOptionsEnabled(this@MainActivity)) {
                    Tiers.developmentSettingsIntent()
                } else {
                    toast("开发者选项还没打开，先到设备信息里连点版本号")
                    Tiers.fallbackSettingsIntent()
                }
                runCatching { startActivity(intent) }
                    .onFailure { toast("这个 ROM 不让我直接跳，请手动进设置") }
            }
        })
        preflight.addView(ui.hint(
            "「选择虚拟定位应用」只能由设置进程授权（本机 shell 被摘了 MANAGE_APP_OPS_MODES）；" +
                "重装 App 会清掉那次授权，所以这一栏每次进来都重读。",
        ))
        column.addView(preflight)

        val policy = ui.card()
        policy.addView(ui.title("哪些 App 要抽空输入"))
        policy.addView(ui.body(
            "勾上的包会屏蔽自己的 WiFi/基站扫描结果，逼它落回系统定位——实测只有高德这类" +
                "「自己算路」的 App 需要。没勾的 App 不受影响。",
        ))
        val filterRow = ui.row()
        policyFilter = EditText(this).apply {
            hint = "过滤名称或包名"
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = renderPolicyRows()
            })
        }
        filterRow.addView(policyFilter, ui.fill())
        filterRow.addView(android.widget.CheckBox(this).apply {
            text = "含系统"
            isChecked = policyIncludeSystem
            setOnCheckedChangeListener { _, on ->
                policyIncludeSystem = on
                renderPolicyRows()
            }
        })
        policy.addView(filterRow)
        policyCount = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6B6B6B.toInt())
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        policy.addView(policyCount)
        // Rows live directly in the page's scroll column. A ListView inside a ScrollView measures
        // itself to one screenful and then fights the parent for the drag, which is how the first
        // version ended up showing about six apps with no way to scroll.
        policyRows = ui.column()
        policy.addView(policyRows)
        val policyButtons = ui.row()
        policyButtons.addView(Button(this).apply {
            text = "保存"
            isAllCaps = false
            setOnClickListener { saveBlindScope() }
        }, ui.fill())
        policyButtons.addView(Button(this).apply {
            text = "清空"
            isAllCaps = false
            setOnClickListener {
                policyChecked.clear()
                renderPolicyRows()
            }
        }, ui.fill())
        policy.addView(policyButtons)
        column.addView(policy)

        val legend = ui.card()
        legend.addView(ui.title("三件事互相独立"))
        legend.addView(ui.body(Tiers.summary().trim()))
        legend.addView(ui.hint(
            "①/②/③ 不是「权限越高越有效」的阶梯：0、1、2 档给目标 App 的是同一个系统 provider，" +
                "所以 Tier 0 推不动的 App，Tier 2 也推不动。",
        ))
        column.addView(legend)

        val limits = ui.card()
        limits.addView(ui.title("这几样定位层改不了"))
        limits.addView(ui.body(Tiers.outsideScope.joinToString("\n")))
        column.addView(limits)
        return scroll
    }

    private fun loadPolicyList() {
        val packages = runCatching {
            packageManager.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
        }.getOrElse { emptyList() }
        policyAll = packages.mapNotNull { info ->
            val pkg = info.packageName
            if (pkg == packageName) return@mapNotNull null
            val permissions = info.requestedPermissions ?: return@mapNotNull null
            val asksForLocation =
                android.Manifest.permission.ACCESS_FINE_LOCATION in permissions ||
                    android.Manifest.permission.ACCESS_COARSE_LOCATION in permissions
            if (!asksForLocation) return@mapNotNull null
            val appInfo = info.applicationInfo
            // A phone has hundreds of platform packages that nominally ask for location; listing
            // them buries the handful the user could possibly mean. Updated system apps stay in,
            // because preinstalled OEM maps and delivery apps are exactly those.
            val system = appInfo != null &&
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system && !policyIncludeSystem) return@mapNotNull null
            val label = runCatching {
                packageManager.getApplicationLabel(appInfo!!).toString()
            }.getOrDefault(pkg)
            pkg to label
        }.sortedBy { it.second }
        renderPolicyRows()
    }

    private fun renderPolicyRows() {
        val needle = policyFilter.text.toString().trim().lowercase()
        val shown = policyAll.filter { (pkg, label) ->
            needle.isEmpty() || label.lowercase().contains(needle) || pkg.lowercase().contains(needle)
        }
        policyRows.removeAllViews()
        val appPoints = bridge.appPoints()
        shown.forEach { (pkg, label) ->
            val row = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(android.widget.CheckBox(this).apply {
                text = "$label  ${Tiers.needsOf(pkg).label.take(1)}\n$pkg"
                textSize = 13f
                isChecked = pkg in policyChecked
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                setOnCheckedChangeListener { _, on ->
                    if (on) policyChecked.add(pkg) else policyChecked.remove(pkg)
                }
            }, ui.fill())
            // Per-app fixed point (REFACTOR-PLAN 2.2): the pill marks apps that have one, and the
            // tap offers "reuse the last injected point / clear" - no second map picker needed.
            row.addView(Button(this).apply {
                text = if (pkg in appPoints) "📍" else "位置"
                isAllCaps = false
                setTextSize(11f)
                setOnClickListener { promptAppPoint(pkg, label) }
            })
            policyRows.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        if (shown.isEmpty()) {
            policyRows.addView(ui.body("没有匹配的 App（试试勾「含系统」）"))
        }
        policyCount.text = buildString {
            append("共 ${shown.size} 个可选项，已选 ${policyChecked.size} 个致盲")
            val withPoint = appPoints.size
            if (withPoint > 0) append("，$withPoint 个独立点位")
            if (!policyIncludeSystem) append("（已隐藏系统 App）")
            // "作用域内全部" is not expressible as a ticked list, so saving from here would quietly
            // narrow it. Say so instead of letting the write look like a no-op that lost their setting.
            if (bridge.blindScope().mode == com.mockloc.Scope.Mode.ALL) {
                append("\n当前生效的是「作用域内全部」；在这里保存会改成只致盲勾上的包")
            }
        }
    }

    private fun saveBlindScope() {
        val picked = policyChecked.toList()
        bridge.setBlindScope(Scope.only(picked))
        toast(
            if (picked.isEmpty()) "已清空：没有 App 需要抽空输入"
            else "已选 ${picked.size} 个；跑 tools/mockloc.py sync 或刷 root 模块后生效，且要重启目标 App"
        )
        refreshStatus()
    }

    private fun statusPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tierChips = ui.chipRow().apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), 0) }
        riskChips = ui.chipRow().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0) }
        riskText = TextView(this).apply {
            textSize = 12f
            setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2))
            setTextColor(ui.hintColor())
        }
        statusText = TextView(this).apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)) }
        val actions = ui.row().apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(8)) }
        actions.addView(Button(this).apply {
            text = "刷新"
            isAllCaps = false
            setOnClickListener { refreshStatus() }
        }, ui.fill())
        actions.addView(Button(this).apply {
            text = if (bridge.probe()) "探针：开" else "探针：关"
            isAllCaps = false
            setOnClickListener {
                bridge.setProbe(!bridge.probe())
                toast(if (bridge.probe()) "探针已开，记得跑一次 tools/mockloc.py sync" else "探针已关")
                refreshStatus()
            }
        }, ui.fill())
            actions.addView(Button(this).apply {
                text = "停止注入"
                isAllCaps = false
                setOnClickListener {
                    startService(Intent(this@MainActivity, MockService::class.java)
                        .putExtra(MockService.EXTRA_CMD, "stop"))
                    bridge.clear()
                    // If a root publish ever built providers from the App, stop has to tear those
                    // down too or the system keeps serving the old point after "停止".
                    thread {
                        val out = RootInjector.teardown(DEFAULT_PROVIDERS.split(','))
                        runOnUiThread {
                            refreshStatus()
                            if (out.contains("Error") || out.contains("denied")) toast("root 侧停止异常：${out.take(60)}")
                        }
                    }
                }
            }, ui.fill())
        page.addView(tierChips)
        page.addView(riskChips)
        page.addView(riskText)
        page.addView(ScrollView(this).apply { addView(statusText) }, weightParams())
        page.addView(actions)
        return page
    }

    /**
     * Risk card (REFACTOR-PLAN 阶段四, bounded version of 全域's 16-dim score): only facts this
     * process can actually observe. Everything green = 低; a yellow = 中; a red = 高. It scores
     * exposure, not "how well the spoof works" - the two get confused easily and only the first
     * is measurable from here.
     */
    private fun renderRisk(mockActive: Boolean, mismatch: Boolean) {
        val notes = Diagnostics.snapshot()
        val items = listOf(
            "注入" to if (mockActive) Ui.State.OK else Ui.State.OFF,
            "配置一致" to if (!mismatch || !Config.enabled) Ui.State.OK else Ui.State.BAD,
            "致盲" to if (Config.blindWifi.enabled || Config.blindCell.enabled) Ui.State.OK else Ui.State.OFF,
            "挂钩台账" to if (notes.isEmpty()) Ui.State.OK else Ui.State.WARN,
            "卫星视图(档0/1/2)" to if (mockActive) Ui.State.WARN else Ui.State.OFF,
        )
        riskChips.removeAllViews()
        items.forEach { (label, state) -> riskChips.addView(ui.chip(label, state)) }
        val worst = when {
            items.any { it.second == Ui.State.BAD } -> "高"
            items.any { it.second == Ui.State.WARN } -> "中"
            else -> "低"
        }
        riskText.text = buildString {
            append("暴露风险（可观测面，非效果评估）：$worst")
            if (mockActive) append(" · 档0/1/2 的卫星视图是结构性缺口（GnssStatus 在系统侧，本层改不了）")
            if (notes.isNotEmpty()) append(" · 台账 ${notes.size} 条见下方")
        }
    }

    // ---------------------------------------------------------------- status

    private fun refreshStatus() {
        val last = prefs.getString(KEY_LAST, "无")
        val label = prefs.getString(KEY_LAST_LABEL, "")
        val fixes = readback()
        Config.refresh()
        renderChips(fixes.any { "mock=true" in it })
        renderStatusBar(fixes.any { "mock=true" in it }, label ?: "")

        val mismatch = Config.enabled && last != "无" &&
            (abs(Config.centerLat - lastLat()) > 1e-3 || abs(Config.centerLng - lastLng()) > 1e-3)
        renderRisk(fixes.any { "mock=true" in it }, mismatch)

        statusText.text = buildString {
            append("当前坐标（本 App 读回）\n")
            fixes.forEach { append("  $it\n") }
            if (mismatch) {
                append("\n⚠ App 选点与模块配置不一致\n")
                append("  App : $label $last\n")
                append("  模块: ${Config.label} ${"%.5f".format(Config.centerLat)},${"%.5f".format(Config.centerLng)}\n")
                append("  档 3 会用模块那份覆盖，所以目标 App 可能仍显示模块里的点。\n")
            }
            append("\n模块配置（${Config.DEFAULT_PATH}）\n")
            append("  开关=${onOff(Config.enabled)} 全局=${onOff(Config.global)} 卫星=${onOff(Config.gnssSpoof)} ")
            append("探针=${onOff(Config.probe)}\n")
            append("  致盲 WiFi=${Config.blindWifi.render()} 基站=${Config.blindCell.render()} ")
            append("WiFi伪造=${onOff(Config.forgeWifi)}\n")
            append("  栈清洗=${onOff(Config.stackClean)} 传感器冻结=${onOff(Config.sensorFreeze)} ")
            append("GMS接管=${onOff(Config.gmsSpoof)} 包隐藏=${Config.hidePkgs.size}个\n")
            append("  路线=${if (Config.routeActive) "运行中 ${Config.routeWaypoints.size}点 @${Config.speedMps}m/s" else "未配置"}")
            append("  独立点位=${Config.appPoints.size}个\n")
            append("  作用包名: ${if (Config.targets.isEmpty()) "LSPosed 作用域内全部" else Config.targets.joinToString()}\n")
            append("  读回: ${Config.lastError ?: "正常（写入于 ${Config.lastLoadedAgo}ms 前）"}\n")
            // Measured on 2026-10-27: this is the one switch that moves Amap's blue dot, and the
            // hooks that implement it are installed per process, so it needs an app restart.
            append("  致盲这俩是高德唯一认的开关（它自己算路，不读系统定位）；开关改动要重启目标 App 才生效\n")
            append("\n把 App 选点同步给模块（二选一）\n")
            append("  PC  : python tools/mockloc.py sync\n")
            append("  免PC: 刷 mockloc-root.zip，模块循环会自己搬\n")
            val notes = Diagnostics.snapshot()
            if (notes.isNotEmpty()) {
                append("\n本进程挂钩记录（目标进程里的同类记录看 logcat 的 mockloc tag）\n")
                notes.forEach { append("  $it\n") }
            }
            append("\n注：以上只反映本 App 读到的值。目标 App 认不认，要在它里面主动发起一次定位请求、看返回坐标\n")
        }
    }

    /**
     * The two facts the no-root tier actually depends on, read fresh every time: the app-op grant
     * (a reinstall clears it) and whether 开发者选项 is even reachable on this ROM.
     */
    private fun renderAppOpChips() {
        appOpChipRow.removeAllViews()
        val allowed = Tiers.mockAppOpAllowed(this)
        appOpChipRow.addView(
            ui.chip("虚拟定位授权", if (allowed) Ui.State.OK else Ui.State.BAD),
        )
        appOpChipRow.addView(
            ui.chip(
                "开发者选项",
                if (Tiers.developerOptionsEnabled(this)) Ui.State.OK else Ui.State.WARN,
            ),
        )
        appOpChipRow.addView(
            ui.chip("本 App 定位权限", if (hasLocationGrant()) Ui.State.OK else Ui.State.BAD),
        )
    }

    private fun hasLocationGrant(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun renderChips(mockActive: Boolean) {
        tierChips.removeAllViews()
        // Wording has to stay inside what this process can actually see: a mock=true read-back says
        // "some test provider is live", not whether 档1 or 档2 put it there - only the config file's
        // GLOBAL flag says a root module is the one maintaining it.
        tierChips.addView(ui.chip("系统 mock 生效", if (mockActive) Ui.State.OK else Ui.State.OFF))
        tierChips.addView(ui.chip("root 模块维持中", if (Config.global) Ui.State.OK else Ui.State.OFF))
        // A grey 档3 pill means "this process is not in the module's scope", which is the normal
        // configuration - not "the module is broken". Conflating those two cost a debugging round.
        tierChips.addView(ui.chip("档3 无痕(本进程)", if (selfCheck()) Ui.State.OK else Ui.State.OFF))
        if (!selfCheck()) tierChips.addView(ui.chip("本 App 未入作用域＝正常", Ui.State.OFF))
    }

    /** One glanceable verdict, colored before it is worded (same rule as the chips). */
    private fun renderStatusBar(mockActive: Boolean, label: String) {
        val dark = ui.night
        val (text, fg, bg) = when {
            mockActive -> Triple(
                "● 模拟中${if (label.isNotBlank()) " · $label" else ""} · 点此看诊断",
                if (dark) 0xFF81C995.toInt() else 0xFF0F6B32.toInt(),
                if (dark) 0xFF12271C.toInt() else 0xFFE8F6EC.toInt(),
            )
            Config.enabled -> Triple(
                "● 模块配置在生效，但本 App 读回无 mock（按App 模式或配置不一致）",
                if (dark) 0xFFFDD663.toInt() else 0xFF8A5A00.toInt(),
                if (dark) 0xFF2B2213.toInt() else 0xFFFFF4E0.toInt(),
            )
            else -> Triple("○ 未注入 · 点地图选点后按「开始模拟」",
                if (dark) 0xFF9AA0A6.toInt() else 0xFF6B6B6B.toInt(),
                if (dark) 0xFF1E2024.toInt() else 0xFFF2F2F2.toInt())
        }
        statusLine.text = text
        statusLine.setTextColor(fg)
        statusLine.setBackgroundColor(bg)
    }

    private fun lastLat(): Double =
        prefs.getString(KEY_LAST, "")?.substringBefore(',')?.toDoubleOrNull() ?: 0.0

    private fun lastLng(): Double =
        prefs.getString(KEY_LAST, "")?.substringAfter(',', "")?.toDoubleOrNull() ?: 0.0

    private fun onOff(value: Boolean) = if (value) "开" else "关"

    // Both accessors are shown on purpose: older apps still call the deprecated one, and the whole
    // point of this screen is to see which flag a target app would be reading.
    @Suppress("DEPRECATION")
    private fun readback(): List<String> {
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager
        return listOf("gps", "fused", "network", "passive").map { name ->
            val line = runCatching {
                val loc = lm?.getLastKnownLocation(name)
                if (loc == null) "null"
                else "${"%.6f".format(loc.latitude)},${"%.6f".format(loc.longitude)} acc=${loc.accuracy} mock=${loc.isFromMockProvider}"
            }.getOrElse { "需授权（${it.javaClass.simpleName}）" }
            "$name : $line"
        }
    }

    // ---------------------------------------------------------------- actions

    /** One tappable row per geocode hit, rendered inline (see searchPage for why not a ListView). */
    private fun renderHits() {
        hitRows.removeAllViews()
        hits.forEachIndexed { index, hit ->
            hitRows.addView(Button(this).apply {
                text = hit.label
                isAllCaps = false
                gravity = Gravity.START
                setBackgroundColor(0x00000000)
                setTextColor(0xFF1F6FEB.toInt())
                setOnClickListener { onPickHit(index) }
            })
        }
        if (hits.isEmpty()) {
            hitRows.addView(ui.body("还没有结果：填好 key 后搜一个地点"))
        }
    }

    private fun runSearch() {
        val key = keyField.text.toString().trim()
        val query = queryField.text.toString().trim()
        if (key.isEmpty()) return toast("先填并保存高德 key")
        if (query.isEmpty()) return toast("先输入地点")
        toast("搜索中…")
        thread {
            try {
                val found = AmapSearch.geocode(key, query)
                runOnUiThread {
                    hits = found
                    renderHits()
                    if (found.isEmpty()) toast("没有解析结果")
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("失败：${t.message}") }
            }
        }
    }

    private fun onPickHit(index: Int) {
        val hit = hits.getOrNull(index) ?: return
        val w = CoordTransform.gcj02ToWgs84(hit.latGcj, hit.lngGcj)
        centerOn(w[0], w[1])
        inject(w[0], w[1], hit.name)
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
        val w = CoordTransform.gcj02ToWgs84(point.latitude, point.longitude)
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
        mapInfo.text = "GCJ ${"%.6f".format(point.latitude)},${"%.6f".format(point.longitude)}\n" +
            "WGS  ${"%.6f".format(w[0])},${"%.6f".format(w[1])}  ← 注入用这组"
    }

    // ---------------------------------------------------------------- route page (2.3)

    /** 路线 tab: tap waypoints on the map, pick a speed, press start - every tier shares the epoch
     *  anchor, so the route reads as one movement no matter which tier carries it. */
    private fun routePage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        routeMap = MapView(this)
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
            speedRow.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(11f)
                setOnClickListener { routeSpeedField.setText(value.toString()) }
            })
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
        runRow.addView(Button(this).apply {
            text = "开始路线"
            isAllCaps = false
            setOnClickListener { startRoute() }
        }, ui.fill())
        runRow.addView(Button(this).apply {
            text = "停止路线"
            isAllCaps = false
            setOnClickListener {
                bridge.stopRoute()
                toast("路线已停：注入点回到静态中心")
                renderRouteWaypoints()
                refreshStatus()
            }
        }, ui.fill())
        controls.addView(runRow)
        val ioRow = ui.row()
        ioRow.addView(Button(this).apply {
            text = "导入GPX"
            isAllCaps = false
            setOnClickListener {
                runCatching {
                    routeGpxUri = null
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*"),
                        REQUEST_GPX_IMPORT,
                    )
                }.onFailure { toast("这个系统不给打开文件选择器：${it.message}") }
            }
        }, ui.fill())
        ioRow.addView(Button(this).apply {
            text = "导出GPX"
            isAllCaps = false
            setOnClickListener {
                if (routeWaypoints.size < 2) return@setOnClickListener toast("没有可导出的航点")
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
            }
        }, ui.fill())
        ioRow.addView(Button(this).apply {
            text = "清空"
            isAllCaps = false
            setOnClickListener {
                routeWaypoints.clear()
                renderRouteWaypoints()
            }
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
                val g = CoordTransform.wgs84ToGcj02(pt.lat, pt.lng)
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
            val alt = Elevation.resolve(this@MainActivity, first.lat, first.lng)
                ?: MockService.DEFAULT_ALT
            val useRoot = RootInjector.rootAvailable()
            runOnUiThread {
                bridge.setRoute(routeWaypoints.toList(), speed, loop, started)
                if (useRoot) {
                    bridge.publish(first.lat, first.lng, label, global = true)
                    val published = RootInjector.publishConfig(this@MainActivity, bridge.render())
                    RootInjector.apply(
                        first.lat, first.lng, bridge.injectionAcc(),
                        DEFAULT_PROVIDERS.split(','), alt,
                    )
                    toast(if (published) "路线启动（root 档2，模块循环推进）" else "root 配置写入失败")
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
                        val g = CoordTransform.wgs84ToGcj02(pts[0].lat, pts[0].lng)
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

    private fun promptAppPoint(pkg: String, label: String) {
        val current = bridge.appPoints()[pkg]
        val last = prefs.getString(KEY_LAST, "")
        val options = mutableListOf<String>()
        if (!last.isNullOrBlank() && last != "无") options.add("用最近注入的点 $last")
        if (current != null) {
            options.add("清除独立点位（当前 ${"%.5f".format(current[0])},${"%.5f".format(current[1])}）")
        }
        if (options.isEmpty()) {
            return toast("先注入一个点，再来给这个 App 指定独立点位")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("$label 的独立点位")
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which].startsWith("用最近")) {
                    val parts = last!!.split(',')
                    val la = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                    val ln = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                    if (la == null || ln == null) {
                        toast("最近点位读不懂：$last")
                    } else {
                        bridge.setAppPoint(pkg, la, ln)
                        toast("已设 $label 独立点 $last；重新注入生效，并重启该 App")
                    }
                } else {
                    bridge.clearAppPoint(pkg)
                    toast("已清除 $label 的独立点位")
                }
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun injectPicked(fallbackName: String) {
        val w = pickedWgs ?: return toast("先点一下地图")
        inject(w[0], w[1], fallbackName)
    }

    private fun inject(lat: Double, lng: Double, label: String) {
        prefs.edit()
            .putString(KEY_LAST_LABEL, label)
            .putString(KEY_LAST, "%.7f,%.7f".format(lat, lng))
            .apply()
        toast("已注入 $label")
        thread {
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
                    val out = RootInjector.apply(
                        lat, lng, bridge.injectionAcc(),
                        DEFAULT_PROVIDERS.split(','), alt,
                    )
                    if (published) toast("已注入（root 直写，档2）") else toast("root 配置写入失败：${out.take(60)}")
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
        }
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

    private fun centerOn(lat: Double, lng: Double) {
        map.controller.setCenter(GeoPoint(lat, lng))
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "settings"
        const val KEY_AMAP = "amap_key"
        const val KEY_LAST = "last_injected"
        const val KEY_LAST_LABEL = "last_label"
        const val KEY_WIZARD_SKIP = "wizard_skip"
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        const val REQUEST_GPX_IMPORT = 71
        const val REQUEST_GPX_EXPORT = 72
        val TABS = listOf("搜索", "地图", "路线", "收藏", "策略", "状态")
    }
}
