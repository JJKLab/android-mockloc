package com.mockloc.pages

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mockloc.AmapTiles
import org.osmdroid.events.MapEventsReceiver
import com.mockloc.CoordTransform
import com.mockloc.Elevation
import com.mockloc.Gpx
import com.mockloc.MainActivity
import com.mockloc.MockService
import com.mockloc.RootInjector
import com.mockloc.Route
import com.mockloc.Ui
import kotlin.concurrent.thread

/**
 * 路线页（阶段四拆分第 3 步）：地图上点航点、选速度、走起来。
 *
 * 路线的推进由 [com.mockloc.Config] + MockService 的 tick 完成，这里只负责编辑与启动。
 */
internal class RoutePage(private val a: MainActivity) {

    private val ui get() = a.ui
    private val bridge get() = a.bridge

    /** Swap the placeholder for the real route page on first tab show - one MapView less on the
     *  cold-start path, and saved waypoints (if any) come back with it. */
    internal fun buildRoutePage() {
        val index = TABS.indexOf("路线")
        a.routePageBuilt = true
        // A saved route survives process death: the conf keys are the source of truth, so the
        // editor comes back pre-filled instead of silently empty.
        if (a.routeWaypoints.isEmpty()) a.routeWaypoints.addAll(bridge.routeWaypoints())
        val page = routePage()
        a.flipper.removeViewAt(index)
        a.flipper.addView(page, index, a.pageParams())
        a.flipper.displayedChild = index
        renderRouteWaypoints()
    }

