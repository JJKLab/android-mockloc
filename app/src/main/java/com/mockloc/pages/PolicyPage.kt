package com.mockloc.pages

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.mockloc.BlindKind
import com.mockloc.MainActivity
import com.mockloc.ModuleBridge
import com.mockloc.RootInjector
import com.mockloc.Scope
import com.mockloc.Tiers
import com.mockloc.Ui
import kotlin.concurrent.thread

/**
 * 策略页（原 MainActivity 的 policyPage + 应用列表 + 致盲三路开关 + 免 root 自检）。
 *
 * 阶段四拆分。勾选框 = 掐网名单；致盲 WiFi/基站/BLE 由三个按钮单独设（P1-1/P1-2）。
 */
internal class PolicyPage(private val a: MainActivity) {

    private val ui get() = a.ui
    private val bridge get() = a.bridge

    /** ListView 的适配器：只绑定可见行，一次过滤重绑 ~7 行而不是重建 593 个 CheckBox。 */
    inner class Adapter : android.widget.BaseAdapter() {
        var shown: List<Pair<String, String>> = emptyList()

        fun refill(list: List<Pair<String, String>>) {
            shown = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Pair<String, String> = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val holder = (convertView?.tag as? Holder) ?: Holder().also { vh ->
                vh.row = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
                vh.box = android.widget.CheckBox(a).apply {
                    textSize = 13f
                    setPadding(0, ui.dp(6), 0, ui.dp(6))
                }
                vh.point = Button(a).apply {
                    isAllCaps = false
                    setTextSize(11f)
                }
                vh.row.addView(vh.box, ui.fill())
                vh.row.addView(vh.point)
                vh.row.tag = vh
            }
            val (pkg, label) = getItem(position)
            // Recycled views carry the previous row's state and listener: reset in the fixed
            // order (listener off → state on → listener on) or ticks migrate between rows.
            holder.box.setOnCheckedChangeListener(null)
            holder.box.text = "$label  ${Tiers.needsOf(pkg).label.take(1)}\n$pkg"
            holder.box.isChecked = pkg in a.policyChecked
            holder.box.setOnCheckedChangeListener { _, on ->
                if (on) a.policyChecked.add(pkg) else a.policyChecked.remove(pkg)
            }
            // Per-app fixed point (REFACTOR-PLAN 2.2): the pill marks apps that have one, and the
            // tap offers "reuse the last injected point / clear" - no second map picker needed.
            holder.point.text = if (pkg in a.policyPointed) "📍" else "位置"
            holder.point.setOnClickListener { a.promptAppPoint(pkg, label) }
            return holder.row
        }
    }

    private class Holder {
        lateinit var row: LinearLayout
        lateinit var box: android.widget.CheckBox
        lateinit var point: Button
    }

