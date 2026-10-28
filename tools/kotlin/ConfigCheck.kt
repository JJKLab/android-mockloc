import com.mockloc.Config
import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone acceptance check for the module-side config parser. Not part of the APK sources.
 *
 * Worth running because the two halves of this contract are written in different languages on
 * different machines: tools/mockloc.py emits the file, Config.kt parses it inside every hooked
 * process, and a key-name typo only shows up as "the module ignores what I asked for" on a phone.
 *
 * Config reads one hard-coded path, so this writes that same path from the JVM. On Windows that
 * lands on the current drive; if the directory cannot be created the check reports SKIP rather
 * than pretending it verified something.
 */
private var fails = 0

private fun check(label: String, got: Any?, want: Any?) {
    val ok = got == want
    println("${if (ok) "PASS" else "FAIL"} $label: got=$got want=$want")
    if (!ok) fails++
}

private val sample = """
    MOCKLOC_ENABLED=true
    MOCKLOC_GLOBAL=false
    MOCKLOC_PROVIDERS=gps,network,fused
    MOCKLOC_LAT=31.2400000
    MOCKLOC_LNG=121.4600000
    MOCKLOC_ACC=5.0
    MOCKLOC_RADIUS=0.0
    MOCKLOC_ALT=12.5
    MOCKLOC_INTERVAL=15
    MOCKLOC_LOOP_INTERVAL=3
    MOCKLOC_TARGETS=com.tencent.mm
    MOCKLOC_LABEL=31.240000,121.460000
    MOCKLOC_GNSS=true
    MOCKLOC_BLIND_WIFI=true
    MOCKLOC_BLIND_CELL=false
    MOCKLOC_PROBE=true
""".trimIndent()

// Exactly what an older build of the CLI writes: no anti-detection keys at all.
private val legacy = """
    MOCKLOC_ENABLED=true
    MOCKLOC_GLOBAL=true
    MOCKLOC_PROVIDERS=gps
    MOCKLOC_LAT=39.9087220
    MOCKLOC_LNG=116.3974990
    MOCKLOC_ACC=8.0
    MOCKLOC_RADIUS=30.0
    MOCKLOC_INTERVAL=20
    MOCKLOC_TARGETS=
    MOCKLOC_LABEL=天安门
""".trimIndent()

