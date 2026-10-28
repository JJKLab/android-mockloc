package com.mockloc

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * One read-back from inside the hooked process, logged once per process at attach time.
 *
 * This exists because LSPosed refuses to put a module in its own scope, so the app's status page
 * can never show what the hooks do to other apps. Reading the same APIs a target app would read is
 * the only way to answer "did the substitution bite?" without a third-party app to watch.
 *
 * Read-only on purpose: registering location updates inside someone else's process would be a side
 * effect this module has no business causing.
 *
 * To make the result meaningful, point the config somewhere other than the system test provider -
 * if the log shows the config coordinate while `dumpsys location` shows the provider's, the hook
 * changed the value; if both show the same thing the test proves nothing.
 */
object SelfTest {

    private val probeProviders = listOf(
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused",
    )

    /**
     * `onPackageReady` fires while the process is still binding the application, and
     * `ActivityThread.currentApplication()` is null at that moment - the first version of this
     * check only ever logged "拿不到 Application". Retrying on the main looper lets the attempt
     * land after the Application exists without hooking anything just to observe it.
     */
    private val attempts = longArrayOf(1500L, 4000L, 10000L)

    fun schedule(pkg: String, note: (String) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        attempts.forEachIndexed { index, delay ->
            handler.postDelayed({
                if (!run(pkg, note)) {
                    if (index == attempts.lastIndex) note("$pkg 自检跳过：拿不到 Application/LocationManager")
                }
            }, delay)
        }
    }

    /** True when the read-back happened; false means the process is not ready yet. */
    private fun run(pkg: String, note: (String) -> Unit): Boolean {
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? Application ?: return false

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val fixes = probeProviders.joinToString(" ") { name ->
            val loc = runCatching { lm.getLastKnownLocation(name) }.getOrNull()
            "$name=" + (loc?.let { "${fmt(it.latitude)},${fmt(it.longitude)} mock=${mock(it)}" } ?: "null")
        }
        val providers = runCatching { lm.getProviders(false) }.getOrNull()
        val enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()
        val setting = runCatching {
            Settings.Secure.getString(app.contentResolver, "mock_location")
        }.getOrNull()

        note("$pkg 自检(进程内读回) $fixes | providers=$providers gpsEnabled=$enabled mockSetting=$setting${leakProbe(app)}")
        return true
    }

    /**
     * Leak probe (REFACTOR-PLAN 3.2): with the probe flag on, read the radio inputs a detecting
     * app would read and report how much real evidence survived. Runs in the *target's* process,
     * so it answers the question from the detector's side, not ours - "AP=0 基站=0" means the
     * blinding scope is closed; any count above zero is an open lane, named.
     */
    private fun leakProbe(app: Application): String {
        if (!Config.probe) return ""
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val aps = runCatching { wifi?.scanResults?.size }.getOrNull()
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val cells = runCatching {
            @Suppress("DEPRECATION")
            tm?.allCellInfo?.size
        }.getOrNull()
        val bssid = runCatching {
            @Suppress("DEPRECATION")
            wifi?.connectionInfo?.bssid
        }.getOrNull()
        val verdict = when {
            (aps ?: 0) == 0 && (cells ?: 0) == 0 -> "致盲面闭合"
            else -> "有真实射频证据泄露"
        }
        return " | 泄露反推: AP=$aps 基站=$cells 已连BSSID=$bssid → $verdict"
    }

    private fun fmt(value: Double) = "%.4f".format(value)

    @Suppress("DEPRECATION")
    private fun mock(location: android.location.Location): Boolean =
        location.isFromMockProvider
}