    /** 路线 tab: tap waypoints on the map, pick a speed, press start - every tier shares the epoch
     *  anchor, so the route reads as one movement no matter which tier carries it. */
    private fun routePage(): View {
        val page = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        a.routeMap = org.osmdroid.views.MapView(a).apply {
            setTileSource(AmapTiles())
            setMultiTouchControls(true)
            controller.setZoom(13.0)
            post { controller.setCenter(org.osmdroid.util.GeoPoint(31.2304, 121.4737)) }
        }
        a.routeMap.getOverlays().add(
            org.osmdroid.views.overlay.MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint): Boolean {
                    // Map tiles are GCJ-referenced; the route is consumed in WGS-84 like every
                    // other injection path in this app.
                    routeWaypointTap(p)
                    return true
                }

                override fun longPressHelper(p: org.osmdroid.util.GeoPoint): Boolean = false
            }),
        )
        page.addView(a.routeMap, a.weightParams())

        a.routeInfo = TextView(a).apply {
            textSize = 13f
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(2))
        }
        page.addView(a.routeInfo)

        a.routeWayRows = ui.column()
        page.addView(
            ScrollView(a).apply {
                addView(a.routeWayRows)
                isFillViewport = false
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f,
            ),
        )

        val controls = ui.column().apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(6)) }
        val speedRow = ui.row()
        speedRow.addView(
            TextView(a).apply {
                text = "速度 m/s"
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, ui.dp(8), 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        a.routeSpeedField = EditText(a).apply {
            setText("1.4")
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        speedRow.addView(a.routeSpeedField, ui.fill())
        listOf("步行1.4" to 1.4, "骑行5.5" to 5.5, "驾车12.0" to 12.0).forEach { (label, value) ->
            speedRow.addView(ui.button(label) { a.routeSpeedField.setText(value.toString()) })
        }
        controls.addView(speedRow)
        controls.addView(
            android.widget.CheckBox(a).apply {
                a.routeLoopBox = this
                text = "循环（走完回到起点继续）"
                isChecked = true
            },
        )
        val runRow = ui.row()
        runRow.addView(ui.button("开始路线", Ui.Btn.PRIMARY) { startRoute() }, ui.fill())
        runRow.addView(ui.button("停止路线") {
            bridge.stopRoute()
            a.toast("路线已停：注入点回到静态中心")
            renderRouteWaypoints()
            a.refreshStatus()
        }, ui.fill())
        controls.addView(runRow)
        val ioRow = ui.row()
        ioRow.addView(ui.button("导入GPX") {
            runCatching {
                a.routeGpxUri = null
                a.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    REQUEST_GPX_IMPORT,
                )
            }.onFailure { a.toast("这个系统不给打开文件选择器：${it.message}") }
        }, ui.fill())
        ioRow.addView(ui.button("导出GPX") {
            if (a.routeWaypoints.size < 2) return@button a.toast("没有可导出的航点")
            runCatching {
                a.routeGpxUri = null
                a.startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/gpx+xml")
                        .putExtra(Intent.EXTRA_TITLE, "mockloc-route.gpx"),
                    REQUEST_GPX_EXPORT,
                )
            }.onFailure { a.toast("这个系统不给打开文件选择器：${it.message}") }
        }, ui.fill())
        ioRow.addView(ui.button("清空") {
            a.routeWaypoints.clear()
            renderRouteWaypoints()
        }, ui.fill())
        controls.addView(ioRow)
        page.addView(controls)
        return page
    }

    private fun routeWaypointTap(p: org.osmdroid.util.GeoPoint) {
        val w = CoordTransform.gcj02ToWgs84(p.latitude, p.longitude)
        a.routeWaypoints.add(Route.Pt(w[0], w[1]))
        renderRouteWaypoints()
    }

    internal fun renderRouteWaypoints() {
        // Rebuild markers from scratch - waypoint count is small and this beats incremental
        // marker surgery on three code paths (tap / import / clear).
        a.routeMarkers.forEach { a.routeMap.getOverlays().remove(it) }
        a.routeMarkers.clear()
        a.routeWaypoints.forEachIndexed { index, pt ->
            val m = org.osmdroid.views.overlay.Marker(a.routeMap, a).apply {
                // Tiles are GCJ: draw the marker where the tap landed, not at the raw WGS value.
                val g = a.mapSrc.toDisplay(pt.lat, pt.lng)
                // setPosition/setTitle, not the Kotlin property form: osmdroid 6.1.20 has no
                // matching getter pair here (the README recorded this trap in v0.4.0).
                setPosition(org.osmdroid.util.GeoPoint(g[0], g[1]))
                setTitle("航点${index + 1}")
            }
            a.routeMarkers.add(m)
            a.routeMap.getOverlays().add(m)
        }
        a.routeMap.invalidate()
        a.routeWayRows.removeAllViews()
        a.routeWaypoints.forEachIndexed { index, pt ->
            a.routeWayRows.addView(
                ui.body("${index + 1}. ${"%.5f".format(pt.lat)},${"%.5f".format(pt.lng)}"),
            )
        }
        val path = Route.build(a.routeWaypoints)
        a.routeInfo.text = buildString {
            if (a.routeWaypoints.size < 2) {
                append("在地图上点 ≥2 个航点；长按地图页可注入单点")
            } else {
                append("航点 ${a.routeWaypoints.size} 个 · 全程 ${"%.0f".format(path!!.totalMeters)} m")
            }
            if (bridge.routeRunning()) {
                val speed = bridge.routeSpeed()
                val etaMin = if (path != null) "%.0f".format(path.totalMeters / speed / 60.0) else "?"
                append(" · 运行中 @${"%.1f".format(speed)} m/s（单圈约 $etaMin 分钟）")
            }
        }
    }

    internal fun startRoute() {
        if (a.routeWaypoints.size < 2) return a.toast("先在地图上点至少两个航点")
        val speed = a.routeSpeedField.text.toString().toDoubleOrNull()
            ?: return a.toast("速度读不懂：填 0.1–60 之间的数字")
        if (speed < 0.1 || speed > 60.0) return a.toast("速度要在 0.1–60 m/s 之间")
        val loop = routeMapLoopChecked()
        val started = System.currentTimeMillis()
        val label = "路线 ${a.routeWaypoints.size} 点"
        val first = a.routeWaypoints.first()
        a.toast("启动路线…")
        thread {
            runCatching {
                val alt = Elevation.resolve(a, first.lat, first.lng) ?: MockService.DEFAULT_ALT
                val useRoot = RootInjector.rootAvailable()
                a.runOnUiThread {
                    bridge.setRoute(a.routeWaypoints.toList(), speed, loop, started)
                    if (useRoot) {
                        bridge.publish(first.lat, first.lng, label, global = true)
                        val published = RootInjector.publishConfig(a, bridge.render())
                        a.useRootPipeline = true
                        a.startForegroundService(
                            Intent(a, MockService::class.java)
                                .putExtra(MockService.EXTRA_CMD, "start")
                                .putExtra(MockService.EXTRA_ROOT, true)
                                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                                .putExtra(MockService.EXTRA_LAT, "%.7f".format(first.lat))
                                .putExtra(MockService.EXTRA_LNG, "%.7f".format(first.lng))
                                .putExtra(MockService.EXTRA_ACC, "%.1f".format(bridge.injectionAcc()))
                                .putExtra(MockService.EXTRA_ALT, "%.1f".format(alt))
                                .putExtra(MockService.EXTRA_INTERVAL, bridge.injectionIntervalSec().toString()),
                        )
                        a.toast(if (published) "路线启动（ROOT 巡检 + 路线推进）" else "路线启动（conf 发布失败）")
                    } else {
                        bridge.publish(first.lat, first.lng, label, global = false)
                        a.startForegroundService(
                            Intent(a, MockService::class.java)
                                .putExtra(MockService.EXTRA_CMD, "start")
                                .putExtra(MockService.EXTRA_PROVIDERS, DEFAULT_PROVIDERS)
                                .putExtra(MockService.EXTRA_LAT, "%.7f".format(first.lat))
                                .putExtra(MockService.EXTRA_LNG, "%.7f".format(first.lng))
                                .putExtra(MockService.EXTRA_ACC, "%.1f".format(bridge.injectionAcc()))
                                .putExtra(MockService.EXTRA_RADIUS, "0.0")
                                .putExtra(MockService.EXTRA_ALT, "%.1f".format(alt))
                                .putExtra(MockService.EXTRA_INTERVAL, bridge.injectionIntervalSec().toString()),
                        )
                        a.toast("路线启动（档0，按 ${bridge.injectionIntervalSec()}s 推进）")
                    }
                    a.showWizardIfNeeded()
                    renderRouteWaypoints()
                    a.refreshStatus()
                }
            }.onFailure {
                a.runOnUiThread { a.toast("路线启动失败：${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}") }
            }
        }
    }

    internal fun routeMapLoopChecked(): Boolean = a.routeLoopBox.isChecked

    /** GPX 导入/导出结果由 MainActivity.onActivityResult 转进来（页面不持有 Activity 的 result 回调）。 */
    internal fun onGpxResult(requestCode: Int, uri: Uri) {
        when (requestCode) {
            REQUEST_GPX_IMPORT -> thread {
                val text = runCatching {
                    a.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                val pts = text?.let { Gpx.parse(it) }.orEmpty()
                a.runOnUiThread {
                    if (pts.size < 2) {
                        a.toast("这个 GPX 里没读到 ≥2 个轨迹点")
                    } else {
                        a.routeWaypoints.clear()
                        a.routeWaypoints.addAll(pts)
                        renderRouteWaypoints()
                        val g = a.mapSrc.toDisplay(pts[0].lat, pts[0].lng)
                        a.routeMap.controller.setCenter(org.osmdroid.util.GeoPoint(g[0], g[1]))
                        a.toast("导入 ${pts.size} 个航点")
                    }
                }
            }
            REQUEST_GPX_EXPORT -> thread {
                val body = Gpx.export("mockloc route", a.routeWaypoints)
                val ok = runCatching {
                    a.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                        os.write(body.toByteArray())
                    } != null
                }.getOrDefault(false)
                a.runOnUiThread { a.toast(if (ok) "GPX 已导出" else "写入失败") }
            }
        }
    }

    private companion object {
        const val DEFAULT_PROVIDERS = "gps,network,fused"
        const val REQUEST_GPX_IMPORT = 71
        const val REQUEST_GPX_EXPORT = 72
        val TABS = listOf("地图", "路线", "收藏", "策略", "状态")
    }
}
