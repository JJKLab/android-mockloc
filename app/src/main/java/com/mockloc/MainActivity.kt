package com.mockloc

import android.app.Activity
import android.content.Context
import android.content.Intent
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

/**
 * Four tabs, four jobs: search, tap the map, switch between saved points, and read what the device
 * is actually reporting. Views rather than Compose on purpose - this file is compiled and checked
 * locally against android.jar and osmdroid's own classes, which is the only verification available
 * to a project that is never built on a dev machine.
 */
class MainActivity : Activity() {

    /** Rewritten by the module; false means LSPosed never loaded it into this process. */
    fun selfCheck(): Boolean = false

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val places by lazy { Places(this) }

    private lateinit var flipper: ViewFlipper
    private lateinit var map: MapView
    private lateinit var mapInfo: TextView
    private lateinit var statusText: TextView
    private lateinit var keyField: EditText
    private lateinit var queryField: EditText
    private lateinit var hitAdapter: ArrayAdapter<String>
    private lateinit var placeAdapter: ArrayAdapter<String>

    private var hits: List<Hit> = emptyList()
    private var pickedWgs: DoubleArray? = null
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().setUserAgentValue(packageName)
        setContentView(buildUi())
        map.setTileSource(AmapTiles())
        map.setMultiTouchControls(true)
        map.controller.setZoom(11.0)
        // setCenter before the first layout is dropped by osmdroid, which leaves the view parked
        // at 0,0 in the Gulf of Guinea.
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
            // targetSdk 35+ forces edge-to-edge on Android 15/16: without this the tab row is
            // drawn underneath the status bar and the app looks like it has no navigation at all.
            fitsSystemWindows = true
        }
        flipper = ViewFlipper(this)
        // ViewFlipper is a FrameLayout: passing LinearLayout.LayoutParams here gets converted and
        // the conversion keeps height=0 while dropping weight, so every page measured zero pixels
        // tall and the app looked like a blank screen.
        flipper.addView(searchPage(), pageParams())
        flipper.addView(mapPage(), pageParams())
        flipper.addView(placesPage(), pageParams())
        flipper.addView(statusPage(), pageParams())

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TABS.forEachIndexed { index, title ->
            tabRow.addView(Button(this).apply {
                text = title
                isAllCaps = false
                // Explicit colours: the OEM default button style rendered these with no visible
                // background or text, which made a zero-height content bug look like a dead app.
                setBackgroundColor(0xFF1F6FEB.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 28, 0, 28)
                setOnClickListener { flipper.displayedChild = index }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabRow)
        root.addView(flipper, weightParams())
        return root
    }

    private fun weightParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    private fun pageParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun searchPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyField = EditText(this).apply {
            hint = "高德 Web服务 key"
            setText(prefs.getString(KEY_AMAP, ""))
            isSingleLine = true
        }
        queryField = EditText(this).apply {
            hint = "地点，如 东方明珠"
            isSingleLine = true
        }
        val goRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        goRow.addView(queryField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        goRow.addView(Button(this).apply {
            text = "搜索"
            isAllCaps = false
            setOnClickListener { runSearch() }
        })
        val saveRow = Button(this).apply {
            text = "保存 key"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putString(KEY_AMAP, keyField.text.toString().trim()).apply()
                toast("key 已存到本机私有配置")
            }
        }
        val list = ListView(this)
        hitAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = hitAdapter
        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> onPickHit(pos) }
        page.addView(keyField)
        page.addView(goRow)
        page.addView(saveRow)
        page.addView(list, weightParams())
        page.addView(hint("点一条即注入；结果坐标是高德 GCJ-02，注入前自动反算 WGS-84"))
        return page
    }

    private fun mapPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        map = MapView(this)
        mapInfo = TextView(this).apply { text = "点地图任意位置选点" }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "注入"
            isAllCaps = false
            setOnClickListener { injectPicked("地图点选") }
        })
        actions.addView(Button(this).apply {
            text = "收藏"
            isAllCaps = false
            setOnClickListener {
                val w = pickedWgs ?: return@setOnClickListener toast("先点一下地图")
                places.add(Place("点${System.currentTimeMillis() % 10000}", w[0], w[1], "地图点选"))
                reloadPlaces()
                toast("已收藏")
            }
        })
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
            places.removeAt(pos)
            reloadPlaces()
            true
        }
        page.addView(list, weightParams())
        page.addView(hint("点=注入   长按=删除"))
        return page
    }

    private fun statusPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusText = TextView(this)
        val scroll = ScrollView(this)
        scroll.addView(statusText)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "刷新"
            isAllCaps = false
            setOnClickListener { refreshStatus() }
        })
        actions.addView(Button(this).apply {
            text = "停止注入"
            isAllCaps = false
            setOnClickListener {
                startService(Intent(this@MainActivity, MockService::class.java)
                    .putExtra(MockService.EXTRA_CMD, "stop"))
                refreshStatus()
            }
        })
        page.addView(scroll, weightParams())
        page.addView(actions)
        return page
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(16, 8, 16, 8)
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
                    if (found.isEmpty()) toast("没有解析结果") else flipper.displayedChild = 0
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
        startForegroundService(
            Intent(this, MockService::class.java)
                .putExtra(MockService.EXTRA_CMD, "start")
                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                .putExtra(MockService.EXTRA_LAT, "%.7f".format(lat))
                .putExtra(MockService.EXTRA_LNG, "%.7f".format(lng))
                .putExtra(MockService.EXTRA_ACC, "5.0")
                .putExtra(MockService.EXTRA_RADIUS, "0")
                .putExtra(MockService.EXTRA_INTERVAL, "15")
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

    private fun refreshStatus() {
        val last = prefs.getString(KEY_LAST, "无")
        val label = prefs.getString(KEY_LAST_LABEL, "")
        val fixes = readback()
        Config.refresh()
        statusText.text = buildString {
            appendLine("系统注入 : ${if (fixes.any { "mock=true" in it }) "生效中" else "未注入"}")
            appendLine("无痕模块 : ${if (selfCheck()) "已加载" else "未加载（需 LSPosed）"}")
            appendLine("最近 : $label $last")
            appendLine("----")
            fixes.forEach { appendLine(it) }
            appendLine("----")
            // What the module in every other process is going to read next time it refreshes.
            appendLine("模块开关 : ${onOff(Config.enabled)} 全局=${onOff(Config.global)}")
            appendLine("作用包名 : ${if (Config.targets.isEmpty()) "LSPosed 作用域内全部" else Config.targets.joinToString()}")
            appendLine("伪造卫星 : ${onOff(Config.gnssSpoof)}  致盲WiFi/基站 : ${onOff(Config.blindWifi)}/${onOff(Config.blindCell)}")
            appendLine("配置来源 : ${Config.DEFAULT_PATH}")
            appendLine(
                if (Config.lastError == null) "读取正常（写入于 ${Config.lastLoadedAgo}ms 前）"
                else "读取失败：${Config.lastError}"
            )
            val notes = Diagnostics.snapshot()
            if (notes.isNotEmpty()) {
                appendLine("----")
                appendLine("本进程挂钩记录（目标进程的同类记录看 logcat 的 mockloc tag）：")
                notes.forEach { appendLine("  $it") }
            }
            appendLine("----")
            // Deliberately not claiming anything about other apps: this page only measures what
            // *this* process reads. Whether a target app honours the fix is a separate test.
            appendLine("注：以上只反映本 App 读到的值。目标 App 认不认，要在它里面主动发起一次定位请求、看返回坐标")
        }
    }

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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PREFS = "settings"
        const val KEY_AMAP = "amap_key"
        const val KEY_LAST = "last_injected"
        const val KEY_LAST_LABEL = "last_label"
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        val TABS = listOf("搜索", "地图", "收藏", "状态")
    }
}