fun main() {
    val file = File(Config.DEFAULT_PATH)
    val dir = file.parentFile
    if (dir == null || (!dir.exists() && !dir.mkdirs())) {
        println("SKIP: 写不了 ${Config.DEFAULT_PATH}（这个平台不允许创建该目录）")
        exitProcess(2)
    }
    println("写入 ${file.absolutePath}")

    file.writeText(sample)
    Config.refresh()
    check("enabled", Config.enabled, true)
    check("global", Config.global, false)
    check("providers", Config.providers, listOf("gps", "network", "fused"))
    check("provider(first)", Config.provider, "gps")
    check("lat", Config.centerLat, 31.24)
    check("lng", Config.centerLng, 121.46)
    check("accuracy", Config.accuracy, 5.0f)
    check("radius", Config.radius, 0.0)
    check("targets", Config.targets, setOf("com.tencent.mm"))
    check("label", Config.label, "31.240000,121.460000")
    check("gnssSpoof", Config.gnssSpoof, true)
    check("blindWifi", Config.blindWifi.render(), "true")
    check("blindCell", Config.blindCell.render(), "false")
    check("blindWifi 覆盖任意包", Config.blindWifiFor("com.autonavi.minimap"), true)
    check("blindCell 不覆盖任何包", Config.blindCellFor("com.autonavi.minimap"), false)
    check("probe", Config.probe, true)
    check("lastError", Config.lastError, null)
    check("alt", Config.alt, 12.5)
    check("loopInterval", Config.loopIntervalSec, 3)

    // shouldSpoof is the gate every hook consults per call.
    check("spoof listed pkg", Config.shouldSpoof("com.tencent.mm"), true)
    check("spoof other pkg", Config.shouldSpoof("com.autonavi.minimap"), false)

    // The refresh cache keys on mtime, so give the second file a distinguishable one.
    Thread.sleep(1100)
    file.writeText(legacy)
    Config.refresh()
    check("legacy enabled", Config.enabled, true)
    check("legacy global", Config.global, true)
    check("legacy providers", Config.providers, listOf("gps"))
    check("legacy targets empty=all", Config.shouldSpoof("any.pkg"), true)
    check("legacy gnss default", Config.gnssSpoof, true)
    check("legacy blindWifi default", Config.blindWifi.render(), "false")
    check("legacy probe default", Config.probe, false)
    check("legacy alt default=-1 自动", Config.alt, -1.0)
    check("legacy loopInterval default=1", Config.loopIntervalSec, 1)
    check("legacy route 未激活", Config.routeActive, false)
    check("legacy per-app 点位为空", Config.appPoints.isEmpty(), true)
    check("legacy 阶段三默认", Config.stackClean && Config.gmsSpoof &&
        !Config.sensorFreeze && !Config.forgeWifi && Config.hidePkgs.isEmpty(), true)

    Thread.sleep(1100)
    file.writeText(sample.replace("MOCKLOC_GNSS=true", "MOCKLOC_GNSS=false")
        .replace("MOCKLOC_BLIND_CELL=false", "MOCKLOC_BLIND_CELL=1")
        .replace("MOCKLOC_PROBE=true", "MOCKLOC_PROBE=0"))
    Config.refresh()
    check("gnss off", Config.gnssSpoof, false)
    check("blindCell as 1", Config.blindCell.render(), "true")
    check("probe as 0", Config.probe, false)

    // Deleting the file is how `off --wipe` stops a spoof; the module notices on the next
    // refresh past the 1s stat window, not instantly.
    Thread.sleep(1100)

    // The App renders this exact body (ModuleBridge.render) and the root module or `sync` carries it
    // verbatim; feeding the renderer's own output back through the parser is what stops the writer
    // and the reader drifting apart - the drift that made the App pick Shanghai while WeChat showed
    // the module's Beijing.
    Thread.sleep(1100)
    val rendered = com.mockloc.ModuleBridge.render(
        enabled = true,
        lat = "31.2229441",
        lng = "121.4644122",
        label = "东方明珠 & 陆家嘴",
        targets = listOf("com.tencent.mm", "com.autonavi.minimap"),
    )
    file.writeText(rendered)
    Config.refresh()
    check("App 渲染体可被模块解析", Config.lastError, null)
    check("渲染 lat", Config.centerLat, 31.2229441)
    check("渲染 lng", Config.centerLng, 121.4644122)
    check("渲染 label", Config.label, "东方明珠 & 陆家嘴")
    check("渲染 targets", Config.targets, setOf("com.tencent.mm", "com.autonavi.minimap"))
    check("渲染 providers", Config.providers, listOf("gps", "network", "fused"))
    check("渲染默认不致盲", Config.blindWifi.render(), "false")

    // Every field the module reads has to come out of the renderer, or a sync silently resets it.
    // `MOCKLOC_PROBE` was once missing this way; blinding is the field that actually moves Amap's
    // blue dot, so losing it in a sync would look like the module stopped working.
    Thread.sleep(1100)
    file.writeText(
        com.mockloc.ModuleBridge.render(
            enabled = true,
            lat = "30.2638384",
            lng = "120.1927132",
            label = "杭州",
            targets = emptyList(),
            probe = true,
            blind = com.mockloc.Scope.All,
        )
    )
    Config.refresh()
    check("渲染致盲 WiFi", Config.blindWifi.render(), "true")
    check("渲染致盲基站", Config.blindCell.render(), "true")
    check("渲染探针", Config.probe, true)
    check("渲染空 targets = 跟随作用域", Config.targets, emptySet<String>())

    // The tri-state matters because blinding breaks the host app's own WiFi features: "only these
    // packages" is the setting people actually want, and it has to survive the same round trip.
    Thread.sleep(1100)
    file.writeText(
        com.mockloc.ModuleBridge.render(
            enabled = true,
            lat = "30.2638384",
            lng = "120.1927132",
            label = "杭州",
            targets = emptyList(),
            blind = com.mockloc.Scope.only(listOf("com.autonavi.minimap", "com.sankuai.meituan")),
        )
    )
    Config.refresh()
    check("按包致盲 render", Config.blindWifi.render(), "com.autonavi.minimap,com.sankuai.meituan")
    check("按包致盲 命中高德", Config.blindWifiFor("com.autonavi.minimap"), true)
    check("按包致盲 命中美团", Config.blindCellFor("com.sankuai.meituan"), true)
    check("按包致盲 不命中微信", Config.blindWifiFor("com.tencent.mm"), false)
    check("按包致盲 不命中 null", Config.blindWifiFor(null), false)
    check("空 targets 仍对任意包生效", Config.shouldSpoof("com.autonavi.minimap"), true)

    // The new v2 keys must survive the same round trip: whatever render() emits, the parser reads
    // back - and LOOP_INTERVAL is the key the root module's boot loop now owns, so a renderer that
    // drops it would silently put the module back on its own fallback.
    Thread.sleep(1100)
    file.writeText(
        com.mockloc.ModuleBridge.render(
            enabled = true,
            lat = "29.6519846",
            lng = "91.1401397",
            label = "拉萨",
            targets = emptyList(),
            global = true,
            acc = "8.0",
            radius = "12.0",
            interval = "2",
            alt = "3650.0",
        )
    )
    Config.refresh()
    check("渲染 alt", Config.alt, 3650.0)
    check("渲染 loopInterval", Config.loopIntervalSec, 2)
    check("渲染 acc", Config.accuracy, 8.0f)
    check("渲染 radius", Config.radius, 12.0)
    check("渲染 global", Config.global, true)
    check("渲染 interval 同步 loopInterval", Config.loopIntervalSec, 2)

    // v2 route / per-app / phase-3 keys live under the same rule: whatever render() emits must
    // parse back, or a sync silently resets a layer someone turned on.
    Thread.sleep(1100)
    file.writeText(
        com.mockloc.ModuleBridge.render(
            enabled = true,
            lat = "31.2229000",
            lng = "121.4644000",
            label = "路线",
            targets = emptyList(),
            speed = "2.5",
            routeWaypoints = "31.2229000,121.4644000;31.2260000,121.4680000",
            routeStarted = "1700000000000",
            routeLoop = true,
            appPoints = listOf("com.tencent.mm" to "39.9087220,116.3974990,25.0"),
            stackClean = false,
            hidePkgs = listOf("roro.stellar.manager", "com.mockloc"),
            sensorFreeze = true,
            forgeWifi = true,
            gms = false,
        ),
    )
    Config.refresh()
    check("渲染 route 航点数", Config.routeWaypoints.size, 2)
    check("渲染 route 锚点", Config.routeStartedMs, 1700000000000L)
    check("渲染 route 循环", Config.routeLoop, true)
    check("渲染 route 速度", Config.speedMps, 2.5)
    check("渲染 route 激活", Config.routeActive, true)
    val stepped = com.mockloc.Route.sampleElapsed(
        Config.routeWaypoints, Config.speedMps, Config.routeLoop,
        Config.routeStartedMs, Config.routeStartedMs + 10_000,
    )
    check("路线 10s 后有推进", stepped != null && stepped.speedMps == 2.5, true)
    check("per-app 点位已解析", Config.appPoints.containsKey("com.tencent.mm"), true)
    check("per-app 纬度", Config.currentLatFor("com.tencent.mm"), 39.9087220)
    check("per-app 精度", Config.accuracyFor("com.tencent.mm"), 25.0f)
    check("未列出 App 用全局点", Config.currentLatFor("com.autonavi.minimap"), Config.currentLat)
    check("渲染 stackClean off", Config.stackClean, false)
    check("渲染 hidePkgs", Config.hidePkgs, setOf("roro.stellar.manager", "com.mockloc"))
    check("渲染 sensorFreeze on", Config.sensorFreeze, true)
    check("渲染 forgeWifi on", Config.forgeWifi, true)
    check("渲染 gms off", Config.gmsSpoof, false)

    Thread.sleep(1100)
    file.delete()
    Config.refresh()
    check("missing file disables", Config.enabled, false)
    check("missing file error", Config.lastError, "missing ${Config.DEFAULT_PATH}")

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    exitProcess(if (fails == 0) 0 else 1)
}
