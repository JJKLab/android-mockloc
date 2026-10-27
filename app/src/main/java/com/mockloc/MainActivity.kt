package com.mockloc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    private lateinit var statusText: TextView
    private lateinit var tierChips: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var queryField: EditText
    private lateinit var pasteField: EditText
    private lateinit var policyList: ListView
    private lateinit var appOpChipRow: LinearLayout
    private lateinit var hitAdapter: ArrayAdapter<String>
    private lateinit var placeAdapter: ArrayAdapter<String>

    private var hits: List<Hit> = emptyList()
    private var pickedWgs: DoubleArray? = null
    private var marker: Marker? = null
    private var pasteGcj = true
    private var policyPackages: List<String> = emptyList()
    private var policyLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().setUserAgentValue(packageName)
        setContentView(buildUi())
        map.setTileSource(AmapTiles())
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
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        reloadPlaces()
        refreshStatus()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    // ---------------------------------------------------------------- ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // targetSdk 35+ forces edge-to-edge on Android 15/16: without this the tab row is drawn
            // under the status bar and the app looks like it has no navigation at all.
            fitsSystemWindows = true
            setBackgroundColor(0xFFF4F5F7.toInt())
        }
        root.addView(header())

        flipper = ViewFlipper(this)
        // ViewFlipper is a FrameLayout: passing LinearLayout.LayoutParams here gets converted and
        // the conversion keeps height=0 while dropping weight, which is how a whole page measured
        // zero pixels and the app looked blank.
        flipper.addView(searchPage(), pageParams())
        flipper.addView(mapPage(), pageParams())
        flipper.addView(placesPage(), pageParams())
        flipper.addView(policyPage(), pageParams())
        flipper.addView(statusPage(), pageParams())

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
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
                setOnClickListener {
                    flipper.displayedChild = index
                    // Enumerating packages and reading the app-op are both too slow/noisy to do on
                    // every keystroke, so the pages that need them fill in on first show.
                    if (index == TABS.indexOf("策略")) {
                        renderAppOpChips()
                        if (!policyLoaded) {
                            policyLoaded = true
                            loadPolicyList()
                        }
                    }
                    if (index == TABS.lastIndex) refreshStatus()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(ui.dp(3), ui.dp(6), ui.dp(3), ui.dp(4))
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
        hitAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        searchCard.addView(ListView(this).apply {
            adapter = hitAdapter
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(200))
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> onPickHit(pos) }
        })
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
                "「自己算路」的 App 需要。没勾的 App 不受影响。\n" +
                "标记：①系统provider ②抽空输入 ③进进程 未测",
        ))
        policyList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(300))
        }
        policyList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_multiple_choice, mutableListOf<String>(),
        )
        policy.addView(policyList)
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
                for (i in 0 until policyList.count) policyList.setItemChecked(i, false)
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
        val installed = runCatching {
            // One binder call for the whole list: resolving permissions per package took ~600
            // round trips and froze the tab on first open.
            packageManager.getInstalledPackages(
                android.content.pm.PackageManager.GET_PERMISSIONS,
            )
                .mapNotNull { info ->
                    val permissions = info.requestedPermissions
                    if (permissions == null || !(permissions.contains(
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) || permissions.contains(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    ) {
                        return@mapNotNull null
                    }
                    val pkg = info.packageName
                    if (pkg == packageName) return@mapNotNull null
                    pkg to runCatching {
                        packageManager.getApplicationLabel(info.applicationInfo!!).toString()
                    }.getOrDefault(pkg)
                }
                .sortedBy { it.second }
        }.getOrElse { emptyList() }
        policyPackages = installed.map { it.first }
        val chosen = bridge.blindScope()
        (policyList.adapter as ArrayAdapter<String>).apply {
            clear()
            addAll(installed.map { (pkg, name) -> "$name ${Tiers.needsOf(pkg).label.take(1)}  $pkg" })
            notifyDataSetChanged()
        }
        installed.forEachIndexed { index, (pkg, _) ->
            policyList.setItemChecked(index, chosen.covers(pkg))
        }
    }

    private fun saveBlindScope() {
        val checked = policyList.checkedItemPositions
        val picked = (0 until checked.size()).asSequence()
            .filter { checked.valueAt(it) }
            .mapNotNull { policyPackages.getOrNull(checked.keyAt(it)) }
            .toList()
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
                refreshStatus()
            }
        }, ui.fill())
        page.addView(tierChips)
        page.addView(ScrollView(this).apply { addView(statusText) }, weightParams())
        page.addView(actions)
        return page
    }

    // ---------------------------------------------------------------- status

    private fun refreshStatus() {
        val last = prefs.getString(KEY_LAST, "无")
        val label = prefs.getString(KEY_LAST_LABEL, "")
        val fixes = readback()
        Config.refresh()
        renderChips(fixes.any { "mock=true" in it })

        val mismatch = Config.enabled && last != "无" &&
            (abs(Config.centerLat - lastLat()) > 1e-3 || abs(Config.centerLng - lastLng()) > 1e-3)

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
            append("  致盲 WiFi=${Config.blindWifi.render()} 基站=${Config.blindCell.render()}\n")
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
                    hitAdapter.clear()
                    hitAdapter.addAll(found.map { it.label })
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

    private fun injectPicked(fallbackName: String) {
        val w = pickedWgs ?: return toast("先点一下地图")
        inject(w[0], w[1], fallbackName)
    }

    private fun inject(lat: Double, lng: Double, label: String) {
        prefs.edit()
            .putString(KEY_LAST_LABEL, label)
            .putString(KEY_LAST, "%.7f,%.7f".format(lat, lng))
            .apply()
        // Same point into the module's channel, so 档 3 stops disagreeing with 档 1 (ModuleBridge).
        bridge.publish(lat, lng, label)
        startForegroundService(
            Intent(this, MockService::class.java)
                .putExtra(MockService.EXTRA_CMD, "start")
                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                .putExtra(MockService.EXTRA_LAT, "%.7f".format(lat))
                .putExtra(MockService.EXTRA_LNG, "%.7f".format(lng))
                .putExtra(MockService.EXTRA_ACC, "5.0")
                .putExtra(MockService.EXTRA_RADIUS, "0")
                // 1s, not 15s: a stale fix is what makes an app go re-locate through its own path.
                .putExtra(MockService.EXTRA_INTERVAL, "1")
        )
        toast("已注入 $label")
        refreshStatus()
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
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        val TABS = listOf("搜索", "地图", "收藏", "策略", "状态")
    }
}
