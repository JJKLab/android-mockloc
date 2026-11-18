package com.mockloc.pages

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mockloc.AmapSearch
import org.osmdroid.events.MapEventsReceiver
import com.mockloc.BaiduOverlay
import com.mockloc.CoordTransform
import com.mockloc.Hit
import com.mockloc.MainActivity
import com.mockloc.MapSource
import com.mockloc.Place
import com.mockloc.Ui
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 地图页 + 搜索浮层 + 收藏页（阶段四拆分第 3 步）。
 *
 * 地图是全局单例（osmdroid 的 MapView 持有瓦片线程与位图缓存），所以这里仍由 MainActivity 持有
 * [MainActivity.map] / [MainActivity.mapSrc]；页面类只负责搭建与交互。
 */
internal class MapPage(private val a: MainActivity) {

    private val ui get() = a.ui
    private val prefs get() = a.prefs

    // ---------------------------------------------------------------- 构建

    fun build(): View {
        // Google Maps 式布局：全屏地图 + 顶部悬浮搜索条 + 底部选点面板/搜索浮层。
        val page = FrameLayout(a)
        a.map = org.osmdroid.views.MapView(a)
        // 百度/谷歌的瓦片端点会拒非浏览器 UA（osmdroid 默认 UA 直接 403，实测百度全空）：
        // 全局换成浏览器 UA，高德不检查 UA 不受影响。
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue =
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            // 只在 debug 构建开：release 里开着会往 logcat 刷瓦片 provider/下载器细节（P2-2）。
            isDebugTileProviders = a.isDebuggable()
            isDebugMapTileDownloader = a.isDebuggable()
        }
        a.mapSrc = MapSource.entries.firstOrNull {
            it.name == prefs.getString(KEY_MAP_SOURCE, "AMAP")
        } ?: MapSource.AMAP
        a.map.setTileSource(a.mapSrc.tileSource())
        a.map.setMultiTouchControls(true)
        a.map.controller.setZoom(11.0)
        a.map.post { centerOn(31.2304, 121.4737) }
        a.baiduOverlay = BaiduOverlay(a.map).apply { active = a.mapSrc == MapSource.BAIDU }
        a.map.getOverlays().add(a.baiduOverlay)
        a.map.getOverlays().add(org.osmdroid.views.overlay.MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint): Boolean {
                onMapTap(p)
                return true
            }

