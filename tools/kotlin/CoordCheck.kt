import com.mockloc.CoordTransform
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Standalone acceptance check for the Kotlin port of coords.py. Not part of the APK sources -
 * compile together with CoordTransform.kt and run, so a numeric regression shows up locally
 * instead of in a phone hand.
 */
private var fails = 0

private fun check(label: String, got: Double, want: Double, tol: Double) {
    val err = abs(got - want)
    val ok = err <= tol
    println("${if (ok) "PASS" else "FAIL"} $label: got ${"%.7f".format(got)} want ${"%.7f".format(want)} err ${"%.2e".format(err)}")
    if (!ok) fails++
}

fun main() {
    val pearl = CoordTransform.gcj02ToWgs84(31.239703, 121.499733)
    check("pearl gcj->wgs lat", pearl[0], 31.2417030, 3e-6)
    check("pearl gcj->wgs lng", pearl[1], 121.4952840, 3e-6)

    val fwd = CoordTransform.wgs84ToGcj02(31.2419464, 121.4952604)
    check("pearl wgs->gcj lat", fwd[0], 31.2399470, 5e-5)
    check("pearl wgs->gcj lng", fwd[1], 121.4997100, 5e-5)

    val there = CoordTransform.wgs84ToGcj02(31.2304, 121.4737)
    val rt = CoordTransform.gcj02ToWgs84(there[0], there[1])
    check("roundtrip lat", rt[0], 31.2304, 1e-9)
    check("roundtrip lng", rt[1], 121.4737, 1e-9)

    val shenzhenGcj = CoordTransform.wgs84ToGcj02(22.5431, 114.0579)
    val shenzhenBd = CoordTransform.gcj02ToBd09(shenzhenGcj[0], shenzhenGcj[1])
    val bd = CoordTransform.bd09ToWgs84(shenzhenBd[0], shenzhenBd[1])
    check("bd roundtrip lat", bd[0], 22.5431, 1e-6)
    check("bd roundtrip lng", bd[1], 114.0579, 1e-6)

    val shift = CoordTransform.distanceMeters(31.239703, 121.499733, pearl[0], pearl[1])
    println("INFO Lujiazui gcj02->wgs84 shift: ${"%.0f".format(shift)} m")
    check("shift magnitude", shift, 478.0, 120.0)

    check("paris untouched", CoordTransform.wgs84ToGcj02(48.8584, 2.2945)[0], 48.8584, 0.0)

    println()
    println(if (fails == 0) "ALL PASS" else "FAILURES: $fails")
    if (fails > 0) exitProcess(1)
}