    fun build(): View {
        // The app list is a real ListView, not rows built into a scroll column: rebuilding up to
        // 593 CheckBoxes per filter keystroke was the 950ms frame the on-device baseline caught.
        // The list gets its own scroll region (a ListView inside a ScrollView fights the parent
        // for the drag - the trap the first version fell into), and the informational cards ride
        // as list headers so they keep their place without stealing fixed height.
        val page = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        val preflight = ui.card()
        preflight.addView(ui.title("免 root 档自检"))
        a.appOpChipRow = ui.chipRow()
        preflight.addView(a.appOpChipRow)
        preflight.addView(ui.button("去开发者选项") {
            val intent = if (Tiers.developerOptionsEnabled(a)) {
                Tiers.developmentSettingsIntent()
            } else {
                a.toast("开发者选项还没打开，先到设备信息里连点版本号")
                Tiers.fallbackSettingsIntent()
            }
            runCatching { a.startActivity(intent) }
                .onFailure { a.toast("这个 ROM 不让我直接跳，请手动进设置") }
        })
        preflight.addView(ui.hint(
            "「选择虚拟定位应用」只能由设置进程授权（本机 shell 被摘了 MANAGE_APP_OPS_MODES）；" +
                "重装 App 会清掉那次授权，所以这一栏每次进来都重读。",
        ))
        page.addView(preflight)

        val filterRow = ui.row().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0) }
        a.policyFilter = EditText(a).apply {
            hint = "过滤名称或包名"
            isSingleLine = true
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) = renderRows()
            })
        }
        filterRow.addView(a.policyFilter, ui.fill())
        filterRow.addView(android.widget.CheckBox(a).apply {
            text = "含系统"
            isChecked = a.policyIncludeSystem
            setOnCheckedChangeListener { _, on ->
                a.policyIncludeSystem = on
                renderRows()
            }
        })
        page.addView(filterRow)

        // 致盲三路独立开关：关 → 全部 → 按所选（勾选的包）。
        val blindRow = ui.row().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), 0) }
        a.blindWifiBtn = ui.button("致盲WiFi") { cycleBlind(BlindKind.WIFI) }
        a.blindCellBtn = ui.button("致盲基站") { cycleBlind(BlindKind.CELL) }
        a.blindBleBtn = ui.button("致盲BLE") { cycleBlind(BlindKind.BLE) }
        blindRow.addView(a.blindWifiBtn, ui.fill())
        blindRow.addView(a.blindCellBtn, ui.fill())
        blindRow.addView(a.blindBleBtn, ui.fill())
        page.addView(blindRow)
        renderBlindChips()

        a.policyCount = TextView(a).apply {
            textSize = 12f
            setTextColor(ui.hintColor())
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4))
        }
        page.addView(a.policyCount)

        a.policyList = ListView(a).apply {
            adapter = Adapter()
            divider = null
            cacheColorHint = 0
        }
        val intro = ui.card()
        intro.addView(ui.title("哪些 App 要抽空输入"))
        intro.addView(ui.body(
            "勾上的包会被 ROOT 守护按 uid 掐网（实测对高德这类「自己算路」的 App 有效）；" +
                "致盲 WiFi/基站/BLE 用上方三个按钮单独设（关 → 全部 → 按所选）。" +
                "「位置」按钮给它设独立点位与输出坐标系（默认 WGS84；瓦片直读型 App 选 GCJ02）。",
        ))
        a.policyList.addHeaderView(listHeader(intro))
        val legend = ui.card()
        legend.addView(ui.title("三件事互相独立"))
        legend.addView(ui.body(Tiers.summary().trim()))
        legend.addView(ui.hint(
            "①/②/③ 不是「权限越高越有效」的阶梯：0、1、2 档给目标 App 的是同一个系统 provider，" +
                "所以 Tier 0 推不动的 App，Tier 2 也推不动。",
        ))
        a.policyList.addHeaderView(listHeader(legend))
        val limits = ui.card()
        limits.addView(ui.title("这几样定位层改不了"))
        limits.addView(ui.body(Tiers.outsideScope.joinToString("\n")))
        a.policyList.addHeaderView(listHeader(limits))
        page.addView(a.policyList, a.weightParams())

        val policyButtons = ui.row().apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(8)) }
        policyButtons.addView(ui.button("保存", Ui.Btn.PRIMARY) { save() }, ui.fill())
        policyButtons.addView(ui.button("清空") {
            a.policyChecked.clear()
            renderRows()
        }, ui.fill())
        page.addView(policyButtons)
        return page
    }

    /**
     * ListView headers must carry AbsListView.LayoutParams - `ui.card()` pre-sets a
     * LinearLayout.LayoutParams, and ListView's removeUnusedFixedViews casts unconditionally,
     * which is a ClassCastException on the second layout pass (found on device: first entry
     * worked, re-entering the tab crashed). Wrapping in a bare FrameLayout defers the
     * LayoutParams to ListView's own generator.
     */
    internal fun listHeader(card: LinearLayout): android.view.View =
        android.widget.FrameLayout(a).apply { addView(card) }

    /**
     * Enumerating 593 packages with permissions + resolving every label is a multi-hundred-ms
     * binder grind; on the UI thread it was the frozen frame on first entry. Runs off-thread and
     * fills the adapter when done - the count line shows progress meanwhile.
     */
    internal fun loadList() {
        a.policyCount.text = "正在读取应用列表…"
        thread {
            runCatching {
                val t0 = System.currentTimeMillis()
                val packages = runCatching {
                    a.packageManager.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS)
                }.getOrElse { emptyList() }
                val result = packages.mapNotNull { info ->
                    val pkg = info.packageName
                    if (pkg == a.packageName) return@mapNotNull null
                    val permissions = info.requestedPermissions ?: return@mapNotNull null
                    val asksForLocation =
                        android.Manifest.permission.ACCESS_FINE_LOCATION in permissions ||
                            android.Manifest.permission.ACCESS_COARSE_LOCATION in permissions
                    if (!asksForLocation) return@mapNotNull null
                    val appInfo = info.applicationInfo
                    // A phone has hundreds of platform packages that nominally ask for location;
                    // listing them buries the handful the user could possibly mean. Updated system
                    // apps stay in, because preinstalled OEM maps and delivery apps are exactly those.
                    val system = appInfo != null &&
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    if (system && !a.policyIncludeSystem) return@mapNotNull null
                    val label = runCatching {
                        a.packageManager.getApplicationLabel(appInfo!!).toString()
                    }.getOrDefault(pkg)
                    pkg to label
                }.sortedBy { it.second }
                val elapsed = System.currentTimeMillis() - t0
                a.runOnUiThread {
                    a.policyAll = result
                    a.policyLoaded = true
                    a.policyLoadMs = elapsed
                    a.policyPointed = bridge.appPoints().keys
                    renderRows()
                }
            }
        }
    }

    internal fun renderRows() {
        val needle = a.policyFilter.text.toString().trim().lowercase()
        val adapter = a.policyList.adapter as? Adapter ?: return
        adapter.refill(a.policyAll.filter { (pkg, label) ->
            needle.isEmpty() || label.lowercase().contains(needle) || pkg.lowercase().contains(needle)
        })
        a.policyCount.text = buildString {
            append("共 ${adapter.shown.size} 个可选项，已选 ${a.policyChecked.size} 个掐网")
            if (a.policyPointed.isNotEmpty()) append("，${a.policyPointed.size} 个独立点位")
            if (!a.policyIncludeSystem) append("（已隐藏系统 App）")
            if (a.policyLoadMs > 0) append(" · 列表加载 ${a.policyLoadMs}ms")
            // "作用域内全部" is not expressible as a ticked list, so saving from here would quietly
            // narrow it. Say so instead of letting the write look like a no-op that lost their setting.
            if (bridge.netcutScope().mode == Scope.Mode.ALL) {
                append("\n当前生效的是「作用域内全部」；在这里保存会改成只掐网勾上的包")
            }
        }
    }

    internal fun save() {
        val picked = a.policyChecked.toList()
        bridge.setNetcutPkgs(picked)
        a.toast(
            if (picked.isEmpty()) "已清空掐网名单"
            else "已选 ${picked.size} 个掐网（ROOT 守护=按 uid 断网；致盲请用上方三个按钮）"
        )
        // ROOT 守护运行时，conf 一发布循环就在下一个 tick 应用（掐网即时生效，无需重启目标 App）；
        // LSP 路径的致盲钩子仍按进程启动时装，改动后要重启目标 App。
        publish()
    }

    /** 把 bridge 的渲染体发布到模块通道，并在读回窗口后刷新状态。 */
    internal fun publish() {
        thread {
            runCatching {
                if (RootInjector.rootAvailable()) {
                    RootInjector.publishConfig(a, bridge.render())
                }
            }
        }
        thread {
            runCatching { Thread.sleep(1200) }
            a.runOnUiThread { a.refreshStatus() }
        }
    }

    /** 致盲三路循环：关 → 全部 → 按所选（策略页勾选的包）。 */
    internal fun cycleBlind(kind: BlindKind) {
        val cur = when (kind) {
            BlindKind.WIFI -> bridge.blindWifiScope()
            BlindKind.CELL -> bridge.blindCellScope()
            BlindKind.BLE -> bridge.blindBleScope()
        }
        val next = when (cur.mode) {
            Scope.Mode.NONE -> Scope.All
            Scope.Mode.ALL -> Scope.only(a.policyChecked)
            else -> Scope.None
        }
        when (kind) {
            BlindKind.WIFI -> bridge.setBlindWifiScope(next)
            BlindKind.CELL -> bridge.setBlindCellScope(next)
            BlindKind.BLE -> bridge.setBlindBleScope(next)
        }
        renderBlindChips()
        publish()
    }

    internal fun renderBlindChips() {
        a.blindWifiBtn.text = "致盲WiFi：" + blindLabel(bridge.blindWifiScope())
        a.blindCellBtn.text = "致盲基站：" + blindLabel(bridge.blindCellScope())
        a.blindBleBtn.text = "致盲BLE：" + blindLabel(bridge.blindBleScope())
    }

    internal fun blindLabel(scope: Scope): String = when (scope.mode) {
        Scope.Mode.NONE -> "关"
        Scope.Mode.ALL -> "全部"
        else -> "所选 ${scope.packages.size}"
    }

    /**
     * The two facts the no-root tier actually depends on, read fresh every time: the app-op grant
     * (a reinstall clears it) and whether 开发者选项 is even reachable on this ROM.
     */
    internal fun renderAppOpChips() {
        a.appOpChipRow.removeAllViews()
        val allowed = Tiers.mockAppOpAllowed(a)
        a.appOpChipRow.addView(
            ui.chip("虚拟定位授权", if (allowed) Ui.State.OK else Ui.State.BAD),
        )
        a.appOpChipRow.addView(
            ui.chip(
                "开发者选项",
                if (Tiers.developerOptionsEnabled(a)) Ui.State.OK else Ui.State.WARN,
            ),
        )
        a.appOpChipRow.addView(
            ui.chip("本 App 定位权限", if (a.hasLocationGrant()) Ui.State.OK else Ui.State.BAD),
        )
    }
}