            override fun longPressHelper(p: org.osmdroid.util.GeoPoint): Boolean = false
        }))
        page.addView(a.map, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // 顶部悬浮搜索条（MD3 search bar）：点击展开搜索浮层。
        a.searchBar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.pillSurface()
            elevation = ui.dp(4).toFloat()
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            addView(ui.icon(com.mockloc.R.drawable.ic_search, ui.hintColor()))
            addView(TextView(a).apply {
                text = "搜索地点 · 粘贴坐标"
                textSize = 14f
                setTextColor(ui.hintColor())
                setPadding(ui.dp(10), 0, 0, 0)
            })
            setOnClickListener { openSearch() }
        }
        page.addView(a.searchBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(ui.dp(12), ui.dp(12), ui.dp(12), 0) })

        // 源切换 chips：右上角悬浮。切源 = 换瓦片 + 全部覆盖物按新坐标系重投。
        a.sourceChips = ui.row().apply {
            background = ui.pillSurface()
            elevation = ui.dp(4).toFloat()
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        }
        renderSourceChips()
        page.addView(a.sourceChips, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, ui.dp(64), ui.dp(10), 0) })

        // 底部选点面板：点图后浮起，坐标双系 + 动作。
        a.pickGcjView = TextView(a).apply { textSize = 13f; setTextColor(ui.bodyColor()) }
        a.pickWgsView = TextView(a).apply {
            textSize = 13f; setTextColor(ui.bodyColor()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        a.pickPanel = ui.cardHigh().apply {
            addView(TextView(a).apply {
                text = "选点"
                textSize = 16f
                setTextColor(ui.titleColor())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(a.pickGcjView)
            addView(a.pickWgsView)
            val actions = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            actions.addView(ui.button("注入", Ui.Btn.PRIMARY) { injectPicked("地图点选") }, ui.fill())
            actions.addView(ui.button("收藏这个点") {
                val w = a.pickedWgs ?: return@button a.toast("先点一下地图")
                promptPlace("点${"%.4f".format(w[0])}", w[0], w[1], null)
            }, ui.fill())
            addView(actions)
        }
        a.pickPanel.visibility = View.GONE
        page.addView(a.pickPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(10)) })

        // 搜索浮层：占地图底部 ~55%，内含搜索 + 粘贴 + key。
        a.searchSheet = buildSearchSheet()
        a.searchSheet.visibility = View.GONE
        page.addView(a.searchSheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (a.resources.displayMetrics.heightPixels * 0.55f).toInt(),
            Gravity.BOTTOM,
        ))
        return page
    }

    /** MD3 底部浮层外观：28dp 顶圆角 + 顶栏拖把条。 */
    private fun sheetCard(): LinearLayout = LinearLayout(a).apply {
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
        val scroll = ScrollView(a)
        val column = ui.column()
        scroll.addView(column)
        val head = ui.row()
        head.addView(TextView(a).apply {
            text = "搜索与坐标"
            textSize = 16f
            setTextColor(ui.titleColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, ui.fill())
        head.addView(ui.icon(com.mockloc.R.drawable.ic_close, ui.hintColor()).apply {
            setPadding(ui.dp(8), ui.dp(8), 0, 0)
            setOnClickListener { closeSearch() }
        })
        sheet.addView(head)
        sheet.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        column.addView(ui.sectionLabel("搜地点（高德，结果即注入）"))
        val keyField2 = ui.editText("高德 Web服务 key（存本机私有 prefs）")
        a.keyField = keyField2
        keyField2.setText(prefs.getString(KEY_AMAP, ""))
        column.addView(keyField2)
        column.addView(ui.button("保存 key") {
            prefs.edit().putString(KEY_AMAP, keyField2.text.toString().trim()).apply()
            a.toast("key 已存到本机私有配置")
        })
        val goRow = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        a.queryField = ui.editText("地点，如 东方明珠")
        goRow.addView(a.queryField, ui.fill())
        goRow.addView(ui.button("搜索", Ui.Btn.PRIMARY) { runSearch() })
        column.addView(goRow)
        a.hitRows = ui.column()
        column.addView(a.hitRows)

        column.addView(ui.divider())
        column.addView(ui.sectionLabel("粘贴坐标（双系预览，贴错当场可见）"))
        val pasteRow = ui.row()
        a.pasteField = ui.editText("31.239703,121.499733")
        a.pasteField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updatePastePreview()
        })
        pasteRow.addView(a.pasteField, ui.fill())
        a.pasteGcj = prefs.getBoolean(KEY_PASTE_GCJ, true)
        val pasteToggle = Button(a)
        pasteToggle.text = if (a.pasteGcj) "GCJ-02" else "WGS-84"
        ui.styleSegment(pasteToggle, selected = false)
        pasteToggle.setOnClickListener {
            a.pasteGcj = !a.pasteGcj
            prefs.edit().putBoolean(KEY_PASTE_GCJ, a.pasteGcj).apply()
            pasteToggle.text = if (a.pasteGcj) "GCJ-02" else "WGS-84"
            ui.styleSegment(pasteToggle, selected = false)
            updatePastePreview()
        }
        pasteRow.addView(pasteToggle)
        column.addView(pasteRow)
        a.pastePreview = TextView(a).apply {
            textSize = 12f
            setTextColor(ui.bodyColor())
            setPadding(0, ui.dp(6), 0, 0)
        }
        column.addView(a.pastePreview)
        column.addView(ui.button("按这组坐标注入", Ui.Btn.PRIMARY) { injectPasted() })
        column.addView(ui.hint("高德/腾讯/百度网页抄的是 GCJ-02；Google Earth/GPS 工具抄的是 WGS-84"))
        updatePastePreview()
        return sheet
    }

    internal fun openSearch() {
        a.searchSheet.visibility = View.VISIBLE
        a.searchBar.visibility = View.GONE
        a.queryField.requestFocus()
    }

    internal fun closeSearch() {
        a.searchSheet.visibility = View.GONE
        a.searchBar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 收藏页

    fun placesPage(): View {
        val page = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val list = android.widget.ListView(a)
        a.placeAdapter = android.widget.ArrayAdapter(a, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = a.placeAdapter
        list.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, pos, _ ->
            val item = a.places.all().getOrElse(pos) { return@OnItemClickListener }
            a.inject(item.lat, item.lng, item.name)
        }
        list.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val item = a.places.all().getOrElse(pos) { return@OnItemLongClickListener true }
            // Long press used to delete outright, which is the one gesture nobody wants to repeat.
            android.app.AlertDialog.Builder(a)
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
        page.addView(list, a.weightParams())
        page.addView(ui.hint("点 = 注入    长按 = 改名称/备注、删除    收藏存的就是 WGS-84，不再转换"))
        return page
    }

    // ---------------------------------------------------------------- 搜索

    /** One tappable row per geocode hit, rendered inline (see searchPage for why not a ListView). */
    internal fun renderHits() {
        a.hitRows.removeAllViews()
        a.hits.forEachIndexed { index, hit ->
            val row = ui.row().apply {
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                background = ui.pillSurface()
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            }
            val textCol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(a).apply {
                text = hit.title
                textSize = 14f
                setTextColor(ui.bodyColor())
            })
            if (hit.sub.isNotEmpty() && hit.sub != "无地址") {
                textCol.addView(TextView(a).apply {
                    text = hit.sub
                    textSize = 12f
                    setTextColor(ui.hintColor())
                    maxLines = 1
                })
            }
            row.addView(textCol, ui.fill())
            row.setOnClickListener { onPickHit(index) }
            a.hitRows.addView(row)
        }
        if (a.hits.isEmpty()) {
            a.hitRows.addView(ui.body("还没有结果：填好 key 后搜一个地点"))
        }
    }

    /**
     * 双查合并：geocode（门牌号/区域，"XX路100号"型）+ POI 文本搜（"星巴克"型）。
     * 两个 API 各有所长，混着搜再按名称去重，就是高德 App 搜索框的形态。
     */
    internal fun runSearch() {
        val key = a.keyField.text.toString().trim()
        val query = a.queryField.text.toString().trim()
        if (key.isEmpty()) return a.toast("先填并保存高德 key")
        if (query.isEmpty()) return a.toast("先输入地点")
        a.toast("搜索中…")
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
                a.runOnUiThread {
                    a.hits = merged
                    renderHits()
                    errors.forEach { a.toast(it) }
                }
            } catch (t: Throwable) {
                a.runOnUiThread { a.toast("失败：${t.message}") }
            }
        }
    }

    /**
     * 搜索结果点选：
     * - POI → 直接选点（POI 坐标是精确入口）
     * - 门牌号/区域 → 拉周边 POI 列表（"XX号下的公司/餐厅"，和高德 App 同款体验），
     *   空结果（野外门牌）才直接选门牌坐标。
     */
    internal fun onPickHit(index: Int) {
        val hit = a.hits.getOrNull(index) ?: return
        if (hit.source != "geo") return pickHitPoint(hit)
        a.toast("正在取 ${hit.name.substringBefore('号')}附近的地点…")
        thread {
            val near = runCatching {
                AmapSearch.around(a.keyField.text.toString().trim(), hit.latGcj, hit.lngGcj)
            }.getOrDefault(emptyList())
            a.runOnUiThread {
                if (near.isNotEmpty()) {
                    showAround(hit, near)
                } else {
                    pickHitPoint(hit)
                }
            }
        }
    }

    /** 周边 POI 列表替换结果区（带"返回搜索"项），点一条选那个 POI 的精确入口。 */
    internal fun showAround(anchor: Hit, near: List<Hit>) {
        val w = CoordTransform.gcj02ToWgs84(anchor.latGcj, anchor.lngGcj)
        centerOn(w[0], w[1])
        a.aroundAnchor = anchor
        a.hits = near
        renderHits()
        val back = ui.button("↑ 返回「${anchor.name.substringBefore('号')}」的搜索结果", Ui.Btn.GHOST) {
            a.aroundAnchor = null
            a.hits = emptyList()
            runSearch()
        }
        a.hitRows.addView(back, 0)
    }

    internal fun pickHitPoint(hit: Hit) {
        val w = CoordTransform.gcj02ToWgs84(hit.latGcj, hit.lngGcj)
        a.pickedWgs = w
        closeSearch()
        val g = a.mapSrc.toDisplay(w[0], w[1])
        val existing = a.marker
        if (existing == null) {
            a.marker = org.osmdroid.views.overlay.Marker(a.map, a).also {
                it.setPosition(org.osmdroid.util.GeoPoint(g[0], g[1]))
                a.map.getOverlays().add(it)
            }
        } else {
            existing.setPosition(org.osmdroid.util.GeoPoint(g[0], g[1]))
        }
        a.pickGcjView.text = "${a.mapSrc.label}  ${"%.6f".format(g[0])},${"%.6f".format(g[1])}"
        a.pickWgsView.text = "WGS  ${"%.6f".format(w[0])},${"%.6f".format(w[1])}   ← 注入用这组"
        a.pickPanel.visibility = View.VISIBLE
        a.toast("已选「${hit.name}」——按注入生效")
    }

    internal fun updatePastePreview() {
        // 跨对象不能用 ::x.isInitialized，用 runCatching 探测 lateinit。
        val preview = runCatching { a.pastePreview }.getOrNull() ?: return
        preview.text = CoordTransform.dualPreview(a.pasteField.text.toString(), a.pasteGcj)
            ?: "格式：纬度,经度（经度在前也认）"
    }

    internal fun injectPicked(fallbackName: String) {
        val w = a.pickedWgs ?: return a.toast("先点一下地图")
        a.inject(w[0], w[1], fallbackName)
    }

    internal fun injectPasted() {
        val parts = a.pasteField.text.toString().split(',', '，')
        if (parts.size != 2) return a.toast("格式：纬度,经度")
        val x = parts[0].trim().toDoubleOrNull()
        val y = parts[1].trim().toDoubleOrNull()
        if (x == null || y == null) return a.toast("坐标读不懂")
        // Accept either order: what people copy off a Chinese map is usually 经度,纬度.
        val (lat, lng) = if (abs(x) <= 90.0) x to y else y to x
        val w = if (a.pasteGcj) CoordTransform.gcj02ToWgs84(lat, lng) else doubleArrayOf(lat, lng)
        centerOn(w[0], w[1])
        a.inject(w[0], w[1], "粘贴坐标")
    }

    internal fun onMapTap(point: org.osmdroid.util.GeoPoint) {
        val w = a.mapSrc.toWgs(point.latitude, point.longitude)
        a.pickedWgs = w
        val existing = a.marker
        if (existing == null) {
            a.marker = org.osmdroid.views.overlay.Marker(a.map, a).also {
                it.setPosition(point)
                a.map.getOverlays().add(it)
            }
        } else {
            existing.setPosition(point)
        }
        closeSearch()
        a.pickGcjView.text = "${a.mapSrc.label}  ${"%.6f".format(point.latitude)},${"%.6f".format(point.longitude)}"
        a.pickWgsView.text = "WGS  ${"%.6f".format(w[0])},${"%.6f".format(w[1])}   ← 注入用这组"
        a.pickPanel.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 地图源 / 中心

    /** 切换地图源：换瓦片源 + 把所有已画覆盖物按新坐标系重投（坐标真值全部存在 WGS，重画零损耗）。 */
    internal fun switchSource(src: MapSource) {
        if (src == a.mapSrc) return
        a.mapSrc = src
        prefs.edit().putString(KEY_MAP_SOURCE, src.name).apply()
        a.map.setTileSource(src.tileSource())
        a.updateInjectMarker()
        runCatching { a.baiduOverlay }.getOrNull()?.let { it.active = src == MapSource.BAIDU }
        a.pickedWgs?.let { w ->
            val g = src.toDisplay(w[0], w[1])
            a.marker?.setPosition(org.osmdroid.util.GeoPoint(g[0], g[1]))
        }
        a.lastLat().takeIf { it != 0.0 }?.let { centerOn(it, a.lastLng()) }
        renderSourceChips()
        a.toast("地图源 → ${src.label}（${src.hint}）")
    }

    internal fun renderSourceChips() {
        val chips = runCatching { a.sourceChips }.getOrNull() ?: return
        chips.removeAllViews()
        MapSource.entries.forEach { src ->
            chips.addView(ui.chip(src.label, if (src == a.mapSrc) Ui.State.OK else Ui.State.OFF).apply {
                setOnClickListener { switchSource(src) }
            })
        }
    }

    internal fun centerOn(lat: Double, lng: Double) {
        // 瓦片参考系随源变（高德 GCJ / 谷歌 WGS / 百度 BD09）：拿 WGS 值直接 setCenter 会在
        // 非谷歌源上停在真实选点旁几百米——注入值本身没错，显示参考系不同。
        val g = a.mapSrc.toDisplay(lat, lng)
        a.map.controller.setCenter(org.osmdroid.util.GeoPoint(g[0], g[1]))
    }

    // ---------------------------------------------------------------- 收藏

    internal fun reloadPlaces() {
        a.placeAdapter.clear()
        a.placeAdapter.addAll(a.places.all().map { it.label })
    }

    /**
     * Ask for a name and a note instead of inventing "点3782". A saved point with no note is
     * indistinguishable from the one 30 m away, which is the whole reason people stop using
     * favourites.
     */
    internal fun promptPlace(defaultName: String, lat: Double, lng: Double, existing: Place?) {
        val fields = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val nameField = EditText(a).apply {
            setText(existing?.name ?: defaultName)
            hint = "名称"
            isSingleLine = true
        }
        val noteField = EditText(a).apply {
            setText(existing?.note ?: "")
            hint = "备注（哪栋楼、几点打卡、给谁用）"
            isSingleLine = true
        }
        fields.addView(nameField)
        fields.addView(noteField)
        android.app.AlertDialog.Builder(a)
            .setTitle(if (existing == null) "收藏这个点" else "编辑收藏")
            .setView(fields)
            .setPositiveButton("保存") { _, _ ->
                val name = nameField.text.toString().trim().ifEmpty { defaultName }
                val note = noteField.text.toString().trim()
                val edited = existing?.let { old ->
                    a.places.all().indexOfFirst { it.name == old.name && it.lat == old.lat }
                } ?: -1
                if (edited >= 0) a.places.replaceAt(edited, Place(name, lat, lng, note)) else
                    a.places.add(Place(name, lat, lng, note))
                reloadPlaces()
                a.toast(if (existing == null) "已收藏 $name" else "已更新 $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    internal fun confirmDelete(place: Place) {
        android.app.AlertDialog.Builder(a)
            .setTitle("删除 ${place.name}")
            .setMessage(place.label)
            .setPositiveButton("删除") { _, _ ->
                a.places.all().indexOfFirst { it.name == place.name && it.lat == place.lat }
                    .takeIf { it >= 0 }
                    ?.let { a.places.removeAt(it) }
                reloadPlaces()
            }
            .setNegativeButton("留下", null)
            .show()
    }

    private companion object {
        const val KEY_AMAP = "amap_key"
        const val KEY_MAP_SOURCE = "map_source"
        const val KEY_PASTE_GCJ = "paste_gcj"
    }
}
