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
    MOCKLOC_INTERVAL=15
    MOCKLOC_TARGETS=com.tencent.mm
    MOCKLOC_LABEL=31.240000,121.460000
    MOCKLOC_GNSS=true
    MOCKLOC_BLIND_WIFI=true
    MOCKLOC_BLIND_CELL=false
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
    check("blindWifi", Config.blindWifi, true)
    check("blindCell", Config.blindCell, false)
    check("lastError", Config.lastError, null)

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
    check("legacy blindWifi default", Config.blindWifi, false)

    Thread.sleep(1100)
    file.writeText(sample.replace("MOCKLOC_GNSS=true", "MOCKLOC_GNSS=false")
        .replace("MOCKLOC_BLIND_CELL=false", "MOCKLOC_BLIND_CELL=1"))
    Config.refresh()
    check("gnss off", Config.gnssSpoof, false)
    check("blindCell as 1", Config.blindCell, true)

    // Deleting the file is how `off --wipe` stops a spoof; the module notices on the next
    // refresh past the 1s stat window, not instantly.
    Thread.sleep(1100)
    file.delete()
    Config.refresh()
    check("missing file disables", Config.enabled, false)
    check("missing file error", Config.lastError, "missing ${Config.DEFAULT_PATH}")

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    exitProcess(if (fails == 0) 0 else 1)
}
